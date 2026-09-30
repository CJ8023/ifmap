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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;

/**
 * 执行日志保留期清理（设计 §6.4 方案 A）：按 {@code add_time} 分批删除过期日志。
 *
 * <p><b>为什么是"保留期 + 分批删除"而不是分区</b>：分区要求分区列进主键（主键要从 {@code key_id}
 * 改成 {@code (key_id, add_time)}），开源产品不该强制用户先做这套运维改造；需要"按月秒级归档"
 * 的场景见 {@code db/mysql8/partition-execution-log.sql}（默认不启用）。</p>
 *
 * <p><b>为什么"先查 key_id 再按 id 删"而不是 {@code DELETE ... LIMIT}</b>：{@code DELETE ... LIMIT}
 * 是 MySQL 方言（H2 不接受），而 {@code DELETE ... WHERE key_id IN (SELECT ... LIMIT)}
 * 在 MySQL 上会报 1093（不能在子查询里读同一张被删的表）。两步走两个库都合法、语义也一样，
 * 单测因此可以用 H2 跑真实生产 DDL。代价是多一次往返，对"每天一次的后台任务"可忽略。</p>
 *
 * <p>安全阀：① 保留天数必须 &ge; 1（配 0 会退化成"删全表"，直接拒绝）；② 单次任务最多删除
 * {@code maxBatches} 批，避免一次清理把库压住 —— 没删完的留给下个周期继续。</p>
 *
 * @author caijun
 */
public class JdbcExecutionLogCleaner {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcExecutionLogCleaner.class);

    /** 每批删除行数（MySQL 上一条 DELETE 就是一个事务，批太大 → 大事务 + 主从延迟）。 */
    public static final int DEFAULT_BATCH_SIZE = 1000;

    /** 单次任务最多执行多少批（护栏：避免一次跑太久）。 */
    public static final int DEFAULT_MAX_BATCHES = 1000;

    /** 批间停顿毫秒数（给从库追 binlog 留出时间，防主从延迟）。 */
    public static final long DEFAULT_BATCH_SLEEP_MILLIS = 50L;

    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    private final JdbcTemplate jdbc;
    private final TableNameResolver tables;

    /** 使用默认表名前缀（{@code ifmap_}）。 */
    public JdbcExecutionLogCleaner(DataSource dataSource) {
        this(dataSource, TableNameResolver.DEFAULT_PREFIX);
    }

    /** 指定表名前缀（复用存量表时传 {@code "bankint_"}）。 */
    public JdbcExecutionLogCleaner(DataSource dataSource, String tablePrefix) {
        this(new JdbcTemplate(dataSource), new TableNameResolver(tablePrefix));
    }

    /** 完整构造（自带 JdbcTemplate 的 Spring 项目可直接注入）。 */
    public JdbcExecutionLogCleaner(JdbcTemplate jdbcTemplate, TableNameResolver tables) {
        if (jdbcTemplate == null) {
            throw new IfmapConfigException("JdbcTemplate 不能为空");
        }
        this.jdbc = jdbcTemplate;
        this.tables = tables == null ? TableNameResolver.defaults() : tables;
    }

    /** 当前使用的表名解析器。 */
    public TableNameResolver tables() {
        return tables;
    }

    /** 截止时间（早于它的日志会被删除）。 */
    public static Timestamp cutoffBefore(int retentionDays) {
        requireRetention(retentionDays);
        return new Timestamp(System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY);
    }

    /** 按默认批大小 / 批数 / 批间停顿清理。 */
    public long clean(int retentionDays) {
        return clean(retentionDays, DEFAULT_BATCH_SIZE, DEFAULT_MAX_BATCHES, DEFAULT_BATCH_SLEEP_MILLIS);
    }

    /**
     * 清理早于 {@code now - retentionDays} 的执行日志。
     *
     * @param retentionDays    保留天数，必须 &ge; 1
     * @param batchSize        每批删除行数，必须 &ge; 1
     * @param maxBatches       单次最多批数，必须 &ge; 1
     * @param batchSleepMillis 批间停顿毫秒数（&le; 0 表示不停顿）
     * @return 本次实际删除的总行数
     */
    public long clean(int retentionDays, int batchSize, int maxBatches, long batchSleepMillis) {
        Timestamp cutoff = cutoffBefore(retentionDays);
        if (batchSize <= 0) {
            throw new IfmapConfigException("批大小必须大于 0（当前 " + batchSize + "）");
        }
        if (maxBatches <= 0) {
            throw new IfmapConfigException("单次最大批数必须大于 0（当前 " + maxBatches + "）");
        }
        String table = tables.executionLogTable();
        long total = 0L;
        int batches = 0;
        while (batches < maxBatches) {
            List<Long> keyIds = selectExpired(table, cutoff, batchSize);
            if (keyIds.isEmpty()) {
                break;
            }
            int deleted = deleteByKeyIds(table, keyIds);
            total += deleted;
            batches++;
            LOG.info("ifmap 执行日志清理：第 {} 批删除 {} 行（保留 {} 天，早于 {}，表 {}）",
                    batches, deleted, retentionDays, cutoff, table);
            if (keyIds.size() < batchSize) {
                break;
            }
            if (!pause(batchSleepMillis)) {
                LOG.warn("ifmap 执行日志清理被中断，本次已删除 {} 行，剩余数据留待下次清理", total);
                break;
            }
        }
        if (batches >= maxBatches) {
            LOG.warn("ifmap 执行日志清理已达单次最大批数 {}（本次删除 {} 行），剩余数据请在下次执行时继续清理；"
                    + "若每次都能删满，请调大 ifmap.log.clean-max-batches 或缩短调度周期", maxBatches, total);
        } else if (total > 0L) {
            LOG.info("ifmap 执行日志清理完成：共删除 {} 行（{} 批）", total, batches);
        }
        return total;
    }

    private List<Long> selectExpired(String table, Timestamp cutoff, int batchSize) {
        String sql = "SELECT `key_id` FROM `" + table + "` WHERE `add_time` < ?"
                + " ORDER BY `add_time` LIMIT " + batchSize;
        return jdbc.queryForList(sql, Long.class, cutoff);
    }

    private int deleteByKeyIds(String table, List<Long> keyIds) {
        StringBuilder sql = new StringBuilder("DELETE FROM `").append(table).append("` WHERE `key_id` IN (");
        for (int i = 0; i < keyIds.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(")");
        return jdbc.update(sql.toString(), keyIds.toArray());
    }

    /** @return {@code false} 表示线程被中断（调用方应停止本轮清理）。 */
    private static boolean pause(long millis) {
        if (millis <= 0L) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void requireRetention(int retentionDays) {
        if (retentionDays <= 0) {
            throw new IfmapConfigException("保留天数必须大于 0（当前 " + retentionDays
                    + "）；如需停用清理请关闭 ifmap.log.clean-enabled");
        }
    }
}
