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

import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.json.jackson.JacksonJsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemoteConfigRepository} 的契约：请求长什么样、响应怎么映射、排序与筛选由谁兜底、失败如何暴露。
 *
 * @author caijun
 */
class RemoteConfigRepositoryTest {

    private static final JsonOps JSON = new JacksonJsonOps();

    /** 远端返回五条：乱序 + 一条停用 + 一条已删除，用来验证「本地兜底排序与筛选」。 */
    private static final String CONFIG_BODY = "["
            + config(50, "IF_A", 5, 1, 0) + ","
            + config(20, "IF_A", 2, 1, 0) + ","
            + config(30, "IF_A", 3, 0, 0) + ","
            + config(40, "IF_A", 4, 1, 1) + ","
            + config(10, "IF_A", 1, 1, 0)
            + "]";

    private static String config(long keyId, String interfaceNo, int order, int status, int delStatus) {
        return "{\"keyId\":" + keyId + ",\"interfaceNo\":\"" + interfaceNo + "\",\"interfaceCode\":\"CODE\","
                + "\"interfaceName\":\"接口" + keyId + "\",\"busiNode\":\"APPLY\",\"partnerCode\":\"BANK\","
                + "\"interfaceOrder\":" + order + ",\"status\":" + status + ",\"delStatus\":" + delStatus + ","
                + "\"requestParamTemplate\":\"{\\\"bizNo\\\":\\\"@bizNo\\\"}\","
                + "\"resultFlag\":\"$.code\",\"successValue\":\"0000\"}";
    }

    @Test
    @DisplayName("queryConfigs：请求路径与查询参数固定，返回按 order 升序且只含启用未删除的行")
    void queryConfigsBuildsExpectedRequestAndSorts() {
        StubFetcher fetcher = new StubFetcher().reply(CONFIG_BODY);
        RemoteConfigRepository repository = new RemoteConfigRepository(fetcher, JSON);

        List<IfmapConfig> configs = repository.queryConfigs("7", "IF_A", "APPLY");

        assertEquals("/api/bankint/query_bankint_config_list", fetcher.lastUri);
        assertEquals(2, fetcher.lastQuery.size());
        assertEquals("IF_A", fetcher.lastQuery.get("interfaceNo"));
        assertEquals("APPLY", fetcher.lastQuery.get("busiNode"));
        assertEquals(1, fetcher.getCalls);

        assertEquals(3, configs.size(), "停用（status=0）与已删除（del_status=1）的配置不能进执行链路");
        assertEquals(Integer.valueOf(1), configs.get(0).getInterfaceOrder());
        assertEquals(Integer.valueOf(2), configs.get(1).getInterfaceOrder());
        assertEquals(Integer.valueOf(5), configs.get(2).getInterfaceOrder());
        assertEquals(Long.valueOf(10L), configs.get(0).getKeyId());
        assertEquals(7L, configs.get(0).getTenantId().longValue());
        assertEquals("{\"bizNo\":\"@bizNo\"}", configs.get(0).getRequestParamTemplate());
        assertEquals("$.code", configs.get(0).getResultFlag());
        assertTrue(configs.get(0).enabled());
    }

    @Test
    @DisplayName("queryConfigs：空白的 busiNode 不拼进查询串（避免远端按 busi_node='' 过滤）")
    void blankBusiNodeIsNotSent() {
        StubFetcher fetcher = new StubFetcher().reply("[]");
        new RemoteConfigRepository(fetcher, JSON).queryConfigs("-1", "IF_A", null);

        assertEquals(1, fetcher.lastQuery.size());
        assertEquals("IF_A", fetcher.lastQuery.get("interfaceNo"));
        assertFalse(fetcher.lastQuery.containsKey("busiNode"));
    }

    @Test
    @DisplayName("findConfig：本地按 interface_order 精确匹配；顺序号 ≥ 128 也正确（包装类型用 equals 比较）")
    void findConfigMatchesByOrder() {
        String body = "[" + config(1, "IF_A", 128, 1, 0) + "," + config(2, "IF_A", 129, 1, 0) + "]";
        RemoteConfigRepository repository = new RemoteConfigRepository(new StubFetcher().reply(body), JSON);

        assertEquals(Long.valueOf(2L), repository.findConfig("7", "IF_A", "APPLY", 129).get().getKeyId());
        assertEquals(Long.valueOf(1L), repository.findConfig("7", "IF_A", "APPLY", 128).get().getKeyId());
        assertEquals(Optional.empty(), repository.findConfig("7", "IF_A", "APPLY", 130));
    }

    @Test
    @DisplayName("queryLogicBranches：远端不给顺序列时保持返回顺序（下标补齐），已删除的被剔除")
    void queryLogicBranchesKeepsRemoteOrder() {
        String body = "["
                + "{\"keyId\":11,\"interfaceNo\":\"IF_A\",\"logicBranchName\":\"甲行\",\"logicBranchFlag\":\"$.partnerCode\"},"
                + "{\"keyId\":12,\"interfaceNo\":\"IF_A\",\"logicBranchName\":\"乙行\",\"logicBranchFlag\":\"$.partnerCode\"},"
                + "{\"keyId\":13,\"interfaceNo\":\"IF_A\",\"logicBranchName\":\"已删\",\"delStatus\":1},"
                + "{\"keyId\":14,\"interfaceNo\":\"IF_A\",\"logicBranchName\":\"兜底\",\"logicBranchFlag\":\"\"}"
                + "]";
        StubFetcher fetcher = new StubFetcher().reply(body);
        List<LogicBranchConfig> branches = new RemoteConfigRepository(fetcher, JSON).queryLogicBranches("-1", "IF_A");

        assertEquals("/api/bankint/query_logic_branch_list", fetcher.lastUri);
        assertEquals("IF_A", fetcher.lastQuery.get("interfaceNo"));
        assertEquals(3, branches.size());
        assertEquals("甲行", branches.get(0).getLogicBranchName());
        assertEquals(Integer.valueOf(1), branches.get(0).getLogicBranchOrder());
        assertEquals("乙行", branches.get(1).getLogicBranchName());
        assertEquals("兜底", branches.get(2).getLogicBranchName(), "兜底分支（空 flag）不能被丢掉，顺序也不受影响");
        // 下标补齐用的是「远端返回的下标」，已删除的那条也占位，所以这里是 4 而不是 3；
        // 只要保留下来的分支顺序单调即可，序号本身不参与匹配
        assertEquals(Integer.valueOf(4), branches.get(2).getLogicBranchOrder());
    }

    @Test
    @DisplayName("queryLogicBranches：显式顺序优先于下标")
    void explicitBranchOrderWins() {
        String body = "[{\"keyId\":1,\"interfaceNo\":\"IF_A\",\"logicBranchName\":\"后\",\"logicBranchOrder\":9},"
                + "{\"keyId\":2,\"interfaceNo\":\"IF_A\",\"logicBranchName\":\"先\",\"logicBranchOrder\":1}]";
        List<LogicBranchConfig> branches = new RemoteConfigRepository(new StubFetcher().reply(body), JSON)
                .queryLogicBranches("-1", "IF_A");
        assertEquals("先", branches.get(0).getLogicBranchName());
        assertEquals("后", branches.get(1).getLogicBranchName());
    }

    @Test
    @DisplayName("saveExecutionLog：POST JSON 原文；远端显式 false 视为写失败（引擎会降级为 WARN，不中断业务）")
    void saveExecutionLogPostsJson() {
        StubFetcher fetcher = new StubFetcher().reply("true");
        RemoteConfigRepository repository = new RemoteConfigRepository(fetcher, JSON);

        ExecutionLog log = new ExecutionLog();
        log.setKeyId(Long.valueOf(100L));
        log.setTenantId(Long.valueOf(7L));
        log.setInterfaceNo("IF_A");
        log.setBizId("BIZ_1");
        log.setRequestParam("{\"bizNo\":\"***\"}");
        log.setResponseParam("{\"code\":\"0000\"}");
        log.setExecutionTime(Long.valueOf(12L));
        log.setExecutionResult("SUCCESS");
        log.setAddUserId("op-1");
        log.setAddRequestId("req-1");
        repository.saveExecutionLog(log);

        assertEquals("/api/bankint/insert_log", fetcher.lastUri);
        assertNull(fetcher.lastQuery);
        assertTrue(fetcher.lastBody.contains("\"interfaceNo\":\"IF_A\""), fetcher.lastBody);
        assertTrue(fetcher.lastBody.contains("\"requestParam\":\"{\\\"bizNo\\\":\\\"***\\\"}\""), fetcher.lastBody);

        StubFetcher falseFetcher = new StubFetcher().reply("false");
        IfmapConfigException error = assertThrows(IfmapConfigException.class,
                () -> new RemoteConfigRepository(falseFetcher, JSON).saveExecutionLog(log));
        assertTrue(error.getMessage().contains("执行日志未写入"), error.getMessage());

        StubFetcher emptyFetcher = new StubFetcher().reply(null);
        new RemoteConfigRepository(emptyFetcher, JSON).saveExecutionLog(log);

        assertThrows(IfmapConfigException.class,
                () -> new RemoteConfigRepository(fetcher, JSON).saveExecutionLog(null));
    }

    @Test
    @DisplayName("空响应体/空数组 → 空列表；非法 JSON 或非数组 → 失败")
    void emptyAndMalformedResponses() {
        assertEquals(0, new RemoteConfigRepository(new StubFetcher().reply(null), JSON)
                .queryConfigs("-1", "IF_A", "APPLY").size());
        assertEquals(0, new RemoteConfigRepository(new StubFetcher().reply("[]"), JSON)
                .queryLogicBranches("-1", "IF_A").size());
        assertThrows(IfmapConfigException.class, () -> new RemoteConfigRepository(new StubFetcher().reply("{}"), JSON)
                .queryConfigs("-1", "IF_A", "APPLY"));
    }

    @Test
    @DisplayName("租户 ID 非数字 → 失败（与 JdbcConfigRepository 同口径）")
    void nonNumericTenantIdFails() {
        RemoteConfigRepository repository = new RemoteConfigRepository(new StubFetcher().reply("[]"), JSON);
        IfmapConfigException error = assertThrows(IfmapConfigException.class,
                () -> repository.queryConfigs("abc", "IF_A", "APPLY"));
        assertTrue(error.getMessage().contains("租户 ID"), error.getMessage());
        assertEquals(0, repository.queryConfigs(null, "IF_A", "APPLY").size());
    }

    @Test
    @DisplayName("ConfigFetcher 抛出的异常原样向上传播（不吞成空结果）")
    void fetcherFailurePropagates() {
        IllegalStateException failure = new IllegalStateException("connect timed out");
        RemoteConfigRepository repository = new RemoteConfigRepository(new StubFetcher().fail(failure), JSON);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> repository.queryConfigs("-1", "IF_A", "APPLY")));
    }

    @Test
    @DisplayName("构造参数校验；baseUri 支持相对路径与绝对 URL，末尾多余的 / 会去掉")
    void constructorValidation() {
        assertThrows(IfmapConfigException.class, () -> new RemoteConfigRepository(null, JSON));
        assertThrows(IfmapConfigException.class, () -> new RemoteConfigRepository(new StubFetcher(), null));
        assertThrows(IfmapConfigException.class, () -> new RemoteConfigRepository(new StubFetcher(), JSON, "  "));

        StubFetcher fetcher = new StubFetcher().reply("[]");
        assertEquals("/api/bankint", new RemoteConfigRepository(fetcher, JSON).baseUri());
        assertEquals("/v2/bankint", new RemoteConfigRepository(fetcher, JSON, "/v2/bankint///").baseUri());

        StubFetcher absolute = new StubFetcher().reply("[]");
        new RemoteConfigRepository(absolute, JSON, "http://financing-scheme/api/bankint").queryConfigs("-1", "IF_A", "APPLY");
        assertEquals("http://financing-scheme/api/bankint/query_bankint_config_list", absolute.lastUri);
    }

    /** 记录调用参数的测试桩：只测「本实现发出了什么请求」，不模拟远端业务。 */
    private static final class StubFetcher implements ConfigFetcher {

        private String response;
        private RuntimeException failure;
        private String lastUri;
        private Map<String, String> lastQuery;
        private String lastBody;
        private int getCalls;

        StubFetcher reply(String response) {
            this.response = response;
            return this;
        }

        StubFetcher fail(RuntimeException failure) {
            this.failure = failure;
            return this;
        }

        @Override
        public String get(String uri, Map<String, String> query) {
            getCalls++;
            lastUri = uri;
            lastQuery = query == null ? null : new java.util.LinkedHashMap<String, String>(query);
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public String postJson(String uri, String jsonBody) {
            lastUri = uri;
            lastQuery = null;
            lastBody = jsonBody;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
