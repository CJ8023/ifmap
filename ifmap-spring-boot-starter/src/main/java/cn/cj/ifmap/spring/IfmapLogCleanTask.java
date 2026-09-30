package cn.cj.ifmap.spring;

import cn.cj.ifmap.jdbc.JdbcExecutionLogCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行日志保留期清理的<b>定时任务</b>（设计 §6.4 方案 A）。
 *
 * <p>默认每天 03:30 跑一次（{@code ifmap.log.clean-cron}，由 {@link IfmapLogCleanScheduler} 用守护线程驱动，
 * <b>不依赖宿主的 {@code @EnableScheduling}</b>），按 {@code ifmap.log.retention-days} 保留、
 * 分批删除 —— 一条 DELETE 就是一个事务，批太大既会放大 binlog 事件导致主从延迟，也会长时间持锁。</p>
 *
 * <p><b>失败不影响业务</b>：清理异常只 WARN（下个周期自动重试），绝不向上抛 —— 否则调度线程会被
 * 异常刷屏，甚至被某些调度器标记为"任务失败"停止后续执行。</p>
 *
 * <p>要手工触发（运维窗口内清一次），直接注入本 bean 调 {@link #cleanNow()}；若要把清理交给宿主自己的
 * 调度体系（xxl-job / ShedLock / 分布式选举），也调它。</p>
 *
 * @author caijun
 */
public class IfmapLogCleanTask {

    private static final Logger LOG = LoggerFactory.getLogger(IfmapLogCleanTask.class);

    private final JdbcExecutionLogCleaner cleaner;
    private final int retentionDays;
    private final int batchSize;
    private final int maxBatches;
    private final long batchSleepMillis;

    public IfmapLogCleanTask(JdbcExecutionLogCleaner cleaner, int retentionDays, int batchSize,
                             int maxBatches, long batchSleepMillis) {
        if (cleaner == null) {
            throw new IllegalArgumentException("JdbcExecutionLogCleaner 不能为空");
        }
        this.cleaner = cleaner;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.batchSleepMillis = batchSleepMillis;
    }

    /**
     * 立即清理一次。
     *
     * @return 实际删除行数；清理失败返回 {@code -1}（已 WARN，调用方可据此提示）
     */
    public long cleanNow() {
        try {
            return cleaner.clean(retentionDays, batchSize, maxBatches, batchSleepMillis);
        } catch (RuntimeException e) {
            LOG.warn("ifmap 执行日志清理失败（不影响业务，下个周期重试）：{}", e.getMessage(), e);
            return -1L;
        }
    }

    /** 当前保留天数（便于运维日志/自检打印）。 */
    public int getRetentionDays() {
        return retentionDays;
    }
}
