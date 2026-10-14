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
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.exception.IfmapStrategyException;
import cn.cj.ifmap.core.model.PartnerCall;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.spi.PartnerServiceGateway;
import cn.cj.ifmap.core.spi.ClockProvider;
import cn.cj.ifmap.core.spi.DefaultLogMasker;
import cn.cj.ifmap.core.spi.HeaderTenantResolver;
import cn.cj.ifmap.core.spi.RepositoryExecutionLogSink;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.CallbackRegistry;
import cn.cj.ifmap.core.strategy.FullParamStrategy;
import cn.cj.ifmap.core.strategy.FullParamStrategyRegistry;
import cn.cj.ifmap.core.strategy.IfmapAction;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.strategy.SpecialDealStrategy;
import cn.cj.ifmap.core.strategy.SpecialDealStrategyRegistry;
import cn.cj.ifmap.core.strategy.StrategyContext;
import cn.cj.ifmap.core.testkit.TestConfigs;
import cn.cj.ifmap.core.testkit.TestJsonOps;
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

    /** 宿主规则。 */
    public static class Rules {
        @IfmapRule("orgNo")
        public String orgNo(String code) {
            return String.format("%06d", Integer.parseInt(code));
        }
    }

    /** 补参数策略。 */
    public static class ExtraParamStrategy implements SpecialDealStrategy {
        @Override
        public Map<String, Object> apply(StrategyContext context) {
            Map<String, Object> extra = new LinkedHashMap<String, Object>();
            extra.put("extra", "FROM_STRATEGY");
            return extra;
        }
    }

    /** 记录相位；后序返回 {@code postApplied}，前序返回 {@code preApplied}。 */
    static final class PhaseRecordingStrategy implements SpecialDealStrategy {
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
        config.setResultFlag("$.code");
        config.setSuccessValue("0000");
        return config;
    }

    private IfmapOrchestrator orchestrator(PartnerServiceGateway gateway, ActionRegistry actions) {
        SpecialDealStrategyRegistry specialDeals = new SpecialDealStrategyRegistry();
        specialDeals.register("extraParamStrategy", new ExtraParamStrategy());
        return orchestrator(gateway, actions, specialDeals);
    }

    private IfmapOrchestrator orchestrator(PartnerServiceGateway gateway, ActionRegistry actions,
                                           SpecialDealStrategyRegistry specialDeals) {
        FullParamStrategyRegistry fullParams = new FullParamStrategyRegistry();
        fullParams.register(new CmbFullParam());
        return IfmapOrchestrator.builder()
                .engine(engine())
                .jsonOps(jsonOps)
                .repository(repository)
                .tenantResolver(new HeaderTenantResolver())
                .specialDeals(specialDeals)
                .fullParams(fullParams)
                .actions(actions)
                .gateway(gateway)
                .logSink(new RepositoryExecutionLogSink(repository))
                .logMasker(new DefaultLogMasker())
                .clock(clock)
                .build();
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
    }

    @Test
    void specialDealStrategyAddsParamAndRequestIsRenderedAgain() {
        IfmapConfig config = TestConfigs.config("IF_B", "GP81", 1, 1L);
        config.setPreStrategyName("extraParamStrategy");
        config.setRequestParamTemplate("{\"extra\":\"$.extra\"}");
        config.setResultFlag(null);
        repository.add(config);
        CapturingGateway gateway = new CapturingGateway("{\"ok\":true}");

        IfmapResult result = orchestrator(gateway, new ActionRegistry())
                .execute(IfmapRequest.empty(), "IF_B", "GP81");

        assertTrue(result.isSuccess());
        assertTrue(gateway.requests.get(0).contains("FROM_STRATEGY"), gateway.requests.get(0));
    }

    @Test
    void missingSpecialDealStrategyFailsLoudly() {
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
        config.setResultFlag(null);
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
        config.setResultFlag(null);
        repository.add(config);

        PhaseRecordingStrategy strategy = new PhaseRecordingStrategy();
        SpecialDealStrategyRegistry specialDeals = new SpecialDealStrategyRegistry();
        specialDeals.register("phaseRecording", strategy);

        CapturingGateway gateway = new CapturingGateway("{\"data\":{\"applyNo\":\"AP1\"}}");
        IfmapResult result = orchestrator(gateway, new ActionRegistry(), specialDeals)
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
        config.setResultFlag("$.code");
        config.setSuccessValue("0000");
        repository.add(config);

        PhaseRecordingStrategy strategy = new PhaseRecordingStrategy();
        SpecialDealStrategyRegistry specialDeals = new SpecialDealStrategyRegistry();
        specialDeals.register("phaseRecording", strategy);

        CapturingGateway gateway = new CapturingGateway("{\"code\":\"9999\",\"data\":{\"applyNo\":\"AP1\"}}");
        IfmapResult result = orchestrator(gateway, new ActionRegistry(), specialDeals)
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
        first.setResultFlag(null);
        IfmapConfig second = TestConfigs.config("IF_2", "GP81", 2, 2L);
        second.setFrontInterfaceNo("IF_1");
        second.setRequestParamTemplate("{\"exact\":\"$.pool.IF_1.firstKey\",\"flat\":\"$.firstKey\"}");
        second.setResponseParamTemplate("{\"secondKey\":\"$.data.echo\"}");
        second.setResultFlag(null);
        repository.add(first).add(second);

        CapturingGateway gateway = new CapturingGateway("{\"data\":{\"applyNo\":\"AP1\",\"echo\":\"E1\"}}");
        IfmapResult result = orchestrator(gateway, new ActionRegistry())
                .execute(IfmapRequest.empty(), "IF_2", "GP81");

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
        first.setResultFlag(null);
        IfmapConfig second = TestConfigs.config("IF_4", "GP81", 2, 2L);
        second.setFrontInterfaceNo("IF_3");
        second.setResponseParamTemplate("{\"secondKey\":\"$.data.echo\"}");
        second.setResultFlag("$.code");
        second.setSuccessValue("0000");
        repository.add(first).add(second);

        CapturingGateway gateway = new CapturingGateway("{\"code\":\"9999\",\"data\":{\"applyNo\":\"AP1\",\"echo\":\"E1\"}}");
        IfmapResult result = orchestrator(gateway, new ActionRegistry())
                .execute(IfmapRequest.empty(), "IF_4", "GP81");

        assertFalse(result.isSuccess());
        assertEquals("AP1", result.getData().get("firstKey"));
        assertNull(result.getData().get("secondKey"), "失败步骤的产物不进 data（不给下游留半成品）");
        assertEquals(Collections.singletonList("IF_3"), new ArrayList<String>(poolOf(result).keySet()));
    }

    @Test
    void presetPoolFromPayloadSeedsThePoolAndIsNotWrittenBack() {
        IfmapConfig config = TestConfigs.config("IF_S", "GP81", 2, 2L);
        config.setRequestParamTemplate("{\"exact\":\"$.pool.IF_0.old\",\"flat\":\"$.old\"}");
        config.setResultFlag(null);
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
        repository.add(TestConfigs.branch(10L, "IF_D", 1, "submit", "0000", "doSubmit"));

        ActionRegistry actions = new ActionRegistry();
        actions.register(new SubmitAction());

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"0000\"}"), actions)
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_D", "GP81");

        assertTrue(result.isSuccess());
        assertEquals("submit", result.getMatchedBranch());
        assertTrue(actions.contains("doSubmit"));
    }

    @Test
    void defaultBranchFiresWhenNoOtherBranchMatches() {
        // 存量语义：`logic_branch_flag` 为空的兜底分支在"所有常规分支均未命中"时生效。
        // 迁移来自 ECC 的兜底分支（flag/value 皆空）如果在 ifmap 里静默不生效，
        // 表现就是"迁移后某些银行的业务回调不执行"——最难查的一类问题，所以单独锁死。
        repository.add(mainConfig("IF_I", 1, 1L));
        repository.add(TestConfigs.branch(20L, "IF_I", 1, "submit", "1111", "recSubmit"));
        repository.add(TestConfigs.branch(21L, "IF_I", 9, null, null, "recFallback"));

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
        repository.add(TestConfigs.branch(30L, "IF_J", 1, null, null, "recFallback"));
        repository.add(TestConfigs.branch(31L, "IF_J", 2, "submit", "0000", "recSubmit"));

        Recorder recorder = new Recorder();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new RecSubmitAction(recorder));
        actions.register(new RecFallbackAction(recorder));

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"0000\"}"), actions)
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_J", "GP81");

        assertTrue(result.isSuccess());
        assertEquals(Collections.singletonList("submit"), recorder.executed,
                "兜底分支排在前面也不能覆盖常规分支的命中");
        assertEquals("submit", result.getMatchedBranch());
    }

    @Test
    void missingActionFailsLoudly() {
        repository.add(mainConfig("IF_E", 1, 1L));
        repository.add(TestConfigs.branch(11L, "IF_E", 1, "submit", "0000", "notRegisteredAction"));

        IfmapOrchestrator orchestrator = orchestrator(new CapturingGateway("{\"code\":\"0000\"}"), new ActionRegistry());
        IfmapStrategyException e = assertThrows(IfmapStrategyException.class,
                () -> orchestrator.execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_E", "GP81"));
        assertTrue(e.getMessage().contains("notRegisteredAction"));
    }

    @Test
    void failureResultStopsPlanAndLogsFail() {
        IfmapConfig failing = mainConfig("IF_F", 1, 1L);
        repository.add(failing);
        IfmapConfig later = TestConfigs.config("IF_F2", "GP81", 2, 2L);
        later.setFrontInterfaceNo("IF_F");
        later.setResultFlag(null);
        repository.add(later);

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"9999\"}"), new ActionRegistry())
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "IF_F", "GP81");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未命中成功值"));
        assertEquals(Collections.singletonList("IF_F"), result.getExecutedInterfaces(), "失败即停止（stopOnFailure=true）");
        assertEquals("FAIL", repository.getLogs().get(0).getExecutionResult());
    }

    @Test
    void frontInterfacesExecuteInTopologicalOrderAndDataMerges() {
        IfmapConfig main = mainConfig("MAIN", 1, 10L);
        main.setFrontInterfaceNo("PRE");
        IfmapConfig pre = TestConfigs.config("PRE", "GP81", 9, 90L);
        pre.setResponseParamTemplate("{\"preField\":\"$.data.applyNo\"}");
        pre.setResultFlag(null);
        repository.add(main);
        repository.add(pre);

        IfmapResult result = orchestrator(new CapturingGateway("{\"code\":\"0000\",\"data\":{\"applyNo\":\"AP7\"}}"),
                new ActionRegistry())
                .execute(IfmapRequest.builder().put("orgCode", "1").build(), "MAIN", "GP81");

        assertTrue(result.isSuccess());
        assertEquals(java.util.Arrays.asList("PRE", "MAIN"), result.getExecutedInterfaces());
        assertEquals("AP7", result.getData().get("applyNo"));
        assertEquals("AP7", result.getData().get("preField"));
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
    void callbackRegistryDispatchesAndRejectsUnknownInterface() {
        CallbackRegistry callbacks = new CallbackRegistry();
        callbacks.register("CB_A", (request, rawBody) -> IfmapResult.success(Collections.singletonMap("body", rawBody)));

        IfmapOrchestrator orchestrator = IfmapOrchestrator.builder()
                .engine(engine()).repository(repository).tenantResolver(new HeaderTenantResolver())
                .callbacks(callbacks).build();

        assertEquals("hi", orchestrator.callback(IfmapRequest.empty(), "CB_A", "hi").getData().get("body"));
        assertThrows(IfmapStrategyException.class,
                () -> orchestrator.callback(IfmapRequest.empty(), "CB_B", "hi"));
    }

    @Test
    void strategyContextIsAccessibleToStrategies() {
        StrategyContext context = StrategyContext.builder().build().putParam("k", "v");
        assertEquals("v", context.get("k"));
        assertNull(context.get("nope"));
        assertTrue(context.getParams().containsKey("k"));
    }
}
