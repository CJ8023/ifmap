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
 * <p>两个维度别搞混（它们是<b>正交</b>的）：</p>
 * <ul>
 *   <li>{@code branchJudge} 决定<b>是否命中</b>：{@code {"op":"always"}} = 兜底分支，不参与常规匹配，
 *       仅当所有常规分支都未命中时生效（排在前面也不会抢命中）。</li>
 *   <li>{@code methodFlag} 为空表示该分支<b>不执行动作</b>（只占位，用于"命中即可、无需动作"的场景）。</li>
 * </ul>
 *
 * <p>常规分支的匹配顺序由 {@code logicBranchOrder} 升序决定（同值按 {@code keyId} 兜底）。</p>
 *
 * <p>规格语法与 {@code result_judge} 同源（同一解析器、同一深度上限、同一 {@code ignoreCase} 默认），
 * 额外支持两个分支专用算子 {@code strategy}（策略键）与 {@code judged}（比结果判定值）。</p>
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
    /** 动作标识（Action Key）；空=该分支不执行动作。 */
    private String methodFlag;
    /** 逻辑分支名称。 */
    private String logicBranchName;
    /**
     * 分支判定规格（JSON；<b>必填</b>）：兜底分支写 {@code {"op":"always"}}。
     *
     * <p>空值不是"兜底"，而是<b>非法配置</b> —— 运行期 fail-closed（{@code SPEC_INVALID}），
     * 不会静默落到兜底分支。</p>
     */
    private String branchJudge;
    /** 分支匹配顺序，升序，先命中先生效；兜底分支不受顺序影响（建议仍设为最大，便于阅读）。 */
    private Integer logicBranchOrder;
    /** 备注。 */
    private String remark;
    /** 删除标识：0 未删除 / 1 已删除。 */
    private Integer delStatus;
    /** 软删除唯一化。 */
    private Long deletedSeq;

    /** 该分支是否<b>不执行动作</b>（{@code method_flag} 为空）；与"是否兜底"无关，兜底看 {@code branchJudge}。 */
    public boolean isActionAbsent() {
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

    public String getBranchJudge() {
        return branchJudge;
    }

    public void setBranchJudge(String branchJudge) {
        this.branchJudge = branchJudge;
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
