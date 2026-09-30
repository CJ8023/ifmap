package cn.cj.ifmap.admin.dto;

import cn.cj.ifmap.core.config.IfmapConfig;

/**
 * 校验请求体（{@code POST /configs/validate}）：不落库，只返回校验结论。
 *
 * @author caijun
 */
public class ValidateRequest {

    private IfmapConfig config;
    private Boolean isCreate = Boolean.TRUE;

    public IfmapConfig getConfig() {
        return config;
    }

    public void setConfig(IfmapConfig config) {
        this.config = config;
    }

    public Boolean getIsCreate() {
        return isCreate;
    }

    public void setIsCreate(Boolean isCreate) {
        this.isCreate = isCreate;
    }
}
