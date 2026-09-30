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

import java.util.List;

/**
 * 管理端统一错误体（HTTP 状态码 + 业务信息 + 可选校验明细）。
 *
 * @author caijun
 */
public class ApiError {

    private final String error;
    private final String type;
    private List<String> errors;
    private List<String> warnings;

    public ApiError(String error, String type) {
        this.error = error;
        this.type = type;
    }

    public ApiError(String error, String type, List<String> errors, List<String> warnings) {
        this.error = error;
        this.type = type;
        this.errors = errors;
        this.warnings = warnings;
    }

    /** 恒定 false：成功响应不会走这个错误体。保留 getter 是为了 JSON 里有 {@code "ok": false}。 */
    public boolean isOk() {
        return false;
    }

    public String getError() {
        return error;
    }

    public String getType() {
        return type;
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
