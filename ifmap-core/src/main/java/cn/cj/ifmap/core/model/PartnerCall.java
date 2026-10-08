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

import cn.cj.ifmap.core.config.IfmapConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次出网调用的入参：交给宿主机的 {@code PartnerServiceGateway} 实现。
 *
 * @author caijun
 */
public final class PartnerCall {

    private final IfmapConfig config;
    private final IfmapRequest request;
    private final String requestJson;
    private final Map<String, Object> params;

    private PartnerCall(Builder builder) {
        this.config = builder.config;
        this.request = builder.request;
        this.requestJson = builder.requestJson;
        this.params = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.params));
    }

    public static PartnerCall of(IfmapConfig config, IfmapRequest request, String requestJson, Map<String, Object> params) {
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
        return "PartnerCall{interfaceNo=" + (config == null ? null : config.getInterfaceNo())
                + ", partnerCode=" + (config == null ? null : config.getPartnerCode()) + '}';
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

        public PartnerCall build() {
            return new PartnerCall(this);
        }
    }
}
