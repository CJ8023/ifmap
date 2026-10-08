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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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

    /** JSON 字符串字面量（含转义），group(1) 为未转义前的原文。 */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

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

    /**
     * 扫一遍模板里每个字符串值与 key，报出「会被静默忽略」的表达式错语法（设计 §4.4 U4-C）。
     *
     * <p>为什么要扫 key：{@code @array@} 形态把取值路径写在字段名里
     * （{@code "rows@array@$.items[*]" }），只扫 value 会漏。</p>
     *
     * <p>为什么不能只靠管理端的正则：正则从原文里「剜」路径，遇到 {@code ,} 就截断 ——
     * {@code @sum@$.a,$.b} 抽出 {@code $.a} 而且是合法路径，于是静默放行、线上求和成 0。
     * 这里改用 {@link DslExpressions} 的 DSL 感知切分，启动期与管理端共用同一套判定。</p>
     *
     * @return 问题描述（去重、保序；空表示没发现问题）
     */
    public static List<String> expressionProblems(String templateJson) {
        List<String> problems = new ArrayList<String>();
        if (templateJson == null || templateJson.length() == 0) {
            return problems;
        }
        Set<String> seen = new LinkedHashSet<String>();
        Matcher m = STRING_LITERAL.matcher(templateJson);
        while (m.find()) {
            for (String problem : DslExpressions.operandProblems(unescape(m.group(1)))) {
                if (seen.add(problem)) {
                    problems.add(problem);
                }
            }
        }
        return problems;
    }

    /** JSON 字符串的少量反转义（只处理会影响表达式判定的反斜杠与引号）。 */
    private static String unescape(String text) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                if (next == 'n') {
                    sb.append('\n');
                } else if (next == 't') {
                    sb.append('\t');
                } else {
                    sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 校验模板 JSON 文本非空。 */
    public static void requireNonEmpty(String templateJson, String what) {
        if (templateJson == null || templateJson.trim().length() == 0) {
            throw new IfmapConfigException(what + "不能为空");
        }
    }
}
