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
package cn.cj.ifmap.core.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动期模板校验失败：模板里引用了未注册的规则（或规则名拼写错误）。
 *
 * <p>本异常是 ifmap 相对存量引擎的核心改进之一：把「运行期静默 null」
 * 提前成「启动失败并列出全部非法引用」，避免生产报文里出现
 * {@code "@FUN(farmatDate,...)"} 这样的字面量。</p>
 *
 * @author caijun
 */
public class StartupValidationException extends IfmapConfigException {

    private static final long serialVersionUID = 1L;

    /** 模板标识 -> 该模板中缺失的规则名（有序）。 */
    private final Map<String, java.util.Set<String>> missingRules;

    public StartupValidationException(String message, Map<String, java.util.Set<String>> missingRules) {
        super(message);
        Map<String, java.util.Set<String>> copy = new LinkedHashMap<String, java.util.Set<String>>();
        if (missingRules != null) {
            copy.putAll(missingRules);
        }
        this.missingRules = Collections.unmodifiableMap(copy);
    }

    public Map<String, java.util.Set<String>> getMissingRules() {
        return missingRules;
    }
}
