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
import cn.cj.ifmap.core.exception.IfmapStrategyException;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.model.PartnerCall;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.rule.RuleContext;
import cn.cj.ifmap.core.spi.PartnerServiceGateway;
import cn.cj.ifmap.core.spi.ClockProvider;
import cn.cj.ifmap.core.spi.ExecutionLogSink;
import cn.cj.ifmap.core.spi.LogMasker;
import cn.cj.ifmap.core.spi.SystemClockProvider;
import cn.cj.ifmap.core.spi.TenantResolver;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.CallbackRegistry;
import cn.cj.ifmap.core.strategy.FullParamStrategyRegistry;
import cn.cj.ifmap.core.strategy.LogicBranchStrategyRegistry;
import cn.cj.ifmap.core.strategy.SpecialDealStrategy;
import cn.cj.ifmap.core.strategy.SpecialDealStrategyRegistry;
import cn.cj.ifmap.core.strategy.StrategyContext;
import cn.cj.ifmap.core.util.Logs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接口执行编排器：把 §7.1 的主流程串起来，语义与存量引擎一致。
 *
 * <pre>
 * IfmapRequest
 *   → 租户解析（含 tenantId 的缓存键在仓储/缓存层）
 *   → 配置查询 + 启用过滤 + interface_order 排序 + front_interface_no 拓扑排序（含环检测）
 *   → 主参数组包 FullParamStrategy → 请求模板渲染 → 特殊处理 SpecialDealStrategy
 *   → 出网调用 PartnerServiceGateway（宿主机实现；也可用 mockResponse 做 dry-run）
 *   → 响应模板解析 → 结果判定 result_flag + success_value
 *   → 逻辑分支（logic_branch_order 升序，首个命中；兜底分支最后）→ ActionRegistry 执行动作
 *   → 执行日志（脱敏 + 截断后落库）
 * </pre>
 *
 * <p>与存量的行为差异（均已在上方标注）：排序兜底、环检测、分支/动作未命中不静默、
 * 写日志失败只 WARN、异常不强制转换。</p>
 *
 * @author caijun
 */
public final class IfmapOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(IfmapOrchestrator.class);

    /** 前置接口链的最大加载深度（防止配置成环时无限查库）。 */
    public static final int MAX_FRONT_DEPTH = 64;

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
    private final SpecialDealStrategyRegistry specialDeals;
    private final FullParamStrategyRegistry fullParams;
    private final LogicBranchStrategyRegistry logicBranches;
    private final ActionRegistry actions;
    private final CallbackRegistry callbacks;
    private final PartnerServiceGateway gateway;
    private final ExecutionLogSink logSink;
    private final LogMasker logMasker;
    private final ClockProvider clock;
    private final ResponseJudge judge;
    private final boolean stopOnFailure;
    private final boolean failOnMissingStrategy;
    private final int truncateThreshold;

    private IfmapOrchestrator(Builder builder) {
        this.engine = builder.engine;
        this.jsonOps = builder.jsonOps != null ? builder.jsonOps : builder.engine.getJsonOps();
        this.repository = builder.repository;
        this.tenantResolver = builder.tenantResolver;
        this.specialDeals = builder.specialDeals;
        this.fullParams = builder.fullParams;
        this.logicBranches = builder.logicBranches;
        this.actions = builder.actions;
        this.callbacks = builder.callbacks;
        this.gateway = builder.gateway;
        this.logSink = builder.logSink;
        this.logMasker = builder.logMasker;
        this.clock = builder.clock == null ? SystemClockProvider.INSTANCE : builder.clock;
        this.judge = new ResponseJudge(this.jsonOps);
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
        long start = clock.currentTimeMillis();
        String tenantId = tenantResolver.resolve(request);
        List<IfmapConfig> plan = loadPlan(tenantId, interfaceNo, busiNode);
        if (plan.isEmpty()) {
            throw new IfmapConfigException("未找到可执行的接口配置：tenantId=" + tenantId
                    + ", interfaceNo=" + interfaceNo + ", busiNode=" + busiNode);
        }
        return runPlan(request, tenantId, plan, mockResponse, start);
    }

    /**
     * 加载执行计划：本接口 + {@code front_interface_no} 递归前置链，再统一排序（含环检测）。
     *
     * <p>存在加载深度上限（{@value #MAX_FRONT_DEPTH}），防止配置把链写成环时无限查库。</p>
     */
    private List<IfmapConfig> loadPlan(String tenantId, String interfaceNo, String busiNode) {
        List<IfmapConfig> collected = new java.util.ArrayList<IfmapConfig>();
        Set<String> loaded = new LinkedHashSet<String>();
        Deque<String> pending = new ArrayDeque<String>();
        pending.add(interfaceNo);
        while (!pending.isEmpty()) {
            String current = pending.poll();
            if (current == null || !loaded.add(current)) {
                continue;
            }
            if (loaded.size() > MAX_FRONT_DEPTH) {
                throw new IfmapConfigException("前置接口链过深（>" + MAX_FRONT_DEPTH + "），疑似存在循环依赖");
            }
            List<IfmapConfig> configs = repository.queryConfigs(tenantId, current, busiNode);
            if (configs == null) {
                continue;
            }
            collected.addAll(configs);
            for (IfmapConfig config : configs) {
                if (config == null) {
                    continue;
                }
                String front = trimToNull(config.getFrontInterfaceNo());
                if (front != null && !loaded.contains(front)) {
                    pending.add(front);
                }
            }
        }
        return InterfaceOrdering.plan(collected);
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
        for (IfmapConfig config : plan) {
            StepOutcome outcome = executeOne(request, tenantId, config, mockResponse, pool);
            result.executedInterface(config.getInterfaceNo());
            if (outcome.matchedBranch != null) {
                result.matchedBranch(outcome.matchedBranch);
            }
            if (outcome.rawResponse != null) {
                result.rawResponse(outcome.rawResponse);
            }
            if (!outcome.success) {
                success = false;
                errorMessage = outcome.errorMessage;
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
                .params(request.getPayload()).build();
        Map<String, Object> requestParams = new LinkedHashMap<String, Object>();
        requestParams.putAll(fullParams.assemble(config.getPartnerCode(), config.getBusiNode(), context));
        // 池在后 ⇒ 覆盖 payload / 组包策略的同名顶层键（pool 是保留键，不是业务字段）
        requestParams.putAll(flattenPool(pool));
        requestParams.put(POOL_KEY, pool);
        context.putParams(requestParams);
        String requestJson = render(config.getRequestParamTemplate(), jsonOps.toJson(context.getParams()), ruleContext);
        context.setRequestJson(requestJson);
        requestJson = applySpecialDeal(context, ruleContext, requestJson);

        String response = mockResponse != null ? mockResponse : exchange(context, requestJson);
        context.setResponseJson(response);

        Map<String, Object> data = applyPostDeal(context, renderResponse(config, response, ruleContext));
        // 判定读**原始回参**：后序策略只能加工产物，改不动成败
        Judgement judgement = judge.evaluate(config, response);
        if (judgement.isSuccess()) {
            pool.put(config.getInterfaceNo(), data);
        }
        String matchedBranch = judgement.isSuccess() ? applyBranch(request, tenantId, config, context, response, judgement) : null;
        String errorMessage = judgement.isSuccess() ? null : judgement.getReason();
        long elapsed = clock.currentTimeMillis() - start;
        writeLog(tenantId, request, config, requestJson, response, judgement.isSuccess(), elapsed, errorMessage);
        return new StepOutcome(judgement.isSuccess(), data, response, errorMessage, matchedBranch);
    }

    private String applySpecialDeal(StrategyContext context, RuleContext ruleContext, String requestJson) {
        context.setPhase(StrategyContext.Phase.PRE);
        String strategyName = trimToNull(context.getConfig().getPreStrategyName());
        if (strategyName == null) {
            return requestJson;
        }
        SpecialDealStrategy strategy = specialDeals.lookup(strategyName);
        if (strategy == null) {
            if (failOnMissingStrategy) {
                throw new IfmapStrategyException("前序策略未注册：pre_strategy_name=" + strategyName
                        + "（可查 " + specialDeals.keys() + "）");
            }
            LOG.warn("ifmap 前序策略未注册，已跳过：pre_strategy_name={}", strategyName);
            return requestJson;
        }
        Map<String, Object> extra = strategy.apply(context);
        if (extra == null || extra.isEmpty()) {
            return requestJson;
        }
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
    private Map<String, Object> applyPostDeal(StrategyContext context, Map<String, Object> data) {
        String strategyName = trimToNull(context.getConfig().getPostStrategyName());
        if (strategyName == null) {
            return data;
        }
        SpecialDealStrategy strategy = specialDeals.lookup(strategyName);
        if (strategy == null) {
            if (failOnMissingStrategy) {
                throw new IfmapStrategyException("后序策略未注册：post_strategy_name=" + strategyName
                        + "（可查 " + specialDeals.keys() + "）");
            }
            LOG.warn("ifmap 后序策略未注册，已跳过：post_strategy_name={}", strategyName);
            return data;
        }
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

    /** 初始池：{@code payload.pool} 为 Map 时浅拷一份（不写回、不持有宿主对象）。 */
    private Map<String, Object> initialPool(IfmapRequest request) {
        Map<String, Object> pool = new LinkedHashMap<String, Object>();
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

    private String exchange(StrategyContext context, String requestJson) {
        if (gateway == null) {
            LOG.warn("ifmap 未配置 PartnerServiceGateway，interfaceNo={} 只做本地渲染（dry-run）",
                    context.getConfig().getInterfaceNo());
            return null;
        }
        PartnerCall call = PartnerCall.of(context.getConfig(), context.getRequest(), requestJson, context.getParams());
        return gateway.exchange(call);
    }

    private Map<String, Object> renderResponse(IfmapConfig config, String response, RuleContext ruleContext) {
        if (response == null || trimToNull(config.getResponseParamTemplate()) == null) {
            return Collections.emptyMap();
        }
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
     * <p><b>兜底分支</b>（{@code logic_branch_flag} 为空，存量用"flag、value 皆空"表达）
     * 不参与常规匹配，只有所有常规分支都未命中时才生效；即使它排在前面也不会抢常规
     * 分支的命中。存量 ECC 的兜底分支正是这个语义（先正常匹配、失败再取 blank flag 的那条），
     * 所以迁移过来的配置行为不变。</p>
     */
    private String applyBranch(IfmapRequest request, String tenantId, IfmapConfig config,
                               StrategyContext context, String response, Judgement judgement) {
        List<LogicBranchConfig> branches = repository.queryLogicBranches(tenantId, config.getInterfaceNo());
        if (branches == null || branches.isEmpty()) {
            LOG.debug("ifmap 接口 {} 无逻辑分支配置", config.getInterfaceNo());
            return null;
        }
        LogicBranchConfig catchAll = null;
        for (LogicBranchConfig branch : branches) {
            if (branch == null) {
                continue;
            }
            if (trimToNull(branch.getLogicBranchFlag()) == null) {
                // 兜底分支：留到最后；多余的兜底分支永不生效（admin 侧校验会拦）
                if (catchAll == null) {
                    catchAll = branch;
                }
                continue;
            }
            if (!matches(branch, context, judgement)) {
                continue;
            }
            return executeBranch(context, branch);
        }
        if (catchAll != null) {
            LOG.debug("ifmap 接口 {} 常规分支均未命中，走兜底分支（action={}）", config.getInterfaceNo(),
                    catchAll.getMethodFlag());
            return executeBranch(context, catchAll);
        }
        LOG.warn("ifmap 接口 {} 有 {} 条逻辑分支但均未命中（result={}）", config.getInterfaceNo(),
                branches.size(), judgement.getActualValue());
        return null;
    }

    /** 执行分支对应的动作，返回分支标识（{@code logic_branch_flag}）。 */
    private String executeBranch(StrategyContext context, LogicBranchConfig branch) {
        String flag = trimToNull(branch.getLogicBranchFlag());
        context.setMatchedBranch(flag);
        String actionKey = trimToNull(branch.getMethodFlag());
        actions.execute(actionKey == null ? flag : actionKey, context);
        return flag;
    }

    /** 常规分支是否命中：{@code logic_branch_flag} 策略命中，或判定值在 {@code logic_branch_value} 列表内。 */
    private boolean matches(LogicBranchConfig branch, StrategyContext context, Judgement judgement) {
        String flag = trimToNull(branch.getLogicBranchFlag());
        if (logicBranches.matches(flag, context)) {
            return true;
        }
        String value = trimToNull(branch.getLogicBranchValue());
        if (value == null || judgement.getActualValue() == null) {
            return false;
        }
        for (String candidate : value.split("[;,]")) {
            if (candidate.trim().equalsIgnoreCase(judgement.getActualValue().trim())) {
                return true;
            }
        }
        return false;
    }

    /** 银行回调入口（替换存量 17 个回调方法）。 */
    public IfmapResult callback(IfmapRequest request, String interfaceNo, String rawBody) {
        long start = clock.currentTimeMillis();
        String tenantId = tenantResolver.resolve(request);
        IfmapResult result = callbacks.dispatch(interfaceNo, request, rawBody);
        writeLog(tenantId, request, null, rawBody, null, result.isSuccess(), clock.currentTimeMillis() - start,
                result.getErrorMessage());
        return result;
    }

    private String render(String template, String source, RuleContext ruleContext) {
        if (trimToNull(template) == null) {
            return source;
        }
        return engine.render(template, source, ruleContext);
    }

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

    private void writeLog(String tenantId, IfmapRequest request, IfmapConfig config, String requestJson,
                          String response, boolean success, long elapsed, String errorMessage) {
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
            log.setAddUserId(request.getOperatorId());
            log.setAddRequestId(request.getRequestId());
            logSink.write(log);
        } catch (RuntimeException e) {
            LOG.warn("ifmap 写执行日志失败（不影响业务）：{}", e.getMessage());
        }
    }

    private String protect(String text) {
        String masked = logMasker == null ? text : logMasker.mask(text);
        return Logs.truncate(masked, truncateThreshold);
    }

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

        StepOutcome(boolean success, Map<String, Object> data, String rawResponse, String errorMessage,
                    String matchedBranch) {
            this.success = success;
            // 与 IfmapResult 一致：拷一份 + 只交出不可变视图（不拷只包，调用方仍能通过原引用改到内容）
            this.data = data == null ? Collections.<String, Object>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(data));
            this.rawResponse = rawResponse;
            this.errorMessage = errorMessage;
            this.matchedBranch = matchedBranch;
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
    }

    /** 构造器。 */
    public static final class Builder {

        private IfmapEngine engine;
        private JsonOps jsonOps;
        private ConfigRepository repository;
        private TenantResolver tenantResolver;
        private SpecialDealStrategyRegistry specialDeals;
        private FullParamStrategyRegistry fullParams;
        private LogicBranchStrategyRegistry logicBranches;
        private ActionRegistry actions;
        private CallbackRegistry callbacks;
        private PartnerServiceGateway gateway;
        private ExecutionLogSink logSink;
        private LogMasker logMasker;
        private ClockProvider clock;
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

        public Builder specialDeals(SpecialDealStrategyRegistry specialDeals) {
            this.specialDeals = specialDeals;
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

        public Builder callbacks(CallbackRegistry callbacks) {
            this.callbacks = callbacks;
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
            if (specialDeals == null) {
                this.specialDeals = new SpecialDealStrategyRegistry();
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
            if (callbacks == null) {
                this.callbacks = new CallbackRegistry();
            }
            return new IfmapOrchestrator(this);
        }
    }
}
