package cn.cj.ifmap.admin.dto;

/**
 * 启用 / 停用请求体。
 *
 * @author caijun
 */
public class StatusChangeRequest {

    private Integer status;
    private Integer expectedVersion;
    private String reason;

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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
