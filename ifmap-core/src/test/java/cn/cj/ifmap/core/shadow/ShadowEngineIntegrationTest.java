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
package cn.cj.ifmap.core.shadow;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.model.BankCall;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;
import cn.cj.ifmap.core.spi.BankServiceGateway;
import cn.cj.ifmap.core.spi.ExecutionLogSink;
import cn.cj.ifmap.core.spi.HeaderTenantResolver;
import cn.cj.ifmap.core.spi.RepositoryExecutionLogSink;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.testkit.TestConfigs;
import cn.cj.ifmap.core.testkit.TestJsonOps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 影子运行与新引擎编排器的集成：{@link ShadowEngine#of(IfmapOrchestrator)} 真的把
 * {@link ShadowRequest#getLegacyRawResponse()} 当 mockResponse 用（不出网），
 * 且影子侧异常绝不外泄、影子不污染生产执行日志。
 */
class ShadowEngineIntegrationTest {

    /** 记录出网次数的网关。 */
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
    private final CapturingGateway gateway = new CapturingGateway("{\"code\":\"0000\",\"data\":{\"applyNo\":\"AP1\"}}");

    private IfmapConfig config(String interfaceNo) {
        IfmapConfig config = TestConfigs.config(interfaceNo, "GP81", 1, 1L);
        config.setBankCode("CMB");
        config.setRequestParamTemplate("{\"acctName\":\"$.acctName\"}");
        config.setResponseParamTemplate("{\"applyNo\":\"$.data.applyNo\"}");
        config.setResultFlag("$.code");
        config.setSuccessValue("0000");
        return config;
    }

    private IfmapOrchestrator orchestrator(ExecutionLogSink logSink) {
        return IfmapOrchestrator.builder()
                .engine(IfmapEngine.builder().jsonOps(jsonOps).build())
                .jsonOps(jsonOps)
                .repository(repository)
                .tenantResolver(new HeaderTenantResolver())
                .actions(new ActionRegistry())
                .gateway(gateway)
                .logSink(logSink)
                .build();
    }

    private ShadowRequest request(String legacyRawResponse) {
        return ShadowRequest.builder()
                .interfaceNo("IF_SHADOW").busiNode("GP81")
                .bizId("BIZ-1").requestId("r1").operatorId("u1")
                .param("acctName", "ACME")
                .legacyRawResponse(legacyRawResponse)
                .build();
    }

    private static ShadowOutcome legacyOutcome() {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("applyNo", "AP1");
        return ShadowOutcome.of(true, null, fields);
    }

    @Test
    void reusesLegacyResponseWithoutSecondOutboundCall() {
        repository.add(config("IF_SHADOW"));
        ShadowRunner runner = new ShadowRunner(
                ShadowEngine.of(orchestrator(new RepositoryExecutionLogSink(repository))),
                r -> legacyOutcome(), ShadowDiffSink.noop(), ShadowOptions.defaults());

        ShadowRunResult result = runner.run(request("{\"code\":\"0000\",\"data\":{\"applyNo\":\"AP1\"}}"));

        assertEquals(0, gateway.requests.size(), "影子链路必须复用存量链路的响应，不得对资方二次出网");
        assertNotNull(result.getShadow());
        assertTrue(result.getShadow().isSuccess(), result.getShadowError());
        assertEquals("AP1", result.getShadow().getData().get("applyNo"));
        assertTrue(result.isMatched(), String.valueOf(result.getDiffs()));
    }

    @Test
    void callsGatewayWhenNoLegacyResponseSupplied() {
        repository.add(config("IF_SHADOW"));
        ShadowRunner runner = new ShadowRunner(
                ShadowEngine.of(orchestrator(new RepositoryExecutionLogSink(repository))),
                r -> legacyOutcome(), ShadowDiffSink.noop(), ShadowOptions.defaults());

        ShadowRunResult result = runner.run(request(null));

        assertEquals(1, gateway.requests.size(), "没给存量响应时应正常出网");
        assertTrue(result.isMatched(), String.valueOf(result.getDiffs()));
    }

    @Test
    void shadowEngineFailureIsRecordedAndNeverLeaks() {
        repository.add(config("IF_OTHER"));   // 故意不配 IF_SHADOW
        List<ShadowFieldDiff> reported = new ArrayList<ShadowFieldDiff>();
        ShadowRunner runner = new ShadowRunner(
                ShadowEngine.of(orchestrator(new RepositoryExecutionLogSink(repository))),
                r -> legacyOutcome(), reported::add, ShadowOptions.defaults());

        ShadowRunResult result = runner.run(request(null));

        assertNull(result.getShadow(), "新引擎异常时不应有结果");
        assertNotNull(result.getShadowError());
        assertTrue(result.getShadowError().contains("未找到可执行的接口配置"), result.getShadowError());
        assertEquals(1, result.getDiffs().size());
        assertEquals(ShadowFieldDiff.Kind.SHADOW_ERROR, result.getDiffs().get(0).getKind());
        assertEquals(1, reported.size(), "差异应报到出口");
        assertEquals(Boolean.TRUE, result.getLegacy().isSuccess(), "主链路结果照旧返回");
    }

    @Test
    void shadowLogsDoNotPolluteProductionLogTable() {
        repository.add(config("IF_SHADOW"));
        ShadowRunner runner = new ShadowRunner(
                ShadowEngine.of(orchestrator(new ExecutionLogSink() {
                    @Override
                    public void write(ExecutionLog log) {
                        // 影子链路接空出口：不写生产执行日志表
                    }
                })),
                r -> legacyOutcome(), ShadowDiffSink.noop(), ShadowOptions.defaults());

        runner.run(request("{\"code\":\"0000\",\"data\":{\"applyNo\":\"AP1\"}}"));

        assertEquals(0, repository.getLogs().size(),
                "影子期建议给新链路接空日志出口，避免影子记录污染生产执行日志表");
    }

    @Test
    void toIfmapRequestCarriesIdentityAndParams() {
        IfmapRequest ifmapRequest = request(null).toIfmapRequest();

        assertEquals("BIZ-1", ifmapRequest.getBizId());
        assertEquals("r1", ifmapRequest.getRequestId());
        assertEquals("u1", ifmapRequest.getOperatorId());
        assertEquals("ACME", ifmapRequest.getPayload().get("acctName"));
    }
}
