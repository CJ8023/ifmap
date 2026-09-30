package cn.cj.ifmap.spring;

import cn.cj.ifmap.core.template.NullPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code ifmap.*} 配置项。
 *
 * <pre>{@code
 * ifmap:
 *   table-prefix: ifmap_          # 复用存量表时填 bankint_
 *   ddl:
 *     auto: true                  # 启动时自动建表（表已存在则跳过）
 *   id-strategy: snowflake        # snowflake | auto-increment
 *   worker-id: 12                 # 多实例部署必须区分
 *   null-policy: skip-field       # skip-field | empty-string
 *   cache:
 *     enabled: true
 *     maximum-size: 1000
 *     ttl: 60s
 * }</pre>
 *
 * @author caijun
 */
@ConfigurationProperties(prefix = "ifmap")
public class IfmapProperties {

    /** 主键生成策略。 */
    public enum IdStrategy {
        /** 雪花算法（默认）。 */
        SNOWFLAKE,
        /** 数据库自增：此时建表脚本的 key_id 需改为 AUTO_INCREMENT。 */
        AUTO_INCREMENT
    }

    /** 是否启用 ifmap 自动配置。 */
    private boolean enabled = true;

    /** 表名前缀（会直接拼进 SQL，只允许字母、数字、下划线，且以字母或下划线开头，长度 &le; 32）。 */
    private String tablePrefix = "ifmap_";

    /** 主键生成策略。 */
    private IdStrategy idStrategy = IdStrategy.SNOWFLAKE;

    /** 雪花算法 workerId（0 ~ 1023）；为空时取系统属性 {@code ifmap.worker-id} / 环境变量 {@code IFMAP_WORKER_ID}，仍取不到为 0。 */
    private Long workerId;

    /** 模板渲染遇到 null 时的策略。 */
    private NullPolicy nullPolicy = NullPolicy.SKIP_FIELD;

    /** DDL 开关。 */
    private final Ddl ddl = new Ddl();

    /** 缓存配置。 */
    private final Cache cache = new Cache();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void setTablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }

    public IdStrategy getIdStrategy() {
        return idStrategy;
    }

    public void setIdStrategy(IdStrategy idStrategy) {
        this.idStrategy = idStrategy;
    }

    public Long getWorkerId() {
        return workerId;
    }

    public void setWorkerId(Long workerId) {
        this.workerId = workerId;
    }

    public NullPolicy getNullPolicy() {
        return nullPolicy;
    }

    public void setNullPolicy(NullPolicy nullPolicy) {
        this.nullPolicy = nullPolicy;
    }

    public Ddl getDdl() {
        return ddl;
    }

    public Cache getCache() {
        return cache;
    }

    /** {@code ifmap.ddl.*}。 */
    public static class Ddl {

        /** 是否在启动时建表（表已存在则跳过）；生产环境可由 DBA 执行 SQL 后置 false。 */
        private boolean auto = true;

        public boolean isAuto() {
            return auto;
        }

        public void setAuto(boolean auto) {
            this.auto = auto;
        }
    }

    /** {@code ifmap.cache.*}。 */
    public static class Cache {

        /** 是否缓存逻辑分支列表。 */
        private boolean enabled = true;

        /** 最大条目数。 */
        private int maximumSize = 1000;

        /** 存活时长；0 表示永不过期。 */
        private Duration ttl = Duration.ofSeconds(60);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaximumSize() {
            return maximumSize;
        }

        public void setMaximumSize(int maximumSize) {
            this.maximumSize = maximumSize;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
    }
}
