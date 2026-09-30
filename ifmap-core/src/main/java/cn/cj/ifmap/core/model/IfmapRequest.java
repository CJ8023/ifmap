package cn.cj.ifmap.core.model;

import cn.cj.ifmap.core.exception.IfmapConfigException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 泛化请求上下文：替换存量引擎里渗透到业务代码的 ISF 类型（{@code ApiReq}）。
 *
 * <p>只承载"引擎需要的"东西：租户、操作人、请求号、业务号、请求头、业务负载、扩展属性。
 * 宿主机写一个约 30 行的适配器把 {@code ApiReq} 转成本类即可，业务代码不再感知任何 ISF 类型。</p>
 *
 * @author caijun
 */
public final class IfmapRequest {

    private final String tenantId;
    private final String operatorId;
    private final String requestId;
    private final String bizId;
    private final Map<String, String> headers;
    private final Map<String, Object> payload;
    private final Map<String, Object> attributes;

    private IfmapRequest(Builder builder) {
        this.tenantId = builder.tenantId;
        this.operatorId = builder.operatorId;
        this.requestId = builder.requestId;
        this.bizId = builder.bizId;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.headers));
        this.payload = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.payload));
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.attributes));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 以一份业务负载构造（纯 Java / 单测最常用）。 */
    public static IfmapRequest of(Map<String, Object> payload) {
        return builder().payload(payload).build();
    }

    /** 空请求（仅用于不关心上下文的场景）。 */
    public static IfmapRequest empty() {
        return builder().build();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getBizId() {
        return bizId;
    }

    /** 请求头（大小写不敏感读取）。 */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /** 业务负载（源报文的字段集合）。 */
    public Map<String, Object> getPayload() {
        return payload;
    }

    /** 扩展属性（如工作日历、自定义上下文），可供 {@code ConditionValueResolver} 使用。 */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /** 读请求头，大小写不敏感（HTTP 头本身就大小写不敏感）。 */
    public String getHeader(String name) {
        if (name == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /** 派生一份并追加属性（不改动原对象）。 */
    public IfmapRequest withAttribute(String key, Object value) {
        if (key == null || key.isEmpty()) {
            throw new IfmapConfigException("IfmapRequest 属性名不能为空");
        }
        Builder b = builder().tenantId(tenantId).operatorId(operatorId).requestId(requestId)
                .bizId(bizId).headers(headers).payload(payload).attributes(attributes);
        b.attribute(key, value);
        return b.build();
    }

    @Override
    public String toString() {
        return "IfmapRequest{tenantId='" + tenantId + "', requestId='" + requestId + "', bizId='" + bizId
                + "', payloadKeys=" + payload.keySet() + '}';
    }

    /** 构造器。 */
    public static final class Builder {

        private String tenantId;
        private String operatorId;
        private String requestId;
        private String bizId;
        private final Map<String, String> headers = new LinkedHashMap<String, String>();
        private final Map<String, Object> payload = new LinkedHashMap<String, Object>();
        private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder operatorId(String operatorId) {
            this.operatorId = operatorId;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder bizId(String bizId) {
            this.bizId = bizId;
            return this;
        }

        public Builder header(String name, String value) {
            if (name != null) {
                this.headers.put(name, value);
            }
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        public Builder payload(Map<String, Object> payload) {
            if (payload != null) {
                this.payload.putAll(payload);
            }
            return this;
        }

        public Builder put(String key, Object value) {
            if (key != null) {
                this.payload.put(key, value);
            }
            return this;
        }

        public Builder attribute(String key, Object value) {
            if (key != null) {
                this.attributes.put(key, value);
            }
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            if (attributes != null) {
                this.attributes.putAll(attributes);
            }
            return this;
        }

        public IfmapRequest build() {
            return new IfmapRequest(this);
        }
    }
}
