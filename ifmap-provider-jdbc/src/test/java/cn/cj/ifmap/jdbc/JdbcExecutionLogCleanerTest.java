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
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcExecutionLogCleaner} 测试：真实生产 DDL 建表（H2 MODE=MySQL）+ 真实删除语句。
 *
 * <p>断言的是"哪些行被删、哪些必须留着、每批删多少"，不是"SQL 长什么样"。</p>
 *
 * @author caijun
 */
class JdbcExecutionLogCleanerTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcExecutionLogCleaner cleaner;
    private long seq;

    @BeforeEach
    void setUp() {
        dataSource = TestSchema.freshDataSource();
        jdbc = new JdbcTemplate(dataSource);
        cleaner = new JdbcExecutionLogCleaner(jdbc, TableNameResolver.defaults());
        seq = 0L;
    }

    /** 插一条指定 add_time 的执行日志，返回主键。 */
    private long insertLog(long addTimeMillis) {
        long keyId = 700000L + (++seq);
        jdbc.update("INSERT INTO `ifmap_execution_log`"
                        + " (`key_id`, `tenant_id`, `interface_no`, `biz_id`, `request_param`,"
                        + "  `execution_result`, `add_time`) VALUES (?, ?, ?, ?, ?, ?, ?)",
                keyId, -1L, "IF_CLEAN", "BIZ-" + keyId, "{\"amount\":1}", "SUCCESS",
                new Timestamp(addTimeMillis));
        return keyId;
    }

    private List<Long> allKeyIds() {
        return jdbc.queryForList("SELECT `key_id` FROM `ifmap_execution_log` ORDER BY `key_id`", Long.class);
    }

    private int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM `ifmap_execution_log`", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("只删过期行：早于保留期的删除，晚于保留期的原样保留")
    void deletesOnlyExpiredRows() {
        long now = System.currentTimeMillis();
        long expired1 = insertLog(now - 100L * DAY);
        long expired2 = insertLog(now - 91L * DAY);
        long kept1 = insertLog(now - 89L * DAY);
        long kept2 = insertLog(now - 1L * DAY);

        long deleted = cleaner.clean(90, 100, 10, 0L);

        assertEquals(2L, deleted, "应只删除 2 条过期日志");
        assertEquals(Arrays.asList(kept1, kept2), allKeyIds(), "未过期日志必须保留（含 add_time 顺序）");
        assertFalse(allKeyIds().contains(expired1));
        assertFalse(allKeyIds().contains(expired2));
    }

    @Test
    @DisplayName("保留期边界：刚过期的删、还没过期的留")
    void respectsRetentionBoundary() {
        long now = System.currentTimeMillis();
        long justExpired = insertLog(now - 90L * DAY - 60000L);
        long notYet = insertLog(now - 90L * DAY + 60000L);

        assertEquals(1L, cleaner.clean(90, 100, 10, 0L));
        assertEquals(Arrays.asList(notYet), allKeyIds());
        assertFalse(allKeyIds().contains(justExpired));
    }

    @Test
    @DisplayName("按批删除且可续跑：单批只删 batchSize 行，下一次调用继续删")
    void deletesInBatchesAndCanResume() {
        long old = System.currentTimeMillis() - 200L * DAY;
        for (int i = 0; i < 5; i++) {
            insertLog(old + i);
        }

        assertEquals(2L, cleaner.clean(90, 2, 1, 0L), "maxBatches=1：只允许删一批（2 行）");
        assertEquals(3, count());
        assertEquals(2L, cleaner.clean(90, 2, 1, 0L), "再次调用应续删下一批（剩 3 行 → 删 2 行）");
        assertEquals(1, count());
        assertEquals(1L, cleaner.clean(90, 2, 10, 0L), "最后一批不足 batchSize 时也要删净");
        assertEquals(0, count());
        assertEquals(0L, cleaner.clean(90, 2, 10, 0L), "没有过期数据时应返回 0");
    }

    @Test
    @DisplayName("保留期 &le; 0 直接拒绝（避免配成 0 退化为删全表）")
    void rejectsNonPositiveRetention() {
        insertLog(System.currentTimeMillis() - 400L * DAY);

        IfmapConfigException zero = assertThrows(IfmapConfigException.class, () -> cleaner.clean(0));
        assertTrue(zero.getMessage().contains("保留天数"), zero.getMessage());
        assertThrows(IfmapConfigException.class, () -> cleaner.clean(-1));
        assertThrows(IfmapConfigException.class, () -> JdbcExecutionLogCleaner.cutoffBefore(0));

        assertEquals(1, count(), "拒绝后不应删掉任何数据");
    }

    @Test
    @DisplayName("批大小 / 最大批数非法时抛 IfmapConfigException")
    void rejectsInvalidBatchOptions() {
        assertThrows(IfmapConfigException.class, () -> cleaner.clean(90, 0, 10, 0L));
        assertThrows(IfmapConfigException.class, () -> cleaner.clean(90, 100, 0, 0L));
    }

    @Test
    @DisplayName("表名前缀生效：复用存量 bankint_ 表也能清理")
    void honoursTablePrefix() {
        DataSource prefixed = TestSchema.freshDataSource("bankint_");
        JdbcTemplate prefixedJdbc = new JdbcTemplate(prefixed);
        prefixedJdbc.update("INSERT INTO `bankint_execution_log`"
                        + " (`key_id`, `tenant_id`, `interface_no`, `biz_id`, `execution_result`, `add_time`)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                1L, -1L, "IF_OLD", "BIZ-OLD", "SUCCESS",
                new Timestamp(System.currentTimeMillis() - 365L * DAY));
        prefixedJdbc.update("INSERT INTO `bankint_execution_log`"
                        + " (`key_id`, `tenant_id`, `interface_no`, `biz_id`, `execution_result`, `add_time`)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                2L, -1L, "IF_NEW", "BIZ-NEW", "SUCCESS", new Timestamp(System.currentTimeMillis()));

        JdbcExecutionLogCleaner prefixedCleaner =
                new JdbcExecutionLogCleaner(prefixedJdbc, new TableNameResolver("bankint_"));

        assertEquals("bankint_execution_log", prefixedCleaner.tables().executionLogTable());
        assertEquals(1L, prefixedCleaner.clean(90, 100, 10, 0L));
        assertEquals(1, prefixedJdbc.queryForList("SELECT `key_id` FROM `bankint_execution_log`"
                + " ORDER BY `key_id`", Long.class).size(), "只应剩未过期的那条");
    }

    @Test
    @DisplayName("空表 / 无过期数据时删除 0 行且不报错")
    void noExpiredRowsIsNoop() {
        assertEquals(0L, cleaner.clean(90, 100, 10, 0L), "空表应为 0");
        insertLog(System.currentTimeMillis());
        assertEquals(0L, cleaner.clean(90, 100, 10, 0L));
        assertEquals(1, count());
    }

    @Test
    @DisplayName("线程被中断时停止本轮清理（剩余数据留待下次）")
    void stopsWhenInterrupted() {
        long old = System.currentTimeMillis() - 300L * DAY;
        for (int i = 0; i < 4; i++) {
            insertLog(old + i);
        }
        Thread.currentThread().interrupt();
        try {
            long deleted = cleaner.clean(90, 2, 10, 10L);
            assertEquals(2L, deleted, "中断应发生在第一批之后，本轮只删一批");
            assertTrue(Thread.currentThread().isInterrupted(), "中断标记必须保留给上层");
        } finally {
            // 清掉中断标记，避免影响后续用例
            Thread.interrupted();
        }
        assertEquals(2, count());
    }
}
