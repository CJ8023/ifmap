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
package cn.cj.ifmap.admin;

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.ittest.AdminTestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端 HTTP 端到端：真跑 Tomcat，验证前缀、状态码语义与审计链。
 *
 * @author caijun
 */
@ActiveProfiles("test")
@SpringBootTest(classes = AdminTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ifmap.admin.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:ifmap_admin_web;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
class IfmapAdminWebEndpointTest {

    private static final String BASE = "/ifmap/admin";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 同一个 H2 内存库被本类多个用例共用，逐个用例前清空，避免"列表总数"被上个用例污染。 */
    @BeforeEach
    void cleanTables() {
        for (String table : new String[]{"ifmap_config_history", "ifmap_logic_branch_config", "ifmap_config"}) {
            jdbc.execute("DELETE FROM `" + table + "`");
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(IfmapAdminHeaders.OPERATOR_ID, "http-tester");
        headers.set(IfmapAdminHeaders.REQUEST_ID, "req-http-1");
        return headers;
    }

    private IfmapConfig sample(String interfaceNo, int order) {
        IfmapConfig config = AdminTestSupport.config(interfaceNo, "apply", order);
        config.setTenantId(7L);
        return config;
    }

    @Test
    @DisplayName("全链路：创建 → 查询 → 校验 → 历史 → 改状态 → 回滚 → 删除（HTTP 层）")
    void fullCrudOverHttp() {
        Map<String, Object> create = Map.of("config", sample("WEB_IF_1", 1), "reason", "HTTP 创建");
        ResponseEntity<Map<String, Object>> created = rest.exchange(BASE + "/configs", HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(create, headers()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertEquals(HttpStatus.OK, created.getStatusCode());
        assertTrue(Boolean.TRUE.equals(created.getBody().get("ok")), String.valueOf(created.getBody()));
        long keyId = ((Number) created.getBody().get("keyId")).longValue();

        // 列表（前缀生效才会命中）
        ResponseEntity<Map<String, Object>> list = rest.exchange(BASE + "/configs?tenantId=7",
                HttpMethod.GET, new HttpEntity<Void>(headers()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertEquals(1, ((Number) list.getBody().get("total")).intValue());

        // 详情
        ResponseEntity<Map<String, Object>> detail = rest.exchange(BASE + "/configs/" + keyId, HttpMethod.GET,
                new HttpEntity<Void>(headers()), new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertEquals("WEB_IF_1", detail.getBody().get("interfaceNo"));

        // 保存前校验：故意给错 successValue 仍然合法，但去掉 bankCode 会 422
        IfmapConfig broken = sample("WEB_IF_2", 1);
        broken.setBankCode(null);
        ResponseEntity<Map<String, Object>> badValidate = rest.exchange(BASE + "/configs/validate", HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(Map.of("config", broken, "isCreate", true), headers()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, badValidate.getStatusCode());
        assertNotNull(badValidate.getBody().get("errors"));

        // 乐观锁冲突 → 业务码 conflict=true（HTTP 200 + ok=false）
        IfmapConfig change = AdminTestSupport.config("WEB_IF_1", "apply", 1);
        change.setTenantId(7L);
        change.setSuccessValue("0001");
        ResponseEntity<Map<String, Object>> conflict = rest.exchange(BASE + "/configs/" + keyId, HttpMethod.PUT,
                new HttpEntity<Map<String, Object>>(Map.of("config", change, "expectedVersion", 99, "reason", "版本过期"),
                        headers()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertEquals(HttpStatus.OK, conflict.getStatusCode());
        assertFalse(Boolean.TRUE.equals(conflict.getBody().get("ok")));

        // 正确版本 → 成功 + 历史两条
        ResponseEntity<Map<String, Object>> updated = rest.exchange(BASE + "/configs/" + keyId, HttpMethod.PUT,
                new HttpEntity<Map<String, Object>>(
                        Map.of("config", change, "expectedVersion", 0, "reason", "改成功值"), headers()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertTrue(Boolean.TRUE.equals(updated.getBody().get("ok")), String.valueOf(updated.getBody()));

        ResponseEntity<List<Map<String, Object>>> history = rest.exchange(BASE + "/configs/" + keyId + "/history",
                HttpMethod.GET, new HttpEntity<Void>(headers()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {
                });
        assertEquals(HttpStatus.OK, history.getStatusCode());
        assertEquals(2, history.getBody().size());
        assertEquals("UPDATE", history.getBody().get(0).get("changeType"));
        assertEquals("HTTP 创建", history.getBody().get(1).get("changeReason"));
        // 审计头真的落库了（页面/调用方带 X-Operator-Id 才有“谁改的”）
        assertEquals("http-tester", history.getBody().get(1).get("operatorId"));
        assertEquals("req-http-1", history.getBody().get(1).get("requestId"));

        long createHistoryId = ((Number) history.getBody().get(1).get("keyId")).longValue();
        ResponseEntity<Map<String, Object>> rolled = rest.exchange(BASE + "/configs/" + keyId + "/rollback",
                HttpMethod.POST, new HttpEntity<Map<String, Object>>(
                        Map.of("historyId", createHistoryId, "expectedVersion", 1, "reason", "回滚验证"), headers()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertTrue(Boolean.TRUE.equals(rolled.getBody().get("ok")), String.valueOf(rolled.getBody()));
        ResponseEntity<Map<String, Object>> afterRollback = rest.exchange(BASE + "/configs/" + keyId, HttpMethod.GET,
                new HttpEntity<Void>(headers()), new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertEquals("0000", afterRollback.getBody().get("successValue"));

        // 停用 → 再删除
        ResponseEntity<Map<String, Object>> disabled = rest.exchange(BASE + "/configs/" + keyId + "/status",
                HttpMethod.POST, new HttpEntity<Map<String, Object>>(
                        Map.of("status", 0, "expectedVersion", 2, "reason", "停用"), headers()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertTrue(Boolean.TRUE.equals(disabled.getBody().get("ok")), String.valueOf(disabled.getBody()));

        ResponseEntity<Map<String, Object>> deleted = rest.exchange(BASE + "/configs/" + keyId + "?reason=清理",
                HttpMethod.DELETE, new HttpEntity<Void>(headers()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertTrue(Boolean.TRUE.equals(deleted.getBody().get("ok")));
        assertEquals(0, ((Number) rest.exchange(BASE + "/configs?tenantId=7", HttpMethod.GET,
                new HttpEntity<Void>(headers()), new ParameterizedTypeReference<Map<String, Object>>() {
                }).getBody().get("total")).intValue());
    }

    @Test
    @DisplayName("分支端点 + 试跑端点（dry-run 不出网，用 mock 应答）")
    void branchAndDryRunOverHttp() {
        rest.exchange(BASE + "/configs", HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(
                        Map.of("config", sample("WEB_IF_DR", 1), "reason", "dry-run 用"), headers()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });

        Map<String, Object> branch = new java.util.LinkedHashMap<String, Object>();
        branch.put("tenantId", 7L);
        branch.put("interfaceNo", "WEB_IF_DR");
        branch.put("methodFlag", "submit");
        branch.put("logicBranchName", "命中提交");
        branch.put("logicBranchFlag", "loanResult");
        branch.put("logicBranchValue", "0000");
        branch.put("logicBranchOrder", 1);
        ResponseEntity<Map<String, Object>> branchCreated = rest.exchange(BASE + "/branches", HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(branch, headers()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertEquals(HttpStatus.OK, branchCreated.getStatusCode(), String.valueOf(branchCreated.getBody()));

        ResponseEntity<List<Map<String, Object>>> branches = rest.exchange(BASE + "/branches?tenantId=7&interfaceNo=WEB_IF_DR",
                HttpMethod.GET, new HttpEntity<Void>(headers()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {
                });
        assertEquals(1, branches.getBody().size());

        Map<String, Object> dryRun = new java.util.LinkedHashMap<String, Object>();
        dryRun.put("tenantId", "7");
        dryRun.put("interfaceNo", "WEB_IF_DR");
        dryRun.put("busiNode", "apply");
        dryRun.put("params", Map.of("bizNo", "B9", "amount", 1));
        dryRun.put("mockResponse", "{\"resultCode\":\"0000\",\"data\":{\"applyNo\":\"A9\"}}");
        ResponseEntity<Map<String, Object>> result = rest.exchange(BASE + "/configs/dry-run", HttpMethod.POST,
                new HttpEntity<Map<String, Object>>(dryRun, headers()),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertEquals(HttpStatus.OK, result.getStatusCode(), String.valueOf(result.getBody()));
        assertEquals(Boolean.TRUE, result.getBody().get("success"), String.valueOf(result.getBody()));
        // matchedBranch 承载的是 logic_branch_flag（引擎侧语义），不是 logic_branch_name
        assertEquals("loanResult", result.getBody().get("matchedBranch"), String.valueOf(result.getBody()));
    }

    @Test
    @DisplayName("元数据端点：规则 / 动作 / 策略 / 审计")
    void metaEndpoints() {
        ResponseEntity<List<Map<String, Object>>> rules = rest.exchange(BASE + "/rules", HttpMethod.GET,
                new HttpEntity<Void>(headers()), new ParameterizedTypeReference<List<Map<String, Object>>>() {
                });
        assertEquals(HttpStatus.OK, rules.getStatusCode());
        assertFalse(rules.getBody().isEmpty(), "至少要有内置规则");

        ResponseEntity<List<String>> actions = rest.exchange(BASE + "/actions", HttpMethod.GET,
                new HttpEntity<Void>(headers()), new ParameterizedTypeReference<List<String>>() {
                });
        assertEquals(HttpStatus.OK, actions.getStatusCode());

        ResponseEntity<Map<String, Object>> strategies = rest.exchange(BASE + "/strategies", HttpMethod.GET,
                new HttpEntity<Void>(headers()), new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertTrue(strategies.getBody().containsKey("specialDeals"));

        ResponseEntity<Map<String, Object>> audit = rest.exchange(BASE + "/audit?markdown=true", HttpMethod.GET,
                new HttpEntity<Void>(headers()), new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertEquals(HttpStatus.OK, audit.getStatusCode());
        assertTrue(audit.getBody().containsKey("markdown"), String.valueOf(audit.getBody()));
    }

    @Test
    @DisplayName("不存在的配置 → 400 + 统一错误体")
    void notFoundIsMapped() {
        ResponseEntity<Map<String, Object>> missing = rest.exchange(BASE + "/configs/999999999", HttpMethod.GET,
                new HttpEntity<Void>(headers()), new ParameterizedTypeReference<Map<String, Object>>() {
                });
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertTrue(String.valueOf(missing.getBody().get("error")).contains("配置不存在"),
                String.valueOf(missing.getBody()));
    }

    @Test
    @DisplayName("前缀外的路径不归管理端管（未加前缀的 /configs 应为 404）")
    void prefixIsRequired() {
        ResponseEntity<String> plain = rest.exchange("/configs", HttpMethod.GET, new HttpEntity<Void>(headers()),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, plain.getStatusCode());
    }
}
