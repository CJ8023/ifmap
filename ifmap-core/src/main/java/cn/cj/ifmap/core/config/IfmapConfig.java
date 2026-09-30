package cn.cj.ifmap.core.config;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 接口配置（对应表 {@code ifmap_config}）。
 *
 * <p>引擎运行期**只读**：{@link ConfigRepository} 返回的对象由引擎消费，引擎不会修改其中任何字段。
 * 管理端做增删改时请使用 provider-jdbc 的写接口（如 {@code JdbcConfigWriter}），
 * 它们负责维护 {@code version}（乐观锁）与 {@code deleted_seq}（软删除唯一化）。</p>
 *
 * <p>列名与字段对应关系遵循下划线转驼峰（如 {@code interface_no -> interfaceNo}），
 * 便于 JdbcTemplate 的 {@code BeanPropertyRowMapper} 直接映射。</p>
 *
 * @author caijun
 */
public class IfmapConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键。 */
    private Long keyId;
    /** 租户 ID（单租户固定 -1）。 */
    private Long tenantId;
    /** 接口编号：引擎内部的接口业务编号，同一 busiNode 下唯一标识一个接口。 */
    private String interfaceNo;
    /** 接口编码：对接方（银行/金融机构）的接口编码。 */
    private String interfaceCode;
    /** 项目编号。 */
    private String projectCode;
    /** 接口名称。 */
    private String interfaceName;
    /** 业务节点（取值由宿主机注册）。 */
    private String busiNode;
    /** 资方编码。 */
    private String bankCode;
    /** 资方名称。 */
    private String bankName;
    /** 融资模式（见文档数据字典）。 */
    private String financingMode;
    /** 前置接口编号：本接口执行前必须先执行的接口，空=无前置。 */
    private String frontInterfaceNo;
    /** 接口执行顺序，升序；同值按 keyId 兜底。 */
    private Integer interfaceOrder;
    /** 请求参数模板（DSL），可为 null（纯回调接口无请求体）。 */
    private String requestParamTemplate;
    /** 响应参数模板（DSL），可为 null。 */
    private String responseParamTemplate;
    /** 执行结果标志（JsonPath）。 */
    private String resultFlag;
    /** 成功判断值，多值以 {@code |} 分隔（大小写不敏感）。 */
    private String successValue;
    /** 特殊处理策略标识（= Spring bean 名）。 */
    private String strategyName;
    /** 状态：1 启用 / 0 停用。 */
    private Integer status;
    /** 乐观锁版本。 */
    private Integer version;
    /** 备注。 */
    private String remark;
    /** 删除标识：0 未删除 / 1 已删除。 */
    private Integer delStatus;
    /** 软删除唯一化：未删除=0，删除时=keyId。 */
    private Long deletedSeq;
    /** 添加人。 */
    private String addUserId;
    /** 添加时间。 */
    private LocalDateTime addTime;
    /** 创建请求 ID。 */
    private String addRequestId;
    /** 更新人。 */
    private String modifyUserId;
    /** 更新时间。 */
    private LocalDateTime modifyTime;
    /** 修改请求 ID。 */
    private String modifyRequestId;

    /** 是否启用。 */
    public boolean enabled() {
        return status != null && status == 1;
    }

    /** 模板是否全为空（请求与响应模板都没有内容时引擎无事可做）。 */
    public boolean hasNoTemplate() {
        return isBlank(requestParamTemplate) && isBlank(responseParamTemplate);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
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

    public String getInterfaceCode() {
        return interfaceCode;
    }

    public void setInterfaceCode(String interfaceCode) {
        this.interfaceCode = interfaceCode;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    public String getBusiNode() {
        return busiNode;
    }

    public void setBusiNode(String busiNode) {
        this.busiNode = busiNode;
    }

    public String getBankCode() {
        return bankCode;
    }

    public void setBankCode(String bankCode) {
        this.bankCode = bankCode;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getFinancingMode() {
        return financingMode;
    }

    public void setFinancingMode(String financingMode) {
        this.financingMode = financingMode;
    }

    public String getFrontInterfaceNo() {
        return frontInterfaceNo;
    }

    public void setFrontInterfaceNo(String frontInterfaceNo) {
        this.frontInterfaceNo = frontInterfaceNo;
    }

    public Integer getInterfaceOrder() {
        return interfaceOrder;
    }

    public void setInterfaceOrder(Integer interfaceOrder) {
        this.interfaceOrder = interfaceOrder;
    }

    public String getRequestParamTemplate() {
        return requestParamTemplate;
    }

    public void setRequestParamTemplate(String requestParamTemplate) {
        this.requestParamTemplate = requestParamTemplate;
    }

    public String getResponseParamTemplate() {
        return responseParamTemplate;
    }

    public void setResponseParamTemplate(String responseParamTemplate) {
        this.responseParamTemplate = responseParamTemplate;
    }

    public String getResultFlag() {
        return resultFlag;
    }

    public void setResultFlag(String resultFlag) {
        this.resultFlag = resultFlag;
    }

    public String getSuccessValue() {
        return successValue;
    }

    public void setSuccessValue(String successValue) {
        this.successValue = successValue;
    }

    public String getStrategyName() {
        return strategyName;
    }

    public void setStrategyName(String strategyName) {
        this.strategyName = strategyName;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Integer getDelStatus() {
        return delStatus;
    }

    public void setDelStatus(Integer delStatus) {
        this.delStatus = delStatus;
    }

    public Long getDeletedSeq() {
        return deletedSeq;
    }

    public void setDeletedSeq(Long deletedSeq) {
        this.deletedSeq = deletedSeq;
    }

    public String getAddUserId() {
        return addUserId;
    }

    public void setAddUserId(String addUserId) {
        this.addUserId = addUserId;
    }

    public LocalDateTime getAddTime() {
        return addTime;
    }

    public void setAddTime(LocalDateTime addTime) {
        this.addTime = addTime;
    }

    public String getAddRequestId() {
        return addRequestId;
    }

    public void setAddRequestId(String addRequestId) {
        this.addRequestId = addRequestId;
    }

    public String getModifyUserId() {
        return modifyUserId;
    }

    public void setModifyUserId(String modifyUserId) {
        this.modifyUserId = modifyUserId;
    }

    public LocalDateTime getModifyTime() {
        return modifyTime;
    }

    public void setModifyTime(LocalDateTime modifyTime) {
        this.modifyTime = modifyTime;
    }

    public String getModifyRequestId() {
        return modifyRequestId;
    }

    public void setModifyRequestId(String modifyRequestId) {
        this.modifyRequestId = modifyRequestId;
    }
}
