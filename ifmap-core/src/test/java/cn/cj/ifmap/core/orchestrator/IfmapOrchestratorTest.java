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
import cn.cj.ifmap.core.config.CallDirection;
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.exception.IfmapRemoteException;
import cn.cj.ifmap.core.exception.IfmapStrategyException;
import cn.cj.ifmap.core.model.PartnerCall;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.spi.PartnerOutcome;
import cn.cj.ifmap.core.spi.PartnerServiceGateway;
import cn.cj.ifmap.core.spi.ClockProvider;
import cn.cj.ifmap.core.spi.DefaultLogMasker;
import cn.cj.ifmap.core.spi.HeaderTenantResolver;
import cn.cj.ifmap.core.spi.RepositoryExecutionLogSink;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.FullParamStrategy;
import cn.cj.ifmap.core.strategy.FullParamStrategyRegistry;
import cn.cj.ifmap.core.strategy.IfmapAction;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.strategy.IfmapStrategyHook;
import cn.cj.ifmap.core.strategy.LogicBranch;
import cn.cj.ifmap.core.strategy.LogicBranchStrategy;
import cn.cj.ifmap.core.strategy.LogicBranchStrategyRegistry;
import cn.cj.ifmap.core.strategy.StrategyHookRegistry;
import cn.cj.ifmap.core.strategy.StrategyContext;
import cn.cj.ifmap.core.testkit.TestConfigs;
import cn.cj.ifmap.core.testkit.TestJsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 编排器端到端：渲染 → 出网 → 判定 → 分支动作 → 日志（全部用内存 stub，不碰 DB / 网络）。 */
class IfmapOrchestratorTest {

    /** always 形态：不读响应，等效于"整段不判"。 */
    private static final String ALWAYS = "{\"op\":\"always\"}";
    /** 最常见的单条件形态：$.code == 0000。 */
    private static final String CODE_0000 =
            "{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"}]}";
    /**
     * 被调方向的判定口径：<b>报文可受理</b>（成功/失败都算受理），业务分流交给逻辑分支。
     *
     * <p>不能写成「$.noticeStatus == SUCCESS」——那会把「对方通知我失败」判成引擎失败，
     * 于是处理失败的分支永远走不到。</p>
     */
    private static final String NOTICE_ACCEPTED = "{\"any\":["
            + "{\"path\":\"$.noticeStatus\",\"op\":\"eq\",\"value\":\"SUCCESS\"},"
            + "{\"path\":\"$.noticeStatus\",\"op\":\"eq\",\"value\":\"FAILED\"}]}";

    /** 宿主规则。 */
    public static class Rules {
        @IfmapRule("orgNo")
        public String orgNo(String code) {
            return String.format("%06d", Integer.parseInt(code));
        }
    }

    /** 补参数策略。 */
    public static class ExtraParamStrategy implements IfmapStrategyHook {
        @Override
        public Map<String, Object> apply(StrategyContext context) {
            Map<String, Object> extra = new LinkedHashMap<String, Object>();
            extra.put("extra", "FROM_STRATEGY");
            return extra;
        }
    }

    /** 记录相位；后序返回 {@code postApplied}，前序返回 {@code preApplied}。 */
    static final class PhaseRecordingStrategy implements IfmapStrategyHook {
        final List<StrategyContext.Phase> phases = new ArrayList<StrategyContext.Phase>();

        @Override
        public Map<String, Object> apply(StrategyContext context) {
            phases.add(context.getPhase());
            Map<String, Object> extra = new LinkedHashMap<String, Object>();
            extra.put(context.getPhase() == StrategyContext.Phase.POST ? "postApplied" : "preApplied", "YES");
            return extra;
        }
    }

    /** 组包策略。 */
    @cn.cj.ifmap.core.strategy.FullParam(partnerCode = "CMB", busiNode = "GP81")
    public static class CmbFullParam implements FullParamStrategy {
        @Override
        public Map<String, Object> assemble(StrategyContext context) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("fullParam", "YES");
            return result;
        }
    }

    /** 分支动作。 */
    @IfmapAction("doSubmit")
    public static class SubmitAction implements IfmapActionHandler {
        @Override
        public void execute(StrategyContext context) {
            context.putParam("submitted", Boolean.TRUE);
        }
    }

    /** 策略通道：{@code branch_judge} 里的 {@code strategy} 算子按这个 key 找实现。 */
    @LogicBranch("submit")
    public static class SubmitStrategy implements LogicBranchStrategy {
        @Override
        public boolean match(StrategyContext context) {
            return true;
        }
    }

    /** 记录动作调用（断言"哪个分支的动作被执行了"）。 */
    static final class Recorder {
        final List<String> executed = new ArrayList<String>();
    }

    /** 常规分支动作（记录调用）。 */
    @IfmapAction("recSubmit")
    public static class RecSubmitAction implements IfmapActionHandler {
        private final Recorder recorder;

        RecSubmitAction(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public void execute(StrategyContext context) {
            recorder.executed.add("submit");
        }
    }

    /** 默认兜底分支动作（记录调用）。 */
    @IfmapAction("recFallback")
    public static class RecFallbackAction implements IfmapActionHandler {
        private final Recorder recorder;

        RecFallbackAction(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public void execute(StrategyContext context) {
            recorder.executed.add("fallback");
        }
    }

    /**
     * 被调方向的分支动作：记下读到的入参，并往产物里回填一个字段。
     *
     * <p>回填走的是「原地改 {@code ctx.getResponseData()} 那个 Map」——被调方向没法在动作里组装应答报文，
     * 只能补字段；池与返回值用的是同一个 Map 对象，所以改了就都可见。</p>
     */
    @IfmapAction("noticeHandled")
    public static class NoticeAction implements IfmapActionHandler {
        private final Recorder recorder;

        NoticeAction(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void execute(StrategyContext context) {
            Object status = context.get("noticeStatus");
            recorder.executed.add(String.valueOf(status));
            Object produced = context.getResponseData();
            if (produced instanceof Map) {
                ((Map<String, Object>) produced).put("ackNo", "ACK_" + status);
            }
        }
    }

    /** 捕获出网入参的网关。 */
    static final class CapturingGateway implements PartnerServiceGateway {
        final List<String> requests = new ArrayList<String>();
        private final String response;

        CapturingGateway(String response) {
            this.response = response;
        }

        @Override
        public String exchange(PartnerCall call) {
            requests.add(call.getRequestJson());
            return response;
        }
    }

    private final TestJsonOps jsonOps = new TestJsonOps();
    private final TestConfigs.InMemoryRepository repository = new TestConfigs.InMemoryRepository();
    private final ClockProvider clock = new ClockProvider() {
        private long now = 1_700_000_000_000L;

        @Override
        public long currentTimeMillis() {
            now += 25L;
            return now;
        }
    };

    private IfmapEngine engine() {
        return IfmapEngine.builder().jsonOps(jsonOps).register(new Rules()).build();
    }

    private IfmapConfig mainConfig(String interfaceNo, int order, long keyId) {
        IfmapConfig config = TestConfigs.config(interfaceNo, "GP81", order, keyId);
        config.setPartnerCode("CMB");
        config.setRequestParamTemplate("{\"orgNo\":\"@FUN(orgNo,$.orgCode)\",\"acctName\":\"$.acctName\""
                + ",\"fullParam\":\"$.fullParam\"}");
        config.setResponseParamTemplate("{\"applyNo\":\"$.data.applyNo\"}");
        config.setResultJudge(CODE_0000);
        return config;
    }

    /** 被调方向（INBOUND）的配置：不配请求模板/前序策略，回参就是对方原文。 */
    private IfmapConfig inboundConfig(String interfaceNo, long keyId) {
        IfmapConfig config = TestConfigs.config(interfaceNo, "RCV", 1, keyId);
        config.setCallDirection(CallDirection.INBOUND);
        config.setResultJudge(NOTICE_ACCEPTED);
        config.setResponseParamTemplate("{\"code\":\"0000\",\"message\":\"success\",\"applyNo\":\"$.applyNo\"}");
        return config;
    }

    private IfmapOrchestrator orchestrator(PartnerServiceGateway gateway, ActionRegistry actions) {
        StrategyHookRegistry strategyHooks = new StrategyHookRegistry();
        strategyHooks.register("extraParamStrategy", new ExtraParamStrategy());
        return orchestrator(gateway, actions, strategyHooks);
    }

    private IfmapOrchestrator orchestrator(PartnerServiceGateway gateway, ActionRegistry actions,
                                           StrategyHookRegistry strategyHooks) {
        return orchestrator(gateway, actions, strategyHooks, null);
    }

    private IfmapOrchestrator orchestrator(PartnerServiceGateway gateway, ActionRegistry actions,
                                           StrategyHookRegistry strategyHooks,
                                           LogicBranchStrategyRegistry logicBranches) {
        FullParamStrategyRegistry fullParams = new FullParamStrategyRegistry();
        fullParams.register(new CmbFullParam());
        return IfmapOrchestrator.builder()
                .engine(engine())
                .jsonOps(jsonOps)
                .repository(repository)
                .tenantResolver(new HeaderTenantResolver())
                .strategyHooks(strategyHooks)
                .fullParams(fullParams)
                .logicBranches(logicBranches)
                .actions(actions)
                .gateway(gateway)
                .logSink(new RepositoryExecutionLogSink(repository))
                .logMasker(new DefaultLogMasker())
                .clock(clock)
                .build();
    }

    @Test
    @DisplayName("always 形态：响应不是 JSON 也判成功（短路，不去解析回参）")
    void nonJsonResponseWithAlwaysStillSucceeds() {
        IfmapConfig config = TestConfigs.config("IF_A", "GP81", 1, 1L);   // 不配响应模板：少一层 JSON 依赖
        config.setResultJudge(ALWAYS);
        repository.add(config);
        IfmapResult result = orchestrator(new CapturingGateway("<html>502 Bad Gateway</html>"), new ActionRegistry())
                .execute(IfmapRequest.empty(), "IF_A", "GP81", "<html>502 Bad Gateway</html>");
        assertTrue(result.isSuccess(), "always 形态不读响应，非 JSON 不能判成 NOT_JSON");
    }

    @Test
    @DisplayName("宿主抛 IfmapRemoteException：收敛成步骤失败 + 落日志，而不是抛给调用方")
    void remoteExceptionBecomesStepFailureAndIsLogged() {
        repository.add(mainConfig("IF_A", 1, 1L));
        PartnerServiceGateway failing = new PartnerServiceGateway() {
            @Override
            public String exchange(PartnerCall call) {
                throw new IfmapRemoteException("connection refused");
            }
        };
        IfmapResult result = orchestrator(failing, new ActionRegistry())
                .execute(IfmapRequest.empty(), "IF_A", "GP81");

        assertFalse(result.isSuccess());
        assertEquals("connection refused", result.getErrorMessage());
        assertEquals(1, repository.getLogs().size());
        assertEquals("FAIL", repository.getLogs().get(0).getExecutionResult());
    }

    @Test
    @DisplayName("判定失败：fail_type / error_code 落库，error_msg = 判定原因 + 对方原话（两段都要）")
    void judgeFailureColumnsAreLogged() {
        repository.add(mainConfig("IF_J", 1, 1L));
        CapturingGateway gateway = new CapturingGateway(
                "{\"code\":\"9999\",\"message\":\"余额不足\",\"data\":{\"applyNo\":\"AP9\"}}");

        IfmapResult result = orchestrator(gateway, new ActionRegistry()).execute(IfmapRequest.empty(), "IF_J", "GP81");

        assertFalse(result.isSuccess());
        assertEquals("VALUE_MISMATCH", result.getFailType(), "IfmapResult 要带归因，宿主重试策略靠它判断");
        ExecutionLog log = repository.getLogs().get(0);
        assertEquals("FAIL", log.getExecutionResult());
        assertEquals("VALUE_MISMATCH", log.getFailType());
        assertEquals("9999", log.getErrorCode(), "抽到的对方业务码要落库（排障第一眼看的字段）");
        assertTrue(log.getErrorMsg().contains("$.code"), "error_msg 要含判定口径（哪个路径没过）：" + log.getErrorMsg());
        assertTrue(log.getErrorMsg().contains("余额不足"), "error_msg 要含对方原话：" + log.getErrorMsg());
        assertNull(log.getHttpStatus(), "宿主没上报 HTTP 状态码时留空，不能编一个 200");
        assertNull(log.getTrackId());
    }

    @Test
    @DisplayName("宿主上报 HTTP 500：http_status 落库、fail_type 归到 HTTP_5XX（先判通道再判报文）")
    void httpStatusAndTrackIdAreLogged() {
        repository.add(mainConfig("IF_H", 1, 1L));
        PartnerServiceGateway gateway = new PartnerServiceGateway() {
            @Override
            public String exchange(PartnerCall call) {
                return "{\"code\":\"0000\",\"data\":{\"applyNo\":\"AP1\"}}";
            }

            @Override
            public PartnerOutcome exchangeForOutcome(PartnerCall call) {
                return PartnerOutcome.of("server error", 500,
                        "{\"code\":\"0000\",\"trackId\":\"T-1\",\"message\":\"upstream boom\"}");
            }
        };

        IfmapResult result = orchestrator(gateway, new ActionRegistry()).execute(IfmapRequest.empty(), "IF_H", "GP81");

        assertFalse(result.isSuccess());
        assertEquals("HTTP_5XX", result.getFailType());
        ExecutionLog log = repository.getLogs().get(0);
        assertEquals(Integer.valueOf(500), log.getHttpStatus());
        assertEquals("HTTP_5XX", log.getFailType());
        assertEquals("T-1", log.getTrackId(), "对方链路ID 要落库，否则提工单时对不上账");
        assertTrue(log.getErrorMsg().contains("upstream boom"), log.getErrorMsg());
    }

    @Test
    void rendersOutboundRequestJudgesSuccessAndWritesMaskedLog() {
        repository.add(mainConfig("IF_A", 1, 1L));
        CapturingGateway gateway = new CapturingGateway("{\"code\":\"0000\",\"data\":{\"applyNo\":\"AP1\"}}");

        IfmapRequest request = IfmapRequest.builder()
                .bizId("BIZ-1").operatorId("u1").requestId("r1")
                .put("orgCode", "12").put("acctName", "张三").build();

        IfmapResult result = orchestrator(gateway, new ActionRegistry()).execute(request, "IF_A", "GP81");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("AP1", result.getData().get("applyNo"));
        assertEquals(Collections.singletonList("IF_A"), result.getExecutedInterfaces());
        assertTrue(gateway.requests.get(0).contains("000012"), gateway.requests.get(0));
        assertTrue(gateway.requests.get(0).contains("\"fullParam\":\"YES\""),
                "组包策略结果应能进入请求报文（模板用 $.fullParam 引用）：" + gateway.requests.get(0));

        assertEquals(1, repository.getLogs().size());
        ExecutionLog log = repository.getLogs().get(0);
        assertEquals("SUCCESS", log.getExecutionResult());
        assertEquals("BIZ-1", log.getBizId());
        assertEquals("u1", log.getAddUserId());
        assertEquals("r1", log.getAddRequestId());
        assertEquals(Long.valueOf(-1L), log.getTenantId());
        assertTrue(log.getRequestParam().contains("张*"), "请求日志须脱敏：" + log.getRequestParam());
        assertFalse(log.getRequestParam().contains("张三"), "姓名不应原文入日志");
        assertTrue(log.getResponseParam().contains("AP1"));
        assertEquals(Long.valueOf(25L), log.getExecutionTime());
        assertNull(log.getFailType(), "成功时 fail_type 留空（不是 \"SUCCESS\"：失败类型只描述失败）");
        assertNull(result.getFailType());
    }

    @Test
    void preStrategyHookAddsParamAndRequestIsRenderedAgain() {
        IfmapConfig config = TestConfigs.config("IF_B", "GP81", 1, 1L);
        config.setPreStrategyName("extraParamStrategy");
        config.setRequestParamTemplate("{\"extra\":\"$.extra\"}");
        config.setResultJudge(ALWAYS);
        repository.add(config);
        CapturingGateway gateway = new CapturingGateway("{\"ok\":true}");

        IfmapResult result = orchestrator(gateway, new ActionRegistry())
                .execute(IfmapRequest.empty(), "IF_B", "GP81");

        assertTrue(result.isSuccess());
        assertTrue(gateway.requests.get(0).contains("FROM_STRATEGY"), gateway.requests.get(0));
    }

    @Test
    void missingStrategyHookFailsLoudly() {
        IfmapConfig config = TestConfigs.config("IF_C", "GP81", 1, 1L);
        config.setPreStrategyName("notRegistered");
        repository.add(config);

        IfmapOrchestrator orchestrator = orchestrator(new CapturingGateway("{}"), new ActionRegistry());
        IfmapStrategyException e = assertThrows(IfmapStrategyException.class,
                () -> orchestrator.execute(IfmapRequest.empty(), "IF_C", "GP81"));
        assertTrue(e.getMessage().contains("notRegistered"));
    }

    @Test
    void missingStrategyCanBeTolerated() {
        IfmapConfig config = TestConfigs.config("IF_C2", "GP81", 1, 1L);
        config.setPreStrategyName("notRegistered");
        config.setRequestParamTemplate("{\"a\":\"$.a\"}");
        config.setResultJudge(ALWAYS);
        repository.add(config);

        IfmapOrchestrator orchestrator = IfmapOrchestrator.builder()
                .engine(engine()).repository(repository).tenantResolver(new HeaderTenantResolver())
                .gateway(new CapturingGateway("{\"a\":1}")).failOnMissingStrategy(false).build();
        assertTrue(orchestrator.execute(IfmapRequest.builder().put("a", "1").build(), "IF_C2", "GP81").isSuccess());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> poolOf(IfmapResult result) {
        return (Map<String, Object>) result.getData().get(IfmapOrchestrator.POOL_KEY);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stepOf(IfmapResult result, String interfaceNo) {
        Object step = poolOf(result).get(interfaceNo);
        return step instanceof Map ? (Map<String, Object>) step : null;
    }

    @Test
    void preAndPostStrategiesSeeTheirOwnPhase() {
        IfmapConfig config = TestConfigs.config("IF_P1", "GP81", 1, 1L);
        config.setPreStrategyName("phaseRecording");
        config.setPostStrategyName("phaseRecording");
        config.setRequestParamTemplate("{\"pre\":\"$.preApplied\"}");
        config.setResponseParamTemplate("{\"applyNo\":\"$.data.applyNo\"}");
        config.setResultJudge(ALWAYS);
        repository.add(config);

        PhaseRecordingStrategy strategy = new PhaseRecordingStrategy();
        StrategyHookRegistry strategyHooks = new StrategyHookRegistry();
        strategyHooks.register("phaseRecording", strategy);

        CapturingGateway gateway = new CapturingGateway("{\"data\":{\"applyNo\":\"AP1\"}}");
        IfmapResult result = orchestrator(gateway, new ActionRegistry(), strategyHooks)
                .execute(IfmapRequest.empty(), "IF_P1", "GP81");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(Arrays.asList(StrategyContext.Phase.PRE, StrategyContext.Phase.POST), strategy.phases,
                "同一策略类挂两列时必须能靠 phase 自辨");
        assertTrue(gateway.requests.get(0).contains("\"pre\":\"YES\""),
                "前序返回值合并进 params 后请求模板要重渲染：" + gateway.requests.get(0));
        assertEquals("YES", result.getData().get("postApplied"), "后序返回值合并进产物");
        assertEquals("YES", stepOf(result, "IF_P1").get("postApplied"), "入池的是加工后的产物");
    }

    @Test
    void postStrategyCannotFlipJudgementAndFailedStepStaysOutOfPool() {
        IfmapConfig config = TestConfigs.config("IF_P2", "GP81", 1, 1L);
        config.setPostStrategyName("phaseRecording");
        config.setResponseParamTemplate("{\"applyNo\":\"$.data.applyNo\"}");
        config.setResultJudge(CODE_0000);
        repository.add(config);

        PhaseRecordingStrategy strategy = new PhaseRecordingStrategy();
        StrategyHookRegistry strategyHooks = new StrategyHookRegistry();
        strategyHooks.register("phaseRecording", strategy);

        CapturingGateway gateway = new CapturingGateway("{\"code\":\"9999\",\"data\":{\"applyNo\":\"AP1\"}}");
        IfmapResult result = orchestrator(gateway, new ActionRegistry(), strategyHooks)
                .execute(IfmapRequest.empty(), "IF_P2", "GP81");

        assertFalse(result.isSuccess(), "判定读原始回参，后序策略加字段不能把失败洗成成功");
        assertEquals(Collections.singletonList(StrategyContext.Phase.POST), strategy.phases,
                "后序策略照常执行；只是失败步骤的产物不进池，因此也进不了 data");
        assertTrue(poolOf(result).isEmpty(), "D10：失败步骤不入池，池键集合 = 已成功步骤集合");
    }

    @Test
    void poolCarriesEarlierStepOutputToLaterStep() {
        IfmapConfig first = TestConfigs.config("IF_1", "GP81", 1, 1L);
        first.setResponseParamTemplate("{\"firstKey\":\"$.data.applyNo\"}");
        first.setResultJudge(ALWAYS);
        IfmapConfig second = TestConfigs.config("IF_2", "GP81", 2, 2L);
        second.setRequestParamTemplate("{\"exact\":\"$.pool.IF_1.firstKey\",\"flat\":\"$.firstKey\"}");
        second.setResponseParamTemplate("{\"secondKey\":\"$.data.echo\"}");
        second.setResultJudge(ALWAYS);
        repository.add(first).add(second);

        CapturingGateway gateway = new CapturingGateway("{\"data\":{\"applyNo\":\"AP1\",\"echo\":\"E1\"}}");
        IfmapResult result = orchestrator(gateway, new ActionRegistry())
                .execute(IfmapRequest.empty(), "IF_1", "GP81");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(2, gateway.requests.size());
        assertTrue(gateway.requests.get(1).contains("\"exact\":\"AP1\""),
                "$.pool.{interface_no}.xxx 精确取前序产物：" + gateway.requests.get(1));
        assertTrue(gateway.requests.get(1).contains("\"flat\":\"AP1\""),
                "$.xxx 取最近值：" + gateway.requests.get(1));
        assertEquals("AP1", result.getData().get("firstKey"));
        assertEquals("E1", result.getData().get("secondKey"));
        assertEquals(Arrays.asList("IF_1", "IF_2"), new ArrayList<String>(poolOf(result).keySet()));
    }

    @Test
    void failedStepStaysOutOfFlatData() {
        IfmapConfig first = TestConfigs.config("IF_3", "GP81", 1, 1L);
        first.setResponseParamTemplate("{\"firstKey\":\"$.data.applyNo\"}");
        first.setResultJudge(ALWAYS);
        IfmapConfig second = TestConfigs.config("IF_4", "GP81", 2, 2L);
        second.setResponseParamTemplate("{\"secondKey\":\"$.data.echo\"}");
        second.setResultJudge(CODE_0000);
        repository.add(first).add(second);

        CapturingGateway gateway = new CapturingGateway("{\"code\":\"9999\",\"data\":{\"applyNo\":\"AP1\",\"echo\":\"E1\"}}");
        IfmapResult result = orchestrator(gateway, new ActionRegistry())
                .execute(IfmapRequest.empty(), "IF_3", "GP81");

        assertFalse(result.isSuccess());
        assertEquals("AP1", result.getData().get("firstKey"));
        assertNull(result.getData().get("secondKey"), "失败步骤的产物不进 data（不给下游留半成品）");
        assertEquals(Collections.singletonList("IF_3"), new ArrayList<String>(poolOf(result).keySet()));
    }

    @Test
    void presetPoolFromPayloadSeedsThePoolAndIsNotWrittenBack() {
        IfmapConfig config = TestConfigs.config("IF_S", "GP81", 2, 2L);
        config.setRequestParamTemplate("{\"exact\":\"$.pool.IF_0.old\",\"flat\":\"$.old\"}");
        config.setResultJudge(ALWAYS);
        repository.add(config);

        Map<String, Object> presetStep = new LinkedHashMap<String, Object>();
        presetStep.put("old", "V0");
        Map<String, Object> presetPool = new LinkedHashMap<String, Object>();
        presetPool.put("IF_0", presetStep);

        CapturingGateway gateway = new CapturingGateway("{}");
        IfmapResult result = orchestrator(gateway, new ActionRegistry())
                .execute(IfmapRequest.builder().put("pool", presetPool).build(), "IF_S", "GP81");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertTrue(gateway.requests.get(0).contains("\"exact\":\"V0\""), gateway.requests.get(0));
        assertTrue(gateway.requests.get(0).contains("\"flat\":\"V0\""), gateway.requests.get(0));
        assertEquals(Arrays.asList("IF_0", "IF_S"), new ArrayList<String>(poolOf(result).keySet()),
                "预置池要保留，且新成功的步骤追加在后（断点续跑往返）");
        assertFalse(presetPool.containsKey("IF_S"), "引擎不得写回宿主传入的 pool 对象");
    }

    @Test
    void branchMatchesByValueAndExecutesAction() {
        repository.add(mainConfig("IF_D", 1, 1L));
        repository.add(TestConfigs.judgedBranch(10L, "IF_D", 1, "SUCCESS_BRANCH", "doSubmit", "0000"));

        ActionRegistry actions = new ActionRegistry();
        actions.register(new SubmitAction());

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"0000\"}"), actions)
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_D", "GP81");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("SUCCESS_BRANCH", result.getMatchedBranch(), "matchedBranch 是分支名，不再是策略键");
        assertTrue(actions.contains("doSubmit"));
    }

    @Test
    void defaultBranchFiresWhenNoOtherBranchMatches() {
        // 存量语义：`logic_branch_flag` 为空的兜底分支在"所有常规分支均未命中"时生效。
        // 迁移来自 ECC 的兜底分支（flag/value 皆空）如果在 ifmap 里静默不生效，
        // 表现就是"迁移后某些银行的业务回调不执行"——最难查的一类问题，所以单独锁死。
        repository.add(mainConfig("IF_I", 1, 1L));
        repository.add(TestConfigs.judgedBranch(20L, "IF_I", 1, "SUBMIT_BRANCH", "recSubmit", "1111"));
        repository.add(TestConfigs.fallbackBranch(21L, "IF_I", 9, "FALLBACK", "recFallback"));

        Recorder recorder = new Recorder();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new RecSubmitAction(recorder));
        actions.register(new RecFallbackAction(recorder));

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"0000\"}"), actions)
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_I", "GP81");

        assertTrue(result.isSuccess());
        assertEquals(Collections.singletonList("fallback"), recorder.executed,
                "常规分支未命中时应执行兜底分支的动作");
    }

    @Test
    void defaultBranchDoesNotShadowMatchedBranch() {
        // 兜底分支即使排在前面（order 更小）也不能抢常规分支的命中
        repository.add(mainConfig("IF_J", 1, 1L));
        repository.add(TestConfigs.fallbackBranch(30L, "IF_J", 1, "FALLBACK", "recFallback"));
        repository.add(TestConfigs.judgedBranch(31L, "IF_J", 2, "SUBMIT_BRANCH", "recSubmit", "0000"));

        Recorder recorder = new Recorder();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new RecSubmitAction(recorder));
        actions.register(new RecFallbackAction(recorder));

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"0000\"}"), actions)
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_J", "GP81");

        assertTrue(result.isSuccess());
        assertEquals(Collections.singletonList("submit"), recorder.executed,
                "兜底分支排在前面也不能覆盖常规分支的命中");
        assertEquals("SUBMIT_BRANCH", result.getMatchedBranch());
    }

    @Test
    void missingActionFailsLoudly() {
        repository.add(mainConfig("IF_E", 1, 1L));
        repository.add(TestConfigs.judgedBranch(11L, "IF_E", 1, "SUBMIT_BRANCH", "notRegisteredAction", "0000"));

        IfmapOrchestrator orchestrator = orchestrator(new CapturingGateway("{\"code\":\"0000\"}"), new ActionRegistry());
        IfmapStrategyException e = assertThrows(IfmapStrategyException.class,
                () -> orchestrator.execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_E", "GP81"));
        assertTrue(e.getMessage().contains("notRegisteredAction"));
    }

    @Test
    @DisplayName("分支规格非法 → fail-closed：该步判失败（SPEC_INVALID），一条动作都不执行，也不落到兜底")
    void invalidBranchSpecFailsClosed() {
        repository.add(mainConfig("IF_K", 1, 1L));
        repository.add(TestConfigs.fallbackBranch(50L, "IF_K", 9, "FALLBACK", "recFallback"));
        // 顺序故意让合法分支排在坏分支前面：坏配置不能因为"别的分支先命中"就被掩盖
        repository.add(TestConfigs.judgedBranch(51L, "IF_K", 1, "SUBMIT_BRANCH", "recSubmit", "0000"));
        repository.add(TestConfigs.branch(52L, "IF_K", 2, "BAD_BRANCH", "{\"all\":[]}", "recSubmit"));

        Recorder recorder = new Recorder();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new RecSubmitAction(recorder));
        actions.register(new RecFallbackAction(recorder));

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"0000\"}"), actions)
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_K", "GP81");

        assertFalse(result.isSuccess(), "坏规格不能静默当兜底放过");
        assertEquals("SPEC_INVALID", result.getFailType());
        assertNull(result.getMatchedBranch());
        assertTrue(recorder.executed.isEmpty(), "任一分支规格非法则一条动作都不执行：" + recorder.executed);
        ExecutionLog log = repository.getLogs().get(0);
        assertEquals("FAIL", log.getExecutionResult());
        assertEquals("SPEC_INVALID", log.getFailType());
        assertTrue(log.getErrorMsg().contains("BAD_BRANCH"), log.getErrorMsg());
        assertTrue(log.getErrorMsg().contains("分支判定规格非法"), log.getErrorMsg());
    }

    @Test
    @DisplayName("branch_judge 为空（存量未回填）→ fail-closed，错误信息指向回填")
    void emptyBranchJudgeFailsClosed() {
        repository.add(mainConfig("IF_K2", 1, 1L));
        repository.add(TestConfigs.branch(53L, "IF_K2", 1, "LEGACY_BRANCH", "", "recSubmit"));

        Recorder recorder = new Recorder();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new RecSubmitAction(recorder));

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"0000\"}"), actions)
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_K2", "GP81");

        assertFalse(result.isSuccess(), "空规格不是兜底，而是非法配置");
        assertEquals("SPEC_INVALID", result.getFailType());
        assertTrue(recorder.executed.isEmpty());
        assertTrue(repository.getLogs().get(0).getErrorMsg().contains("回填"),
                repository.getLogs().get(0).getErrorMsg());
    }

    @Test
    @DisplayName("strategy 通道：branch_judge 写策略键，交 @LogicBranch 注册表求值")
    void strategyBranchMatchesThroughRegistry() {
        repository.add(mainConfig("IF_L", 1, 1L));
        repository.add(TestConfigs.branch(60L, "IF_L", 1, "STRATEGY_BRANCH",
                "{\"any\":[{\"op\":\"strategy\",\"value\":\"submit\"}]}", "recSubmit"));

        LogicBranchStrategyRegistry logicBranches = new LogicBranchStrategyRegistry();
        logicBranches.register(new SubmitStrategy());
        Recorder recorder = new Recorder();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new RecSubmitAction(recorder));

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"0000\"}"), actions,
                new StrategyHookRegistry(), logicBranches)
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_L", "GP81");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("STRATEGY_BRANCH", result.getMatchedBranch());
        assertEquals(Collections.singletonList("submit"), recorder.executed);
    }

    @Test
    @DisplayName("非 JSON 回参 + path 规格 → 按取不到值处理：调用仍成功，只是不命中分支")
    void nonJsonResponseWithPathSpecDoesNotFail() {
        // 判定是 always（不看回参），分支却写了 path：规格本身合法，只是这次读不到东西
        repository.add(TestConfigs.config("IF_M", "GP81", 1, 1L));
        repository.add(TestConfigs.pathBranch(70L, "IF_M", 1, "OK_BRANCH", "recSubmit",
                "$.noticeStatus", "eq", "SUCCESS"));

        Recorder recorder = new Recorder();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new RecSubmitAction(recorder));

        IfmapResult result = orchestrator(new CapturingGateway("not-json-at-all"), actions)
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_M", "GP81");

        assertTrue(result.isSuccess(), "分支配置不能把成功的调用判失败：" + result.getErrorMessage());
        assertNull(result.getMatchedBranch());
        assertTrue(recorder.executed.isEmpty());
    }

    @Test
    void failureResultStopsPlanAndLogsFail() {
        IfmapConfig failing = mainConfig("IF_F", 1, 1L);
        repository.add(failing);
        IfmapConfig later = TestConfigs.config("IF_F2", "GP81", 2, 2L);
        later.setResultJudge(ALWAYS);
        repository.add(later);

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"9999\"}"), new ActionRegistry())
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_F", "GP81");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未命中"), result.getErrorMessage());
        assertEquals(Collections.singletonList("IF_F"), result.getExecutedInterfaces(), "失败即停止（stopOnFailure=true）");
        assertEquals("FAIL", repository.getLogs().get(0).getExecutionResult());
    }

    @Test
    @DisplayName("起点是第一站 → 按 interface_order 跑完整条计划，各步产物扁平合并")
    void planRunsFromOrderAndDataMerges() {
        IfmapConfig pre = TestConfigs.config("PRE", "GP81", 1, 10L);
        pre.setResponseParamTemplate("{\"preField\":\"$.data.applyNo\"}");
        pre.setResultJudge(ALWAYS);
        IfmapConfig main = mainConfig("MAIN", 2, 20L);
        repository.add(pre);
        repository.add(main);

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"0000\",\"data\":{\"applyNo\":\"AP7\"}}"),
                new ActionRegistry())
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "PRE", "GP81");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(Arrays.asList("PRE", "MAIN"), result.getExecutedInterfaces());
        assertEquals("AP7", result.getData().get("applyNo"));
        assertEquals("AP7", result.getData().get("preField"));
    }

    @Test
    @DisplayName("起点在中间 → 前面已成功的步骤不再重跑（断点续跑就是这么用的）")
    void startPointSkipsEarlierSteps() {
        IfmapConfig first = TestConfigs.config("S1", "GP81", 1, 1L);
        first.setResponseParamTemplate("{\"k1\":\"$.data.applyNo\"}");
        first.setResultJudge(ALWAYS);
        IfmapConfig second = TestConfigs.config("S2", "GP81", 2, 2L);
        second.setResponseParamTemplate("{\"k2\":\"$.data.echo\"}");
        second.setResultJudge(ALWAYS);
        repository.add(first).add(second);

        CapturingGateway gateway = new CapturingGateway("{\"data\":{\"echo\":\"E2\"}}");
        IfmapResult result = orchestrator(gateway, new ActionRegistry())
                .execute(IfmapRequest.empty(), "S2", "GP81");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(1, gateway.requests.size(), "S1 已在上次跑完，这次不该再出网");
        assertEquals(Collections.singletonList("S2"), result.getExecutedInterfaces());
        assertNull(result.getData().get("k1"), "没跑的步骤没有产物");
        assertEquals("E2", result.getData().get("k2"));
        assertEquals(Collections.singletonList("S2"), new ArrayList<String>(poolOf(result).keySet()));
    }

    @Test
    @DisplayName("起点不在计划内（停用/删除/编号写错）→ 报错而不是静默跑一半")
    void startPointNotInPlanThrows() {
        repository.add(mainConfig("ONLY", 1, 1L));

        IfmapConfigException e = assertThrows(IfmapConfigException.class,
                () -> orchestrator(new CapturingGateway("{\"code\":\"0000\"}"), new ActionRegistry())
                        .execute(IfmapRequest.empty(), "OTHER", "GP81"));
        assertTrue(e.getMessage().contains("起点接口不在"), e.getMessage());
        assertTrue(e.getMessage().contains("OTHER"), e.getMessage());
    }

    @Test
    @DisplayName("接口号传 null → 从第一步跑完整个业务节点")
    void nullStartRunsWholeNode() {
        repository.add(mainConfig("W1", 1, 1L));
        repository.add(mainConfig("W2", 2, 2L));

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"0000\"}"), new ActionRegistry())
                .execute(IfmapRequest.empty(), null, "GP81");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(Arrays.asList("W1", "W2"), result.getExecutedInterfaces());
    }

    @Test
    void dryRunUsesMockResponseWithoutGateway() {
        repository.add(mainConfig("IF_G", 1, 1L));
        IfmapResult result = orchestrator(null, new ActionRegistry())
                .execute(IfmapRequest.builder().put("orgCode", "3").build(), "IF_G", "GP81",
                        "{\"code\":\"0000\",\"data\":{\"applyNo\":\"MOCK\"}}");

        assertTrue(result.isSuccess());
        assertEquals("MOCK", result.getData().get("applyNo"));
    }

    @Test
    void missingConfigThrowsConfigException() {
        IfmapConfigException e = assertThrows(IfmapConfigException.class,
                () -> orchestrator(null, new ActionRegistry()).execute(IfmapRequest.empty(), "NOPE", "GP81"));
        assertTrue(e.getMessage().contains("未找到可执行的接口配置"));
    }

    @Test
    void logIsTruncatedByThreshold() {
        repository.add(mainConfig("IF_H", 1, 1L));
        IfmapOrchestrator orchestrator = IfmapOrchestrator.builder()
                .engine(engine()).repository(repository).tenantResolver(new HeaderTenantResolver())
                .logSink(new RepositoryExecutionLogSink(repository)).logMasker(new DefaultLogMasker())
                .gateway(new CapturingGateway("{\"code\":\"0000\"}")).truncateThreshold(10).build();

        orchestrator.execute(IfmapRequest.builder().put("orgCode", "1").put("acctName", "张三").build(),
                "IF_H", "GP81");

        assertTrue(repository.getLogs().get(0).getRequestParam().endsWith("...truncated"),
                repository.getLogs().get(0).getRequestParam());
    }

    @Test
    @DisplayName("被调方向：不出网，判定与应答模板都读对方原文，日志记「原文 → 我方应答」")
    void inboundSkipsGatewayAndLogsOurAck() {
        repository.add(inboundConfig("IF_N1", 1L));
        CapturingGateway gateway = new CapturingGateway("{\"code\":\"0000\"}");
        String body = "{\"noticeStatus\":\"SUCCESS\",\"applyNo\":\"AP7\"}";

        IfmapResult result = orchestrator(gateway, new ActionRegistry())
                .executeInbound(IfmapRequest.empty(), "IF_N1", "RCV", body);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertTrue(gateway.requests.isEmpty(), "被调方向不得出网");
        assertEquals("AP7", result.getData().get("applyNo"), "应答模板按对方原文的路径取值");
        assertEquals(body, result.getRawResponse());

        ExecutionLog log = repository.getLogs().get(0);
        assertEquals(body, log.getRequestParam(), "request_param 记对方原文");
        assertTrue(log.getResponseParam().contains("\"code\":\"0000\""), log.getResponseParam());
        assertFalse(log.getResponseParam().contains("noticeStatus"),
                "response_param 不能把原文再记一遍（那样两列同值，排障时看不出我方回了什么）：" + log.getResponseParam());
    }

    @Test
    @DisplayName("被调方向：分支命中后动作原地改产物，返回值与日志都能看到回填字段")
    void inboundBranchFillsAckThroughResponseData() {
        repository.add(inboundConfig("IF_N2", 2L));
        repository.add(TestConfigs.pathBranch(40L, "IF_N2", 1, "PAY_SUCCESS", "noticeHandled",
                "$.noticeStatus", "eq", "SUCCESS"));
        Recorder recorder = new Recorder();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new NoticeAction(recorder));

        IfmapResult result = orchestrator(null, actions).executeInbound(IfmapRequest.empty(), "IF_N2", "RCV",
                "{\"noticeStatus\":\"SUCCESS\",\"applyNo\":\"AP7\"}");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("PAY_SUCCESS", result.getMatchedBranch());
        assertEquals(Collections.singletonList("SUCCESS"), recorder.executed,
                "payload 为空时入参来自对方原文，动作里 $.noticeStatus 要读得到");
        assertEquals("ACK_SUCCESS", result.getData().get("ackNo"), "动作原地改产物必须能进返回值");
        assertTrue(repository.getLogs().get(0).getResponseParam().contains("ACK_SUCCESS"),
                "日志记的是动作回填后的应答：" + repository.getLogs().get(0).getResponseParam());
    }

    @Test
    @DisplayName("被调方向：成功/失败通知都算「可受理」，各自走自己的分支（判定不能判业务成败）")
    void inboundBothStatesAreAcceptedAndRouted() {
        repository.add(inboundConfig("IF_N3", 3L));
        repository.add(TestConfigs.pathBranch(41L, "IF_N3", 1, "PAY_SUCCESS", "noticeHandled",
                "$.noticeStatus", "eq", "SUCCESS"));
        repository.add(TestConfigs.pathBranch(42L, "IF_N3", 2, "PAY_FAILED", "noticeHandled",
                "$.noticeStatus", "eq", "FAILED"));
        Recorder recorder = new Recorder();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new NoticeAction(recorder));
        IfmapOrchestrator orchestrator = orchestrator(null, actions);

        IfmapResult ok = orchestrator.executeInbound(IfmapRequest.empty(), "IF_N3", "RCV",
                "{\"noticeStatus\":\"SUCCESS\",\"applyNo\":\"AP7\"}");
        IfmapResult fail = orchestrator.executeInbound(IfmapRequest.empty(), "IF_N3", "RCV",
                "{\"noticeStatus\":\"FAILED\",\"applyNo\":\"AP7\"}");

        assertTrue(ok.isSuccess() && fail.isSuccess(), "两条都要判成受理成功");
        assertEquals("PAY_SUCCESS", ok.getMatchedBranch());
        assertEquals("PAY_FAILED", fail.getMatchedBranch());
        assertEquals(Arrays.asList("SUCCESS", "FAILED"), recorder.executed);
    }

    @Test
    @DisplayName("被调方向：状态不在白名单 → 判定失败，分支动作不执行（照旧落 FAIL 日志）")
    void inboundUnknownStatusFailsWithoutBranch() {
        repository.add(inboundConfig("IF_N4", 4L));
        repository.add(TestConfigs.pathBranch(43L, "IF_N4", 1, "PAY_SUCCESS", "noticeHandled",
                "$.noticeStatus", "eq", "SUCCESS"));
        Recorder recorder = new Recorder();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new NoticeAction(recorder));

        IfmapResult result = orchestrator(null, actions).executeInbound(IfmapRequest.empty(), "IF_N4", "RCV",
                "{\"noticeStatus\":\"WHATEVER\"}");

        assertFalse(result.isSuccess());
        assertEquals("VALUE_MISMATCH", result.getFailType());
        assertNull(result.getMatchedBranch());
        assertTrue(recorder.executed.isEmpty(), "判定失败不跑分支动作");
        assertEquals("FAIL", repository.getLogs().get(0).getExecutionResult());
    }

    @Test
    @DisplayName("被调方向：判定读原文、入参读 payload —— 两者不一致时各走各的（不能互相顶掉）")
    void inboundPayloadWinsOverRawBodyForParamsOnly() {
        repository.add(inboundConfig("IF_N5", 5L));
        repository.add(TestConfigs.judgedBranch(44L, "IF_N5", 1, "PAY_SUCCESS", "noticeHandled", "SUCCESS"));
        repository.add(TestConfigs.judgedBranch(45L, "IF_N5", 2, "PAY_FAILED", "noticeHandled", "FAILED"));
        Recorder recorder = new Recorder();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new NoticeAction(recorder));

        // 原文是 FAILED（判定与分流按它走），宿主补过的 payload 是 SUCCESS（动作里读到的参数按它走）
        IfmapResult result = orchestrator(null, actions).executeInbound(
                IfmapRequest.builder().put("noticeStatus", "SUCCESS").build(), "IF_N5", "RCV",
                "{\"noticeStatus\":\"FAILED\",\"applyNo\":\"AP7\"}");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("PAY_FAILED", result.getMatchedBranch(), "判定读原文：分流按原文的 FAILED 命中");
        assertEquals(Collections.singletonList("SUCCESS"), recorder.executed,
                "入参读 payload：动作里 $.noticeStatus 是宿主传的结构化值，不被原文顶掉");
    }

    @Test
    @DisplayName("被调方向：dry-run 的 mockResponse 仍然优先于对方原文（要能换掉入参）")
    void inboundMockResponseWins() {
        repository.add(inboundConfig("IF_N6", 6L));

        IfmapResult result = orchestrator(null, new ActionRegistry()).execute(
                IfmapRequest.empty().withRawBody("{\"noticeStatus\":\"WHATEVER\"}"), "IF_N6", "RCV",
                "{\"noticeStatus\":\"SUCCESS\",\"applyNo\":\"AP7\"}");

        assertTrue(result.isSuccess(), "dry-run 要能拿一份合法入参替换掉真实原文：" + result.getErrorMessage());
        assertEquals("AP7", result.getData().get("applyNo"));
    }

    @Test
    void strategyContextIsAccessibleToStrategies() {
        StrategyContext context = StrategyContext.builder().build().putParam("k", "v");
        assertEquals("v", context.get("k"));
        assertNull(context.get("nope"));
        assertTrue(context.getParams().containsKey("k"));
    }
}
