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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定管线的<b>列级</b>契约：逐列断言，不比对列数。
 *
 * <p><b>为什么不比对列数</b>：列数相等完全掩盖得住「少一列 + 多一列」（{@code result_flag} 换成
 * {@code result_judge} 正好列数不变），而这类偏差在运行期表现为"配置读得到、判定永远失败"。
 * 所以这里按列名查 {@code information_schema}，逐列核对类型 / 可空 / 默认值。</p>
 *
 * <p>{@code result_judge} 必须是 {@code NOT NULL} 且<b>没有 DEFAULT</b>：这是"未配置判定规格
 * 就等于失败"的落库口径 —— 一旦给了默认值（哪怕是 {@code '{}'}），漏填配置就会被当成合法规格放行。</p>
 *
 * @author caijun
 */
class ResultJudgeSchemaTest {

    private JdbcTemplate jdbc;
    private String prefix;

    @BeforeEach
    void setUp() {
        TestDatabases.Schema schema = TestDatabases.freshWithTables();
        this.jdbc = new JdbcTemplate(schema.dataSource());
        this.prefix = schema.prefix();
    }

    @Test
    @DisplayName("config.result_judge：text + NOT NULL + 无默认值（漏配置 = 判定失败，不静默放行）")
    void resultJudgeIsRequiredWithoutDefault() {
        Map<String, Object> column = column("config", "result_judge");

        assertTrue(typeOf(column).startsWith("text"), "期望 text（配置可能很长）：" + typeOf(column));
        assertEquals("NO", column.get("IS_NULLABLE"));
        assertNull(defaultOf(column), "有 DEFAULT 就等于「漏填也算配置了」，与必填口径冲突");
    }

    @Test
    @DisplayName("config.judge_name：varchar(64) + NOT NULL DEFAULT ''（空串 = 走默认 ruleSpec）")
    void judgeNameDefaultsToEmpty() {
        Map<String, Object> column = column("config", "judge_name");

        assertTrue(typeOf(column).startsWith("varchar(64)"), "期望 varchar(64)：" + typeOf(column));
        assertEquals("NO", column.get("IS_NULLABLE"));
        assertEquals("", defaultOf(column));
    }

    @Test
    @DisplayName("旧的 result_flag / success_value 已从 config 表移除（单列判定，避免两套口径并存）")
    void legacyJudgeColumnsAreGone() {
        List<String> names = columnNames("config");

        assertFalse(names.contains("result_flag"), "result_flag 应被 005 删除：" + names);
        assertFalse(names.contains("success_value"), "success_value 应被 005 删除：" + names);
        assertTrue(names.containsAll(Arrays.asList("result_judge", "judge_name")));
    }

    @Test
    @DisplayName("config.call_direction：varchar(16) + NOT NULL DEFAULT 'OUTBOUND'（存量行零回填，老行为不变）")
    void callDirectionDefaultsToOutbound() {
        Map<String, Object> column = column("config", "call_direction");

        assertTrue(typeOf(column).startsWith("varchar(16)"), "期望 varchar(16)：" + typeOf(column));
        assertEquals("NO", column.get("IS_NULLABLE"));
        assertEquals("OUTBOUND", defaultOf(column), "默认必须是 OUTBOUND：存量行不回填也不会变成被调方向");
    }

    @Test
    @DisplayName("execution_log 新增四列：http_status 可空、其余 NOT NULL DEFAULT ''")
    void executionLogHasFourNewColumns() {
        List<String> names = columnNames("execution_log");
        assertTrue(names.containsAll(Arrays.asList("http_status", "fail_type", "error_code", "track_id")),
                "006 未生效：" + names);

        Map<String, Object> httpStatus = column("execution_log", "http_status");
        assertEquals("YES", httpStatus.get("IS_NULLABLE"), "宿主可以不上报 HTTP 状态码（如本地超时）");
        assertNull(defaultOf(httpStatus));

        Map<String, Object> failType = column("execution_log", "fail_type");
        assertEquals("NO", failType.get("IS_NULLABLE"));
        assertEquals("", defaultOf(failType));

        Map<String, Object> trackId = column("execution_log", "track_id");
        assertTrue(typeOf(trackId).startsWith("varchar(128)"), "期望 varchar(128)：" + typeOf(trackId));
    }

    @Test
    @DisplayName("DB 层兜底：显式省略 result_judge 的 INSERT 必须失败（不能靠应用层 alone）")
    void insertWithoutResultJudgeFails() {
        String table = "`" + prefix + "config`";
        // 只有应用层 require 是不够的：SQL 直接写、或换一套写入实现时，DB 必须自己拦住
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + table
                + " (`key_id`,`interface_no`,`interface_code`,`interface_name`,`busi_node`,`partner_code`,"
                + "`status`,`version`,`del_status`,`deleted_seq`) VALUES (9001,'IF_X','C','N','APPLY','PSBC',1,0,0,0)"));
    }

    private Map<String, Object> column(String table, String column) {
        String sql = "SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS"
                + " WHERE LOWER(TABLE_NAME) = ? AND LOWER(COLUMN_NAME) = ?" + currentSchemaClause();
        return jdbc.queryForMap(sql, (prefix + table).toLowerCase(java.util.Locale.ROOT),
                column.toLowerCase(java.util.Locale.ROOT));
    }

    private List<String> columnNames(String table) {
        return jdbc.queryForList("SELECT LOWER(COLUMN_NAME) FROM information_schema.COLUMNS"
                + " WHERE LOWER(TABLE_NAME) = ?" + currentSchemaClause(),
                String.class, (prefix + table).toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 列类型（H2 的 {@code COLUMN_TYPE} 是整段定义、MySQL 的只是类型）：取第一个空白前的前缀比对，
     * 这样两种库上都能断言"是 text / varchar(64)"。
     */
    private static String typeOf(Map<String, Object> column) {
        String type = String.valueOf(column.get("COLUMN_TYPE")).toLowerCase();
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
