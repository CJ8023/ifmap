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

import cn.cj.ifmap.core.config.ConfigQuery;
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

    /** 引擎主路径的查询：只取<b>启用且未删除</b>的行，按 {@code interface_order, key_id} 升序。 */
    @Override
    public List<IfmapConfig> queryConfigs(String tenantId, String interfaceNo, String busiNode) {
        String sql = "SELECT " + IfmapRowMappers.CONFIG_COLUMNS + " FROM `" + tables.configTable() + "`"
                + " WHERE `tenant_id` = ? AND `interface_no` = ? AND `busi_node` = ?"
                + " AND `del_status` = 0 AND `status` = 1"
                + " ORDER BY `interface_order` ASC, `key_id` ASC";
        return jdbc.query(sql, IfmapRowMappers.config(), tenant(tenantId), interfaceNo, busiNode);
    }

    /** 点查单条：同样只取启用行；万一有多条（历史脏数据）按 {@code key_id} 取第一条，不抛。 */
    @Override
    public Optional<IfmapConfig> findConfig(String tenantId, String interfaceNo, String busiNode, int order) {
        String sql = "SELECT " + IfmapRowMappers.CONFIG_COLUMNS + " FROM `" + tables.configTable() + "`"
                + " WHERE `tenant_id` = ? AND `interface_no` = ? AND `busi_node` = ? AND `interface_order` = ?"
                + " AND `del_status` = 0 AND `status` = 1"
                + " ORDER BY `key_id` ASC LIMIT 1";
        List<IfmapConfig> list = jdbc.query(sql, IfmapRowMappers.config(), tenant(tenantId), interfaceNo, busiNode, order);
        return list.isEmpty() ? Optional.<IfmapConfig>empty() : Optional.of(list.get(0));
    }

    /** 逻辑分支：未删除的全量返回（该表无启用状态列），按 {@code logic_branch_order, key_id} 升序。 */
    @Override
    public List<LogicBranchConfig> queryLogicBranches(String tenantId, String interfaceNo) {
        String sql = "SELECT " + IfmapRowMappers.LOGIC_BRANCH_COLUMNS + " FROM `" + tables.logicBranchTable() + "`"
                + " WHERE `tenant_id` = ? AND `interface_no` = ? AND `del_status` = 0"
                + " ORDER BY `logic_branch_order` ASC, `key_id` ASC";
        return jdbc.query(sql, IfmapRowMappers.logicBranch(), tenant(tenantId), interfaceNo);
    }

    /**
     * 写执行日志：主键缺省由 {@link IdGenerator}（雪花）补；{@code fail_type}/{@code error_code}/
     * {@code track_id} 三列是 {@code NOT NULL DEFAULT ''}，所以显式列 + 空串绑定，不能传 null。
     */
    @Override
    public void saveExecutionLog(ExecutionLog log) {
        if (log == null) {
            throw new IfmapConfigException("执行日志不能为空");
        }
        // 主键优先用调用方给的（重放/回填场景），没有才让 IdGenerator 生成；两者都没有就直接报错，不静默写入
        Long id = log.getKeyId();
        if (id == null) {
            id = idGenerator.nextId();
        }
        if (id == null) {
            throw new IfmapConfigException("执行日志缺少主键：请提供 keyId，或为 JdbcConfigRepository 注入会生成 ID 的 IdGenerator");
        }
        log.setKeyId(id);
        // 显式列 + 占位符：del_status 硬编码 0，所以 VALUES 里少一个 ?，合计 16 个占位符
        String sql = "INSERT INTO `" + tables.executionLogTable() + "`"
                + " (`key_id`,`tenant_id`,`interface_no`,`biz_id`,`request_param`,`response_param`,"
                + "`execution_time`,`execution_result`,`error_msg`,`http_status`,`fail_type`,`error_code`,"
                + "`track_id`,`remark`,`del_status`,`add_user_id`,`add_request_id`)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)";
        jdbc.update(sql, id, tenant(log.getTenantId()),
                JdbcValues.require(log.getInterfaceNo(), "interfaceNo"),
                JdbcValues.require(log.getBizId(), "bizId"),
                log.getRequestParam(), log.getResponseParam(),
                JdbcValues.orDefault(log.getExecutionTime(), 0L),
                JdbcValues.require(log.getExecutionResult(), "executionResult"),
                log.getErrorMsg(), log.getHttpStatus(),
                // 失败类型/错误码/链路ID 都是 NOT NULL DEFAULT ''：显式列 + null 绑定会报错，按空串落库
                JdbcValues.orEmpty(log.getFailType()), JdbcValues.orEmpty(log.getErrorCode()),
                JdbcValues.orEmpty(log.getTrackId()), JdbcValues.orEmpty(log.getRemark()),
                JdbcValues.orEmpty(log.getAddUserId()), JdbcValues.orEmpty(log.getAddRequestId()));
    }

    /**
     * 管理端分页查询（设计 §8.2 `GET /configs`）：条件为空即不过滤，{@code tenantId} 为空按 {@code -1}。
     *
     * <p>与 {@link #queryConfigs} 的区别：这里<b>不强制</b>只取启用行 —— 管理端要能看到停用与（可选）已删除配置。</p>
     */
    public List<IfmapConfig> queryConfigs(ConfigQuery query) {
        ConfigQuery q = query == null ? new ConfigQuery() : query;
        // size/offset 只能拼串（MySQL 占位符不支持 LIMIT ? 的旧版本），靠 ConfigQuery 自身把 size 收敛到 [1,200]，
        // 因此这里拼进去的一定是非负整数，不存在注入面
        StringBuilder sql = new StringBuilder("SELECT ").append(IfmapRowMappers.CONFIG_COLUMNS)
                .append(" FROM `").append(tables.configTable()).append("` WHERE ").append(whereClause(q));
        sql.append(" ORDER BY `tenant_id` ASC, `interface_no` ASC, `interface_order` ASC, `key_id` ASC")
                .append(" LIMIT ").append(q.getSize()).append(" OFFSET ").append(q.offset());
        return jdbc.query(sql.toString(), IfmapRowMappers.config(), whereArgs(q).toArray());
    }

    /** 管理端分页查询的总条数（与 {@link #queryConfigs(ConfigQuery)} 条件一致）。 */
    public long countConfigs(ConfigQuery query) {
        ConfigQuery q = query == null ? new ConfigQuery() : query;
        String sql = "SELECT COUNT(1) FROM `" + tables.configTable() + "` WHERE " + whereClause(q);
        Long count = jdbc.queryForObject(sql, Long.class, whereArgs(q).toArray());
        return count == null ? 0L : count.longValue();
    }

    /**
     * 按接口号查询该接口下<b>所有业务节点</b>的配置（管理端分支维护用）。
     *
     * <p>返回包含停用与已删除行（管理端视角），按 {@code interface_order} 升序。</p>
     */
    public List<IfmapConfig> queryConfigsByInterface(String tenantId, String interfaceNo) {
        if (interfaceNo == null || interfaceNo.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        String sql = "SELECT " + IfmapRowMappers.CONFIG_COLUMNS + " FROM `" + tables.configTable() + "`"
                + " WHERE `tenant_id` = ? AND `interface_no` = ?"
                + " ORDER BY `interface_order` ASC, `key_id` ASC";
        return jdbc.query(sql, IfmapRowMappers.config(), tenant(tenantId), interfaceNo);
    }

    /**
     * 管理端查询逻辑分支：可选包含已删除行（分支表无 {@code status} 列，只有软删除标记）。
     *
     * <p>与 {@link #queryLogicBranches} 的区别：引擎链路只读未删除行；管理端要能看"删了什么"。</p>
     */
    public List<LogicBranchConfig> queryLogicBranches(String tenantId, String interfaceNo, boolean includeDeleted) {
        String sql = "SELECT " + IfmapRowMappers.LOGIC_BRANCH_COLUMNS + " FROM `" + tables.logicBranchTable() + "`"
                + " WHERE `tenant_id` = ? AND `interface_no` = ?"
                + (includeDeleted ? "" : " AND `del_status` = 0")
                + " ORDER BY `logic_branch_order` ASC, `key_id` ASC";
        return jdbc.query(sql, IfmapRowMappers.logicBranch(), tenant(tenantId), interfaceNo);
    }

    /** 按主键查逻辑分支（含已删除行，管理端编辑/删除前后取租户与接口号用）。 */
    public java.util.Optional<LogicBranchConfig> findLogicBranch(long keyId) {
        String sql = "SELECT " + IfmapRowMappers.LOGIC_BRANCH_COLUMNS + " FROM `" + tables.logicBranchTable() + "`"
                + " WHERE `key_id` = ?";
        List<LogicBranchConfig> list = jdbc.query(sql, IfmapRowMappers.logicBranch(), keyId);
        return list.isEmpty() ? java.util.Optional.<LogicBranchConfig>empty() : java.util.Optional.of(list.get(0));
    }

    /**
     * 引擎取执行计划：该节点下全部启用配置，按 {@code interface_order} 升序（SPI 契约的落地）。
     *
     * <p>与 {@link #queryConfigs(ConfigQuery)} 的区别：这里<b>强制</b>只取启用且未删除的行，
     * 且排序口径是「节点内的步骤顺序」而不是管理端列表的「先按接口号」。</p>
     */
    @Override
    public List<IfmapConfig> queryConfigsByBusiNode(String tenantId, String busiNode) {
        // 空 busiNode 直接返回空列表：否则 SQL 里的 `busi_node` = '' 会真的去查一遍，还可能命中脏数据
        if (busiNode == null || busiNode.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        String sql = "SELECT " + IfmapRowMappers.CONFIG_COLUMNS + " FROM `" + tables.configTable() + "`"
                + " WHERE `tenant_id` = ? AND `busi_node` = ? AND `del_status` = 0 AND `status` = 1"
                + " ORDER BY `interface_order` ASC, `key_id` ASC";
        return jdbc.query(sql, IfmapRowMappers.config(), tenant(tenantId), busiNode);
    }

    /** 管理端动态条件拼接；<b>必须与 {@link #whereArgs(ConfigQuery)} 严格同序</b>（少一处就条件与值错位）。 */
    private static String whereClause(ConfigQuery q) {
        // 子句顺序必须与 whereArgs 完全一致：条件与值是两条独立代码路径，顺序一错就是"按租户查了接口号"
        StringBuilder sb = new StringBuilder();
        if (!q.isAllTenants()) {
            sb.append("`tenant_id` = ?");
        }
        if (notBlank(q.getInterfaceNo())) {
            appendAnd(sb);
            sb.append("`interface_no` = ?");
        }
        if (notBlank(q.getBusiNode())) {
            appendAnd(sb);
            sb.append("`busi_node` = ?");
        }
        if (notBlank(q.getPartnerCode())) {
            appendAnd(sb);
            sb.append("`partner_code` = ?");
        }
        if (q.getStatus() != null) {
            appendAnd(sb);
            sb.append("`status` = ?");
        }
        // 软删过滤是「无占位符」的子句：whereArgs 里对应位置不能加值，否则后面全部错位
        if (!q.isIncludeDeleted()) {
            appendAnd(sb);
            sb.append("`del_status` = 0");
        }
        return sb.length() == 0 ? "1 = 1" : sb.toString();
    }

    /** 拼接 AND（首个子句不加）。 */
    private static void appendAnd(StringBuilder sb) {
        if (sb.length() > 0) {
            sb.append(" AND ");
        }
    }

    /** 与 {@link #whereClause(ConfigQuery)} 一一对应的占位符实参（顺序即 {@code ?} 顺序）。 */
    private static java.util.List<Object> whereArgs(ConfigQuery q) {
        // 与 whereClause 逐条对照阅读：每个 ? 必须有且只有一个值，且顺序相同
        java.util.List<Object> args = new java.util.ArrayList<Object>();
        if (!q.isAllTenants()) {
            args.add(tenant(q.getTenantId()));
        }
        if (notBlank(q.getInterfaceNo())) {
            args.add(q.getInterfaceNo().trim());
        }
        if (notBlank(q.getBusiNode())) {
            args.add(q.getBusiNode().trim());
        }
        if (notBlank(q.getPartnerCode())) {
            args.add(q.getPartnerCode().trim());
        }
        if (q.getStatus() != null) {
            args.add(q.getStatus());
        }
        return args;
    }

    /** 查询条件是否有效：空白串按「没填」处理。 */
    private static boolean notBlank(String value) {
        return value != null && value.trim().length() > 0;
    }

    /** 当前生效的表名解析器。 */
    public TableNameResolver tables() {
        return tables;
    }

    /** 按业务单查执行日志（最近 N 条，按时间倒序）——运维排障用，非引擎链路。 */
    public List<ExecutionLog> recentLogs(String tenantId, String bizId, int limit) {
        String sql = "SELECT `key_id`,`tenant_id`,`interface_no`,`biz_id`,`request_param`,`response_param`,"
                + "`execution_time`,`execution_result`,`error_msg`,`http_status`,`fail_type`,`error_code`,"
                + "`track_id`,`remark`,`add_user_id`,`add_request_id`"
                + " FROM `" + tables.executionLogTable() + "`"
                + " WHERE `tenant_id` = ? AND `biz_id` = ? AND `del_status` = 0"
                + " ORDER BY `add_time` DESC LIMIT ?";
        return jdbc.query(sql, IfmapRowMappers.executionLog(), tenant(tenantId), bizId, limit);
    }

    /**
     * 租户号转 {@code long}：{@code null}/空串 → {@code -1}（单租户默认值）；<b>非数字直接抛</b>而不是也当 -1 ——
     * 否则写错租户会静默读到别的租户的数据。
     */
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

    /** {@code Long} 版：null → {@code -1}。 */
    private static long tenant(Long tenantId) {
        return tenantId == null ? TENANT_DEFAULT : tenantId;
    }

}
