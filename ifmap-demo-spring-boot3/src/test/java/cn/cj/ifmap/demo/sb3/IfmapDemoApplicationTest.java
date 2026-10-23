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

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.cache.CachingConfigRepository;
import cn.cj.ifmap.core.config.CallDirection;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;
import cn.cj.ifmap.core.rule.RuleContext;
import cn.cj.ifmap.core.rule.RuleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import cn.cj.ifmap.testkit.TestDatabases;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端测试：真实 Spring Boot 上下文 + 生产建表脚本 + 生产仓储 + 生产引擎。
 *
 * <p>默认跑 {@code application.yml} 里的 H2 内存库；设了 {@code IFMAP_JDBC_URL} 后同一套用例改跑真 MySQL
 * （表前缀换成本用例专属前缀，避免污染示例默认的 {@code ifmap_} 表）。</p>
 *
 * @author caijun
 */
@SpringBootTest
class IfmapDemoApplicationTest {

    private static final String TEST_DB = "ifmap_demo_sb3";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!TestDatabases.mysqlEnabled()) {
            return;
        }
        registry.add("spring.datasource.url", TestDatabases::mysqlUrl);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("ifmap.table-prefix", () -> TestDatabases.prefixFor(TEST_DB));
    }

    @Autowired
    private ConfigRepository repository;

    @Autowired
    private IfmapEngine engine;

    @Autowired
    private RuleRegistry registry;

    @Autowired
    private IfmapOrchestrator orchestrator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("starter 自动建表：4 张表都在")
    void tablesCreatedByStarter() {
        String prefix = TestDatabases.mysqlEnabled()
                ? TestDatabases.prefixFor(TEST_DB) : "ifmap_";
        for (String table : new String[]{prefix + "config", prefix + "logic_branch_config",
                prefix + "execution_log", prefix + "config_history"}) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE lower(table_name) = ?",
                    Integer.class, table);
            assertEquals(1, count == null ? -1 : count, "表不存在：" + table);
        }
    }

    @Test
    @DisplayName("仓储被缓存装饰器包装，且能查到 DemoRunner 写入的配置")
    void repositoryWorks() {
        assertInstanceOf(CachingConfigRepository.class, repository);
        List<IfmapConfig> configs = repository.queryConfigs("1001", "BIZ_APPLY", "apply");
        assertEquals(1, configs.size());
        assertEquals("CMB", configs.get(0).getPartnerCode());
    }

    @Test
    @DisplayName("宿主机 @IfmapRule bean 被自动收集，模板可直接调用")
    void hostRuleRegistered() {
        assertTrue(registry.contains("bankOrgNo"), "BankRules 应被自动注册");
        Object orgNo = engine.renderToMap("{\"orgNo\":\"@FUN(bankOrgNo,12)\"}", "{}", RuleContext.empty())
                .get("orgNo");
        assertEquals("000012", orgNo);
    }

    @Test
    @DisplayName("整链路：库里的配置模板 + 内置规则 + 宿主规则 一起渲染")
    void endToEndRender() {
        IfmapConfig config = repository.queryConfigs("1001", "BIZ_APPLY", "apply").get(0);
        String source = "{\"orgCode\":\"12\",\"applyNo\":\"AP20250101001\","
                + "\"applyTime\":\"2025-03-08 10:20:30\",\"acctName\":\"张三\"}";
        String rendered = engine.render(config.getRequestParamTemplate(), source);

        assertTrue(rendered.contains("\"orgNo\":\"000012\""), "宿主规则应生效：" + rendered);
        assertTrue(rendered.contains("\"applyDate\":\"20250308\""), "内置日期规则应生效：" + rendered);
        assertTrue(rendered.contains("\"applyNo\":\"AP20250101001\""), "路径取值应生效：" + rendered);
        assertTrue(rendered.contains("\"acctName\":\"张*\""), "strMask(NAME) 应脱敏：" + rendered);
    }

    @Test
    @DisplayName("被调方向的演示配置：INBOUND + 无请求模板 + 无前序策略 + 两条分支")
    void inboundDemoSeeded() {
        List<IfmapConfig> configs = repository.queryConfigs("1001", "BIZ_NOTICE", "notice");
        assertEquals(1, configs.size());
        IfmapConfig config = configs.get(0);
        assertEquals(CallDirection.INBOUND, config.getCallDirection());
        assertTrue(config.inbound());
        assertEquals(Integer.valueOf(1), config.getInterfaceOrder(), "被调方向必须是本节点第一步");
        assertNull(config.getRequestParamTemplate(), "INBOUND 不渲染请求模板");
        // 列是 NOT NULL DEFAULT ''：写 null 会被库兜成空串，两者在引擎里等价（都表示"没配"）
        assertTrue(config.getPreStrategyName() == null || config.getPreStrategyName().isEmpty(),
                "INBOUND 不跑前序策略");

        List<LogicBranchConfig> branches = repository.queryLogicBranches("1001", "BIZ_NOTICE");
        assertEquals(2, branches.size());
    }

    @Test
    @DisplayName("被调方向：业务成功 → 受理分支动作原地补字段，应答照常是 0000")
    void inboundAcceptedFillsAck() {
        IfmapResult result = orchestrator.executeInbound(noticeRequest(), "BIZ_NOTICE", "notice", notice("SUCCESS"));

        assertTrue(result.isSuccess(), "报文可受理 → 判定通过：" + result.getErrorMessage());
        assertEquals("ACCEPTED", result.getMatchedBranch());
        Map<String, Object> ack = ackOf(result);
        assertEquals("0000", ack.get("code"));
        // $.seqNo 是保留路径（生成我方流水号），取对方同名字段必须写 $.['seqNo']
        assertEquals("SEQ-9001", ack.get("partnerSeqNo"), "对方流水号要靠方括号路径才取得到");
        assertEquals(20, String.valueOf(ack.get("ourSeqNo")).length(), "$.seqNo 生成 20 位我方流水号");
        assertEquals("ACC-BIZ-NOTICE-0001", ack.get("approveNo"), "动作回填的字段必须对外可见");
    }

    @Test
    @DisplayName("被调方向：业务失败但报文可受理 → 失败分支把应答码覆盖成 2001 + retryable")
    void inboundRejectedOverwritesAck() {
        IfmapResult result = orchestrator.executeInbound(noticeRequest(), "BIZ_NOTICE", "notice", notice("FAILED"));

        assertTrue(result.isSuccess(), "业务失败 ≠ 报文不可受理，判定仍要过");
        assertEquals("FAILED", result.getMatchedBranch());
        Map<String, Object> ack = ackOf(result);
        assertEquals("2001", ack.get("code"), "动作原地改 Map 覆盖模板里的 0000");
        assertEquals(Boolean.TRUE, ack.get("retryable"));
    }

    @Test
    @DisplayName("被调方向：状态不认识 → 判定失败、不进分支、没有应答（宿主自己回 1xxx）")
    void inboundUnknownStatusHasNoAck() {
        IfmapResult result = orchestrator.executeInbound(noticeRequest(), "BIZ_NOTICE", "notice", notice("PENDING"));

        assertFalse(result.isSuccess());
        assertEquals("VALUE_MISMATCH", result.getFailType());
        assertNull(result.getMatchedBranch(), "判定失败时分支不执行");
        assertNull(ackOf(result), "产物不入池 ⇒ 没有应答，引擎不兜底");
    }

    private static IfmapRequest noticeRequest() {
        return IfmapRequest.builder()
                .tenantId("1001")
                .bizId("BIZ-NOTICE-0001")
                .operatorId("partner")
                .requestId("REQ-NOTICE-1")
                .build();
    }

    private static String notice(String status) {
        return "{\"seqNo\":\"SEQ-9001\",\"applyNo\":\"AP20250101001\",\"noticeStatus\":\""
                + status + "\"}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ackOf(IfmapResult result) {
        Object pool = result.getData() == null ? null : result.getData().get(IfmapOrchestrator.POOL_KEY);
        if (!(pool instanceof Map)) {
            return null;
        }
        Object ack = ((Map<String, Object>) pool).get("BIZ_NOTICE");
        return ack instanceof Map ? (Map<String, Object>) ack : null;
    }
}
