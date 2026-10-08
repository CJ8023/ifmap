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
package cn.cj.ifmap.testkit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TestDatabases} 自身的行为：前缀形状栅栏、生产 DDL 的方言适配、Spring 属性生成、连接可用性。
 *
 * <p>真库专属分支（URL 补默认参数）只在设置了 {@code IFMAP_JDBC_URL} 时才会有值，因此这里按当前档位分支断言。</p>
 *
 * @author caijun
 */
class TestDatabasesTest {

    /** 与生产 DDL 同形状的样例（表尾存储引擎选项 + json 列 + 表级 COMMIT + 行注释）。 */
    private static final String SAMPLE_DDL =
            "-- 建表（样例）\n"
                    + "CREATE TABLE `${tablePrefix}config` (\n"
                    + "  `key_id` bigint NOT NULL COMMENT '主键',\n"
                    + "  `request_param` json DEFAULT NULL COMMENT '请求报文',\n"
                    + "  `remark` varchar(200) DEFAULT NULL COMMENT '备注',\n"
                    + "  PRIMARY KEY (`key_id`)\n"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC\n"
                    + "  COMMENT='资方接口配置';\n";

    @Test
    @DisplayName("前缀栅栏：只认 itt_<hint>_<hex8>_，任何 ifmap_/bankint_ 默认前缀都进不来")
    void prefixFenceAcceptsOnlyManagedShape() {
        assertTrue(TestDatabases.TEST_PREFIX_PATTERN.matcher("itt_ifmap_ab12cd34_").matches());
        assertTrue(TestDatabases.TEST_PREFIX_PATTERN.matcher("itt__a1b2c3d4_").matches(), "hint 可以为空");
        assertTrue(TestDatabases.TEST_PREFIX_PATTERN.matcher("itt_bankint_ab12cd34_").matches());

        assertFalse(TestDatabases.TEST_PREFIX_PATTERN.matcher("ifmap_").matches(), "生产默认前缀绝不能匹配");
        assertFalse(TestDatabases.TEST_PREFIX_PATTERN.matcher("bankint_").matches());
        assertFalse(TestDatabases.TEST_PREFIX_PATTERN.matcher("itt_ifmap_ab12cd34").matches(), "结尾必须有下划线");
        assertFalse(TestDatabases.TEST_PREFIX_PATTERN.matcher("itt_ifmap_AB12CD34_").matches(), "hex 必须小写");
        assertFalse(TestDatabases.TEST_PREFIX_PATTERN.matcher("itt_ifmap_ab12cd3_").matches(), "hex 必须 8 位");
        assertFalse(TestDatabases.TEST_PREFIX_PATTERN.matcher("itt_abcdefghijklmnopqrs_ab12cd34_").matches(),
                "hint 超过 18 字符会让前缀超长（TableNameResolver 上限 32）");
    }

    @Test
    @DisplayName("H2 适配：剥掉表尾存储引擎选项与表级 COMMENT，json 列降级成 text")
    void h2AdaptationStripsEngineTailAndDowngradesJson() {
        String h2 = TestDatabases.toH2(SAMPLE_DDL, "ifmap_", 1);

        assertTrue(h2.contains("CREATE TABLE `ifmap_config`"), h2);
        assertTrue(h2.contains("`request_param` text"), h2);
        assertFalse(h2.contains("json"), "降级后不应再有 json 列：" + h2);
        assertFalse(h2.contains("ENGINE="), h2);
        assertFalse(h2.contains("ROW_FORMAT"), h2);
        assertFalse(h2.contains("COMMENT='资方接口配置'"), "表级 COMMENT 属于存储引擎选项，H2 不识别");
        assertFalse(h2.contains("-- 建表"), "注释行应被剥掉");
        assertFalse(h2.endsWith(";"), "行尾分号应被剥掉（Statement.execute 不接受）");
    }

    @Test
    @DisplayName("H2 适配是断言式的：json 列个数/表尾形状与预期不符就报错，不静默跳过")
    void h2AdaptationIsAssertive() {
        IllegalStateException jsonMismatch =
                assertThrows(IllegalStateException.class, () -> TestDatabases.toH2(SAMPLE_DDL, "ifmap_", 0));
        assertTrue(jsonMismatch.getMessage().contains("json 列降级数量与预期不符"), jsonMismatch.getMessage());

        String noTail = SAMPLE_DDL.replace(") ENGINE=InnoDB", ")");
        IllegalStateException tailMismatch =
                assertThrows(IllegalStateException.class, () -> TestDatabases.toH2(noTail, "ifmap_", 1));
        assertTrue(tailMismatch.getMessage().contains("表尾存储引擎选项"), tailMismatch.getMessage());
    }

    @Test
    @DisplayName("MySQL 适配：列类型与表尾选项原样保留，只替换前缀与剥注释")
    void mysqlAdaptationKeepsDialectSpecifics() {
        String mysql = TestDatabases.toMysql(SAMPLE_DDL, "itt_x_ab12cd34_");

        assertTrue(mysql.contains("CREATE TABLE `itt_x_ab12cd34_config`"), mysql);
        assertTrue(mysql.contains("`request_param` json"), "真库档必须保留 json 列：" + mysql);
        assertTrue(mysql.contains("ENGINE=InnoDB"), mysql);
        assertTrue(mysql.contains("COMMENT='资方接口配置'"), mysql);
        assertFalse(mysql.contains("-- 建表"), mysql);
        assertFalse(mysql.endsWith(";"), mysql);
    }

    @Test
    @DisplayName("json 列预期个数：配置历史表 2 个；执行日志表 0 个（request_param 是 mediumtext）")
    void expectedJsonColumnsByFile() {
        assertEquals(2, TestDatabases.expectedJsonColumns("db/changelog/v1.0.0/004-create-config-history.sql"));
        assertEquals(0, TestDatabases.expectedJsonColumns("db/changelog/v1.0.0/003-create-execution-log.sql"));
        assertEquals(0, TestDatabases.expectedJsonColumns("db/changelog/v1.0.0/001-create-ifmap-config.sql"));
        assertEquals(0, TestDatabases.expectedJsonColumns(TestDatabases.ARCHIVE_DDL));
    }

    @Test
    @DisplayName("DDL 脚本不存在时立刻失败（而不是拿到 null 继续跑）")
    void missingDdlFailsLoudly() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TestDatabases.read("db/no-such-file.sql"));
        assertTrue(e.getMessage().contains("建表脚本缺失"), e.getMessage());
    }

    @Test
    @DisplayName("Schema 可用：前缀按档位解析，数据源能建表读写，清理幂等")
    void schemaIsUsableAndCleanupIsIdempotent() throws Exception {
        TestDatabases.Schema schema = TestDatabases.fresh("ifmap_");
        if (TestDatabases.mysqlEnabled()) {
            assertTrue(TestDatabases.TEST_PREFIX_PATTERN.matcher(schema.prefix()).matches(), schema.prefix());
            assertTrue(schema.prefix().startsWith("itt_ifmap_"), "hint 里的下划线要保留：" + schema.prefix());
        } else {
            assertEquals("ifmap_", schema.prefix(), "H2 档前缀就是逻辑前缀");
        }

        String probe = schema.prefix() + "probe";
        try (Connection conn = schema.dataSource().getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE `" + probe + "` (`id` bigint primary key)");
            st.execute("INSERT INTO `" + probe + "` VALUES (1)");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + probe + "`")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }

        TestDatabases.drop(schema.prefix());
        TestDatabases.drop(schema.prefix());
        TestDatabases.cleanupAll();
        if (TestDatabases.mysqlEnabled()) {
            assertTrue(TestDatabases.tablesWithPrefix(schema.prefix()).isEmpty(), "drop 后不应残留测试表");
        } else {
            assertTrue(TestDatabases.dropAllTestTables().isEmpty(), "H2 档没有落库的表要清理");
        }
    }

    @Test
    @DisplayName("清理栅栏：只删 itt_ 前缀的表，其它前缀（包括默认 ifmap_）一律不碰")
    void cleanupNeverTouchesForeignTables() throws Exception {
        Assumptions.assumeTrue(TestDatabases.mysqlEnabled(), "只在真库档验证（H2 档没有落库的表）");

        String canary = "zcanary_" + System.nanoTime() + "_probe";
        try (Connection conn = TestDatabases.dataSource().getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS `" + canary + "`");
            st.execute("CREATE TABLE `" + canary + "` (`id` bigint primary key)");
        }
        try {
            TestDatabases.cleanupAll();
            TestDatabases.dropAllTestTables();
            assertTrue(TestDatabases.tablesWithPrefix("zcanary_").contains(canary),
                    "非 itt_ 前缀的表绝不能被测试清理删掉");
            assertThrows(IllegalStateException.class, () -> TestDatabases.drop("ifmap_"),
                    "拿默认前缀当参数必须被拒绝（即使误写代码也删不到真表）");
        } finally {
            try (Connection conn = TestDatabases.dataSource().getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS `" + canary + "`");
            }
        }
    }

    @Test
    @DisplayName("真库连接：会话时区与 JVM 时区对齐（否则 CURRENT_TIMESTAMP 默认值会偏移几小时）")
    void mysqlSessionTimeZoneAlignsWithJvm() throws Exception {
        Assumptions.assumeTrue(TestDatabases.mysqlEnabled(), "只在真库档验证");

        try (Connection conn = TestDatabases.dataSource().getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT NOW(3)")) {
            assertTrue(rs.next());
            long serverNow = rs.getTimestamp(1).getTime();
            long diff = Math.abs(System.currentTimeMillis() - serverNow);
            assertTrue(diff < 120000L,
                    "服务端 NOW() 与 JVM 当前时间差了 " + diff + "ms：serverTimezone 与 sessionVariables 必须成对对齐，"
                            + "URL=" + TestDatabases.mysqlUrl());
        }
    }

    @Test
    @DisplayName("真库连接：url 参数用 %2B 转义 +（未转义会被解码成空格，驱动直接报 Invalid ZoneId）")
    void mysqlUrlEncodesTimezonePlus() {
        Assumptions.assumeTrue(TestDatabases.mysqlEnabled(), "只在真库档验证（H2 档没有真库 URL）");

        String url = TestDatabases.mysqlUrl();
        assertTrue(url.contains("serverTimezone="), url);
        assertFalse(url.contains("serverTimezone=+"), "未转义的 + 会被解码成空格：" + url);
        assertTrue(url.contains("sessionVariables=time_zone='"), url);
    }

    @Test
    @DisplayName("残留清扫：按创建时间判定，刚建的表不会被误删；阈值 0 时才清")
    void staleSweepOnlyDropsOldTables() throws Exception {
        Assumptions.assumeTrue(TestDatabases.mysqlEnabled(), "只在真库档验证（H2 档没有落库的表）");

        String hint = "ifmap_stale";
        String prefix = TestDatabases.prefixFor(hint);
        String probe = prefix + "probe";
        try (Connection conn = TestDatabases.dataSource().getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE `" + probe + "` (`id` bigint primary key)");
        }
        assertFalse(TestDatabases.tablesWithPrefix(prefix).isEmpty(), "前置条件：测试表已建");

        // 默认 30 分钟阈值：刚建的表绝不能被动（否则会和并行构建互相踩）
        assertTrue(TestDatabases.sweepStaleTestTables(TestDatabases.STALE_MINUTES).isEmpty(),
                "刚建的表被误判为陈旧");
        assertFalse(TestDatabases.tablesWithPrefix(prefix).isEmpty(), "默认阈值下不该删任何表");

        // 阈值 0：所有 itt_ 表都算陈旧，必须被清掉
        assertFalse(TestDatabases.sweepStaleTestTables(0).isEmpty(), "阈值 0 时应清掉测试表");
        assertTrue(TestDatabases.tablesWithPrefix(prefix).isEmpty(), "清扫后不应残留");
    }

    @Test
    @DisplayName("Spring 属性：按档位给 url/账号/驱动，并把解析后的表前缀一起给出")
    void springPropertiesFollowDialect() {
        Map<String, String> props = TestDatabases.springProperties("ifmap_orc_default");

        String prefix = props.get("ifmap.table-prefix");
        assertNotNull(prefix);
        assertEquals(TestDatabases.prefixFor("ifmap_orc_default"), prefix);
        if (TestDatabases.mysqlEnabled()) {
            assertTrue(props.get("spring.datasource.url").startsWith("jdbc:mysql:"), props.toString());
            assertNotNull(props.get("spring.datasource.username"));
            assertEquals("com.mysql.cj.jdbc.Driver", props.get("spring.datasource.driver-class-name"));
            assertTrue(prefix.startsWith("itt_"), prefix);
        } else {
            assertTrue(props.get("spring.datasource.url").startsWith("jdbc:h2:mem:"), props.toString());
            assertEquals("sa", props.get("spring.datasource.username"));
            assertEquals("org.h2.Driver", props.get("spring.datasource.driver-class-name"));
            assertEquals("ifmap_orc_default", prefix);
        }
    }

    @Test
    @DisplayName("Spring 属性：同一 hint 复用同一个库（与原来\"同一 H2 库名\"口径一致）")
    void springPropertiesAreStablePerHint() {
        if (TestDatabases.mysqlEnabled()) {
            assertEquals(TestDatabases.prefixFor("ifmap_stable"), TestDatabases.prefixFor("ifmap_stable"));
            return;
        }
        assertEquals(TestDatabases.springProperties("ifmap_stable").get("spring.datasource.url"),
                TestDatabases.springProperties("ifmap_stable").get("spring.datasource.url"));
    }

    @Test
    @DisplayName("Spring 属性数组：key=value 形式，供 ApplicationContextRunner 直接使用")
    void springPropertyArrayIsKeyValueForm() {
        String[] arr = TestDatabases.springPropertyArray("ifmap_array");
        assertEquals(5, arr.length);
        boolean hasPrefix = false;
        for (String s : arr) {
            assertTrue(s.contains("="), s);
            if (s.startsWith("ifmap.table-prefix=")) {
                hasPrefix = true;
            }
        }
        assertTrue(hasPrefix, "必须带上 ifmap.table-prefix");
    }
}
