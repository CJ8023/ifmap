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
package cn.cj.ifmap.core.template;

import cn.cj.ifmap.core.exception.IfmapConfigException;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板静态扫描：抽取模板里引用到的规则名，供启动期校验。
 *
 * @author caijun
 */
public final class TemplateScanner {

    private static final Pattern FUN_PATTERN =
            Pattern.compile("@FUN\\(\\s*([A-Za-z_][A-Za-z0-9_]*)");

    private TemplateScanner() {
    }

    /** 抽取模板中 {@code @FUN(name,...)} 引用的全部规则名（去重、保序）。 */
    public static Set<String> ruleNames(String templateJson) {
        if (templateJson == null || templateJson.length() == 0) {
            return Collections.emptySet();
        }
        Set<String> names = new LinkedHashSet<String>();
        Matcher m = FUN_PATTERN.matcher(templateJson);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /** 校验模板 JSON 文本非空。 */
    public static void requireNonEmpty(String templateJson, String what) {
        if (templateJson == null || templateJson.trim().length() == 0) {
            throw new IfmapConfigException(what + "不能为空");
        }
    }
}
