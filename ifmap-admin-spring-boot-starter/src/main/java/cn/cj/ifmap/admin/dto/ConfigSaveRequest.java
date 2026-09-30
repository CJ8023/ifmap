package cn.cj.ifmap.admin.dto;

import cn.cj.ifmap.core.config.IfmapConfig;

/**
 * 新增 / 修改配置的请求体。
 *
 * <p>{@code expectedVersion} 只在修改（PUT）时必需：乐观锁版本号，不匹配则返回 409。</p>
 *
 * @author caijun
 */
public class ConfigSaveRequest {

    private IfmapConfig config;
    private Integer expectedVersion;
    private String reason;

    public IfmapConfig getConfig() {
        return config;
    }

    public void setConfig(IfmapConfig config) {
        this.config = config;
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
