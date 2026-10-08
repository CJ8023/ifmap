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
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.rule.RuleContext;
import cn.cj.ifmap.core.rule.RuleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端测试：真实 Spring Boot 上下文 + H2 + 生产建表脚本 + 生产仓储 + 生产引擎。
 *
 * @author caijun
 */
@SpringBootTest
class IfmapDemoApplicationTest {

    @Autowired
    private ConfigRepository repository;

    @Autowired
    private IfmapEngine engine;

    @Autowired
    private RuleRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("starter 自动建表：4 张表都在")
    void tablesCreatedByStarter() {
        for (String table : new String[]{"ifmap_config", "ifmap_logic_branch_config",
                "ifmap_execution_log", "ifmap_config_history"}) {
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
}
