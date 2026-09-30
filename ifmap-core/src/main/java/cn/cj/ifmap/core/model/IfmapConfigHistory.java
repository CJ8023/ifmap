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
package cn.cj.ifmap.core.model;

import java.io.Serializable;

/**
 * 配置变更历史（对应 `ifmap_config_history`，设计 §6.5）。
 *
 * <p>{@code snapshot} 是变更后的配置快照（JSON 文本；{@code DELETE} 时存删除前快照）。
 * 快照的序列化/反序列化由管理端负责（引擎不依赖任何 JSON 库）。</p>
 *
 * @author caijun
 */
public class IfmapConfigHistory implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 新增配置。 */
    public static final String CREATE = "CREATE";
    /** 修改配置。 */
    public static final String UPDATE = "UPDATE";
    /** 逻辑删除。 */
    public static final String DELETE = "DELETE";
    /** 启用。 */
    public static final String ENABLE = "ENABLE";
    /** 停用。 */
    public static final String DISABLE = "DISABLE";

    private Long keyId;
    private Long configKeyId;
    private Long tenantId;
    private String interfaceNo;
    private String changeType;
    private String changeReason;
    private String snapshot;
    private String diff;
    private String addUserId;
    private java.util.Date addTime;
    private String addRequestId;

    public Long getKeyId() {
        return keyId;
    }

    public void setKeyId(Long keyId) {
        this.keyId = keyId;
    }

    public Long getConfigKeyId() {
        return configKeyId;
    }

    public void setConfigKeyId(Long configKeyId) {
        this.configKeyId = configKeyId;
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

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public void setChangeReason(String changeReason) {
        this.changeReason = changeReason;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(String snapshot) {
        this.snapshot = snapshot;
    }

    public String getDiff() {
        return diff;
    }

    public void setDiff(String diff) {
        this.diff = diff;
    }

    public String getAddUserId() {
        return addUserId;
    }

    public void setAddUserId(String addUserId) {
        this.addUserId = addUserId;
    }

    /** 创建时间（返回副本，避免调用方改到内部状态）。 */
    public java.util.Date getAddTime() {
        return addTime == null ? null : new java.util.Date(addTime.getTime());
    }

    /** 创建时间（存副本，避免调用方后续改动影响快照）。 */
    public void setAddTime(java.util.Date addTime) {
        this.addTime = addTime == null ? null : new java.util.Date(addTime.getTime());
    }

    public String getAddRequestId() {
        return addRequestId;
    }

    public void setAddRequestId(String addRequestId) {
        this.addRequestId = addRequestId;
    }

    @Override
    public String toString() {
        return "IfmapConfigHistory{keyId=" + keyId + ", configKeyId=" + configKeyId
                + ", changeType=" + changeType + ", interfaceNo=" + interfaceNo + '}';
    }
}
