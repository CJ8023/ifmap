package cn.cj.ifmap.spring;

import cn.cj.ifmap.core.template.NullPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
 *   tenant-header: X-Tenant-Id    # 租户解析请求头
 *   default-tenant-id: '-1'       # 取不到租户时的默认值
 *   orchestrator:
 *     stop-on-failure: true       # 前置接口链中失败即停止
 *     fail-on-missing-strategy: true   # strategy_name 未注册时抛异常（不再静默跳过）
 *   log:
 *     truncate-threshold: 65536   # 超过则截断并追加 ...truncated
 *     mask-fields: [acctName]     # 按字段名脱敏（留空用内置默认）
 *     exclude-fields: []          # 这些字段整体替换为 *** 不落库
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

    /** 租户解析请求头名。 */
    private String tenantHeader = "X-Tenant-Id";

    /** 取不到租户时的默认租户 ID。 */
    private String defaultTenantId = "-1";

    /** DDL 开关。 */
    private final Ddl ddl = new Ddl();

    /** 执行编排开关。 */
    private final Orchestrator orchestrator = new Orchestrator();

    /** 日志（脱敏/截断）配置。 */
    private final Log log = new Log();

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

    public String getTenantHeader() {
        return tenantHeader;
    }

    public void setTenantHeader(String tenantHeader) {
        this.tenantHeader = tenantHeader;
    }

    public String getDefaultTenantId() {
        return defaultTenantId;
    }

    public void setDefaultTenantId(String defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    public Orchestrator getOrchestrator() {
        return orchestrator;
    }

    public Log getLog() {
        return log;
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

    /** {@code ifmap.orchestrator.*}。 */
    public static class Orchestrator {

        /** 前置接口链中某接口失败后是否立即停止（false 则继续跑完并合并成功字段）。 */
        private boolean stopOnFailure = true;

        /** {@code strategy_name} 对应的策略 bean 未注册时是否抛异常（true 表示不再静默跳过，P0-7）。 */
        private boolean failOnMissingStrategy = true;

        /** 逻辑分支动作未注册时是否抛异常。 */
        private boolean failOnMissingAction = true;

        public boolean isStopOnFailure() {
            return stopOnFailure;
        }

        public void setStopOnFailure(boolean stopOnFailure) {
            this.stopOnFailure = stopOnFailure;
        }

        public boolean isFailOnMissingStrategy() {
            return failOnMissingStrategy;
        }

        public void setFailOnMissingStrategy(boolean failOnMissingStrategy) {
            this.failOnMissingStrategy = failOnMissingStrategy;
        }

        public boolean isFailOnMissingAction() {
            return failOnMissingAction;
        }

        public void setFailOnMissingAction(boolean failOnMissingAction) {
            this.failOnMissingAction = failOnMissingAction;
        }
    }

    /** {@code ifmap.log.*}：执行日志的脱敏与截断。 */
    public static class Log {

        /** 是否写执行日志（{@code ifmap_execution_log}）。 */
        private boolean enabled = true;

        /** 单字段最大长度，超过则截断并追加 {@code ...truncated}；&le; 0 表示不截断。 */
        private long truncateThreshold = 65536L;

        /** 按<b>字段名</b>脱敏的字段（姓名类）；留空用内置默认（acctName/realName/...）。 */
        private List<String> maskFields = new ArrayList<String>();

        /** 这些字段<b>整体</b>替换为 {@code ***}，连长度都不落库。 */
        private List<String> excludeFields = new ArrayList<String>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTruncateThreshold() {
            return truncateThreshold;
        }

        public void setTruncateThreshold(long truncateThreshold) {
            this.truncateThreshold = truncateThreshold;
        }

        public List<String> getMaskFields() {
            return maskFields;
        }

        public void setMaskFields(List<String> maskFields) {
            this.maskFields = maskFields;
        }

        public List<String> getExcludeFields() {
            return excludeFields;
        }

        public void setExcludeFields(List<String> excludeFields) {
            this.excludeFields = excludeFields;
        }
    }
}
