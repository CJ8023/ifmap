package cn.cj.ifmap.admin.dto;

/**
 * 回滚请求体。
 *
 * @author caijun
 */
public class RollbackRequest {

    private Long historyId;
    private Integer expectedVersion;
    private String reason;

    public Long getHistoryId() {
        return historyId;
    }

    public void setHistoryId(Long historyId) {
        this.historyId = historyId;
    }

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Integer expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
