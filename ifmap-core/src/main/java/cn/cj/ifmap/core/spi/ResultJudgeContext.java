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
package cn.cj.ifmap.core.spi;

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.json.JsonOps;

/**
 * 判定入参（不可变）：配置 + 响应文本 + JSON 能力。
 *
 * @author caijun
 */
public final class ResultJudgeContext {

    private final IfmapConfig config;
    private final String responseJson;
    private final JsonOps jsonOps;

    public ResultJudgeContext(IfmapConfig config, String responseJson, JsonOps jsonOps) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为 null");
        }
        if (jsonOps == null) {
            throw new IllegalArgumentException("jsonOps 不能为 null");
        }
        this.config = config;
        this.responseJson = responseJson;
        this.jsonOps = jsonOps;
    }

    public IfmapConfig getConfig() {
        return config;
    }

    /** 对方原始回参（可能为 null / 非 JSON）。 */
    public String getResponseJson() {
        return responseJson;
    }

    public JsonOps getJsonOps() {
        return jsonOps;
    }

    /** 本步骤所属租户（多租户判定器可能用得上）。 */
    public Long getTenantId() {
        return config.getTenantId();
    }
}
