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
