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
package cn.cj.ifmap.remote;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.model.PartnerCall;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;
import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.spi.PartnerServiceGateway;
import cn.cj.ifmap.core.spi.DefaultLogMasker;
import cn.cj.ifmap.core.spi.RepositoryExecutionLogSink;
import cn.cj.ifmap.core.spi.TenantResolver;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.IfmapAction;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.strategy.StrategyContext;
import cn.cj.ifmap.json.jackson.JacksonJsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：<b>配置与日志全在远端服务</b>时跑通一条完整链路 ——
 * 远端取配置 → 渲染报文 → 出网 → 判定 → 逻辑分支动作 → 日志回写远端。
 *
 * <p>与 {@code IfmapOrchestratorTest}（内存仓储）的区别：这里唯一的数据来源是
 * {@link ConfigFetcher}，所以能钉住「远端报文字段 → 引擎行为」这条真实链路上的一切：
 * 模板DSL、结果判定、分支顺序、兜底分支语义、日志脱敏、日志回写。远端调用全部由
 * {@link ScriptedFetcher} 记录，不需要网络与 Docker。</p>
 *
 * @author caijun
 */
class RemoteConfigRepositoryEngineTest {

    private static final JacksonJsonOps JSON = new JacksonJsonOps();

    /** 远端配置报文：结果的 {@code $.code} 等于 {@code 0000} 判成功。 */
    private static final String CONFIG_JSON = "["
            + "{\"key_id\":1001,\"interface_no\":\"IF_REMOTE\",\"interface_code\":\"CODE\","
            + "\"interface_name\":\"远端接口\",\"busi_node\":\"GP81\",\"partner_code\":\"CMB\","
            + "\"interface_order\":1,"
            + "\"request_param_template\":\"{\\\"orgNo\\\":\\\"@FUN(orgNo,$.orgCode)\\\",\\\"acctName\\\":\\\"$.acctName\\\"}\","
            + "\"response_param_template\":\"{\\\"applyNo\\\":\\\"$.data.applyNo\\\"}\","
            + "\"result_flag\":\"$.code\",\"success_value\":\"0000\"}"
            + "]";

    /** 远端分支报文：一条常规（按结果值命中）+ 一条兜底（flag 为空，只在常规全不命中时生效）。 */
    private static final String BRANCH_JSON = "["
            + "{\"keyId\":2001,\"interfaceNo\":\"IF_REMOTE\",\"logicBranchName\":\"成功提交\","
            + "\"logicBranchFlag\":\"submit\",\"logicBranchValue\":\"0000\",\"methodFlag\":\"doSubmit\"},"
            + "{\"keyId\":2002,\"interfaceNo\":\"IF_REMOTE\",\"logicBranchName\":\"兜底\","
            + "\"logicBranchFlag\":\"\",\"methodFlag\":\"doFallback\"}"
            + "]";

    /** 宿主规则。 */
    public static class Rules {
        @IfmapRule("orgNo")
        public String orgNo(String code) {
            return String.format("%06d", Integer.parseInt(code));
        }
    }

    /** 分支动作：把命中的分支名记下来，用于断言「哪个分支的动作被执行了」。 */
    @IfmapAction("doSubmit")
    public static class SubmitAction implements IfmapActionHandler {
        private final List<String> executed;

        SubmitAction(List<String> executed) {
            this.executed = executed;
        }

        @Override
        public void execute(StrategyContext context) {
            executed.add("submit");
            context.putParam("submitted", Boolean.TRUE);
        }
    }

    /** 兜底分支动作。 */
    @IfmapAction("doFallback")
    public static class FallbackAction implements IfmapActionHandler {
        private final List<String> executed;

        FallbackAction(List<String> executed) {
            this.executed = executed;
        }

        @Override
        public void execute(StrategyContext context) {
            executed.add("fallback");
        }
    }

    @Test
    @DisplayName("远端配置 + 远端日志：渲染 → 出网 → 判成功 → 常规分支动作 → 脱敏日志回写远端")
    void remoteConfigDrivesWholeChain() {
        ScriptedFetcher fetcher = new ScriptedFetcher()
                .on(RemoteConfigRepository.PATH_CONFIGS, CONFIG_JSON)
                .on(RemoteConfigRepository.PATH_BRANCHES, BRANCH_JSON)
                .on(RemoteConfigRepository.PATH_LOG, "true");
        RemoteConfigRepository repository = new RemoteConfigRepository(fetcher, JSON);
        List<String> executed = new ArrayList<String>();
        CapturingGateway gateway = new CapturingGateway("{\"code\":\"0000\",\"data\":{\"applyNo\":\"AP9\"}}");

        IfmapResult result = orchestrator(repository, gateway, executed).execute(
                IfmapRequest.builder().bizId("BIZ-9").operatorId("u9").requestId("r9")
                        .put("orgCode", "12").put("acctName", "张三").build(),
                "IF_REMOTE", "GP81");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("AP9", result.getData().get("applyNo"));
        assertEquals("submit", result.getMatchedBranch());
        assertEquals(java.util.Collections.singletonList("submit"), executed,
                "常规分支命中后不应再执行兜底分支");
        assertEquals(1, gateway.requests.size());
        assertTrue(gateway.requests.get(0).contains("\"orgNo\":\"000012\""), gateway.requests.get(0));
        assertTrue(gateway.requests.get(0).contains("\"acctName\":\"张三\""), gateway.requests.get(0));

        assertEquals(1, fetcher.posts.size(), "执行日志必须回写到远端服务");
        String posted = fetcher.posts.get(0);
        assertTrue(posted.contains("\"interfaceNo\":\"IF_REMOTE\""), posted);
        assertTrue(posted.contains("\"executionResult\":\"SUCCESS\""), posted);
        assertTrue(posted.contains("\"bizId\":\"BIZ-9\""), posted);
        assertTrue(posted.contains("\"addUserId\":\"u9\""), posted);
        // 注意：日志体里 responseParam 是「转义后的 JSON 文本」，所以断言字段名与值分别包含
        assertTrue(posted.contains("applyNo") && posted.contains("AP9"), posted);
        assertTrue(posted.contains("张*"), "远端日志同样要脱敏：" + posted);
        assertFalse(posted.contains("张三"), "姓名不应原文入日志：" + posted);
    }

    @Test
    @DisplayName("远端配置 + dry-run：常规分支不命中时兜底分支生效（默认分支语义在远端路径同样成立）")
    void remoteDefaultBranchFiresWhenNothingMatches() {
        // 用成功值列表让 9999 也判成功，从而走到分支阶段：常规分支按值 0000 匹配不上，兜底分支生效
        String configJson = CONFIG_JSON.replace("\"success_value\":\"0000\"", "\"success_value\":\"0000;9999\"");
        ScriptedFetcher fetcher = new ScriptedFetcher()
                .on(RemoteConfigRepository.PATH_CONFIGS, configJson)
                .on(RemoteConfigRepository.PATH_BRANCHES, BRANCH_JSON)
                .on(RemoteConfigRepository.PATH_LOG, "true");
        RemoteConfigRepository repository = new RemoteConfigRepository(fetcher, JSON);
        List<String> executed = new ArrayList<String>();

        // mockResponse 非空 → 不出网，直接拿它当响应（管理端 dry-run 的用法）
        IfmapResult result = orchestrator(repository, null, executed).execute(
                IfmapRequest.builder().put("orgCode", "1").build(),
                "IF_REMOTE", "GP81", "{\"code\":\"9999\"}");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(java.util.Collections.singletonList("fallback"), executed,
                "常规分支都不命中时才执行兜底分支");
        assertEquals(1, fetcher.posts.size(), "dry-run 也要留痕");
        assertTrue(fetcher.posts.get(0).contains("\"executionResult\":\"SUCCESS\""), fetcher.posts.get(0));
    }

    @Test
    @DisplayName("判定失败时不执行任何分支动作（远端配置与 JDBC 配置行为一致），但日志照样回写")
    void branchesAreNotAppliedWhenJudgementFails() {
        ScriptedFetcher fetcher = new ScriptedFetcher()
                .on(RemoteConfigRepository.PATH_CONFIGS, CONFIG_JSON)
                .on(RemoteConfigRepository.PATH_BRANCHES, BRANCH_JSON)
                .on(RemoteConfigRepository.PATH_LOG, "true");
        RemoteConfigRepository repository = new RemoteConfigRepository(fetcher, JSON);
        List<String> executed = new ArrayList<String>();

        IfmapResult result = orchestrator(repository, null, executed).execute(
                IfmapRequest.builder().put("orgCode", "1").build(),
                "IF_REMOTE", "GP81", "{\"code\":\"9999\"}");

        assertFalse(result.isSuccess());
        assertTrue(executed.isEmpty(), "判定失败时不跑业务回调：" + executed);
        assertEquals(1, fetcher.posts.size(), "失败也要留痕");
        assertTrue(fetcher.posts.get(0).contains("\"executionResult\":\"FAIL\""), fetcher.posts.get(0));
    }

    private IfmapOrchestrator orchestrator(RemoteConfigRepository repository, PartnerServiceGateway gateway,
                                           List<String> executed) {
        ActionRegistry actions = new ActionRegistry();
        actions.register(new SubmitAction(executed));
        actions.register(new FallbackAction(executed));
        return IfmapOrchestrator.builder()
                .engine(IfmapEngine.builder().jsonOps(JSON).register(new Rules()).build())
                .repository(repository)
                .tenantResolver(new TenantResolver() {
                    @Override
                    public String resolve(IfmapRequest request) {
                        return "7";
                    }
                })
                .actions(actions)
                .gateway(gateway)
                .logSink(new RepositoryExecutionLogSink(repository))
                .logMasker(new DefaultLogMasker())
                .build();
    }

    /** 按路径返回预设报文，并记录 POST 出去的日志。 */
    private static final class ScriptedFetcher implements ConfigFetcher {

        private final Map<String, String> responses = new LinkedHashMap<String, String>();
        private final List<String> posts = new ArrayList<String>();

        ScriptedFetcher on(String path, String body) {
            responses.put(RemoteConfigRepository.DEFAULT_BASE_URI + path, body);
            return this;
        }

        @Override
        public String get(String uri, Map<String, String> query) {
            assertTrue(responses.containsKey(uri), "未预设的请求：" + uri);
            return responses.get(uri);
        }

        @Override
        public String postJson(String uri, String jsonBody) {
            posts.add(jsonBody);
            assertTrue(responses.containsKey(uri), "未预设的请求：" + uri);
            return responses.get(uri);
        }
    }

    /** 捕获出网报文。 */
    private static final class CapturingGateway implements PartnerServiceGateway {

        private final List<String> requests = new ArrayList<String>();
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
}
