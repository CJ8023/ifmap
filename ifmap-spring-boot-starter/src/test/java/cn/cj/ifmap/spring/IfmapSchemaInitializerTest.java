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
package cn.cj.ifmap.spring;

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.jdbc.TableNameResolver;
import cn.cj.ifmap.testkit.TestDatabases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IfmapSchemaInitializer} 单测（真实 H2 + 真实建表脚本）：
 * 建表、幂等、前缀替换、非 MySQL 表尾适配、脚本原文校验。
 *
 * @author caijun
 */
class IfmapSchemaInitializerTest {

    /**
     * 当前档位下该用例真正使用的表前缀：H2 档就是传入的逻辑前缀；真库档是与用例名对应的 {@code itt_*} 前缀
     * （同一个库里靠不同前缀隔离，互不踩表）。
     */
    private static String prefix(String caseName, String logicalPrefix) {
        return TestDatabases.mysqlEnabled() ? TestDatabases.prefixFor(caseName) : logicalPrefix;
    }

    private static IfmapSchemaInitializer initializer(String caseName, String logicalPrefix) {
        return new IfmapSchemaInitializer(TestDatabases.fresh(caseName).dataSource(),
                new TableNameResolver(prefix(caseName, logicalPrefix)), new DefaultResourceLoader());
    }

    @Test
    @DisplayName("首次启动建 4 张表；再次启动全部跳过（幂等）")
    void createsThenSkips() {
        final String caseName = "ifmap_ddl_idempotent";
        String prefix = prefix(caseName, TableNameResolver.DEFAULT_PREFIX);
        IfmapSchemaInitializer initializer = initializer(caseName, TableNameResolver.DEFAULT_PREFIX);

        List<String> created = initializer.createTablesIfAbsent();
        assertEquals(4, created.size(), "应新建 4 张表：" + created);
        assertTrue(created.contains(prefix + "config"));
        assertTrue(created.contains(prefix + "logic_branch_config"));
        assertTrue(created.contains(prefix + "execution_log"));
        assertTrue(created.contains(prefix + "config_history"));

        assertEquals(0, initializer.createTablesIfAbsent().size(), "第二次启动不应再建表");
    }

    @Test
    @DisplayName("表名前缀生效（复用存量 bankint_ 表）")
    void honoursPrefix() {
        final String caseName = "ifmap_ddl_prefix";
        String prefix = prefix(caseName, "bankint_");
        IfmapSchemaInitializer initializer = initializer(caseName, "bankint_");

        assertEquals(4, initializer.createTablesIfAbsent().size());
        assertTrue(initializer.tableExists(prefix + "config"));
        assertTrue(initializer.tableExists(prefix + "execution_log"));
        assertFalse(initializer.tableExists(prefix(caseName + "_default", TableNameResolver.DEFAULT_PREFIX) + "config"),
                "不应同时建出默认前缀的表");
    }

    @Test
    @DisplayName("tableExists 对不存在的表返回 false（不抛异常）")
    void tableExistsIsSafe() {
        final String caseName = "ifmap_ddl_exists";
        IfmapSchemaInitializer initializer = initializer(caseName, "nope_");
        assertFalse(initializer.tableExists(prefix(caseName, "nope_") + "config"));
    }

    @Test
    @DisplayName("starter 执行的就是生产脚本本身（占位符 + MySQL 表尾选项都在）")
    void readsProductionScriptVerbatim() {
        Resource resource = new DefaultResourceLoader()
                .getResource("classpath:db/changelog/v1.0.0/001-create-ifmap-config.sql");
        assertTrue(resource.exists(), "生产建表脚本必须在 classpath 上（来自 ifmap-provider-jdbc）");
        String ddl = read(resource);
        assertTrue(ddl.contains("${tablePrefix}config"), "脚本应使用 ${tablePrefix} 占位符");
        assertTrue(ddl.contains("ENGINE=InnoDB"), "脚本应是 MySQL 生产脚本原文");
        assertTrue(ddl.contains("COLLATE="), "脚本应保留 MySQL 表尾选项");
        assertTrue(ddl.contains("uk_${tablePrefix}config_biz"), "唯一键应随前缀走");
    }

    @Test
    @DisplayName("H2 会走「非 MySQL」分支：检测为 H2 且写盘 SQL 已剥表尾")
    void detectsH2AndStripsTail() {
        IfmapSchemaInitializer initializer = initializer("ifmap_ddl_dialect", TableNameResolver.DEFAULT_PREFIX);
        if (TestDatabases.mysqlEnabled()) {
            assertTrue(initializer.isMysqlFamily(), "真库档应判定为 MySQL 系（脚本原样执行）");
        } else {
            assertFalse(initializer.isMysqlFamily(), "H2 不应被判定为 MySQL 系");
        }
        assertEquals(4, initializer.createTablesIfAbsent().size(), "剥离表尾后 H2 也能建表");
    }

    @Test
    @DisplayName("表尾适配：MySQL 原样保留；非 MySQL 剥掉；结构不符则失败（断言式）")
    void adaptDdlIsAssertive() {
        String mysqlDdl = "CREATE TABLE t (id bigint) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        assertEquals(mysqlDdl, IfmapSchemaInitializer.adaptDdl(mysqlDdl, true));

        String stripped = IfmapSchemaInitializer.adaptDdl(mysqlDdl, false);
        assertTrue(stripped.startsWith("CREATE TABLE t (id bigint)"));
        assertFalse(stripped.contains("ENGINE=InnoDB"), "非 MySQL 应剥掉表尾表选项：" + stripped);
        assertFalse(stripped.contains("CHARSET"), "非 MySQL 应剥掉表尾表选项：" + stripped);

        assertThrows(IfmapConfigException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                IfmapSchemaInitializer.adaptDdl("CREATE TABLE t (id bigint);", false);
            }
        }, "没有表尾却要求剥离，必须失败而不是静默放行");
    }

    @Test
    @DisplayName("非 MySQL：json 列降级为 text（H2 的 JSON 类型存不了 JDBC 字符串）")
    void nonMysqlDowngradesJsonColumns() {
        String ddl = "CREATE TABLE `t` (\n"
                + "  `snapshot` json         NOT NULL COMMENT '快照',\n"
                + "  `diff`     json                  DEFAULT NULL COMMENT '差异',\n"
                + "  `note`     varchar(32)  NOT NULL DEFAULT '' COMMENT 'json in comment'\n"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        String adapted = IfmapSchemaInitializer.adaptDdl(ddl, false);
        assertTrue(adapted.contains("`snapshot` text"), adapted);
        assertTrue(adapted.contains("`diff`     text"), adapted);
        assertFalse(adapted.contains("`snapshot` json"), adapted);
        assertTrue(adapted.contains("json in comment"), "注释里的 json 不能被误伤：" + adapted);
        assertEquals(ddl, IfmapSchemaInitializer.adaptDdl(ddl, true), "MySQL 侧不改写");

        // 真跑一遍：H2 必须把 json 列降级（否则 setString 会被包成 JSON 字符串字面量）；真库保留 json 列语义
        final String caseName = "ifmap_ddl_json";
        javax.sql.DataSource dataSource = TestDatabases.fresh(caseName).dataSource();
        String prefix = prefix(caseName, TableNameResolver.DEFAULT_PREFIX);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        new IfmapSchemaInitializer(dataSource, new TableNameResolver(prefix),
                new DefaultResourceLoader()).createTablesIfAbsent();
        String historyTable = prefix + "config_history";
        String type = jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE lower(table_name) = ? AND lower(column_name) = 'snapshot'", String.class,
                historyTable.toLowerCase(java.util.Locale.ROOT));
        assertNotNull(type);
        if (TestDatabases.mysqlEnabled()) {
            assertEquals("json", type.toLowerCase(java.util.Locale.ROOT), "真库上 snapshot 保持 json 列");
        } else {
            assertFalse("JSON".equalsIgnoreCase(type), "H2 上 snapshot 不能还是 JSON 类型");
        }
        jdbc.update("INSERT INTO `" + historyTable + "` (`key_id`,`config_key_id`,`tenant_id`,`interface_no`,"
                + "`change_type`,`snapshot`) VALUES (1, 1, 1, 'IF_A', 'CREATE', ?)", "{\"a\":1}");
        // H2 是文本列，原样读回；MySQL 的 json 列会把文本规范化（这正是日志列改 mediumtext 的原因）
        String expected = TestDatabases.mysqlEnabled() ? "{\"a\": 1}" : "{\"a\":1}";
        assertEquals(expected, jdbc.queryForObject(
                        "SELECT `snapshot` FROM `" + historyTable + "` WHERE `key_id` = 1", String.class),
                "写进去的 JSON 文本必须按当前库的语义读回");
    }

    @Test
    @DisplayName("语句分隔符扫描：跳过单引号内容（列注释里有分号，不能误判成多条语句）")
    void separatorScannerSkipsQuotedSemicolon() {
        assertFalse(IfmapSchemaInitializer.hasStatementSeparator(
                "CREATE TABLE t (a bigint COMMENT '删除标识;0:未删除1:已删除', b bigint)"));
        assertTrue(IfmapSchemaInitializer.hasStatementSeparator("CREATE TABLE t (a bigint); SELECT 1"));
        assertFalse(IfmapSchemaInitializer.hasStatementSeparator("CREATE TABLE t (a bigint COMMENT 'It''s ok')"));
    }

    @Test
    @DisplayName("四张表列数与 W2 口径一致（28 / 17 / 17 / 11）")
    void columnCountsMatch() {
        final String caseName = "ifmap_ddl_columns";
        javax.sql.DataSource dataSource = TestDatabases.fresh(caseName).dataSource();
        String prefix = prefix(caseName, TableNameResolver.DEFAULT_PREFIX);
        new IfmapSchemaInitializer(dataSource, new TableNameResolver(prefix),
                new DefaultResourceLoader()).createTablesIfAbsent();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertEquals(28, columnsOf(jdbc, prefix + "config"));
        assertEquals(17, columnsOf(jdbc, prefix + "logic_branch_config"));
        assertEquals(17, columnsOf(jdbc, prefix + "execution_log"));
        assertEquals(11, columnsOf(jdbc, prefix + "config_history"));
    }

    private static int columnsOf(JdbcTemplate jdbc, String table) {
        String sql = "SELECT COUNT(*) FROM information_schema.columns WHERE lower(table_name) = ?";
        if (TestDatabases.mysqlEnabled()) {
            // 真库里可能同时存在别的库的同名表，按当前库限定
            sql += " AND table_schema = DATABASE()";
        }
        Integer count = jdbc.queryForObject(sql, Integer.class, table.toLowerCase(java.util.Locale.ROOT));
        return count == null ? -1 : count;
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + resource + " 失败", e);
        }
    }
}
