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
