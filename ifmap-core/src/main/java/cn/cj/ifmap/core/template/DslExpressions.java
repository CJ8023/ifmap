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

import java.util.ArrayList;
import java.util.List;

/**
 * 模板 DSL 表达式的结构解析：一处实现，渲染期（{@link TemplateEngine}）与管理端校验共用。
 *
 * <p>需要共用是因为「校验用的切分规则」和「执行用的切分规则」一旦不一致，校验就会漏网：
 * 管理端曾用正则从原文里「剜」路径，遇到 {@code @sum@$.a,$.b} 会截断成 {@code $.a} 而判为合法，
 * 线上求和静默变 0。</p>
 *
 * <p>「顶层」= 括号深度 0。{@code @FUN(concat,$.a@sum@$.b)} 里的 {@code @sum@} 属于内层实参，
 * 不是本层的中缀运算符。</p>
 *
 * @author caijun
 */
public final class DslExpressions {

    /** 中缀运算符（按 {@link TemplateEngine#resolve} 的判定优先级排列）。 */
    private static final String[] INFIX_TOKENS = {
            TemplateConstants.SEPARATOR_AND,
            TemplateConstants.SEPARATOR_OR,
            TemplateConstants.SEPARATOR_CONCAT,
            TemplateConstants.SEPARATOR_APPEND,
            TemplateConstants.SEPARATOR_SUM
    };

    private DslExpressions() {
    }

    /** 是否为完整闭合的 {@code @FUN(...)} 调用。 */
    public static boolean isFun(String value) {
        return value != null
                && value.startsWith(TemplateConstants.FUN_PREFIX)
                && value.endsWith(TemplateConstants.FUN_SUFFIX);
    }

    /** 取规则名（{@code @FUN( concat , ... )} -> {@code concat}）；非函数调用返回空串。 */
    public static String funName(String value) {
        if (!isFun(value)) {
            return "";
        }
        List<String> parts = splitTopLevel(inner(value));
        return parts.isEmpty() ? "" : parts.get(0).trim();
    }

    /**
     * 取规则实参（顶层逗号切分，已 trim；空实参保留为空串占位，不能被丢掉）。
     */
    public static List<String> funArgs(String value) {
        if (!isFun(value)) {
            return new ArrayList<String>();
        }
        List<String> parts = splitTopLevel(inner(value));
        List<String> args = new ArrayList<String>(Math.max(0, parts.size() - 1));
        for (int i = 1; i < parts.size(); i++) {
            args.add(parts.get(i).trim());
        }
        return args;
    }

    /** 括号外的中缀运算符是否存在。 */
    public static boolean hasInfix(String value, String token) {
        return value != null && indexOfTopLevel(value, token) >= 0;
    }

    /** 按中缀运算符切分操作数并 trim（前缀写法 {@code @sum@$.a} 的第一个操作数为空串）。 */
    public static List<String> infixOperands(String value, String token) {
        List<String> parts = new ArrayList<String>();
        if (value == null) {
            return parts;
        }
        int depth = 0;
        int from = 0;
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0 && value.startsWith(token, i)) {
                parts.add(value.substring(from, i).trim());
                i += token.length();
                from = i;
                continue;
            }
            i++;
        }
        parts.add(value.substring(from).trim());
        return parts;
    }

    /** 是否为模板表达式（而不是字面量常量）。 */
    public static boolean isExpression(String value) {
        if (value == null || value.length() == 0) {
            return false;
        }
        if (isFun(value)) {
            return true;
        }
        for (String token : INFIX_TOKENS) {
            if (hasInfix(value, token)) {
                return true;
            }
        }
        return value.startsWith(TemplateConstants.PATH_PREFIX);
    }

    /** 是否为字面量常量（null / 空串既不是表达式也不是字面量）。 */
    public static boolean isLiteral(String value) {
        return value != null && value.length() > 0 && !isExpression(value);
    }

    /**
     * 抽出表达式里引用的全部 {@code $.path}（在逗号 / 括号 / 中缀运算符 / 空格处截断，
     * 支持 {@code [*]} / {@code [?(@.x > 1)]} / 切片等下标写法）。
     */
    public static List<String> extractPathTokens(String value) {
        List<String> out = new ArrayList<String>();
        if (value == null) {
            return out;
        }
        int i = 0;
        while (i < value.length()) {
            if (value.charAt(i) == '$' && i + 1 < value.length() && value.charAt(i + 1) == '.') {
                int j = i + 2;
                while (j < value.length()) {
                    char c = value.charAt(j);
                    if (c == '[') {
                        j = skipBrackets(value, j);
                    } else if (isPathChar(c)) {
                        j++;
                    } else {
                        break;
                    }
                }
                out.add(value.substring(i, j));
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * 表达式结构问题（供管理端保存校验与启动期校验使用）。
     *
     * <p>能识别的两类错语法都是「静默出错」型：{@code @FUN(concat,$.a} 未闭合会被当字面量原样进报文；
     * {@code @sum@$.a,$.b} 会被当成一个非法路径、按 0 求和。两者都不会抛异常。</p>
     *
     * @param value 表达式原文
     * @return 问题描述列表（空表示没发现问题）
     */
    public static List<String> operandProblems(String value) {
        List<String> problems = new ArrayList<String>();
        if (value == null || value.length() == 0) {
            return problems;
        }
        if (value.startsWith(TemplateConstants.FUN_PREFIX) && !value.endsWith(TemplateConstants.FUN_SUFFIX)) {
            problems.add("表达式 [" + value + "] 以 " + TemplateConstants.FUN_PREFIX
                    + " 开头但缺少右括号，会被当成字面量原样写进报文");
            return problems;
        }
        for (String token : INFIX_TOKENS) {
            if (!hasInfix(value, token)) {
                continue;
            }
            for (String operand : infixOperands(value, token)) {
                if (operand.length() == 0) {
                    continue;
                }
                if (indexOfTopLevel(operand, TemplateConstants.ARG_SEPARATOR) >= 0) {
                    problems.add(token + " 的操作数 [" + operand + "] 里出现逗号：中缀写法只接受 `$.a" + token
                            + "$.b`；多个操作数请用 @FUN(numSum,...) 或逐个串联");
                }
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------ 内部工具

    /** 去掉 {@code @FUN(} 与末尾 {@code )} 后的内容。 */
    public static String inner(String fun) {
        return fun.substring(TemplateConstants.FUN_PREFIX.length(),
                fun.length() - TemplateConstants.FUN_SUFFIX.length());
    }

    /** 按顶层逗号切分（支持实参里嵌套 {@code @FUN(...)}），不做 trim。 */
    static List<String> splitTopLevel(String inner) {
        List<String> parts = new ArrayList<String>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static int indexOfTopLevel(String value, String token) {
        int depth = 0;
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (depth == 0 && value.startsWith(token, i)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** {@code [} 开始的下标段：跳到配对的 {@code ]} 之后（内容原样保留，含过滤器里的括号与引号）。 */
    private static int skipBrackets(String value, int from) {
        int depth = 0;
        int i = from;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return value.length();
    }

    private static boolean isPathChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '*';
    }
}
