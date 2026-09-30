package cn.cj.ifmap.core.config;

import java.io.Serializable;

/**
 * 执行日志（对应表 {@code ifmap_execution_log}）。
 *
 * <p>写入前必须完成脱敏（详见 {@code ifmap.log.mask} 相关约定）；超长报文由写入方按阈值截断。</p>
 *
 * @author caijun
 */
public class ExecutionLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 执行成功。 */
    public static final String RESULT_SUCCESS = "SUCCESS";
    /** 执行失败。 */
    public static final String RESULT_FAIL = "FAIL";
    /** 跳过（如响应模板缺失、条件不满足）。 */
    public static final String RESULT_SKIP = "SKIP";
    /** 超时。 */
    public static final String RESULT_TIMEOUT = "TIMEOUT";

    /** 主键。 */
    private Long keyId;
    /** 租户 ID。 */
    private Long tenantId;
    /** 接口编号。 */
    private String interfaceNo;
    /** 业务 ID。 */
    private String bizId;
    /** 请求参数（已脱敏）。 */
    private String requestParam;
    /** 响应参数（已脱敏）。 */
    private String responseParam;
    /** 执行耗时（毫秒）。 */
    private Long executionTime;
    /** 执行结果：SUCCESS / FAIL / SKIP / TIMEOUT。 */
    private String executionResult;
    /** 失败原因（截断）。 */
    private String errorMsg;
    /** 备注。 */
    private String remark;
    /** 添加人。 */
    private String addUserId;
    /** 创建请求 ID。 */
    private String addRequestId;

    /** 快速构造成功日志。 */
    public static ExecutionLog success(String interfaceNo, String bizId, long elapsedMs) {
        ExecutionLog log = new ExecutionLog();
        log.setInterfaceNo(interfaceNo);
        log.setBizId(bizId);
        log.setExecutionTime(elapsedMs);
        log.setExecutionResult(RESULT_SUCCESS);
        return log;
    }

    /** 快速构造失败日志。 */
    public static ExecutionLog fail(String interfaceNo, String bizId, long elapsedMs, String errorMsg) {
        ExecutionLog log = new ExecutionLog();
        log.setInterfaceNo(interfaceNo);
        log.setBizId(bizId);
        log.setExecutionTime(elapsedMs);
        log.setExecutionResult(RESULT_FAIL);
        log.setErrorMsg(errorMsg);
        return log;
    }

    public Long getKeyId() {
        return keyId;
    }

    public void setKeyId(Long keyId) {
        this.keyId = keyId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getInterfaceNo() {
        return interfaceNo;
    }

    public void setInterfaceNo(String interfaceNo) {
        this.interfaceNo = interfaceNo;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public String getRequestParam() {
        return requestParam;
    }

    public void setRequestParam(String requestParam) {
        this.requestParam = requestParam;
    }

    public String getResponseParam() {
        return responseParam;
    }

    public void setResponseParam(String responseParam) {
        this.responseParam = responseParam;
    }

    public Long getExecutionTime() {
        return executionTime;
    }

    public void setExecutionTime(Long executionTime) {
        this.executionTime = executionTime;
    }

    public String getExecutionResult() {
        return executionResult;
    }

    public void setExecutionResult(String executionResult) {
        this.executionResult = executionResult;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getAddUserId() {
        return addUserId;
    }

    public void setAddUserId(String addUserId) {
        this.addUserId = addUserId;
    }

    public String getAddRequestId() {
        return addRequestId;
    }

    public void setAddRequestId(String addRequestId) {
        this.addRequestId = addRequestId;
    }
}
