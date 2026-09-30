package cn.cj.ifmap.core.spi;

import cn.cj.ifmap.core.exception.IfmapConfigException;

/**
 * 雪花 ID 生成器（默认实现，无第三方依赖，线程安全）。
 *
 * <p>结构与标准 Twitter Snowflake 一致：<br>
 * {@code 0 | 41 位毫秒时间戳 | 10 位 workerId | 12 位序列号}<br>
 * 时间戳纪元取 2020-01-01T00:00:00Z，可用约 69 年。</p>
 *
 * <p><b>部署要点</b>：同一时刻内多实例的 {@code workerId} 必须互不相同，否则可能产生重复 ID。
 * 容器化部署时建议：环境变量 {@code IFMAP_WORKER_ID} &gt; JVM 参数 &gt; 代码默认值；也可用
 * {@code workerIdFromHostname(...)} 按主机名/实例序号取模。</p>
 *
 * <p><b>时钟回拨</b>：回拨小于 {@link #MAX_BACKWARD_MS} 时等待追上；超过则抛
 * {@link IfmapConfigException}（宁可失败也不产生重复 ID）。</p>
 *
 * @author caijun
 */
public class SnowflakeIdGenerator implements IdGenerator {

    /** 起始纪元：2020-01-01T00:00:00Z。 */
    private static final long EPOCH = 1577836800000L;
    private static final int WORKER_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    /** 允许的时钟回拨上限（毫秒）。 */
    public static final long MAX_BACKWARD_MS = 5L;
    /** 自旋等待时钟追上时的单次休眠（毫秒）。 */
    private static final long SPIN_SLEEP_MS = 1L;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IfmapConfigException("workerId 必须在 0 ~ " + MAX_WORKER_ID + " 之间，当前：" + workerId);
        }
        this.workerId = workerId;
    }

    /** JVM 级共享实例（**默认路径必须用它**：每次 {@code new} 一个实例会让各自的序列号独立，产生重复 ID）。 */
    private static final class SharedHolder {
        private static final SnowflakeIdGenerator INSTANCE = fromEnvironment();
    }

    /** JVM 级共享实例，workerId 取系统属性 {@code ifmap.worker-id} / 环境变量 {@code IFMAP_WORKER_ID}，取不到为 0。 */
    public static SnowflakeIdGenerator shared() {
        return SharedHolder.INSTANCE;
    }

    /** 从环境变量 {@code IFMAP_WORKER_ID} 取 workerId，取不到用 0。 */
    public static SnowflakeIdGenerator fromEnvironment() {
        return new SnowflakeIdGenerator(workerIdFromSystemPropertyOrEnv());
    }

    /** 按宿主机名取 workerId（同一批实例主机名互不相同即可）。 */
    public static SnowflakeIdGenerator workerIdFromHostname(String hostName) {
        String name = hostName == null ? "" : hostName;
        return new SnowflakeIdGenerator(Math.floorMod((long) name.hashCode(), MAX_WORKER_ID + 1));
    }

    private static long workerIdFromSystemPropertyOrEnv() {
        String value = System.getProperty("ifmap.worker-id");
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv("IFMAP_WORKER_ID");
        }
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IfmapConfigException("workerId 配置不是数字：" + value, e);
        }
    }

    @Override
    public synchronized Long nextId() {
        long timestamp = currentTime();
        long back = lastTimestamp - timestamp;
        if (back > MAX_BACKWARD_MS) {
            throw new IfmapConfigException("检测到时钟回拨 " + back + "ms（上限 " + MAX_BACKWARD_MS + "ms），拒绝生成 ID");
        }
        while (timestamp < lastTimestamp) {
            timestamp = currentTime();
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT) | (workerId << WORKER_ID_SHIFT) | sequence;
    }

    private long waitNextMillis(long lastTs) {
        long ts = currentTime();
        while (ts <= lastTs) {
            ts = currentTime();
            if (ts <= lastTs) {
                try {
                    Thread.sleep(SPIN_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IfmapConfigException("等待下一毫秒时被中断", e);
                }
            }
        }
        return ts;
    }

    /** 当前毫秒时间戳（单独抽出便于测试覆盖时钟回拨分支）。 */
    protected long currentTime() {
        return System.currentTimeMillis();
    }
}
