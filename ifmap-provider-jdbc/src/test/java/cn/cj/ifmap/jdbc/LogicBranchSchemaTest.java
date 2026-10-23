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
package cn.cj.ifmap.jdbc;

import cn.cj.ifmap.testkit.TestDatabases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逻辑分支表的判定列结构（008 加列 / 010 删旧列）验收。
 *
 * <p>与 {@link ResultJudgeSchemaTest} 同样的理由：配置表的结构契约不能只靠"跑一遍业务用例没报错"，
 * 因为<b>列名写错会被 H2/MySQL 报出来，但"多了一列旧列"不会</b> —— 而旧列留在那里就意味着
 * 两套口径并存（新写入只填 {@code branch_judge}，旧列永远是陈旧的默认值，谁读谁拿错结论）。</p>
 *
 * <p>断言 {@code branch_judge} 是 {@code text NOT NULL} 且<b>无 DEFAULT</b>、
 * {@code logic_branch_flag} / {@code logic_branch_value} 已不存在，并额外验证 DB 层兜底：
 * 显式省略 {@code branch_judge} 的 INSERT 必须失败。</p>
 *
 * @author caijun
 */
class LogicBranchSchemaTest {

    private JdbcTemplate jdbc;
    private String prefix;

    @BeforeEach
    void setUp() {
        TestDatabases.Schema schema = TestDatabases.freshWithTables();
        this.jdbc = new JdbcTemplate(schema.dataSource());
        this.prefix = schema.prefix();
    }

    @Test
    @DisplayName("logic_branch_config.branch_judge：text + NOT NULL + 无默认值（空 = 非法，不是兜底）")
    void branchJudgeIsRequiredWithoutDefault() {
        Map<String, Object> column = column("branch_judge");

        assertTrue(typeOf(column).startsWith("text"), "期望 text（规格可能很长）：" + typeOf(column));
        assertEquals("NO", column.get("IS_NULLABLE"));
        assertNull(defaultOf(column),
                "有 DEFAULT 就等于「漏填也算配了判定」，与「空规格 fail-closed」的口径冲突");
    }

    @Test
    @DisplayName("旧的 logic_branch_flag / logic_branch_value 已被 010 移除（单列判定，避免两套口径并存）")
    void legacyBranchColumnsAreGone() {
        List<String> names = columnNames();

        assertFalse(names.contains("logic_branch_flag"), "logic_branch_flag 应被 010 删除：" + names);
        assertFalse(names.contains("logic_branch_value"), "logic_branch_value 应被 010 删除：" + names);
        assertTrue(names.containsAll(Arrays.asList("branch_judge", "logic_branch_name", "method_flag",
                "logic_branch_order")), "核心列丢了：" + names);
    }

    @Test
    @DisplayName("DB 层兜底：显式省略 branch_judge 的 INSERT 必须失败（不能只靠应用层校验）")
    void insertWithoutBranchJudgeFails() {
        String table = "`" + prefix + "logic_branch_config`";
        // 只有应用层 require 是不够的：SQL 直接写、或换一套写入实现时，DB 必须自己拦住
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + table
                + " (`key_id`,`tenant_id`,`interface_no`,`logic_branch_name`,`logic_branch_order`,"
                + "`del_status`,`deleted_seq`) VALUES (9002,-1,'IF_X','分支',0,0,0)"));
    }

    private Map<String, Object> column(String column) {
        String sql = "SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS"
                + " WHERE LOWER(TABLE_NAME) = ? AND LOWER(COLUMN_NAME) = ?" + currentSchemaClause();
        return jdbc.queryForMap(sql, (prefix + "logic_branch_config").toLowerCase(Locale.ROOT),
                column.toLowerCase(Locale.ROOT));
    }

    private List<String> columnNames() {
        return jdbc.queryForList("SELECT LOWER(COLUMN_NAME) FROM information_schema.COLUMNS"
                + " WHERE LOWER(TABLE_NAME) = ?" + currentSchemaClause(),
                String.class, (prefix + "logic_branch_config").toLowerCase(Locale.ROOT));
    }

    /** 列类型（H2 的 {@code COLUMN_TYPE} 是整段定义、MySQL 的只是类型）：取第一个空白前的前缀比对。 */
    private static String typeOf(Map<String, Object> column) {
        String type = String.valueOf(column.get("COLUMN_TYPE")).toLowerCase(Locale.ROOT);
        int space = type.indexOf(' ');
        return space < 0 ? type : type.substring(0, space);
    }

    /** 默认值（H2 把"无默认值"表示成字符串 {@code NULL}，MySQL 表示成 SQL NULL —— 统一成 null）。 */
    private static String defaultOf(Map<String, Object> column) {
        Object value = column.get("COLUMN_DEFAULT");
        if (value == null || "NULL".equals(String.valueOf(value))) {
            return null;
        }
        String text = String.valueOf(value);
        return text.startsWith("'") && text.endsWith("'") && text.length() >= 2
                ? text.substring(1, text.length() - 1) : text;
    }

    /** 真库档要限定当前库：同一个 MySQL 实例里可能有别的库存在同名表。 */
    private static String currentSchemaClause() {
        return TestDatabases.mysqlEnabled() ? " AND TABLE_SCHEMA = DATABASE()" : "";
    }
}
