package cn.cj.ifmap.jdbc;

import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.spi.IdGenerator;
import cn.cj.ifmap.core.spi.SnowflakeIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * {@link ConfigRepository} 的 JDBC 实现（MySQL，兼容 5.7 / 8.0）。
 *
 * <p>按 {@link ConfigRepository} 的约定：查询结果由 SQL 的 {@code ORDER BY} 保证有序
 * （{@code interface_order, key_id} / {@code logic_branch_order, key_id}），只返回
 * {@code del_status = 0 AND status = 1} 的行。</p>
 *
 * <p>表名前缀由 {@link TableNameResolver} 校验后拼入 SQL；所有取值参数走 {@code PreparedStatement}。</p>
 *
 * @author caijun
 */
public class JdbcConfigRepository implements ConfigRepository {

    private static final int TENANT_DEFAULT = -1;

    private final JdbcTemplate jdbc;
    private final TableNameResolver tables;
    private final IdGenerator idGenerator;

    /** 使用默认表名前缀（{@code ifmap_}）与 JVM 共享雪花 ID。 */
    public JdbcConfigRepository(DataSource dataSource) {
        this(dataSource, TableNameResolver.DEFAULT_PREFIX);
    }

    /** 指定表名前缀（复用存量表时传 {@code "bankint_"}）。 */
    public JdbcConfigRepository(DataSource dataSource, String tablePrefix) {
        this(new JdbcTemplate(dataSource), new TableNameResolver(tablePrefix), SnowflakeIdGenerator.shared());
    }

    /** 完整构造（自带 JdbcTemplate 的 Spring 项目可直接注入）。 */
    public JdbcConfigRepository(JdbcTemplate jdbcTemplate, TableNameResolver tables, IdGenerator idGenerator) {
        if (jdbcTemplate == null) {
            throw new IfmapConfigException("JdbcTemplate 不能为空");
        }
        this.jdbc = jdbcTemplate;
        this.tables = tables == null ? TableNameResolver.defaults() : tables;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.shared() : idGenerator;
    }

    @Override
    public List<IfmapConfig> queryConfigs(String tenantId, String interfaceNo, String busiNode) {
        String sql = "SELECT " + IfmapRowMappers.CONFIG_COLUMNS + " FROM `" + tables.configTable() + "`"
                + " WHERE `tenant_id` = ? AND `interface_no` = ? AND `busi_node` = ?"
                + " AND `del_status` = 0 AND `status` = 1"
                + " ORDER BY `interface_order` ASC, `key_id` ASC";
        return jdbc.query(sql, IfmapRowMappers.config(), tenant(tenantId), interfaceNo, busiNode);
    }

    @Override
    public Optional<IfmapConfig> findConfig(String tenantId, String interfaceNo, String busiNode, int order) {
        String sql = "SELECT " + IfmapRowMappers.CONFIG_COLUMNS + " FROM `" + tables.configTable() + "`"
                + " WHERE `tenant_id` = ? AND `interface_no` = ? AND `busi_node` = ? AND `interface_order` = ?"
                + " AND `del_status` = 0 AND `status` = 1"
                + " ORDER BY `key_id` ASC LIMIT 1";
        List<IfmapConfig> list = jdbc.query(sql, IfmapRowMappers.config(), tenant(tenantId), interfaceNo, busiNode, order);
        return list.isEmpty() ? Optional.<IfmapConfig>empty() : Optional.of(list.get(0));
    }

    @Override
    public List<LogicBranchConfig> queryLogicBranches(String tenantId, String interfaceNo) {
        String sql = "SELECT " + IfmapRowMappers.LOGIC_BRANCH_COLUMNS + " FROM `" + tables.logicBranchTable() + "`"
                + " WHERE `tenant_id` = ? AND `interface_no` = ? AND `del_status` = 0"
                + " ORDER BY `logic_branch_order` ASC, `key_id` ASC";
        return jdbc.query(sql, IfmapRowMappers.logicBranch(), tenant(tenantId), interfaceNo);
    }

    @Override
    public void saveExecutionLog(ExecutionLog log) {
        if (log == null) {
            throw new IfmapConfigException("执行日志不能为空");
        }
        Long id = log.getKeyId();
        if (id == null) {
            id = idGenerator.nextId();
        }
        if (id == null) {
            throw new IfmapConfigException("执行日志缺少主键：请提供 keyId，或为 JdbcConfigRepository 注入会生成 ID 的 IdGenerator");
        }
        log.setKeyId(id);
        String sql = "INSERT INTO `" + tables.executionLogTable() + "`"
                + " (`key_id`,`tenant_id`,`interface_no`,`biz_id`,`request_param`,`response_param`,"
                + "`execution_time`,`execution_result`,`error_msg`,`remark`,`del_status`,"
                + "`add_user_id`,`add_request_id`) VALUES (?,?,?,?,?,?,?,?,?,?,0,?,?)";
        jdbc.update(sql, id, tenant(log.getTenantId()),
                JdbcValues.require(log.getInterfaceNo(), "interfaceNo"),
                JdbcValues.require(log.getBizId(), "bizId"),
                log.getRequestParam(), log.getResponseParam(),
                JdbcValues.orDefault(log.getExecutionTime(), 0L),
                JdbcValues.require(log.getExecutionResult(), "executionResult"),
                log.getErrorMsg(), JdbcValues.orEmpty(log.getRemark()),
                JdbcValues.orEmpty(log.getAddUserId()), JdbcValues.orEmpty(log.getAddRequestId()));
    }

    /** 当前生效的表名解析器。 */
    public TableNameResolver tables() {
        return tables;
    }

    /** 按业务单查执行日志（最近 N 条，按时间倒序）——运维排障用，非引擎链路。 */
    public List<ExecutionLog> recentLogs(String tenantId, String bizId, int limit) {
        String sql = "SELECT `key_id`,`tenant_id`,`interface_no`,`biz_id`,`request_param`,`response_param`,"
                + "`execution_time`,`execution_result`,`error_msg`,`remark`,`add_user_id`,`add_request_id`"
                + " FROM `" + tables.executionLogTable() + "`"
                + " WHERE `tenant_id` = ? AND `biz_id` = ? AND `del_status` = 0"
                + " ORDER BY `add_time` DESC LIMIT ?";
        return jdbc.query(sql, IfmapRowMappers.executionLog(), tenant(tenantId), bizId, limit);
    }

    /** {@code null} / 空串租户 → 单租户默认值。 */
    private static long tenant(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return TENANT_DEFAULT;
        }
        try {
            return Long.parseLong(tenantId.trim());
        } catch (NumberFormatException e) {
            throw new IfmapConfigException("租户 ID 不是数字：" + tenantId, e);
        }
    }

    private static long tenant(Long tenantId) {
        return tenantId == null ? TENANT_DEFAULT : tenantId;
    }

}
