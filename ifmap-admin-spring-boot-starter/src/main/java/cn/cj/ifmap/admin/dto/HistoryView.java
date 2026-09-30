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
package cn.cj.ifmap.admin.dto;

import cn.cj.ifmap.core.model.IfmapConfigHistory;

import java.util.Date;

/**
 * 历史记录视图：元信息 + 已解析的快照/差异（前端直接渲染，不用二次解析 JSON 字符串）。
 *
 * @author caijun
 */
public class HistoryView {

    private final long keyId;
    private final long configKeyId;
    private final String interfaceNo;
    private final String changeType;
    private final String changeReason;
    private final String operatorId;
    private final String requestId;
    private final Date addTime;
    private final Object snapshot;
    private final Object diff;

    public HistoryView(IfmapConfigHistory row, Object snapshot, Object diff) {
        this.keyId = row.getKeyId() == null ? 0L : row.getKeyId();
        this.configKeyId = row.getConfigKeyId() == null ? 0L : row.getConfigKeyId();
        this.interfaceNo = row.getInterfaceNo();
        this.changeType = row.getChangeType();
        this.changeReason = row.getChangeReason();
        this.operatorId = row.getAddUserId();
        this.requestId = row.getAddRequestId();
        this.addTime = row.getAddTime();
        this.snapshot = snapshot;
        this.diff = diff;
    }

    public long getKeyId() {
        return keyId;
    }

    public long getConfigKeyId() {
        return configKeyId;
    }

    public String getInterfaceNo() {
        return interfaceNo;
    }

    public String getChangeType() {
        return changeType;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public String getRequestId() {
        return requestId;
    }

    public Date getAddTime() {
        return addTime;
    }

    public Object getSnapshot() {
        return snapshot;
    }

    public Object getDiff() {
        return diff;
    }
}
