package cn.cj.ifmap.spring;

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.jdbc.TableNameResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    private static DriverManagerDataSource h2(String dbName) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        return dataSource;
    }

    private static IfmapSchemaInitializer initializer(String dbName, String prefix) {
        return new IfmapSchemaInitializer(h2(dbName), new TableNameResolver(prefix),
                new DefaultResourceLoader());
    }

    @Test
    @DisplayName("首次启动建 4 张表；再次启动全部跳过（幂等）")
    void createsThenSkips() {
        IfmapSchemaInitializer initializer = initializer("ifmap_ddl_idempotent", TableNameResolver.DEFAULT_PREFIX);

        List<String> created = initializer.createTablesIfAbsent();
        assertEquals(4, created.size(), "应新建 4 张表：" + created);
        assertTrue(created.contains("ifmap_config"));
        assertTrue(created.contains("ifmap_logic_branch_config"));
        assertTrue(created.contains("ifmap_execution_log"));
        assertTrue(created.contains("ifmap_config_history"));

        assertEquals(0, initializer.createTablesIfAbsent().size(), "第二次启动不应再建表");
    }

    @Test
    @DisplayName("表名前缀生效（复用存量 bankint_ 表）")
    void honoursPrefix() {
        IfmapSchemaInitializer initializer = initializer("ifmap_ddl_prefix", "bankint_");

        assertEquals(4, initializer.createTablesIfAbsent().size());
        assertTrue(initializer.tableExists("bankint_config"));
        assertTrue(initializer.tableExists("bankint_execution_log"));
        assertFalse(initializer.tableExists("ifmap_config"), "不应同时建出 ifmap_ 前缀的表");
    }

    @Test
    @DisplayName("tableExists 对不存在的表返回 false（不抛异常）")
    void tableExistsIsSafe() {
        IfmapSchemaInitializer initializer = initializer("ifmap_ddl_exists", "nope_");
        assertFalse(initializer.tableExists("nope_config"));
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
        assertFalse(initializer.isMysqlFamily(), "H2 不应被判定为 MySQL 系");
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

        // 真跑一遍：H2 建出来后 json 列已不存在（否则 setString 会被包成 JSON 字符串字面量）
        javax.sql.DataSource dataSource = h2("ifmap_ddl_json");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        new IfmapSchemaInitializer(dataSource, new TableNameResolver(TableNameResolver.DEFAULT_PREFIX),
                new DefaultResourceLoader()).createTablesIfAbsent();
        String type = jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE lower(table_name) = 'ifmap_config_history' AND lower(column_name) = 'snapshot'", String.class);
        assertNotNull(type);
        assertNotEquals("JSON", type == null ? null : type.toUpperCase(), "H2 上 snapshot 不能还是 JSON 类型");
        jdbc.update("INSERT INTO `ifmap_config_history` (`key_id`,`config_key_id`,`tenant_id`,`interface_no`,"
                + "`change_type`,`snapshot`) VALUES (1, 1, 1, 'IF_A', 'CREATE', ?)", "{\"a\":1}");
        assertEquals("{\"a\":1}", jdbc.queryForObject(
                "SELECT `snapshot` FROM `ifmap_config_history` WHERE `key_id` = 1", String.class),
                "H2 上写进去的 JSON 文本必须原样读回（MySQL 语义）");
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
        DriverManagerDataSource dataSource = h2("ifmap_ddl_columns");
        new IfmapSchemaInitializer(dataSource, new TableNameResolver(TableNameResolver.DEFAULT_PREFIX),
                new DefaultResourceLoader()).createTablesIfAbsent();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertEquals(28, columnsOf(jdbc, "ifmap_config"));
        assertEquals(17, columnsOf(jdbc, "ifmap_logic_branch_config"));
        assertEquals(17, columnsOf(jdbc, "ifmap_execution_log"));
        assertEquals(11, columnsOf(jdbc, "ifmap_config_history"));
    }

    private static int columnsOf(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE lower(table_name) = ?",
                Integer.class, table);
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
