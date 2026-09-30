package cn.cj.ifmap.core.model;

import cn.cj.ifmap.core.config.IfmapConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次出网调用的入参：交给宿主机的 {@code BankServiceGateway} 实现。
 *
 * @author caijun
 */
public final class BankCall {

    private final IfmapConfig config;
    private final IfmapRequest request;
    private final String requestJson;
    private final Map<String, Object> params;

    private BankCall(Builder builder) {
        this.config = builder.config;
        this.request = builder.request;
        this.requestJson = builder.requestJson;
        this.params = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.params));
    }

    public static BankCall of(IfmapConfig config, IfmapRequest request, String requestJson, Map<String, Object> params) {
        return builder().config(config).request(request).requestJson(requestJson).params(params).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public IfmapConfig getConfig() {
        return config;
    }

    public IfmapRequest getRequest() {
        return request;
    }

    /** 渲染 + 特殊处理后的请求报文（JSON 文本）。 */
    public String getRequestJson() {
        return requestJson;
    }

    /** 组包后的参数（未序列化形态，个别银行 SDK 需要结构化入参）。 */
    public Map<String, Object> getParams() {
        return params;
    }

    @Override
    public String toString() {
        return "BankCall{interfaceNo=" + (config == null ? null : config.getInterfaceNo())
                + ", bankCode=" + (config == null ? null : config.getBankCode()) + '}';
    }

    /** 构造器。 */
    public static final class Builder {

        private IfmapConfig config;
        private IfmapRequest request;
        private String requestJson;
        private final Map<String, Object> params = new LinkedHashMap<String, Object>();

        public Builder config(IfmapConfig config) {
            this.config = config;
            return this;
        }

        public Builder request(IfmapRequest request) {
            this.request = request;
            return this;
        }

        public Builder requestJson(String requestJson) {
            this.requestJson = requestJson;
            return this;
        }

        public Builder params(Map<String, Object> params) {
            if (params != null) {
                this.params.putAll(params);
            }
            return this;
        }

        public BankCall build() {
            return new BankCall(this);
        }
    }
}
