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

import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.IfmapAction;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.strategy.SpecialDealStrategyRegistry;
import cn.cj.ifmap.core.strategy.StrategyContext;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import cn.cj.ifmap.json.jackson.JacksonJsonOps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全量巡检：跨接口统计 + 定位到具体接口/行。
 *
 * @author caijun
 */
class ConfigAuditorTest {

    private AdminTestSupport.Db db;
    private String configTable;
    private JdbcConfigWriter writer;
    private ConfigAuditor auditor;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        db = AdminTestSupport.db("ifmap_admin_auditor");
        db.createSchema();
        jdbc = db.jdbc();
        configTable = db.configTable();
        JdbcConfigRepository repository = db.repository();
        writer = db.writer();

        JacksonJsonOps jsonOps = new JacksonJsonOps();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new SubmitAction());
        ConfigValidator validator = new ConfigValidator(null, jsonOps, new SpecialDealStrategyRegistry(),
                actions, repository);
        auditor = new ConfigAuditor(repository, validator, 100);
    }

    @Test
    @DisplayName("干净库 → 0 配置 0 问题，Markdown 报告有表头")
    void cleanAudit() {
        AuditReport report = auditor.audit(null, null);
        assertTrue(report.isClean(), String.valueOf(report.getErrors()));
        assertEquals(0, report.getConfigCount());
        assertEquals(0, report.getBranchCount());
        assertFalse(report.isTruncated());
        String markdown = report.toMarkdown();
        assertTrue(markdown.contains("配置数：0"), markdown);
        assertTrue(markdown.contains("结论：通过"), markdown);
        assertTrue(markdown.contains("## errors"), markdown);
    }

    @Test
    @DisplayName("有问题的配置 → 报告里能定位到 interfaceNo 与 busiNode")
    void auditLocatesProblems() {
        writer.insert(AdminTestSupport.config("IF_BAD", "apply", 1));
        // 直接改坏模板，模拟历史脏数据
        jdbc.update("UPDATE `" + configTable + "` SET `request_param_template` = ? WHERE `interface_no` = ?",
                "{bad-json", "IF_BAD");

        writer.insert(AdminTestSupport.branch("IF_BAD", "submit", "重复1", "f", "v", 1));
        writer.insert(AdminTestSupport.branch("IF_BAD", "submit", "重复2", "f", "v", 1));

        AuditReport report = auditor.audit(null, null);
        assertFalse(report.isClean());
        assertEquals(1, report.getConfigCount());
        assertEquals(2, report.getBranchCount());
        String errors = report.getErrors().toString();
        assertTrue(errors.contains("IF_BAD"), errors);
        assertTrue(errors.contains("apply"), errors);
        assertTrue(report.toMarkdown().contains("IF_BAD"), report.toMarkdown());
    }

    @Test
    @DisplayName("超过 auditMaxConfigs → truncated=true（不静默漏检）")
    void truncatedFlag() {
        writer.insert(AdminTestSupport.config("IF_1", "apply", 1));
        writer.insert(AdminTestSupport.config("IF_2", "apply", 1));
        ConfigAuditor small = new ConfigAuditor(db.repository(),
                new ConfigValidator(null, new JacksonJsonOps(), new SpecialDealStrategyRegistry(),
                        new ActionRegistry(), db.repository()), 1);
        AuditReport report = small.audit(null, null);
        assertTrue(report.isTruncated());
        assertEquals(1, report.getConfigCount());
    }

    @IfmapAction("submit")
    static class SubmitAction implements IfmapActionHandler {
        @Override
        public void execute(StrategyContext context) {
            context.putParam("handled", Boolean.TRUE);
        }
    }
}
