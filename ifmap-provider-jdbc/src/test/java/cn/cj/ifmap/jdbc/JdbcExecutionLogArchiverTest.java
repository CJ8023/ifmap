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

import cn.cj.ifmap.core.exception.IfmapConfigException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcExecutionLogArchiver} 测试：用**真实生产 DDL**（热表来自 changelog、冷表来自
 * {@code db/optional/execution-log-partition/04-create-archive-table.sql}）+ 真实搬迁语句在 H2(MySQL 模式) 上跑。
 *
 * <p>断言的是"哪些行被搬到冷表、热表还剩什么、失败时给什么提示"，不是"SQL 长什么样"。</p>
 *
 * @author caijun
 */
class JdbcExecutionLogArchiverTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    private JdbcTemplate jdbc;
    private JdbcExecutionLogArchiver archiver;
    private String prefix;
    private long seq;

    @BeforeEach
    void setUp() {
        DataSource dataSource = TestSchema.freshDataSourceWithArchive();
        jdbc = new JdbcTemplate(dataSource);
        archiver = new JdbcExecutionLogArchiver(jdbc, TableNameResolver.defaults());
        prefix = TableNameResolver.DEFAULT_PREFIX;
        seq = 0L;
    }

    /** 插一条指定 add_time 的执行日志，返回主键。 */
    private long insertLog(long addTimeMillis) {
        long keyId = 900000L + (++seq);
        jdbc.update("INSERT INTO `" + prefix + "execution_log`"
                        + " (`key_id`, `tenant_id`, `interface_no`, `biz_id`, `request_param`,"
                        + "  `response_param`, `execution_time`, `execution_result`, `remark`,"
                        + "  `add_user_id`, `add_time`, `add_request_id`)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                keyId, -1L, "IF_ARCH", "BIZ-" + keyId, "{\"amount\":1}", "{\"code\":\"0000\"}",
                12L, "SUCCESS", "备注", "u1", new Timestamp(addTimeMillis), "req-1");
        return keyId;
    }

    private List<Long> hotKeyIds() {
        return jdbc.queryForList("SELECT `key_id` FROM `ifmap_execution_log` ORDER BY `key_id`", Long.class);
    }

    private List<Long> coldKeyIds() {
        return jdbc.queryForList("SELECT `key_id` FROM `ifmap_execution_log_archive` ORDER BY `key_id`", Long.class);
    }

    @Test
    @DisplayName("只搬过期行：早于阈值的进冷表，晚于阈值的原样留在热表")
    void archivesOnlyExpiredRows() {
        long now = System.currentTimeMillis();
        long cold1 = insertLog(now - 200L * DAY);
        long cold2 = insertLog(now - 181L * DAY);
        long hot1 = insertLog(now - 179L * DAY);
        long hot2 = insertLog(now - 1L * DAY);

        LogArchiveResult result = archiver.archive(180, 100, 10, 0L);

        assertEquals(2L, result.getArchived(), "应有 2 条写入冷表");
        assertEquals(2L, result.getRemoved(), "应有 2 条从热表删除");
        assertEquals(1, result.getBatches());
        assertFalse(result.isEmpty());
        assertEquals(Arrays.asList(hot1, hot2), hotKeyIds(), "未过期的日志必须留在热表");
        assertEquals(Arrays.asList(cold1, cold2), coldKeyIds());
    }

    @Test
    @DisplayName("搬迁保真：业务列原样复制，冷表只多一个 archive_time")
    void copiesAllBusinessColumns() {
        long now = System.currentTimeMillis();
        long keyId = insertLog(now - 10L * DAY);

        archiver.archive(5, 100, 10, 0L);

        // 逐列 getString()：H2 会把 mediumtext 当 CLOB 返回，getObject() 拿到的是 Clob 而不是内容
        List<Map<String, String>> rows = jdbc.query(
                "SELECT `key_id`, `tenant_id`, `interface_no`, `biz_id`, `request_param`, `response_param`,"
                        + " `execution_time`, `execution_result`, `remark`, `add_user_id`, `add_request_id`,"
                        + " `add_time`, `archive_time` FROM `ifmap_execution_log_archive` WHERE `key_id` = " + keyId,
                (rs, rowNum) -> {
                    Map<String, String> values = new LinkedHashMap<String, String>();
                    ResultSetMetaData meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        values.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getString(i));
                    }
                    return values;
                });
        assertEquals(1, rows.size(), "冷表应有且只有这 1 行");
        Map<String, String> row = rows.get(0);
        assertEquals("-1", row.get("tenant_id"));
        assertEquals("IF_ARCH", row.get("interface_no"));
        assertEquals("BIZ-" + keyId, row.get("biz_id"));
        assertEquals("{\"amount\":1}", row.get("request_param"));
        assertEquals("{\"code\":\"0000\"}", row.get("response_param"));
        assertEquals("12", row.get("execution_time"));
        assertEquals("SUCCESS", row.get("execution_result"));
        assertEquals("备注", row.get("remark"));
        assertEquals("u1", row.get("add_user_id"));
        assertEquals("req-1", row.get("add_request_id"));
        assertEquals(now - 10L * DAY, Timestamp.valueOf(row.get("add_time")).getTime(), 2000L,
                "add_time 必须是原业务时间，不能改成归档时间");
        assertTrue(Timestamp.valueOf(row.get("archive_time")).getTime() >= now - 60000L,
                "archive_time 应是归档发生时间");
    }

    @Test
    @DisplayName("阈值边界：刚好到期前的一秒搬走，到期后的一秒留下")
    void respectsRetentionBoundary() {
        long now = System.currentTimeMillis();
        long expired = insertLog(now - 90L * DAY - 60000L);
        long kept = insertLog(now - 90L * DAY + 60000L);

        archiver.archive(90, 100, 10, 0L);

        assertEquals(Arrays.asList(kept), hotKeyIds());
        assertEquals(Arrays.asList(expired), coldKeyIds());
    }

    @Test
    @DisplayName("分批搬迁：批大小 2、5 条过期数据 → 3 批搬完")
    void archivesInBatches() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            insertLog(now - (100L + i) * DAY);
        }

        LogArchiveResult result = archiver.archive(90, 2, 100, 0L);

        assertEquals(5L, result.getArchived());
        assertEquals(5L, result.getRemoved());
        assertEquals(3, result.getBatches(), "5 条按每批 2 条是 3 批（第 3 批只剩 1 条）");
        assertTrue(hotKeyIds().isEmpty(), "热表应被搬空");
        assertEquals(5, coldKeyIds().size());
    }

    @Test
    @DisplayName("单次最大批数护栏：批大小 1 + 最多 2 批 → 只搬 2 条，其余留给下次")
    void stopsAtMaxBatches() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            insertLog(now - (100L + i) * DAY);
        }

        LogArchiveResult result = archiver.archive(90, 1, 2, 0L);

        assertEquals(2L, result.getRemoved());
        assertEquals(2, result.getBatches());
        assertEquals(3, hotKeyIds().size(), "没搬完的必须留在热表，留给下次执行");
        assertEquals(2, coldKeyIds().size());
    }

    @Test
    @DisplayName("可重入：冷表里已存在的行不重复写，但热表照样清掉（中断续跑场景）")
    void retryIsIdempotent() {
        long now = System.currentTimeMillis();
        long keyId = insertLog(now - 10L * DAY);
        // 模拟"上次 INSERT 成功但 DELETE 之前进程被杀"：冷表已有同一 key_id 的旧副本
        jdbc.update("INSERT INTO `ifmap_execution_log_archive` (`key_id`, `tenant_id`, `interface_no`,"
                        + " `biz_id`, `request_param`, `execution_result`, `add_time`, `remark`)"
                        + " VALUES (?, -1, 'IF_ARCH', ?, '{}', 'SUCCESS', ?, '旧副本')",
                keyId, "BIZ-" + keyId, new Timestamp(now - 10L * DAY));

        LogArchiveResult result = archiver.archive(5, 100, 10, 0L);

        assertEquals(0L, result.getArchived(), "冷表已有该行，不应重复插入");
        assertEquals(1L, result.getRemoved(), "但热表里的重复行必须清掉，否则下次还会被选中");
        assertTrue(hotKeyIds().isEmpty());
        assertEquals("旧副本", jdbc.queryForObject(
                "SELECT `remark` FROM `ifmap_execution_log_archive` WHERE `key_id` = " + keyId, String.class));
    }

    @Test
    @DisplayName("归档表不存在：报错要指向建表脚本（而不是抛裸 SQL 异常）")
    void missingArchiveTableFailsWithActionableMessage() {
        jdbc.execute("DROP TABLE `ifmap_execution_log_archive`");
        insertLog(System.currentTimeMillis() - 10L * DAY);

        IfmapConfigException e = assertThrows(IfmapConfigException.class, () -> archiver.archive(5));
        assertTrue(e.getMessage().contains("04-create-archive-table.sql"),
                "报错必须告诉运维该执行哪个脚本：" + e.getMessage());
        assertTrue(e.getMessage().contains("ifmap_execution_log_archive"));
    }

    @Test
    @DisplayName("热表列漂移：加了列却忘了同步归档器 → 直接失败，不静默少归档一列")
    void rejectsHotTableColumnDrift() {
        jdbc.execute("ALTER TABLE `ifmap_execution_log` ADD COLUMN `legacy_note` varchar(16) NOT NULL DEFAULT ''");
        insertLog(System.currentTimeMillis() - 10L * DAY);

        IfmapConfigException e = assertThrows(IfmapConfigException.class, () -> archiver.archive(5));
        assertTrue(e.getMessage().contains("legacy_note"), e.getMessage());
        assertTrue(e.getMessage().contains("ARCHIVED_COLUMNS"), e.getMessage());
    }

    @Test
    @DisplayName("冷表缺列：报错要列出缺了哪些列")
    void rejectsArchiveTableMissingColumn() {
        jdbc.execute("ALTER TABLE `ifmap_execution_log_archive` DROP COLUMN `remark`");
        insertLog(System.currentTimeMillis() - 10L * DAY);

        IfmapConfigException e = assertThrows(IfmapConfigException.class, () -> archiver.archive(5));
        assertTrue(e.getMessage().contains("remark"), e.getMessage());
    }

    @Test
    @DisplayName("表名前缀生效：bankint_ 前缀下冷表是 bankint_execution_log_archive")
    void respectsTablePrefix() {
        jdbc = new JdbcTemplate(TestSchema.freshDataSourceWithArchive("bankint_"));
        archiver = new JdbcExecutionLogArchiver(jdbc, new TableNameResolver("bankint_"));
        prefix = "bankint_";
        insertLog(System.currentTimeMillis() - 10L * DAY);

        assertEquals(1L, archiver.archive(5, 100, 10, 0L).getRemoved());
        assertEquals(1, jdbc.queryForList("SELECT `key_id` FROM `bankint_execution_log_archive`", Long.class).size());
        assertTrue(jdbc.queryForList("SELECT `key_id` FROM `bankint_execution_log`", Long.class).isEmpty());
    }

    @Test
    @DisplayName("阈值必须为正：0/负数一律拒绝（0 会把整张热表搬空）")
    void rejectsNonPositiveThreshold() {
        assertThrows(IfmapConfigException.class, () -> archiver.archive(0));
        assertThrows(IfmapConfigException.class, () -> archiver.archive(-1));
        assertThrows(IfmapConfigException.class, () -> JdbcExecutionLogArchiver.cutoffBefore(0));
        assertThrows(IfmapConfigException.class, () -> archiver.archive(5, 0, 10, 0L));
        assertThrows(IfmapConfigException.class, () -> archiver.archive(5, 10, 0, 0L));
    }

    @Test
    @DisplayName("线程被中断：搬完当前批就停，剩余留给下次")
    void stopsWhenInterrupted() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 4; i++) {
            insertLog(now - (100L + i) * DAY);
        }
        Thread.currentThread().interrupt();
        try {
            LogArchiveResult result = archiver.archive(90, 1, 100, 1L);
            assertEquals(1, result.getBatches(), "中断后不应继续下一批");
            assertEquals(1L, result.getRemoved());
        } finally {
            Thread.interrupted(); // 清掉中断标志，别污染其它测试
        }
        assertEquals(3, hotKeyIds().size());
    }

    @Test
    @DisplayName("内置列清单与生产执行日志表 DDL 一致（防漂移）")
    void archivedColumnsMatchProductionDdl() {
        Set<String> actual = jdbc.query("SELECT * FROM `ifmap_execution_log` WHERE 1 = 0", rs -> {
            Set<String> names = new LinkedHashSet<String>();
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                names.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
            }
            return names;
        });
        assertEquals(new LinkedHashSet<String>(JdbcExecutionLogArchiver.ARCHIVED_COLUMNS), actual,
                "执行日志表加了列/改了名，必须同步 JdbcExecutionLogArchiver.ARCHIVED_COLUMNS 与冷表 DDL");
        assertEquals(17, JdbcExecutionLogArchiver.ARCHIVED_COLUMNS.size());
    }

    @Test
    @DisplayName("冷表脚本契约：列覆盖归档清单、多一个 archive_time、主键是 key_id（可重入的前提）")
    void archiveTableFromScriptHasExpectedShape() {
        Set<String> coldColumns = jdbc.query("SELECT * FROM `ifmap_execution_log_archive` WHERE 1 = 0", rs -> {
            Set<String> names = new LinkedHashSet<String>();
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                names.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
            }
            return names;
        });
        assertTrue(coldColumns.containsAll(JdbcExecutionLogArchiver.ARCHIVED_COLUMNS),
                "冷表脚本缺少归档列：" + coldColumns);
        assertTrue(coldColumns.contains("archive_time"), "冷表必须有 archive_time 以区分业务时间与归档时间");
        assertEquals(JdbcExecutionLogArchiver.ARCHIVED_COLUMNS.size() + 1, coldColumns.size(),
                "冷表除 archive_time 外不应有额外列");

        Set<String> primaryKeys = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Set<String>>) con -> {
            Set<String> keys = new LinkedHashSet<String>();
            java.sql.ResultSet rs = con.getMetaData().getPrimaryKeys(null, null, "ifmap_execution_log_archive");
            while (rs.next()) {
                keys.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            rs.close();
            return keys;
        });
        assertEquals(new LinkedHashSet<String>(Arrays.asList("key_id")), primaryKeys,
                "冷表主键必须是 key_id 单列：归档器靠它做幂等（已归档的行不重复插入）");
    }

    @Test
    @DisplayName("阈值换算：cutoffBefore(1) 恰好是 24 小时前")
    void cutoffBeforeIsDaysBack() {
        Timestamp cutoff = JdbcExecutionLogArchiver.cutoffBefore(1);
        long diff = System.currentTimeMillis() - cutoff.getTime();
        assertTrue(diff >= DAY - 5000L && diff <= DAY + 5000L, "偏差过大：" + diff);
    }
}
