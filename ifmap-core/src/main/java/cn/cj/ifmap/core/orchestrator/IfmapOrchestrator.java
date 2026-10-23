/*
 * Copyright 2026 caijun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.cj.ifmap.core.orchestrator;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.exception.IfmapRemoteException;
import cn.cj.ifmap.core.exception.IfmapStrategyException;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonReadContext;
import cn.cj.ifmap.core.orchestrator.branch.BranchJudge;
import cn.cj.ifmap.core.model.PartnerCall;
import cn.cj.ifmap.core.orchestrator.judgement.JudgementPipeline;
import cn.cj.ifmap.core.orchestrator.judgement.ResultJudgeRegistry;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.rule.RuleContext;
import cn.cj.ifmap.core.spi.PartnerOutcome;
import cn.cj.ifmap.core.spi.PartnerServiceGateway;
import cn.cj.ifmap.core.spi.ClockProvider;
import cn.cj.ifmap.core.spi.ExecutionLogSink;
import cn.cj.ifmap.core.spi.LogMasker;
import cn.cj.ifmap.core.spi.SystemClockProvider;
import cn.cj.ifmap.core.spi.TenantResolver;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.FullParamStrategyRegistry;
import cn.cj.ifmap.core.strategy.LogicBranchStrategyRegistry;
import cn.cj.ifmap.core.strategy.IfmapStrategyHook;
import cn.cj.ifmap.core.strategy.StrategyHookRegistry;
import cn.cj.ifmap.core.strategy.StrategyContext;
import cn.cj.ifmap.core.util.Logs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口执行编排器：把 §7.1 的主流程串起来，语义与存量引擎一致。
 *
 * <pre>
 * IfmapRequest
 *   → 租户解析（含 tenantId 的缓存键在仓储/缓存层）
 *   → 按 busi_node 取整条计划 + 启用过滤 + interface_order 升序 + 按起点截断
 *   → 主参数组包 FullParamStrategy → 请求模板渲染 → 策略钩子 IfmapStrategyHook
 *   → 出网调用 PartnerServiceGateway（宿主机实现；也可用 mockResponse 做 dry-run）
 *   → 被调方向（call_direction=INBOUND）跳过上面两步：回参就是对方原文，不出网
 * 
 *   → 响应模板解析 → 结果判定 result_judge
 *   → 逻辑分支（branch_judge 规格，logic_branch_order 升序首个命中；{"op":"always"} 兜底最后）
 *     → ActionRegistry 执行动作
 *   → 执行日志（脱敏 + 截断后落库）
 * </pre>
 *
 * <p>与存量的行为差异（均已在上方标注）：步骤顺序只有 interface_order 一个来源、起点缺失即报错、
 * 分支/动作未命中不静默、写日志失败只 WARN、异常不强制转换、跨步骤共享池（后序步骤能读到前序产物）。</p>
 *
 * @author caijun
 */
public final class IfmapOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(IfmapOrchestrator.class);

    /**
     * 跨步骤共享池的保留根：请求模板里 {@code $.pool.{interface_no}.xxx} 取指定步骤的产物。
     *
     * <p>业务 payload 顶层不应有 {@code pool}；引擎的池会<b>覆盖</b>同名顶层键。</p>
     */
    public static final String POOL_KEY = "pool";

    private final IfmapEngine engine;
    private final JsonOps jsonOps;
    private final ConfigRepository repository;
    private final TenantResolver tenantResolver;
    private final StrategyHookRegistry strategyHooks;
    private final FullParamStrategyRegistry fullParams;
    private final LogicBranchStrategyRegistry logicBranches;
    private final BranchJudge branchJudge;
    private final ActionRegistry actions;
    private final PartnerServiceGateway gateway;
    private final ExecutionLogSink logSink;
    private final LogMasker logMasker;
    private final ClockProvider clock;
    private final JudgementPipeline pipeline;
    private final boolean stopOnFailure;
    private final boolean failOnMissingStrategy;
    private final int truncateThreshold;

    private IfmapOrchestrator(Builder builder) {
        this.engine = builder.engine;
        this.jsonOps = builder.jsonOps != null ? builder.jsonOps : builder.engine.getJsonOps();
        this.repository = builder.repository;
        this.tenantResolver = builder.tenantResolver;
        this.strategyHooks = builder.strategyHooks;
        this.fullParams = builder.fullParams;
        this.logicBranches = builder.logicBranches;
        this.branchJudge = new BranchJudge(this.jsonOps,
                this.logicBranches != null ? this.logicBranches : new LogicBranchStrategyRegistry());
        this.actions = builder.actions;
        this.gateway = builder.gateway;
        this.logSink = builder.logSink;
        this.logMasker = builder.logMasker;
        this.clock = builder.clock == null ? SystemClockProvider.INSTANCE : builder.clock;
        this.pipeline = new JudgementPipeline(
                builder.resultJudges != null ? builder.resultJudges : ResultJudgeRegistry.withDefaults(this.jsonOps),
                this.jsonOps);
        this.stopOnFailure = builder.stopOnFailure;
        this.failOnMissingStrategy = builder.failOnMissingStrategy;
        this.truncateThreshold = builder.truncateThreshold;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 执行一个接口（出网走 {@link PartnerServiceGateway}）。
     */
    public IfmapResult execute(IfmapRequest request, String interfaceNo, String busiNode) {
        return execute(request, interfaceNo, busiNode, null);
    }

    /**
     * 执行一个接口；{@code mockResponse} 非空时<b>不出网</b>，直接用它当响应（管理端 dry-run / 单测）。
     */
    public IfmapResult execute(IfmapRequest request, String interfaceNo, String busiNode, String mockResponse) {
        // 计时从最前面开始：把配置加载也算进去，端到端耗时才完整（日志与 IfmapResult.elapsedMs 都用它）
        long start = clock.currentTimeMillis();
        // 租户由 TenantResolver 从请求对象/请求头取，都取不到落默认 -1；后续所有查询都带它
        String tenantId = tenantResolver.resolve(request);
        // 起点截断在这里完成：interfaceNo 是「流水线上的起点」，不是「只跑这一步」
        List<IfmapConfig> plan = loadPlan(tenantId, interfaceNo, busiNode);
        // 空计划直接抛：调用方拿到成功+空 data 会误以为「跑完了没输出」，比报错难查得多
        if (plan.isEmpty()) {
            throw new IfmapConfigException("未找到可执行的接口配置：tenantId=" + tenantId
                    + ", interfaceNo=" + interfaceNo + ", busiNode=" + busiNode);
        }
        return runPlan(request, tenantId, plan, mockResponse, start);
    }

    /**
     * 执行一个<b>被调</b>接口（{@code call_direction=INBOUND}）：对方报文原文由 {@code rawBody} 传入，<b>不出网</b>。
     *
     * <p>等价于 {@code execute(request.withRawBody(rawBody), interfaceNo, busiNode)}，单独开一个入口是为了让宿主
     * 一眼看出「这条是对方调我」，而不是靠记住某条配置的方向列。方向本身仍以配置为准：
     * 传了原文但配置是 OUTBOUND，照旧按出网调用执行（原文只作为 payload 的补充载体存在）。</p>
     *
     * @param rawBody 对方推过来的 JSON 原文；判定（{@code $.noticeStatus} 之类）与应答模板都按它的路径取值
     */
    public IfmapResult executeInbound(IfmapRequest request, String interfaceNo, String busiNode, String rawBody) {
        return execute(request.withRawBody(rawBody), interfaceNo, busiNode, null);
    }

    /**
     * 加载执行计划：该业务节点下的<b>全部</b>启用配置，按 {@code interface_order} 升序。
     *
     * <p>{@code interfaceNo} 只作为这条流水线上的<b>起点</b>：从它开始往后跑。
     * 传 null / 空串表示从第一步跑完整个节点。为什么不做隐式截断（比如只跑一步）？
     * 因为一个 {@code busi_node} 就是一条流水线，「起点的位置」就是「进度」——
     * 断点续跑只需要把上次最后成功的接口号当起点传进来即可，不需要额外字段或状态表。</p>
     *
     * @throws IfmapConfigException {@code interfaceNo} 非空但不在该节点的计划内（配置被停用/删除，
     *                              或接口号写错）—— 这种情况静默跑一半比报错危险得多
     */
    private List<IfmapConfig> loadPlan(String tenantId, String interfaceNo, String busiNode) {
        List<IfmapConfig> plan = InterfaceOrdering.sort(InterfaceOrdering.enabled(
                repository.queryConfigsByBusiNode(tenantId, busiNode)));
        String start = trimToNull(interfaceNo);
        if (start == null || plan.isEmpty()) {
            // 空计划交给 runPlan 那侧的「未找到可执行的接口配置」统一报错（消息里带 tenantId/busiNode 更全）
            return plan;
        }
        // 起点 = 下标截断：命中就返回尾段，所以「断点续跑」只需要把上次最后成功的接口号再传进来
        for (int i = 0; i < plan.size(); i++) {
            if (start.equals(plan.get(i).getInterfaceNo())) {
                return new ArrayList<IfmapConfig>(plan.subList(i, plan.size()));
            }
        }
        throw new IfmapConfigException("起点接口不在该业务节点的执行计划内（已停用/已删除/编号写错？）：tenantId="
                + tenantId + ", interfaceNo=" + start + ", busiNode=" + busiNode);
    }

    /**
     * 顺序执行计划；<b>本方法持有跨步骤共享池</b>，逐步把成功的产物注入下一步的参数。
     *
     * <p>池只在步骤判定成功时写入 ⇒ 池的键集合 = 已成功步骤集合 = 断点续跑的进度。</p>
     */
    private IfmapResult runPlan(IfmapRequest request, String tenantId, List<IfmapConfig> plan,
                                String mockResponse, long start) {
        IfmapResult.Builder result = IfmapResult.builder();
        Map<String, Object> pool = initialPool(request);
        boolean success = true;
        String errorMessage = null;
        // 串行执行：一个 busi_node 就是一条流水线，前一步的产物是后一步的入参；池在循环外创建，逐步被填充
        for (IfmapConfig config : plan) {
            StepOutcome outcome = executeOne(request, tenantId, config, mockResponse, pool);
            // 下面三个输出字段都是「覆盖」语义：对外只暴露最后一个实际执行步骤的值
            result.executedInterface(config.getInterfaceNo());
            if (outcome.matchedBranch != null) {
                result.matchedBranch(outcome.matchedBranch);
            }
            if (outcome.rawResponse != null) {
                result.rawResponse(outcome.rawResponse);
            }
            // 失败即记首个失败原因；stopOnFailure=false 时继续跑余下步骤（多留日志、看到完整链路）
            if (!outcome.success) {
                success = false;
                errorMessage = outcome.errorMessage;
                result.failType(outcome.failType);
                if (stopOnFailure) {
                    break;
                }
            }
        }
        // 既有扁平语义保留（调用方读 getData().get("applyNo") 不受影响），另加一个可往返的 pool 结构
        Map<String, Object> data = flattenPool(pool);
        data.put(POOL_KEY, pool);
        return result.success(success).data(data).errorMessage(errorMessage)
                .elapsedMs(clock.currentTimeMillis() - start).build();
    }

    /**
     * 执行单条配置：渲染 → 出网 → 判定 → 分支动作 → 日志。
     *
     * <p><b>独立调用</b>（不在计划里）时自建池，只服务本条步骤；跨步骤共享请用
     * {@link #execute(IfmapRequest, String, String)}。</p>
     */
    public StepOutcome executeOne(IfmapRequest request, String tenantId, IfmapConfig config, String mockResponse) {
        return executeOne(request, tenantId, config, mockResponse, initialPool(request));
    }

    /** 执行单条配置；判定成功时把产物写进共享池 {@code pool}。 */
    private StepOutcome executeOne(IfmapRequest request, String tenantId, IfmapConfig config,
                                   String mockResponse, Map<String, Object> pool) {
        long start = clock.currentTimeMillis();
        RuleContext ruleContext = RuleContext.builder()
                .tenantId(tenantId)
                .interfaceNo(config.getInterfaceNo())
                .attributes(request.getAttributes())
                .build();
        StrategyContext context = StrategyContext.builder()
                .request(request).config(config).ruleContext(ruleContext)
                .params(initialParams(request, config)).build();
        // 参数三层合成，优先级从低到高：组包策略产物 → 池展平键 → 池本体（pool 是保留键，不是业务字段）
        Map<String, Object> requestParams = new LinkedHashMap<String, Object>();
        requestParams.putAll(fullParams.assemble(config.getPartnerCode(), config.getBusiNode(), context));
        // 池在后 ⇒ 覆盖 payload / 组包策略的同名顶层键（pool 是保留键，不是业务字段）
        requestParams.putAll(flattenPool(pool));
        requestParams.put(POOL_KEY, pool);
        context.putParams(requestParams);
        // 被调方向：对方原文即本步的「请求报文」，判定与应答模板都按它的路径取值，不需要也没有可渲染的模板
        boolean inbound = config.inbound();
        String requestJson;
        if (inbound) {
            requestJson = inboundSource(request);
            context.setRequestJson(requestJson);
        } else {
            // 第一次渲染：请求模板只认 $.xxx 与 $.pool.*，所以先把合成好的参数序列化成源报文
            requestJson = render(config.getRequestParamTemplate(), jsonOps.toJson(context.getParams()), ruleContext);
            context.setRequestJson(requestJson);
            // 前序策略返回非空时会按新参数「重新渲染」一次，所以以它返回的报文为准
            requestJson = applyPreStrategy(context, ruleContext, requestJson);
        }

        // mockResponse 非空 = 不出网（管理端 dry-run / 单测），直接把它当对方原样回参；
        // 被调方向本来就不出网，回参是「对方报文原文」，此时 mock 仍优先（dry-run 要能换掉入参）
        PartnerOutcome outcome = mockResponse != null ? PartnerOutcome.ofBody(mockResponse)
                : inbound ? PartnerOutcome.ofBody(requestJson) : exchange(context, requestJson);
        String response = outcome.getBody();
        context.setResponseJson(response);

        // 产物 = 响应模板渲染结果，再交后序策略加工（加工只影响产物，不影响判定）
        Map<String, Object> data = applyPostStrategy(context, renderResponse(config, response, ruleContext));
        // 判定读**原始回参**：后序策略只能加工产物，改不动成败
        Judgement judgement = pipeline.judge(config, response, outcome);
        // 产物回填到 context：后序策略未配置时上面会早返回，这里不设的话动作里 getResponseData() 一直是 null，
        // 「在动作里回填应答字段（改这个 Map 的键）」这条通道就断了（池与返回值用的是同一个对象，改了都可见）
        context.setResponseData(data);
        // 分支只在结果判定成功时求值：失败时选分支既没意义，还会掩盖真实失败原因。
        // 但分支**规格非法**照样能把这一步判失败（fail-closed）——此时结果判定已过，不能算成功。
        String matchedBranch = null;
        if (judgement.isSuccess()) {
            BranchOutcome branch = applyBranch(tenantId, config, context, response, judgement);
            if (branch.getInvalidSpec() != null) {
                judgement = judgement.asBranchFailure("分支判定规格非法：" + branch.getInvalidSpec());
            } else {
                matchedBranch = branch.getMatchedName();
            }
        }
        // 只有判定成功才入池：池的键集合 == 已成功步骤集合 == 断点续跑的进度
        // （必须在分支结论之后：分支 fail-closed 的步骤不算成功，不能进池）
        if (judgement.isSuccess()) {
            pool.put(config.getInterfaceNo(), data);
        }
        String errorMessage = judgement.isSuccess() ? null : failureMessage(judgement);
        long elapsed = clock.currentTimeMillis() - start;
        // 日志放在最终结论之后：记的是真实耗时与最终判定；writeLog 内部吞异常，落库失败不影响返回值。
        // 被调方向下 response 就是 requestJson（回参=原文），再记一遍等于两列同值；此处改为记我方应答（动作回填后的产物）
        writeLog(tenantId, request, config, requestJson, inbound ? toJsonQuietly(data) : response, judgement, elapsed);
        return new StepOutcome(judgement.isSuccess(), data, response, errorMessage, matchedBranch,
                judgement.getFailType());
    }

    /**
     * 前序策略：把本步入参交给策略加工，返回的 Map 合并进参数后<b>重新渲染</b>请求报文。
     *
     * <p>返回 null/空 Map 表示「不改」，保持原文；策略没注册时按 {@code failOnMissingStrategy}
     * 决定抛还是跳过（跳过也保留原文，等价于没配）。</p>
     */
    private String applyPreStrategy(StrategyContext context, RuleContext ruleContext, String requestJson) {
        // 先立相位再查策略：策略内部用同一个 context，靠 phase 判断自己处于前序还是后序
        context.setPhase(StrategyContext.Phase.PRE);
        String strategyName = trimToNull(context.getConfig().getPreStrategyName());
        if (strategyName == null) {
            return requestJson;
        }
        IfmapStrategyHook strategy = strategyHooks.lookup(strategyName);
        if (strategy == null) {
            if (failOnMissingStrategy) {
                throw new IfmapStrategyException("前序策略未注册：pre_strategy_name=" + strategyName
                        + "（可查 " + strategyHooks.keys() + "）");
            }
            LOG.warn("ifmap 前序策略未注册，已跳过：pre_strategy_name={}", strategyName);
            return requestJson;
        }
        // 返回 null/空 Map = 「不改参数」，此时**保持原文不重渲染**（避免无谓地把报文规范化一遍）
        Map<String, Object> extra = strategy.apply(context);
        if (extra == null || extra.isEmpty()) {
            return requestJson;
        }
        // 有改动就必须重渲染：策略只能改参数，不能直接改报文（报文永远由模板决定）
        context.putParams(extra);
        String rendered = render(context.getConfig().getRequestParamTemplate(),
                jsonOps.toJson(context.getParams()), ruleContext);
        context.setRequestJson(rendered);
        return rendered;
    }

    /**
     * 后序策略：响应产物之后、成功判定之前加工本步产物。
     *
     * <p>策略可读 {@link StrategyContext#getResponseData()}（当前产物），用
     * {@link StrategyContext#setResponseData(Object)} 整体替换产物（换成 Map 才生效），
     * 或返回一个 Map 与产物合并（返回键覆盖同键的已有值）。判定读原始回参，故后序策略
     * 影响不了成败判定。</p>
     */
    private Map<String, Object> applyPostStrategy(StrategyContext context, Map<String, Object> data) {
        String strategyName = trimToNull(context.getConfig().getPostStrategyName());
        if (strategyName == null) {
            return data;
        }
        IfmapStrategyHook strategy = strategyHooks.lookup(strategyName);
        if (strategy == null) {
            if (failOnMissingStrategy) {
                throw new IfmapStrategyException("后序策略未注册：post_strategy_name=" + strategyName
                        + "（可查 " + strategyHooks.keys() + "）");
            }
            LOG.warn("ifmap 后序策略未注册，已跳过：post_strategy_name={}", strategyName);
            return data;
        }
        // 后序阶段把「当前产物」也塞进 context，策略既能读也能整体替换（必须换成 Map 才生效）
        context.setPhase(StrategyContext.Phase.POST);
        context.setResponseData(data);
        Map<String, Object> extra = strategy.apply(context);
        Map<String, Object> merged = new LinkedHashMap<String, Object>();
        Object replaced = context.getResponseData();
        if (replaced instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) replaced).entrySet()) {
                merged.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else {
            merged.putAll(data);
        }
        if (extra != null) {
            merged.putAll(extra);
        }
        return merged;
    }

    /**
     * 本步入参的来源。
     *
     * <p>宿主已经结构化传进来的 {@code payload} 是<b>权威</b>的（可能补过值、脱过敏），
     * 只有「被调方向 + payload 为空」时才退化成把对方原文宽松解析成字段 —— 两者混用会让同一个接口
     * 时而读原文时而读 payload，排障时说不清哪来的值。</p>
     */
    private Map<String, Object> initialParams(IfmapRequest request, IfmapConfig config) {
        Map<String, Object> payload = request.getPayload();
        if (!config.inbound() || (payload != null && !payload.isEmpty())) {
            return payload;
        }
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        Object parsed = parseLenient(inboundSource(request));
        if (parsed instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
                fields.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return fields;
    }

    /**
     * 被调方向的「回参」= 对方报文原文。
     *
     * <p>没传原文时退化成 payload 的序列化形态（宿主选择了结构化传参也一样能跑）；
     * 两者都没有就返回 null，交给判定给出 {@code NOT_JSON}，比编一份空报文好查。</p>
     */
    private String inboundSource(IfmapRequest request) {
        String body = trimToNull(request.getRawBody());
        if (body != null) {
            return body;
        }
        Map<String, Object> payload = request.getPayload();
        return payload == null || payload.isEmpty() ? null : jsonOps.toJson(payload);
    }

    /** 产物序列化（只为写日志）：失败返回 null，绝不因为「日志记不下来」影响业务返回。 */
    private String toJsonQuietly(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        try {
            return jsonOps.toJson(data);
        } catch (RuntimeException e) {
            LOG.warn("ifmap 产物序列化失败（日志记为 null）：{}", e.getMessage());
            return null;
        }
    }

    /** 初始池：{@code payload.pool} 为 Map 时浅拷一份（不写回、不持有宿主对象）。 */
    private Map<String, Object> initialPool(IfmapRequest request) {
        Map<String, Object> pool = new LinkedHashMap<String, Object>();
        // 预置池 = payload.pool（断点续跑时宿主把上次的池回放进来）；浅拷一份，不持有宿主对象
        Object preset = request == null ? null : request.getPayload().get(POOL_KEY);
        if (preset instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) preset).entrySet()) {
                pool.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return pool;
    }

    /**
     * 把池里各步的产物摊平到一层（供 {@code $.applyNo} 这类「取最近值」写法使用）。
     *
     * <p>按池的插入顺序 reduce ⇒ 同名键后者胜：预置项（断点续跑回放）在前，本次成功的
     * 步骤按计划顺序在后。要精确取某一步的字段请用 {@code $.pool.{interface_no}.xxx}。</p>
     */
    private Map<String, Object> flattenPool(Map<String, Object> pool) {
        Map<String, Object> flat = new LinkedHashMap<String, Object>();
        // 按池的插入顺序摊平 ⇒ 同名键后者胜：预置项在前，本次成功的步骤按计划顺序在后
        for (Object step : pool.values()) {
            if (!(step instanceof Map)) {
                continue;
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) step).entrySet()) {
                flat.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return flat;
    }

    /**
     * 出网：把宿主实现包成 {@link PartnerOutcome}。
     *
     * <p>{@link IfmapRemoteException} 在这里被<b>收敛成判定失败</b>（而不是抛给宿主）：
     * 传输失败也要能落库归因，并且遵守 {@code stopOnFailure}；其他 {@code RuntimeException}
     * 仍然照原样传播（配置/编程错误不该被判定层吞掉）。</p>
     */
    private PartnerOutcome exchange(StrategyContext context, String requestJson) {
        if (gateway == null) {
            LOG.warn("ifmap 未配置 PartnerServiceGateway，interfaceNo={} 只做本地渲染（dry-run）",
                    context.getConfig().getInterfaceNo());
            return PartnerOutcome.ofBody(null);
        }
        // 网关实现只管「怎么出网」：配置、请求、报文、参数都已装进这份 call
        PartnerCall call = PartnerCall.of(context.getConfig(), context.getRequest(), requestJson, context.getParams());
        try {
            return gateway.exchangeForOutcome(call);
        } catch (IfmapRemoteException e) {
            LOG.warn("ifmap 出网失败，interfaceNo={}：{}", context.getConfig().getInterfaceNo(), e.getMessage());
            // 传输失败收敛成 PartnerOutcome.failure（不带 httpStatus），交给判定管线归因并落库
            return PartnerOutcome.failure(e.getMessage());
        }
    }

    /**
     * 响应模板渲染：模板为空 → 空产物（等于「不提取」）；渲染结果不是 JSON 对象时同样返回空产物。
     *
     * <p>失败与否<b>不在这里判</b> —— 判定读原始回参（{@link JudgementPipeline}），
     * 所以模板写错不会把一笔成功的调用判成失败。</p>
     */
    private Map<String, Object> renderResponse(IfmapConfig config, String response, RuleContext ruleContext) {
        if (response == null || trimToNull(config.getResponseParamTemplate()) == null) {
            return Collections.emptyMap();
        }
        // 响应模板的源报文是「对方原始回参」，不是上一步产物
        String rendered = render(config.getResponseParamTemplate(), response, ruleContext);
        Object parsed = parseLenient(rendered);
        if (parsed instanceof Map) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
                data.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return data;
        }
        return Collections.emptyMap();
    }

    /**
     * 逻辑分支：按 {@code logic_branch_order} 升序取首个命中者，执行其动作。
     *
     * <p><b>兜底分支</b>（{@code branch_judge={"op":"always"}}）不参与常规匹配，只有所有常规分支
     * 都未命中时才生效；即使它排在前面也不会抢常规分支的命中（存量 ECC 的兜底正是这个语义）。</p>
     *
     * <p><b>先全部校验、再匹配</b>：任何一条分支的规格非法，整个接口直接 fail-closed（{@code SPEC_INVALID}），
     * 不取决于"这次回参恰好先命中了哪条"。否则坏配置只在特定报文下才暴露，属于最难查的一类问题。
     * 校验走 {@link BranchJudge} 的进程内缓存，热路径仍是每个分支一次哈希查找。</p>
     *
     * <p>{@code branch_judge} 为空（存量未回填）同样算非法：静默当兜底会让所有流量走错分支。</p>
     */
    private BranchOutcome applyBranch(String tenantId, IfmapConfig config, StrategyContext context, String response,
                                      Judgement judgement) {
        List<LogicBranchConfig> branches = repository.queryLogicBranches(tenantId, config.getInterfaceNo());
        if (branches == null || branches.isEmpty()) {
            LOG.debug("ifmap 接口 {} 无逻辑分支配置", config.getInterfaceNo());
            return BranchOutcome.none();
        }
        // 一趟先校验：坏配置不因为"别的分支先命中"而被掩盖
        for (LogicBranchConfig branch : branches) {
            if (branch == null) {
                continue;
            }
            String raw = trimToNull(branch.getBranchJudge());
            if (raw == null) {
                return BranchOutcome.invalid("分支 " + describeBranch(branch)
                        + " 未配置 branch_judge；兜底分支请写 {\"op\":\"always\"}，"
                        + "存量数据按 docs/10 §3.4 回填");
            }
            String error = branchJudge.errorOf(raw);
            if (error != null) {
                return BranchOutcome.invalid("分支 " + describeBranch(branch) + " 的规格非法：" + error);
            }
        }
        JsonReadContext ctx = readContextQuietly(config, response);
        // 兜底分支（always）先存起来、最后才用：它不能抢常规分支的命中；多余的兜底分支永不生效（保存期校验已拦）
        LogicBranchConfig catchAll = null;
        for (LogicBranchConfig branch : branches) {
            if (branch == null) {
                continue;
            }
            String raw = trimToNull(branch.getBranchJudge());
            if (branchJudge.isAlways(raw)) {
                if (catchAll == null) {
                    catchAll = branch;
                }
                continue;
            }
            BranchJudge.Result result = branchJudge.evaluate(raw, ctx, judgement, context);
            if (!result.isMatched()) {
                continue;
            }
            return BranchOutcome.matched(executeBranch(context, branch));
        }
        if (catchAll != null) {
            LOG.debug("ifmap 接口 {} 常规分支均未命中，走兜底分支（name={}）", config.getInterfaceNo(),
                    catchAll.getLogicBranchName());
            return BranchOutcome.matched(executeBranch(context, catchAll));
        }
        LOG.warn("ifmap 接口 {} 有 {} 条逻辑分支但均未命中（actual={}）", config.getInterfaceNo(),
                branches.size(), judgement.getActualValue());
        return BranchOutcome.none();
    }

    /**
     * 执行分支对应的动作，返回<b>分支名</b>（{@code logic_branch_name}）。
     *
     * <p>返回值是分支名而不是策略键/判定值：日志与 {@code IfmapResult.matchedBranch} 要回答的是
     * "走了哪条业务分支"，而策略键只是实现细节（同一分支可能既用策略又用取值）。</p>
     *
     * <p>{@code method_flag} 为空 = 该分支不执行动作（命中即返回，不兜底拿别的字段当动作 key）。</p>
     */
    private String executeBranch(StrategyContext context, LogicBranchConfig branch) {
        String name = trimToNull(branch.getLogicBranchName());
        context.setMatchedBranch(name);
        String actionKey = trimToNull(branch.getMethodFlag());
        if (actionKey == null) {
            LOG.debug("ifmap 分支 {} 未配置 method_flag，命中后不执行动作", name);
            return name;
        }
        actions.execute(actionKey, context);
        return name;
    }

    /** 分支的日志标识：名称优先，没名字就退到主键（不打印规格原文，太吵）。 */
    private static String describeBranch(LogicBranchConfig branch) {
        String name = trimToNull(branch.getLogicBranchName());
        return name != null ? name : ("#" + branch.getKeyId());
    }

    /**
     * 建立原文读取上下文；对方回参不是 JSON 时返回 {@code null}（按"取不到值"求值）。
     *
     * <p>分支规格里的 {@code path} 读不到东西只意味着"这条分支不命中"，<b>不能</b>因为分支配置
     * 或对方回参格式把一笔已成功的调用判失败。</p>
     */
    private JsonReadContext readContextQuietly(IfmapConfig config, String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        try {
            return jsonOps.readContext(response);
        } catch (RuntimeException e) {
            LOG.warn("ifmap 接口 {} 的分支判定拿不到 JSON 原文（{}），按取不到值处理", config.getInterfaceNo(),
                    e.getMessage());
            return null;
        }
    }

    /** 分支结论：命中（带分支名）/ 无命中 / 规格非法（fail-closed）。 */
    private static final class BranchOutcome {

        private final String matchedName;
        private final String invalidSpec;

        private BranchOutcome(String matchedName, String invalidSpec) {
            this.matchedName = matchedName;
            this.invalidSpec = invalidSpec;
        }

        static BranchOutcome matched(String name) {
            return new BranchOutcome(name, null);
        }

        static BranchOutcome none() {
            return new BranchOutcome(null, null);
        }

        static BranchOutcome invalid(String reason) {
            return new BranchOutcome(null, reason);
        }

        String getMatchedName() {
            return matchedName;
        }

        String getInvalidSpec() {
            return invalidSpec;
        }
    }

    /** 模板为空 → 原样返回 source：把「没配模板」当成「不做变换」，而不是渲染出 "null"。 */
    private String render(String template, String source, RuleContext ruleContext) {
        if (trimToNull(template) == null) {
            return source;
        }
        return engine.render(template, source, ruleContext);
    }

    /** 宽松解析：不是合法 JSON 就返回 null（调用方按「无产物」处理），只打 DEBUG 不抛。 */
    private Object parseLenient(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return jsonOps.parse(text);
        } catch (RuntimeException e) {
            LOG.debug("ifmap 响应模板渲染结果不是合法 JSON：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 落执行日志。
     *
     * <p>{@code error_msg} 写「失败原因 + 抽到的对方消息」两段：前者说明<b>我们</b>怎么判的
     * （未命中 / 取不到字段 / HTTP 500），后者是<b>对方</b>的原话（"余额不足"）——
     * 只有前者时运维还得翻响应报文，只有后者时不知道是判定规格还是通道问题。</p>
     */
    private void writeLog(String tenantId, IfmapRequest request, IfmapConfig config, String requestJson,
                          String response, Judgement judgement, long elapsed) {
        writeLog(tenantId, request, config, requestJson, response, judgement.isSuccess(), elapsed,
                judgement.isSuccess() ? null : failureMessage(judgement), judgement);
    }

    /**
     * 落执行日志的<b>唯一实现</b>（其余全是薄重载）。
     *
     * <p>整段包在 try/catch 里：日志是旁路，落库失败只 WARN —— 不能因为日志表不可用
     * 把一笔已经成功的调用变成异常返回给调用方。</p>
     */
    private void writeLog(String tenantId, IfmapRequest request, IfmapConfig config, String requestJson,
                          String response, boolean success, long elapsed, String errorMessage, Judgement judgement) {
        if (logSink == null) {
            return;
        }
        try {
            ExecutionLog log = new ExecutionLog();
            log.setTenantId(parseTenantId(tenantId));
            log.setInterfaceNo(config == null ? null : config.getInterfaceNo());
            log.setBizId(request.getBizId());
            log.setRequestParam(protect(requestJson));
            log.setResponseParam(protect(response));
            log.setExecutionTime(elapsed);
            log.setExecutionResult(success ? "SUCCESS" : "FAIL");
            log.setErrorMsg(errorMessage);
            if (judgement != null) {
                log.setFailType(judgement.getFailType());
                log.setErrorCode(judgement.getErrorCode());
                log.setTrackId(judgement.getTrackId());
                log.setHttpStatus(judgement.getHttpStatus());
            }
            log.setAddUserId(request.getOperatorId());
            log.setAddRequestId(request.getRequestId());
            logSink.write(log);
        } catch (RuntimeException e) {
            LOG.warn("ifmap 写执行日志失败（不影响业务）：{}", e.getMessage());
        }
    }

    /** 失败原因（判定口径）+ 对方原文（抽到的 message）；两者都空时给 null。 */
    private static String failureMessage(Judgement judgement) {
        String reason = trimToNull(judgement.getReason());
        String message = trimToNull(judgement.getMessage());
        if (reason == null) {
            return message;
        }
        if (message == null) {
            return reason;
        }
        return reason + "：" + message;
    }

    /** 入日志前的加工：<b>先脱敏再截断</b>（顺序反了会把敏感值切成半截，绕过脱敏规则）。 */
    private String protect(String text) {
        String masked = logMasker == null ? text : logMasker.mask(text);
        return Logs.truncate(masked, truncateThreshold);
    }

    /** 租户号落库列是 bigint：非数字租户（如 "T001"）与空值统一记默认租户 {@code -1}。 */
    private static Long parseTenantId(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return Long.valueOf(-1L);
        }
        try {
            return Long.valueOf(tenantId.trim());
        } catch (NumberFormatException e) {
            return Long.valueOf(-1L);
        }
    }

    /** 去空白；空串归一成 null —— 配置里的空字符串一律等价于「未配置」。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 单条配置的执行结果（供编排器汇总）。 */
    public static final class StepOutcome {

        private final boolean success;
        private final Map<String, Object> data;
        private final String rawResponse;
        private final String errorMessage;
        private final String matchedBranch;
        private final String failType;

        StepOutcome(boolean success, Map<String, Object> data, String rawResponse, String errorMessage,
                    String matchedBranch, String failType) {
            this.success = success;
            // 与 IfmapResult 一致：拷一份 + 只交出不可变视图（不拷只包，调用方仍能通过原引用改到内容）
            this.data = data == null ? Collections.<String, Object>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(data));
            this.rawResponse = rawResponse;
            this.errorMessage = errorMessage;
            this.matchedBranch = matchedBranch;
            this.failType = failType;
        }

        public boolean isSuccess() {
            return success;
        }

        public Map<String, Object> getData() {
            return data;
        }

        public String getRawResponse() {
            return rawResponse;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getMatchedBranch() {
            return matchedBranch;
        }

        /** 失败类型（成功为 null），供 {@code IfmapResult} 归因。 */
        public String getFailType() {
            return failType;
        }
    }

    /** 构造器。 */
    public static final class Builder {

        private IfmapEngine engine;
        private JsonOps jsonOps;
        private ConfigRepository repository;
        private TenantResolver tenantResolver;
        private StrategyHookRegistry strategyHooks;
        private FullParamStrategyRegistry fullParams;
        private LogicBranchStrategyRegistry logicBranches;
        private ActionRegistry actions;
        private PartnerServiceGateway gateway;
        private ExecutionLogSink logSink;
        private LogMasker logMasker;
        private ClockProvider clock;
        private ResultJudgeRegistry resultJudges;
        private boolean stopOnFailure = true;
        private boolean failOnMissingStrategy = true;
        private int truncateThreshold;

        public Builder engine(IfmapEngine engine) {
            this.engine = engine;
            return this;
        }

        public Builder jsonOps(JsonOps jsonOps) {
            this.jsonOps = jsonOps;
            return this;
        }

        public Builder repository(ConfigRepository repository) {
            this.repository = repository;
            return this;
        }

        public Builder tenantResolver(TenantResolver tenantResolver) {
            this.tenantResolver = tenantResolver;
            return this;
        }

        public Builder strategyHooks(StrategyHookRegistry strategyHooks) {
            this.strategyHooks = strategyHooks;
            return this;
        }

        public Builder fullParams(FullParamStrategyRegistry fullParams) {
            this.fullParams = fullParams;
            return this;
        }

        public Builder logicBranches(LogicBranchStrategyRegistry logicBranches) {
            this.logicBranches = logicBranches;
            return this;
        }

        public Builder actions(ActionRegistry actions) {
            this.actions = actions;
            return this;
        }

        public Builder gateway(PartnerServiceGateway gateway) {
            this.gateway = gateway;
            return this;
        }

        public Builder logSink(ExecutionLogSink logSink) {
            this.logSink = logSink;
            return this;
        }

        public Builder logMasker(LogMasker logMasker) {
            this.logMasker = logMasker;
            return this;
        }

        public Builder clock(ClockProvider clock) {
            this.clock = clock;
            return this;
        }

        public Builder stopOnFailure(boolean stopOnFailure) {
            this.stopOnFailure = stopOnFailure;
            return this;
        }

        public Builder failOnMissingStrategy(boolean failOnMissingStrategy) {
            this.failOnMissingStrategy = failOnMissingStrategy;
            return this;
        }

        public Builder resultJudges(ResultJudgeRegistry resultJudges) {
            this.resultJudges = resultJudges;
            return this;
        }

        public Builder truncateThreshold(int truncateThreshold) {
            this.truncateThreshold = truncateThreshold;
            return this;
        }

        public IfmapOrchestrator build() {
            if (engine == null) {
                throw new IllegalArgumentException("engine 不能为 null");
            }
            if (repository == null) {
                throw new IllegalArgumentException("repository 不能为 null");
            }
            if (tenantResolver == null) {
                throw new IllegalArgumentException("tenantResolver 不能为 null");
            }
            if (strategyHooks == null) {
                this.strategyHooks = new StrategyHookRegistry();
            }
            if (fullParams == null) {
                this.fullParams = new FullParamStrategyRegistry();
            }
            if (logicBranches == null) {
                this.logicBranches = new LogicBranchStrategyRegistry();
            }
            if (actions == null) {
                this.actions = new ActionRegistry();
            }
            return new IfmapOrchestrator(this);
        }
    }
}
