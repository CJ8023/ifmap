package cn.cj.ifmap.core.orchestrator;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.exception.IfmapStrategyException;
import cn.cj.ifmap.core.model.BankCall;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.spi.BankServiceGateway;
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

    /** 组包策略。 */
    @cn.cj.ifmap.core.strategy.FullParam(bankCode = "CMB", busiNode = "GP81")
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

    /** 捕获出网入参的网关。 */
    static final class CapturingGateway implements BankServiceGateway {
        final List<String> requests = new ArrayList<String>();
        private final String response;

        CapturingGateway(String response) {
            this.response = response;
        }

        @Override
        public String exchange(BankCall call) {
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
        config.setBankCode("CMB");
        config.setRequestParamTemplate("{\"orgNo\":\"@FUN(orgNo,$.orgCode)\",\"acctName\":\"$.acctName\""
                + ",\"fullParam\":\"$.fullParam\"}");
        config.setResponseParamTemplate("{\"applyNo\":\"$.data.applyNo\"}");
        config.setResultFlag("$.code");
        config.setSuccessValue("0000");
        return config;
    }

    private IfmapOrchestrator orchestrator(BankServiceGateway gateway, ActionRegistry actions) {
        SpecialDealStrategyRegistry specialDeals = new SpecialDealStrategyRegistry();
        specialDeals.register("extraParamStrategy", new ExtraParamStrategy());
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
        config.setStrategyName("extraParamStrategy");
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
        config.setStrategyName("notRegistered");
        repository.add(config);

        IfmapOrchestrator orchestrator = orchestrator(new CapturingGateway("{}"), new ActionRegistry());
        IfmapStrategyException e = assertThrows(IfmapStrategyException.class,
                () -> orchestrator.execute(IfmapRequest.empty(), "IF_C", "GP81"));
        assertTrue(e.getMessage().contains("notRegistered"));
    }

    @Test
    void missingStrategyCanBeTolerated() {
        IfmapConfig config = TestConfigs.config("IF_C2", "GP81", 1, 1L);
        config.setStrategyName("notRegistered");
        config.setRequestParamTemplate("{\"a\":\"$.a\"}");
        config.setResultFlag(null);
        repository.add(config);

        IfmapOrchestrator orchestrator = IfmapOrchestrator.builder()
                .engine(engine()).repository(repository).tenantResolver(new HeaderTenantResolver())
                .gateway(new CapturingGateway("{\"a\":1}")).failOnMissingStrategy(false).build();
        assertTrue(orchestrator.execute(IfmapRequest.builder().put("a", "1").build(), "IF_C2", "GP81").isSuccess());
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
