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
