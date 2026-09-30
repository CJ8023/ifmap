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
package cn.cj.ifmap.json.fastjson;

import com.alibaba.fastjson.JSONPath;

/**
 * 与 fastjson 的 JsonPath 方言有关的纯函数工具：语法体检、路径形状判断、多值段拆分。
 *
 * <p>存在的理由：fastjson 的 {@code JSONPath} 与 jayway 的 {@code json-path}（Jackson 实现用的那个）
 * 是两套方言，且 fastjson 的解析器比 JSON 规范宽松。本类把「哪里不同、我们怎么补」集中到一处，
 * 便于单独测试，也便于在文档里逐条说明差异。</p>
 *
 * @author caijun
 */
final class FastjsonPaths {

    private FastjsonPaths() {
    }

    static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /**
     * 路径语法是否合法（管理端「保存前校验」用，设计 §8.3）。
     *
     * <p>不能只靠 {@link JSONPath#compile(String)}：fastjson 对 {@code $[?(} 这类未闭合表达式
     * 编译期**不报错**，到求值时才抛 {@code StringIndexOutOfBoundsException}（1.2.84 实测）。
     * 所以这里先做「以 $ 开头 + 引号外括号配对」的语法体检，再交给 fastjson 编译。</p>
     */
    static boolean isValid(String path) {
        if (isBlank(path)) {
            return false;
        }
        String trimmed = path.trim();
        if (trimmed.charAt(0) != '$') {
            return false;
        }
        if (trimmed.endsWith(".")) {
            return false;
        }
        if (!isBalanced(trimmed)) {
            return false;
        }
        try {
            JSONPath.compile(trimmed);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 引号外的 {@code []} / {@code ()} 是否配对（{@code $[?(} 与 {@code $.a[} 都要被判为非法）。 */
    private static boolean isBalanced(String path) {
        int brackets = 0;
        int parens = 0;
        char quote = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            switch (c) {
                case '\'':
                case '"':
                    quote = c;
                    break;
                case '[':
                    brackets++;
                    break;
                case ']':
                    if (--brackets < 0) {
                        return false;
                    }
                    break;
                case '(':
                    parens++;
                    break;
                case ')':
                    if (--parens < 0) {
                        return false;
                    }
                    break;
                default:
                    break;
            }
        }
        return brackets == 0 && parens == 0 && quote == 0;
    }

    /**
     * 该路径是否是「多值路径」——即可能命中 0 个或多个结果（通配 {@code [*]}、属性通配 {@code .*}、
     * 过滤器 {@code [?()]}、切片 {@code [a:b]}、多选 {@code [0,2]}、递归下降 {@code ..}）。
     *
     * <p>用途：fastjson 对多值路径命中为空时返回 {@code null}，而 {@code JsonOps} 的契约是
     * 「绝对路径不存在 → null；通配/过滤器不命中 → 空列表」，靠本方法把前者与后者区分开。</p>
     */
    static boolean isMultiValue(String path) {
        char quote = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '*' || c == '?') {
                return true;
            }
            if (c == '.' && i + 1 < path.length() && path.charAt(i + 1) == '.') {
                return true;
            }
            if (c == '[') {
                int end = segmentEnd(path, i);
                if (end < 0) {
                    return false;
                }
                if (isMultiValueSegment(path.substring(i + 1, end))) {
                    return true;
                }
                i = end;
            }
        }
        return false;
    }

    /**
     * 若 {@code path} 的形状是「有界多值段 + 普通属性链」（如 {@code $.items[*].opt}、
     * {@code $.items[?(@.qty > 1)].sku}、{@code $.items[*].buyer.address.city}），
     * 返回该多值段及之前的前缀（{@code $.items[*]}）；否则返回 {@code null}
     * （表示不做 leafToNull 补偿，保持 fastjson 原生语义）。
     *
     * <p>只认这种形状：只有它才能确定「父节点集合」，从而把缺失叶子按父节点个数补成等长列表。
     * 递归下降 {@code $..sku} 的父集合本身不是有界的（各家实现给出的都不一样），故不补偿。</p>
     */
    static String boundedMultiValuePrefix(String path) {
        int end = lastMultiValueSegmentEnd(path);
        if (end < 0 || end + 1 >= path.length()) {
            return null;
        }
        String leaf = path.substring(end + 1);
        if (leaf.charAt(0) != '.' || isMultiValue(leaf)) {
            return null;
        }
        return path.substring(0, end + 1);
    }

    /** 最后一个「有界多值段」的结束下标（{@code ]} 的位置）；没有则返回 -1。 */
    private static int lastMultiValueSegmentEnd(String path) {
        int last = -1;
        char quote = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '[') {
                int end = segmentEnd(path, i);
                if (end < 0) {
                    return -1;
                }
                if (isMultiValueSegment(path.substring(i + 1, end))) {
                    last = end;
                }
                i = end;
            }
        }
        return last;
    }

    /** {@code start} 指向 {@code [}，返回与之配对的 {@code ]} 下标（引号/嵌套感知）；不配对返回 -1。 */
    private static int segmentEnd(String path, int start) {
        int depth = 0;
        char quote = 0;
        for (int i = start; i < path.length(); i++) {
            char c = path.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** 段内容（不含方括号）是否是多值段：{@code *} / {@code ?} / 切片 {@code :} / 多选 {@code ,}（引号内的不算）。 */
    private static boolean isMultiValueSegment(String segment) {
        char quote = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '*' || c == '?' || c == ':' || c == ',') {
                return true;
            }
        }
        return false;
    }
}
