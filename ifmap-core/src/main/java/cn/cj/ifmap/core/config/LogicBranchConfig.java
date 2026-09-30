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
package cn.cj.ifmap.core.config;

import java.io.Serializable;

/**
 * 逻辑分支配置（对应表 {@code ifmap_logic_branch_config}）。
 *
 * <p>{@code methodFlag} 是**动作标识（Action Key）**：分支命中后由宿主机 ActionRegistry 执行的动作；
 * 为空表示默认兜底分支。匹配顺序由 {@code logicBranchOrder} 升序决定（同值按 {@code keyId} 兜底）。</p>
 *
 * @author caijun
 */
public class LogicBranchConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键。 */
    private Long keyId;
    /** 租户 ID。 */
    private Long tenantId;
    /** 接口编号。 */
    private String interfaceNo;
    /** 动作标识（Action Key）；空=默认兜底分支。 */
    private String methodFlag;
    /** 逻辑分支名称。 */
    private String logicBranchName;
    /** 逻辑分支标志（JsonPath 表达式，支持 {@code $.a.b} 与裸字段名）。 */
    private String logicBranchFlag;
    /** 逻辑分支判断值，多值以 {@code |} 分隔。 */
    private String logicBranchValue;
    /** 分支匹配顺序，升序，先命中先生效；默认兜底分支应设为最大。 */
    private Integer logicBranchOrder;
    /** 备注。 */
    private String remark;
    /** 删除标识：0 未删除 / 1 已删除。 */
    private Integer delStatus;
    /** 软删除唯一化。 */
    private Long deletedSeq;

    /** 是否默认兜底分支（未配置动作标识）。 */
    public boolean isDefaultBranch() {
        return methodFlag == null || methodFlag.trim().isEmpty();
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

    public String getMethodFlag() {
        return methodFlag;
    }

    public void setMethodFlag(String methodFlag) {
        this.methodFlag = methodFlag;
    }

    public String getLogicBranchName() {
        return logicBranchName;
    }

    public void setLogicBranchName(String logicBranchName) {
        this.logicBranchName = logicBranchName;
    }

    public String getLogicBranchFlag() {
        return logicBranchFlag;
    }

    public void setLogicBranchFlag(String logicBranchFlag) {
        this.logicBranchFlag = logicBranchFlag;
    }

    public String getLogicBranchValue() {
        return logicBranchValue;
    }

    public void setLogicBranchValue(String logicBranchValue) {
        this.logicBranchValue = logicBranchValue;
    }

    public Integer getLogicBranchOrder() {
        return logicBranchOrder;
    }

    public void setLogicBranchOrder(Integer logicBranchOrder) {
        this.logicBranchOrder = logicBranchOrder;
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
}
