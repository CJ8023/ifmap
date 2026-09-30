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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 执行日志<b>冷热分离</b>（设计 §6.4 方案 C）：把热表里过期的日志搬到归档冷表，再从热表删除。
 *
 * <p><b>它解决什么问题</b>：保留期清理（{@link JdbcExecutionLogCleaner}）是"删掉"，而合规审计常要求
 * "日志留 5 年、但业务只查最近 3 个月"。归档器把老数据搬到 {@code <前缀>execution_log_archive}
 * （建表见 {@code db/optional/execution-log-partition/04-create-archive-table.sql}），
 * 热表因此能一直保持"小到可在线 DDL"，冷表则按月分区 + 压缩存储、平时没人查。</p>
 *
 * <p><b>三步走而不是一步 {@code INSERT ... SELECT ... ON DUPLICATE}</b>：
 * ① 查一批过期的 {@code key_id}（按 {@code add_time} 排序，配合分区裁剪）；
 * ② 查归档表里已存在的 → 从这批里剔除；
 * ③ {@code INSERT INTO 归档表 SELECT ... FROM 热表 WHERE key_id IN (...)}，再 {@code DELETE}。
 * 这样两个库（MySQL / H2）都只用到最基础的 SQL，单测因此可以在 H2 上跑<b>真实生产 DDL</b>；
 * 而"归档表出现在 INSERT 的子查询里"这种写法在 MySQL 上会踩 1093，不能为了少一次往返去赌。</p>
 *
 * <p><b>为什么是可重入的</b>：INSERT 与 DELETE 是两条独立语句（各自自动提交）。若在两者之间进程被杀，
 * 数据会同时留在热表和冷表 —— 下次重跑时第 ② 步会把它们从插入列表里剔除、第 ③ 步照常删除，
 * 结果仍然正确。反过来说：本类<b>不会</b>为了"少一点重复"把归档做成跨表事务，
 * 因为跨表事务会把大批量搬迁变成大事务（binlog 暴涨、主从延迟、长锁），代价远高于"重跑一次"。</p>
 *
 * <p><b>并发约束</b>：同一时刻只应有一个归档任务在跑（多实例部署请用分布式锁/选举，或只在一个实例上开）。
 * 并发跑会在归档表主键上冲突报错 —— 这是<b>有意的</b>失败，而不是静默写重。</p>
 *
 * <p>安全阀与清理器一致：保留天数必须 &ge; 1、单次最多 {@code maxBatches} 批、批间可停顿。</p>
 *
 * @author caijun
 */
public class JdbcExecutionLogArchiver {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcExecutionLogArchiver.class);

    /**
     * 归档时复制的列（= 执行日志表的<b>全部</b>列，顺序与 DDL 一致）。
     *
     * <p>不写 {@code INSERT INTO 归档表 SELECT * FROM 热表}：列顺序一漂移就静默错位。
     * 启动时会核对本清单与热表的实际列<b>完全相同</b> —— 将来给执行日志表加了列却忘了同步归档器，
     * 会在第一次归档时直接失败（而不是悄悄少归档一列）。</p>
     */
    public static final List<String> ARCHIVED_COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "key_id", "tenant_id", "interface_no", "biz_id", "request_param", "response_param",
            "execution_time", "execution_result", "error_msg", "remark", "del_status",
            "add_user_id", "add_time", "add_request_id", "modify_user_id", "modify_time",
            "modify_request_id"));

    /** 每批搬迁行数（一批 = 一对 INSERT/DELETE，批太大 → 大事务 + 主从延迟）。 */
    public static final int DEFAULT_BATCH_SIZE = 1000;

    /** 单次任务最多搬多少批（护栏：避免一次跑太久把库压住）。 */
    public static final int DEFAULT_MAX_BATCHES = 1000;

    /** 批间停顿毫秒数（给从库追 binlog 留出时间）。 */
    public static final long DEFAULT_BATCH_SLEEP_MILLIS = 50L;

    /** 归档表建表脚本的位置（仅用于报错提示，不参与运行）。 */
    public static final String ARCHIVE_DDL_HINT = "db/optional/execution-log-partition/04-create-archive-table.sql";

    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    private final JdbcTemplate jdbc;
    private final TableNameResolver tables;

    /** 使用默认表名前缀（{@code ifmap_}）。 */
    public JdbcExecutionLogArchiver(DataSource dataSource) {
        this(dataSource, TableNameResolver.DEFAULT_PREFIX);
    }

    /** 指定表名前缀（复用存量表时传 {@code "bankint_"}）。 */
    public JdbcExecutionLogArchiver(DataSource dataSource, String tablePrefix) {
        this(new JdbcTemplate(dataSource), new TableNameResolver(tablePrefix));
    }

    /** 完整构造（自带 JdbcTemplate 的 Spring 项目可直接注入）。 */
    public JdbcExecutionLogArchiver(JdbcTemplate jdbcTemplate, TableNameResolver tables) {
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

    /** 截止时间（早于它的日志会被归档）。 */
    public static Timestamp cutoffBefore(int olderThanDays) {
        requireOlderThan(olderThanDays);
        return new Timestamp(System.currentTimeMillis() - olderThanDays * MILLIS_PER_DAY);
    }

    /** 按默认批大小 / 批数 / 批间停顿归档。 */
    public LogArchiveResult archive(int olderThanDays) {
        return archive(olderThanDays, DEFAULT_BATCH_SIZE, DEFAULT_MAX_BATCHES, DEFAULT_BATCH_SLEEP_MILLIS);
    }

    /**
     * 把早于 {@code now - olderThanDays} 的执行日志搬到归档表，并从热表删除。
     *
     * @param olderThanDays    多少天以前的数据算冷数据，必须 &ge; 1
     * @param batchSize        每批搬迁行数，必须 &ge; 1
     * @param maxBatches       单次最多批数，必须 &ge; 1
     * @param batchSleepMillis 批间停顿毫秒数（&le; 0 表示不停顿）
     * @return 本次归档结果（写入冷表行数 / 从热表删除行数 / 批数）
     */
    public LogArchiveResult archive(int olderThanDays, int batchSize, int maxBatches, long batchSleepMillis) {
        Timestamp cutoff = cutoffBefore(olderThanDays);
        if (batchSize <= 0) {
            throw new IfmapConfigException("批大小必须大于 0（当前 " + batchSize + "）");
        }
        if (maxBatches <= 0) {
            throw new IfmapConfigException("单次最大批数必须大于 0（当前 " + maxBatches + "）");
        }
        String hot = tables.executionLogTable();
        String cold = tables.executionLogArchiveTable();
        verifySchema(hot, cold);

        long archived = 0L;
        long removed = 0L;
        int batches = 0;
        while (batches < maxBatches) {
            List<Long> keyIds = selectExpired(hot, cutoff, batchSize);
            if (keyIds.isEmpty()) {
                break;
            }
            List<Long> toInsert = excludeAlreadyArchived(cold, keyIds);
            long alreadyArchived = keyIds.size() - toInsert.size();
            long inserted = toInsert.isEmpty() ? 0L : insertBatch(hot, cold, toInsert);
            int deleted = deleteByKeyIds(hot, keyIds);
            archived += inserted;
            removed += deleted;
            batches++;
            if (inserted + alreadyArchived != keyIds.size()) {
                LOG.warn("ifmap 执行日志归档：本批 {} 条中，归档表已存在 {} 条、新写入 {} 条，对不上（可能有并发归档"
                        + "或热表被并发清理）；请确认同一库上只有一个归档任务在跑", keyIds.size(),
                        alreadyArchived, inserted);
            }
            LOG.info("ifmap 执行日志归档：第 {} 批热表删除 {} 行、冷表新增 {} 行（冷于 {}，热表 {} → 冷表 {}）",
                    batches, deleted, inserted, cutoff, hot, cold);
            if (keyIds.size() < batchSize) {
                break;
            }
            if (!pause(batchSleepMillis)) {
                LOG.warn("ifmap 执行日志归档被中断，本次已归档 {} 行，剩余数据留待下次执行", removed);
                break;
            }
        }
        if (batches >= maxBatches) {
            LOG.warn("ifmap 执行日志归档已达单次最大批数 {}（本次归档 {} 行），剩余数据请在下次执行时继续；"
                    + "若每次都能搬满，请调大批数或缩短调度周期", maxBatches, removed);
        } else if (removed > 0L) {
            LOG.info("ifmap 执行日志归档完成：热表删除 {} 行、冷表新增 {} 行（{} 批，冷于 {}）",
                    removed, archived, batches, cutoff);
        }
        return new LogArchiveResult(archived, removed, batches);
    }

    private List<Long> selectExpired(String hot, Timestamp cutoff, int batchSize) {
        String sql = "SELECT `key_id` FROM `" + hot + "` WHERE `add_time` < ?"
                + " ORDER BY `add_time` LIMIT " + batchSize;
        return jdbc.queryForList(sql, Long.class, cutoff);
    }

    /** 剔除归档表里已存在的 {@code key_id}（重跑/中断续跑时用）。 */
    private List<Long> excludeAlreadyArchived(String cold, List<Long> keyIds) {
        String sql = "SELECT `key_id` FROM `" + cold + "` WHERE `key_id` IN (" + placeholders(keyIds.size()) + ")";
        Set<Long> existing = new LinkedHashSet<Long>(jdbc.queryForList(sql, Long.class, keyIds.toArray()));
        List<Long> result = new ArrayList<Long>(keyIds.size());
        for (Long keyId : keyIds) {
            if (!existing.contains(keyId)) {
                result.add(keyId);
            }
        }
        return result;
    }

    private long insertBatch(String hot, String cold, List<Long> keyIds) {
        StringBuilder sql = new StringBuilder("INSERT INTO `").append(cold).append("` (")
                .append(quotedColumns()).append(") SELECT ").append(quotedColumns())
                .append(" FROM `").append(hot).append("` WHERE `key_id` IN (")
                .append(placeholders(keyIds.size())).append(")");
        Integer inserted = jdbc.update(sql.toString(), keyIds.toArray());
        return inserted == null ? 0L : inserted.longValue();
    }

    private int deleteByKeyIds(String hot, List<Long> keyIds) {
        String sql = "DELETE FROM `" + hot + "` WHERE `key_id` IN (" + placeholders(keyIds.size()) + ")";
        return jdbc.update(sql, keyIds.toArray());
    }

    /**
     * 校验归档表存在、且两边列与内置清单一致。
     *
     * <p>把"表不存在 / 列对不上"从一条难懂的 SQL 报错变成一句可执行的提示 —— 归档是个后台任务，
     * 报错信息就是唯一的排障入口。</p>
     */
    private void verifySchema(String hot, String cold) {
        Set<String> coldColumns = columns(cold, "执行日志归档表 " + cold + " 不存在或不可读：请先在目标库执行 "
                + ARCHIVE_DDL_HINT + " 建表（不使用冷热分离可忽略本错误）");
        Set<String> hotColumns = columns(hot, "执行日志表 " + hot + " 不可读，请确认表名前缀配置是否正确");
        Set<String> expected = new LinkedHashSet<String>(ARCHIVED_COLUMNS);
        if (!hotColumns.equals(expected)) {
            throw new IfmapConfigException("执行日志表 " + hot + " 的列与归档器内置清单不一致（多余 "
                    + difference(hotColumns, expected) + "，缺少 " + difference(expected, hotColumns)
                    + "）：请同步执行日志表 DDL（db/changelog/v1.0.0/003-create-execution-log.sql）、"
                    + "JdbcExecutionLogArchiver.ARCHIVED_COLUMNS 与归档表 DDL（" + ARCHIVE_DDL_HINT + "）");
        }
        List<String> missing = new ArrayList<String>();
        for (String column : ARCHIVED_COLUMNS) {
            if (!coldColumns.contains(column)) {
                missing.add(column);
            }
        }
        if (!missing.isEmpty()) {
            throw new IfmapConfigException("执行日志归档表 " + cold + " 缺少列 " + missing
                    + "（可与热表列不一致，但不能少归档列）：请核对 " + ARCHIVE_DDL_HINT);
        }
    }

    /** 读表头元数据（{@code WHERE 1 = 0} 不扫数据）。表不存在时抛配置异常并带上 {@code hint}。 */
    private Set<String> columns(String table, String hint) {
        try {
            Set<String> names = jdbc.query("SELECT * FROM `" + table + "` WHERE 1 = 0",
                    rs -> {
                        Set<String> result = new LinkedHashSet<String>();
                        ResultSetMetaData meta = rs.getMetaData();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            result.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
                        }
                        return result;
                    });
            return names == null ? Collections.<String>emptySet() : names;
        } catch (DataAccessException e) {
            throw new IfmapConfigException(hint + "（" + e.getMostSpecificCause().getMessage() + "）", e);
        }
    }

    private static String difference(Set<String> left, Set<String> right) {
        List<String> diff = new ArrayList<String>();
        for (String column : left) {
            if (!right.contains(column)) {
                diff.add(column);
            }
        }
        return diff.toString();
    }

    private static String quotedColumns() {
        StringBuilder sb = new StringBuilder();
        for (String column : ARCHIVED_COLUMNS) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append('`').append(column).append('`');
        }
        return sb.toString();
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i == 0 ? "?" : ", ?");
        }
        return sb.toString();
    }

    /** @return {@code false} 表示线程被中断（调用方应停止本轮归档）。 */
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

    private static void requireOlderThan(int olderThanDays) {
        if (olderThanDays <= 0) {
            throw new IfmapConfigException("归档天数阈值必须大于 0（当前 " + olderThanDays
                    + "）：阈值 = 0 会把整张热表搬空，直接拒绝");
        }
    }
}
