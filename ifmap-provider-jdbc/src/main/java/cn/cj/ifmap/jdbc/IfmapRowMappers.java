package cn.cj.ifmap.jdbc;

import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 手写行映射器：显式逐列读取，不依赖反射与列名推断。
 *
 * <p>为什么不用 {@code BeanPropertyRowMapper}：① 列名映射失败只会在运行期静默留 null；
 * ② 各 Spring / 驱动版本对 {@code LocalDateTime} 的处理路径不同。显式映射可被单测覆盖且零魔法。</p>
 *
 * @author caijun
 */
public final class IfmapRowMappers {

    /** 接口配置列清单（顺序与 SQL 中一致，供 SELECT 复用）。 */
    static final String CONFIG_COLUMNS =
            "`key_id`,`tenant_id`,`interface_no`,`interface_code`,`project_code`,`interface_name`,"
            + "`busi_node`,`bank_code`,`bank_name`,`financing_mode`,`front_interface_no`,`interface_order`,"
            + "`request_param_template`,`response_param_template`,`result_flag`,`success_value`,`strategy_name`,"
            + "`status`,`version`,`remark`,`del_status`,`deleted_seq`,"
            + "`add_user_id`,`add_time`,`add_request_id`,`modify_user_id`,`modify_time`,`modify_request_id`";

    /** 逻辑分支列清单。 */
    static final String LOGIC_BRANCH_COLUMNS =
            "`key_id`,`tenant_id`,`interface_no`,`method_flag`,`logic_branch_name`,`logic_branch_flag`,"
            + "`logic_branch_value`,`logic_branch_order`,`remark`,`del_status`,`deleted_seq`";

    private IfmapRowMappers() {
    }

    /** 接口配置映射器。 */
    public static RowMapper<IfmapConfig> config() {
        return new RowMapper<IfmapConfig>() {
            @Override
            public IfmapConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
                IfmapConfig c = new IfmapConfig();
                c.setKeyId(rs.getLong("key_id"));
                c.setTenantId(rs.getLong("tenant_id"));
                c.setInterfaceNo(rs.getString("interface_no"));
                c.setInterfaceCode(rs.getString("interface_code"));
                c.setProjectCode(rs.getString("project_code"));
                c.setInterfaceName(rs.getString("interface_name"));
                c.setBusiNode(rs.getString("busi_node"));
                c.setBankCode(rs.getString("bank_code"));
                c.setBankName(rs.getString("bank_name"));
                c.setFinancingMode(rs.getString("financing_mode"));
                c.setFrontInterfaceNo(rs.getString("front_interface_no"));
                c.setInterfaceOrder(rs.getInt("interface_order"));
                c.setRequestParamTemplate(rs.getString("request_param_template"));
                c.setResponseParamTemplate(rs.getString("response_param_template"));
                c.setResultFlag(rs.getString("result_flag"));
                c.setSuccessValue(rs.getString("success_value"));
                c.setStrategyName(rs.getString("strategy_name"));
                c.setStatus(rs.getInt("status"));
                c.setVersion(rs.getInt("version"));
                c.setRemark(rs.getString("remark"));
                c.setDelStatus(rs.getInt("del_status"));
                c.setDeletedSeq(rs.getLong("deleted_seq"));
                c.setAddUserId(rs.getString("add_user_id"));
                c.setAddTime(toLocalDateTime(rs.getTimestamp("add_time")));
                c.setAddRequestId(rs.getString("add_request_id"));
                c.setModifyUserId(rs.getString("modify_user_id"));
                c.setModifyTime(toLocalDateTime(rs.getTimestamp("modify_time")));
                c.setModifyRequestId(rs.getString("modify_request_id"));
                return c;
            }
        };
    }

    /** 逻辑分支映射器。 */
    public static RowMapper<LogicBranchConfig> logicBranch() {
        return new RowMapper<LogicBranchConfig>() {
            @Override
            public LogicBranchConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
                LogicBranchConfig b = new LogicBranchConfig();
                b.setKeyId(rs.getLong("key_id"));
                b.setTenantId(rs.getLong("tenant_id"));
                b.setInterfaceNo(rs.getString("interface_no"));
                b.setMethodFlag(rs.getString("method_flag"));
                b.setLogicBranchName(rs.getString("logic_branch_name"));
                b.setLogicBranchFlag(rs.getString("logic_branch_flag"));
                b.setLogicBranchValue(rs.getString("logic_branch_value"));
                b.setLogicBranchOrder(rs.getInt("logic_branch_order"));
                b.setRemark(rs.getString("remark"));
                b.setDelStatus(rs.getInt("del_status"));
                b.setDeletedSeq(rs.getLong("deleted_seq"));
                return b;
            }
        };
    }

    /** 执行日志映射器（查询/回放用）。 */
    public static RowMapper<ExecutionLog> executionLog() {
        return new RowMapper<ExecutionLog>() {
            @Override
            public ExecutionLog mapRow(ResultSet rs, int rowNum) throws SQLException {
                ExecutionLog log = new ExecutionLog();
                log.setKeyId(rs.getLong("key_id"));
                log.setTenantId(rs.getLong("tenant_id"));
                log.setInterfaceNo(rs.getString("interface_no"));
                log.setBizId(rs.getString("biz_id"));
                log.setRequestParam(rs.getString("request_param"));
                log.setResponseParam(rs.getString("response_param"));
                log.setExecutionTime(rs.getLong("execution_time"));
                log.setExecutionResult(rs.getString("execution_result"));
                log.setErrorMsg(rs.getString("error_msg"));
                log.setRemark(rs.getString("remark"));
                log.setAddUserId(rs.getString("add_user_id"));
                log.setAddRequestId(rs.getString("add_request_id"));
                return log;
            }
        };
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
