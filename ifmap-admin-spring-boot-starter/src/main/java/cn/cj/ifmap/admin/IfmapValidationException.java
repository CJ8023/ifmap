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
package cn.cj.ifmap.admin;

import cn.cj.ifmap.core.exception.IfmapConfigException;

/**
 * 保存前校验未通过：携带完整违规清单，管理端直接回给前端做定位（不落库）。
 *
 * @author caijun
 */
public class IfmapValidationException extends IfmapConfigException {

    private static final long serialVersionUID = 1L;

    private final transient ValidationResult result;

    public IfmapValidationException(ValidationResult result) {
        super(buildMessage(result));
        this.result = result;
    }

    public ValidationResult getResult() {
        return result;
    }

    private static String buildMessage(ValidationResult result) {
        if (result == null || result.getErrors().isEmpty()) {
            return "配置校验未通过";
        }
        return "配置校验未通过：" + String.join("；", result.getErrors());
    }
}
