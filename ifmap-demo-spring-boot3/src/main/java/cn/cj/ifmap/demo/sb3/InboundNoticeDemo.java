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
package cn.cj.ifmap.demo.sb3;

import cn.cj.ifmap.core.config.CallDirection;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 演示「被调方向」({@code call_direction=INBOUND})：<b>对方调我方</b>，我方不组包、不出网，
 * 只做「判定对方报文是否可受理 → 按逻辑分支分流 → 组装应答报文」。
 *
 * <p>与 {@link DemoRunner} 正好是镜像关系：</p>
 * <table border="1">
 *   <caption>两个方向在同一套抽象里的差别</caption>
 *   <tr><th>维度</th><th>OUTBOUND（DemoRunner）</th><th>INBOUND（本例）</th></tr>
 *   <tr><td>报文来源</td><td>请求模板渲染 {@code params}</td><td>对方原文（{@code rawBody}），不渲染</td></tr>
 *   <tr><td>前序策略</td><td>执行</td><td><b>不执行</b>（没有要加工的请求）</td></tr>
 *   <tr><td>出网</td><td>{@code PartnerServiceGateway.exchange}</td><td><b>不出网</b>（回参就是原文）</td></tr>
 *   <tr><td>判定 / 分支 / 应答模板</td><td colspan="2">完全一致（同一段代码）</td></tr>
 * </table>
 *
 * <p>三个演示用例对应三种终态（见 {@code docs/12-被调接口配置指南.md} §7）：</p>
 * <ol>
 *   <li>报文可受理 + 业务成功 → 分支动作 {@code noticeAccepted} → 应答 {@code code=0000}；</li>
 *   <li>报文可受理 + 业务失败 → 分支动作 {@code noticeFailed} → 动作<b>原地改应答 Map</b>，改成 {@code 2001 + retryable}；</li>
 *   <li>报文不可受理（状态不认识）→ 判定失败、<b>不进任何分支</b> → 没有任何应答，
 *       宿主在接口层自己回 {@code 1xxx}（引擎不做兜底应答，理由见 {@code docs/12} §7）。</li>
 * </ol>
 *
 * @author caijun
 */
@Component
@Order(2)
public class InboundNoticeDemo implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InboundNoticeDemo.class);

    private static final String TENANT = "1001";
    private static final String INTERFACE_NO = "BIZ_NOTICE";
    private static final String BUSI_NODE = "notice";

    /**
     * 应答模板（ACK）：根是<b>对方原文</b>，所以路径都从对方的报文上取。
     * {@code "0000"} / {@code "success"} 不带 {@code $.} 前缀 ⇒ 模板里的字面量，原样输出。
     *
     * <p><b>为什么对方流水号写 {@code $.['seqNo']} 而不是 {@code $.seqNo}</b>：{@code $.seqNo}
     * 是模板 DSL 的<b>保留路径</b>（见 {@code docs/02} §1 第 7 条），它不看源报文、直接生成一个我方流水号。
     * 要取源报文里真名叫 {@code seqNo} 的字段，只能走方括号写法。少写那个点（{@code $['seqNo']}）
     * 更糟：不以 {@code $.} 开头 ⇒ 被当成<b>字面量</b>原样输出，静默出错。</p>
     */
    private static final String ACK_TEMPLATE = "{"
            + "\"code\":\"0000\","
            + "\"message\":\"success\","
            + "\"applyNo\":\"$.applyNo\","
            + "\"partnerSeqNo\":\"$.['seqNo']\","
            + "\"ourSeqNo\":\"$.seqNo\""
            + "}";

    /**
     * 判定口径是「报文<b>可受理</b>」，不是「业务成败」：{@code SUCCESS} 与 {@code FAILED}
     * 都是能处理的报文（各自走不同分支），只有不认识的状态才算拒收。
     * 写成单条 eq 会把「放款失败的通知」也判成不可受理，应答分支就永远进不去。
     */
    private static final String INBOUND_JUDGE = "{\"any\":["
            + "{\"path\":\"$.noticeStatus\",\"op\":\"eq\",\"value\":\"SUCCESS\"},"
            + "{\"path\":\"$.noticeStatus\",\"op\":\"eq\",\"value\":\"FAILED\"}]}";

    private final ConfigRepository repository;
    private final JdbcConfigWriter writer;
    private final IfmapOrchestrator orchestrator;

    public InboundNoticeDemo(ConfigRepository repository, JdbcConfigWriter writer,
                             IfmapOrchestrator orchestrator) {
        this.repository = repository;
        this.writer = writer;
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfAbsent();
        log.info("== 被调方向演示（call_direction=INBOUND）：资方调我方，我方按配置判定 + 分流 + 应答 ==");

        call("成功通知", "{"
                + "\"seqNo\":\"SEQ-9001\","
                + "\"applyNo\":\"AP20250101001\","
                + "\"noticeStatus\":\"SUCCESS\","
                + "\"paidAmount\":\"10000.00\""
                + "}");

        call("失败通知", "{"
                + "\"seqNo\":\"SEQ-9002\","
                + "\"applyNo\":\"AP20250101001\","
                + "\"noticeStatus\":\"FAILED\","
                + "\"failReason\":\"放款账户冻结\""
                + "}");

        call("状态不认识（应拒收）", "{"
                + "\"seqNo\":\"SEQ-9003\","
                + "\"applyNo\":\"AP20250101001\","
                + "\"noticeStatus\":\"PENDING\""
                + "}");

        log.info("提示：日志表里这三行的「响应」列存的是「我方应答」而不是对方原文 —— "
                + "被调方向下两者本来就是同一份报文的两端，记两遍没有信息量");
    }

    /** 模拟一次「对方调我方」：rawBody 是对方推过来的原文，宿主只需把它交给编排器。 */
    private void call(String scene, String rawBody) {
        IfmapRequest request = IfmapRequest.builder()
                .tenantId(TENANT)
                .bizId("BIZ-NOTICE-0001")
                .operatorId("partner")
                .requestId("REQ-NOTICE-" + System.nanoTime())
                .build();
        // 就这一个入口：不出网、不渲染请求模板，判定与应答模板都按 rawBody 的路径取值
        IfmapResult result = orchestrator.executeInbound(request, INTERFACE_NO, BUSI_NODE, rawBody);

        Map<String, Object> ack = ackOf(result);
        if (ack == null) {
            // 拒收：判定没过 ⇒ 分支没跑 ⇒ 池里没有这一步的产物。这里必须由宿主自己回一个错误应答
            log.info("[{}] 不可受理：failType={}, 命中分支={}, 我方应答=<没有，需宿主返回 1xxx 拒绝码>（原因：{}）",
                    scene, result.getFailType(), result.getMatchedBranch(), result.getErrorMessage());
            return;
        }
        log.info("[{}] 可受理：命中分支={}, 我方应答={}", scene, result.getMatchedBranch(), ack);
    }

    /**
     * 取本步产物：成功步骤才会写进共享池，所以「池里有没有这一步」就是「有没有应答」。
     *
     * @return 应答 Map（动作原地改过的就是它）；判失败时为 {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> ackOf(IfmapResult result) {
        Object pool = result.getData() == null ? null : result.getData().get(IfmapOrchestrator.POOL_KEY);
        if (!(pool instanceof Map)) {
            return null;
        }
        Object ack = ((Map<String, Object>) pool).get(INTERFACE_NO);
        return ack instanceof Map ? (Map<String, Object>) ack : null;
    }

    /** 幂等写入演示配置（重复启动不会插重）。 */
    private void seedIfAbsent() {
        if (!repository.queryConfigs(TENANT, INTERFACE_NO, BUSI_NODE).isEmpty()) {
            return;
        }
        IfmapConfig config = new IfmapConfig();
        config.setTenantId(Long.valueOf(TENANT));
        config.setInterfaceNo(INTERFACE_NO);
        config.setInterfaceCode("BIZ_NOTICE_001");
        config.setInterfaceName("放款结果通知-被调");
        config.setBusiNode(BUSI_NODE);
        config.setPartnerCode("CMB");
        config.setPartnerName("招商银行");
        // 被调方向必须是本节点第一步：它不产出「请求」，排在出网步骤之后毫无意义
        config.setInterfaceOrder(Integer.valueOf(1));
        config.setCallDirection(CallDirection.INBOUND);
        // 请求模板与前序策略留空：INBOUND 下它们不参与执行（管理端保存时也会各报一个 error）
        config.setRequestParamTemplate(null);
        config.setPreStrategyName(null);
        config.setResponseParamTemplate(ACK_TEMPLATE);
        config.setResultJudge(INBOUND_JUDGE);
        config.setStatus(Integer.valueOf(1));
        long keyId = writer.insert(config);
        log.info("已写入被调配置 keyId={}（INBOUND / 无请求模板 / 无前序策略）", keyId);

        // 两条常规分支：branch_judge 自己从原文取值判断（顶层只能 always / all / any，
        // 单条件就包一层 any）。先后顺序由 logic_branch_order 决定，首个命中即执行。
        // 也可以写 {"any":[{"op":"judged","value":["SUCCESS"]}]}：judged 比的是 result_judge
        // 命中的那个值（actualValue），这里用路径表达式更直观、也不依赖判定内部实现。
        insertBranch("受理分支", "{\"any\":[{\"path\":\"$.noticeStatus\",\"op\":\"eq\",\"value\":\"SUCCESS\"}]}",
                1, "noticeAccepted");
        insertBranch("失败分支", "{\"any\":[{\"path\":\"$.noticeStatus\",\"op\":\"eq\",\"value\":\"FAILED\"}]}",
                2, "noticeFailed");
    }

    private void insertBranch(String name, String branchJudge, int order, String methodFlag) {
        LogicBranchConfig branch = new LogicBranchConfig();
        branch.setTenantId(Long.valueOf(TENANT));
        branch.setInterfaceNo(INTERFACE_NO);
        branch.setMethodFlag(methodFlag);
        branch.setLogicBranchName(name);
        // 必填：留空是"非法配置"（运行期 fail-closed），不是"兜底"。兜底要显式写 {"op":"always"}
        branch.setBranchJudge(branchJudge);
        branch.setLogicBranchOrder(Integer.valueOf(order));
        long branchId = writer.insert(branch);
        log.info("已写入逻辑分支 keyId={}（{}：judge={} / 动作={}）", branchId, name, branchJudge, methodFlag);
    }
}
