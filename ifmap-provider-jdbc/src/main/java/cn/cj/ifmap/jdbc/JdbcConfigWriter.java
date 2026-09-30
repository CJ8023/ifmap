package cn.cj.ifmap.jdbc;

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.spi.IdGenerator;
import cn.cj.ifmap.core.spi.SnowflakeIdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * 配置写操作（管理端用；引擎运行期只读）。
 *
 * <p>落地两条设计约定：</p>
 * <ul>
 *   <li><b>乐观锁</b>（§6.9）：{@code UPDATE ... SET version = version + 1 WHERE key_id = ? AND version = ?}
 *       —— 影响行数 0 表示"配置已被他人修改"，调用方应提示刷新，而不是静默覆盖。</li>
 *   <li><b>软删除唯一化</b>（§6.8）：{@code deleted_seq} 未删除为 0、删除时置为 {@code key_id}，
 *       这样"删掉再建同名配置"不会撞唯一键，且可无限次重复。</li>
 * </ul>
 *
 * <p>本类不写 {@code ifmap_config_history}（版本对比/回滚属 W7 管理端能力）。</p>
 *
 * @author caijun
 */
public class JdbcConfigWriter {

    private final JdbcTemplate jdbc;
    private final TableNameResolver tables;
    private final IdGenerator idGenerator;

    public JdbcConfigWriter(DataSource dataSource) {
        this(dataSource, TableNameResolver.DEFAULT_PREFIX);
    }

    public JdbcConfigWriter(DataSource dataSource, String tablePrefix) {
        this(new JdbcTemplate(dataSource), new TableNameResolver(tablePrefix), SnowflakeIdGenerator.shared());
    }

    public JdbcConfigWriter(JdbcTemplate jdbcTemplate, TableNameResolver tables, IdGenerator idGenerator) {
        if (jdbcTemplate == null) {
            throw new IfmapConfigException("JdbcTemplate 不能为空");
        }
        this.jdbc = jdbcTemplate;
        this.tables = tables == null ? TableNameResolver.defaults() : tables;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.shared() : idGenerator;
    }

    /**
     * 新增配置，返回主键。
     *
     * <p>自动补齐：{@code keyId}（为空时生成）、{@code version = 0}、{@code delStatus = 0}、
     * {@code deletedSeq = 0}、{@code status}（为空时 1）、{@code tenantId}（为空时 -1）、
     * {@code interfaceOrder}（为空时 0）。</p>
     */
    public long insert(IfmapConfig config) {
        if (config == null) {
            throw new IfmapConfigException("配置不能为空");
        }
        Long id = config.getKeyId() != null ? config.getKeyId() : idGenerator.nextId();
        if (id == null) {
            throw new IfmapConfigException("新增配置缺少主键：请提供 keyId，或注入会生成 ID 的 IdGenerator");
        }
        JdbcValues.require(config.getInterfaceNo(), "interfaceNo");
        JdbcValues.require(config.getInterfaceCode(), "interfaceCode");
        JdbcValues.require(config.getInterfaceName(), "interfaceName");
        JdbcValues.require(config.getBusiNode(), "busiNode");
        JdbcValues.require(config.getBankCode(), "bankCode");
        String sql = "INSERT INTO `" + tables.configTable() + "`"
                + " (`key_id`,`tenant_id`,`interface_no`,`interface_code`,`project_code`,`interface_name`,"
                + "`busi_node`,`bank_code`,`bank_name`,`financing_mode`,`front_interface_no`,`interface_order`,"
                + "`request_param_template`,`response_param_template`,`result_flag`,`success_value`,`strategy_name`,"
                + "`status`,`version`,`remark`,`del_status`,`deleted_seq`,`add_user_id`,`add_request_id`)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,0,0,?,?)";
        try {
            jdbc.update(sql, id,
                    JdbcValues.orDefault(config.getTenantId(), -1L),
                    config.getInterfaceNo(), config.getInterfaceCode(), config.getProjectCode(),
                    config.getInterfaceName(), config.getBusiNode(), config.getBankCode(), config.getBankName(),
                    config.getFinancingMode(), config.getFrontInterfaceNo(),
                    JdbcValues.orDefault(config.getInterfaceOrder(), 0),
                    config.getRequestParamTemplate(), config.getResponseParamTemplate(),
                    JdbcValues.orEmpty(config.getResultFlag()), JdbcValues.orEmpty(config.getSuccessValue()),
                    JdbcValues.orEmpty(config.getStrategyName()),
                    JdbcValues.orDefault(config.getStatus(), 1), JdbcValues.orEmpty(config.getRemark()),
                    JdbcValues.orEmpty(config.getAddUserId()), JdbcValues.orEmpty(config.getAddRequestId()));
        } catch (DuplicateKeyException e) {
            throw duplicate(config, e);
        }
        config.setKeyId(id);
        config.setVersion(0);
        config.setDelStatus(0);
        config.setDeletedSeq(0L);
        return id;
    }

    /**
     * 新增逻辑分支，返回主键。
     *
     * <p>自动补齐：{@code keyId}、{@code delStatus = 0}、{@code deletedSeq = 0}、
     * {@code tenantId}（为空时 -1）、{@code logicBranchOrder}（为空时 0）。</p>
     */
    public long insert(LogicBranchConfig branch) {
        if (branch == null) {
            throw new IfmapConfigException("逻辑分支不能为空");
        }
        Long id = branch.getKeyId() != null ? branch.getKeyId() : idGenerator.nextId();
        if (id == null) {
            throw new IfmapConfigException("新增逻辑分支缺少主键：请提供 keyId，或注入会生成 ID 的 IdGenerator");
        }
        JdbcValues.require(branch.getInterfaceNo(), "interfaceNo");
        JdbcValues.require(branch.getLogicBranchFlag(), "logicBranchFlag");
        JdbcValues.require(branch.getMethodFlag(), "methodFlag");
        String sql = "INSERT INTO `" + tables.logicBranchTable() + "`"
                + " (`key_id`,`tenant_id`,`interface_no`,`method_flag`,`logic_branch_name`,`logic_branch_flag`,"
                + "`logic_branch_value`,`logic_branch_order`,`remark`,`del_status`,`deleted_seq`)"
                + " VALUES (?,?,?,?,?,?,?,?,?,0,0)";
        jdbc.update(sql, id,
                JdbcValues.orDefault(branch.getTenantId(), -1L),
                branch.getInterfaceNo(), JdbcValues.orEmpty(branch.getMethodFlag()),
                JdbcValues.orEmpty(branch.getLogicBranchName()), JdbcValues.orEmpty(branch.getLogicBranchFlag()),
                JdbcValues.orEmpty(branch.getLogicBranchValue()),
                JdbcValues.orDefault(branch.getLogicBranchOrder(), 0),
                JdbcValues.orEmpty(branch.getRemark()));
        branch.setKeyId(id);
        branch.setDelStatus(0);
        branch.setDeletedSeq(0L);
        return id;
    }

    /**
     * 按乐观锁更新配置。仅更新业务列，不触碰 {@code del_status} / {@code deleted_seq} / {@code add_*}。
     *
     * @return {@code true} 更新成功；{@code false} 表示版本已被他人修改（{@code version} 不匹配）
     */
    public boolean update(IfmapConfig config, int expectedVersion, String operatorId, String requestId) {
        if (config == null || config.getKeyId() == null) {
            throw new IfmapConfigException("更新配置必须提供 keyId");
        }
        JdbcValues.require(config.getInterfaceNo(), "interfaceNo");
        JdbcValues.require(config.getInterfaceCode(), "interfaceCode");
        JdbcValues.require(config.getInterfaceName(), "interfaceName");
        JdbcValues.require(config.getBusiNode(), "busiNode");
        JdbcValues.require(config.getBankCode(), "bankCode");
        String sql = "UPDATE `" + tables.configTable() + "` SET"
                + " `interface_no` = ?, `interface_code` = ?, `project_code` = ?, `interface_name` = ?,"
                + " `busi_node` = ?, `bank_code` = ?, `bank_name` = ?, `financing_mode` = ?,"
                + " `front_interface_no` = ?, `interface_order` = ?,"
                + " `request_param_template` = ?, `response_param_template` = ?,"
                + " `result_flag` = ?, `success_value` = ?, `strategy_name` = ?,"
                + " `status` = ?, `remark` = ?,"
                + " `version` = `version` + 1, `modify_user_id` = ?, `modify_time` = CURRENT_TIMESTAMP(3),"
                + " `modify_request_id` = ?"
                + " WHERE `key_id` = ? AND `version` = ? AND `del_status` = 0";
        try {
            int rows = jdbc.update(sql,
                    config.getInterfaceNo(), config.getInterfaceCode(), config.getProjectCode(),
                    config.getInterfaceName(), config.getBusiNode(), config.getBankCode(), config.getBankName(),
                    config.getFinancingMode(), config.getFrontInterfaceNo(),
                    JdbcValues.orDefault(config.getInterfaceOrder(), 0),
                    config.getRequestParamTemplate(), config.getResponseParamTemplate(),
                    JdbcValues.orEmpty(config.getResultFlag()), JdbcValues.orEmpty(config.getSuccessValue()),
                    JdbcValues.orEmpty(config.getStrategyName()),
                    JdbcValues.orDefault(config.getStatus(), 1), JdbcValues.orEmpty(config.getRemark()),
                    JdbcValues.orEmpty(operatorId), JdbcValues.orEmpty(requestId),
                    config.getKeyId(), expectedVersion);
            return rows == 1;
        } catch (DuplicateKeyException e) {
            throw duplicate(config, e);
        }
    }

    /**
     * 原子软删除（§6.8）：{@code del_status = 1, deleted_seq = key_id}，并递增版本。
     *
     * @return {@code true} 删除成功；{@code false} 表示目标不存在或已删除（幂等调用不会报错）
     */
    public boolean softDelete(long keyId, String operatorId, String requestId) {
        String sql = "UPDATE `" + tables.configTable() + "`"
                + " SET `del_status` = 1, `deleted_seq` = `key_id`, `version` = `version` + 1,"
                + " `modify_user_id` = ?, `modify_time` = CURRENT_TIMESTAMP(3), `modify_request_id` = ?"
                + " WHERE `key_id` = ? AND `del_status` = 0";
        return jdbc.update(sql, JdbcValues.orEmpty(operatorId), JdbcValues.orEmpty(requestId), keyId) == 1;
    }

    /** 按主键查询（管理端编辑页需要看到停用/已删除行，故不加 {@code del_status} 过滤）。 */
    public Optional<IfmapConfig> findByKeyId(long keyId) {        String sql = "SELECT " + IfmapRowMappers.CONFIG_COLUMNS + " FROM `" + tables.configTable() + "`"
                + " WHERE `key_id` = ?";
        List<IfmapConfig> list = jdbc.query(sql, IfmapRowMappers.config(), keyId);
        return list.isEmpty() ? Optional.<IfmapConfig>empty() : Optional.of(list.get(0));
    }

    private static IfmapConfigException duplicate(IfmapConfig config, DuplicateKeyException cause) {
        return new IfmapConfigException("配置唯一键冲突：同一 (tenant_id=" + config.getTenantId()
                + ", interface_no=" + config.getInterfaceNo() + ", busi_node=" + config.getBusiNode()
                + ", interface_order=" + config.getInterfaceOrder() + ") 下已存在未删除的配置", cause);
    }
}
