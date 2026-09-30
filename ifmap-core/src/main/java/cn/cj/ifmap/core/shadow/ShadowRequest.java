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
package cn.cj.ifmap.core.shadow;

import cn.cj.ifmap.core.model.IfmapRequest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 影子运行的一次业务请求：**同一份入参，同时喂给存量链路与新引擎**。
 *
 * <p>{@link #getLegacyRawResponse()} 是影子运行的关键字段：影子期推荐把存量链路拿到的
 * <b>原始响应报文</b>回填进来，新引擎就以它为 {@code mockResponse} 执行（不出网）——
 * 这样既能让两条链路看到"同一个资方响应"，避免对资方二次出网，也能把差异的归因
 * 锁定在"报文转换与判定"上，而不是"两次调用返回不同"。</p>
 *
 * <p>存量链路自己的请求对象（如 ECC 的 {@code ApiReq}）不进本类：它由宿主在
 * {@link ShadowTarget} 的适配器里通过闭包持有。</p>
 *
 * @author caijun
 */
public final class ShadowRequest {

    private final String tenantId;
    private final String bizId;
    private final String requestId;
    private final String operatorId;
    private final String interfaceNo;
    private final String busiNode;
    private final Map<String, Object> params;
    private final String legacyRawResponse;

    private ShadowRequest(Builder builder) {
        this.tenantId = builder.tenantId;
        this.bizId = builder.bizId;
        this.requestId = builder.requestId;
        this.operatorId = builder.operatorId;
        this.interfaceNo = builder.interfaceNo;
        this.busiNode = builder.busiNode;
        this.params = builder.params == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.params));
        this.legacyRawResponse = builder.legacyRawResponse;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getBizId() {
        return bizId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getOperatorId() {
        return operatorId;
    }

    /** 新引擎侧的接口编号（{@code ifmap_config.interface_no}）。 */
    public String getInterfaceNo() {
        return interfaceNo;
    }

    /** 业务节点（{@code ifmap_config.busi_node}）。 */
    public String getBusiNode() {
        return busiNode;
    }

    /** 业务参数（扁平 key-value，新引擎按 JsonPath 语义引用）。 */
    public Map<String, Object> getParams() {
        return params;
    }

    /** 存量链路拿到的原始响应报文；非空时新引擎不出网，直接用它当响应。 */
    public String getLegacyRawResponse() {
        return legacyRawResponse;
    }

    /** 转成新引擎需要的请求对象。 */
    public IfmapRequest toIfmapRequest() {
        return IfmapRequest.builder()
                .tenantId(tenantId)
                .bizId(bizId)
                .requestId(requestId)
                .operatorId(operatorId)
                .payload(params)
                .build();
    }

    @Override
    public String toString() {
        return "ShadowRequest{tenantId=" + tenantId + ", interfaceNo=" + interfaceNo
                + ", busiNode=" + busiNode + ", bizId=" + bizId
                + ", hasLegacyResponse=" + (legacyRawResponse != null && !legacyRawResponse.isEmpty()) + "}";
    }

    /** 建造者。 */
    public static final class Builder {

        private String tenantId;
        private String bizId;
        private String requestId;
        private String operatorId;
        private String interfaceNo;
        private String busiNode;
        private Map<String, Object> params;
        private String legacyRawResponse;

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder bizId(String bizId) {
            this.bizId = bizId;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder operatorId(String operatorId) {
            this.operatorId = operatorId;
            return this;
        }

        public Builder interfaceNo(String interfaceNo) {
            this.interfaceNo = interfaceNo;
            return this;
        }

        public Builder busiNode(String busiNode) {
            this.busiNode = busiNode;
            return this;
        }

        public Builder params(Map<String, Object> params) {
            this.params = params;
            return this;
        }

        /** 逐项追加业务参数。 */
        public Builder param(String key, Object value) {
            if (this.params == null) {
                this.params = new LinkedHashMap<String, Object>();
            }
            this.params.put(key, value);
            return this;
        }

        /** 存量链路的原始响应报文（影子链路不出网，直接复用它）。 */
        public Builder legacyRawResponse(String legacyRawResponse) {
            this.legacyRawResponse = legacyRawResponse;
            return this;
        }

        public ShadowRequest build() {
            if (interfaceNo == null || interfaceNo.trim().isEmpty()) {
                throw new IllegalArgumentException("interfaceNo 不能为空");
            }
            return new ShadowRequest(this);
        }
    }
}
