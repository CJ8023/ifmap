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
 * <p>本类不写 {@code ifmap_config_history}：历史快照需要 JSON 序列化能力，由管理端
 * （{@code ifmap-admin-spring-boot-starter}）在自己的事务里落库，provider 层保持零 JSON 依赖。</p>
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
        JdbcValues.require(config.getPartnerCode(), "partnerCode");
        String sql = "INSERT INTO `" + tables.configTable() + "`"
                + " (`key_id`,`tenant_id`,`interface_no`,`interface_code`,`project_code`,`interface_name`,"
                + "`busi_node`,`partner_code`,`partner_name`,`financing_mode`,`front_interface_no`,`interface_order`,"
                + "`request_param_template`,`response_param_template`,`result_flag`,`success_value`,`strategy_name`,"
                + "`status`,`version`,`remark`,`del_status`,`deleted_seq`,`add_user_id`,`add_request_id`)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,0,0,?,?)";
        try {
            jdbc.update(sql, id,
                    JdbcValues.orDefault(config.getTenantId(), -1L),
                    config.getInterfaceNo(), config.getInterfaceCode(), config.getProjectCode(),
                    config.getInterfaceName(), config.getBusiNode(), config.getPartnerCode(), config.getPartnerName(),
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
     *
     * <p><b>空值语义</b>：{@code logic_branch_flag} / {@code logic_branch_value} / {@code method_flag}
     * 都允许为空 —— "flag 与 value 皆空"就是<b>默认兜底分支</b>的定义，{@code method_flag} 为空表示命中该分支后
     * 不执行宿主动作。三列在 DDL 里都是 {@code NOT NULL DEFAULT ''}（或可空），所以它们不能进必填校验，
     * 这里统一按"空串落库"处理（而不是 NULL）：既避开 {@code NOT NULL} 列写 NULL 的报错，也让唯一键
     * {@code uk_..._logic_branch} 对"默认兜底分支"只有一条生效。</p>
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
        JdbcValues.require(branch.getLogicBranchName(), "logicBranchName");
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
        JdbcValues.require(config.getPartnerCode(), "partnerCode");
        String sql = "UPDATE `" + tables.configTable() + "` SET"
                + " `interface_no` = ?, `interface_code` = ?, `project_code` = ?, `interface_name` = ?,"
                + " `busi_node` = ?, `partner_code` = ?, `partner_name` = ?, `financing_mode` = ?,"
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
                    config.getInterfaceName(), config.getBusiNode(), config.getPartnerCode(), config.getPartnerName(),
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

    /**
     * 启用/停用配置（设计 §8.2 {@code POST /configs/{id}/status}）。
     *
     * <p>只改 {@code status} 并递增版本；不触碰模板等业务列，避免与"编辑保存"互相覆盖。</p>
     *
     * @param status          1 启用 / 0 停用
     * @param expectedVersion 客户端看到的版本号（乐观锁）
     * @return {@code true} 成功；{@code false} 版本不匹配或目标已删除
     */
    public boolean updateStatus(long keyId, int status, int expectedVersion, String operatorId, String requestId) {
        if (status != 0 && status != 1) {
            throw new IfmapConfigException("status 只能是 0（停用）或 1（启用）：" + status);
        }
        String sql = "UPDATE `" + tables.configTable() + "`"
                + " SET `status` = ?, `version` = `version` + 1,"
                + " `modify_user_id` = ?, `modify_time` = CURRENT_TIMESTAMP(3), `modify_request_id` = ?"
                + " WHERE `key_id` = ? AND `version` = ? AND `del_status` = 0";
        return jdbc.update(sql, status, JdbcValues.orEmpty(operatorId), JdbcValues.orEmpty(requestId),
                keyId, expectedVersion) == 1;
    }

    /**
     * 更新逻辑分支（分支表无 {@code version} 列，故用主键直更；唯一键冲突会抛业务异常）。
     *
     * @return {@code true} 成功；{@code false} 目标不存在或已删除
     */
    public boolean update(LogicBranchConfig branch) {
        if (branch == null || branch.getKeyId() == null) {
            throw new IfmapConfigException("更新逻辑分支必须提供 keyId");
        }
        JdbcValues.require(branch.getInterfaceNo(), "interfaceNo");
        JdbcValues.require(branch.getLogicBranchName(), "logicBranchName");
        String sql = "UPDATE `" + tables.logicBranchTable() + "` SET"
                + " `interface_no` = ?, `method_flag` = ?, `logic_branch_name` = ?, `logic_branch_flag` = ?,"
                + " `logic_branch_value` = ?, `logic_branch_order` = ?, `remark` = ?"
                + " WHERE `key_id` = ? AND `del_status` = 0";
        try {
            return jdbc.update(sql, branch.getInterfaceNo(), JdbcValues.orEmpty(branch.getMethodFlag()),
                    JdbcValues.orEmpty(branch.getLogicBranchName()), JdbcValues.orEmpty(branch.getLogicBranchFlag()),
                    JdbcValues.orEmpty(branch.getLogicBranchValue()),
                    JdbcValues.orDefault(branch.getLogicBranchOrder(), 0),
                    JdbcValues.orEmpty(branch.getRemark()), branch.getKeyId()) == 1;
        } catch (DuplicateKeyException e) {
            throw duplicateBranch(branch, e);
        }
    }

    /** 原子软删除逻辑分支（{@code del_status = 1, deleted_seq = key_id}）。 */
    public boolean softDeleteBranch(long keyId) {
        String sql = "UPDATE `" + tables.logicBranchTable() + "`"
                + " SET `del_status` = 1, `deleted_seq` = `key_id`"
                + " WHERE `key_id` = ? AND `del_status` = 0";
        return jdbc.update(sql, keyId) == 1;
    }

    private static IfmapConfigException duplicateBranch(LogicBranchConfig branch, DuplicateKeyException cause) {
        return new IfmapConfigException("逻辑分支唯一键冲突：(tenant_id=" + branch.getTenantId()
                + ", interface_no=" + branch.getInterfaceNo() + ", method_flag=" + branch.getMethodFlag()
                + ", logic_branch_name=" + branch.getLogicBranchName() + ") 已存在", cause);
    }

    /** 按主键查询（管理端编辑页需要看到停用/已删除行，故不加 {@code del_status} 过滤）。 */
    public Optional<IfmapConfig> findByKeyId(long keyId) {
        String sql = "SELECT " + IfmapRowMappers.CONFIG_COLUMNS + " FROM `" + tables.configTable() + "`"
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
