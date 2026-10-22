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
package cn.cj.ifmap.core.orchestrator.judgement;

import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonReadContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 结果判定规格（{@code result_judge} 列）的解析 / 校验 / 求值。
 *
 * <p>顶层只有三种形态，且 <b>互不退化</b>：① {@code {"op":"always"}} 恒真；
 * ② 空组合（{@code ""} / {@code {}} / {@code {"all":[]}}）<b>非法</b>；③ 条件组合。</p>
 *
 * <p>深度上限 3：顶层组算第 1 层，条件算叶子。</p>
 *
 * @author caijun
 */
public final class ResultJudgeSpec {

    /** 嵌套深度上限（与模板 DSL 口径一致）。 */
    public static final int MAX_DEPTH = 3;

    private final Node root;
    private final String raw;

    private ResultJudgeSpec(Node root, String raw) {
        this.root = root;
        this.raw = raw;
    }

    // ---------- 校验 ----------

    /**
     * 校验规格，返回违规清单（空 = 合法）。管理端 422 与契约自检共用本方法。
     */
    public static List<String> validate(String spec, JsonOps jsonOps) {
        List<String> errors = new ArrayList<String>();
        if (spec == null || spec.trim().isEmpty()) {
            errors.add("result_judge 不能为空（不需要判定的接口请显式写 {\"op\":\"always\"}）");
            return errors;
        }
        Object parsed;
        if (jsonOps == null || !jsonOps.isJson(spec)) {
            errors.add("result_judge 不是合法 JSON");
            return errors;
        }
        try {
            parsed = jsonOps.parse(spec);
        } catch (RuntimeException e) {
            errors.add("result_judge 解析失败：" + e.getMessage());
            return errors;
        }
        if (!(parsed instanceof Map)) {
            errors.add("result_judge 必须是 JSON 对象");
            return errors;
        }
        parseNode(castMap(parsed), 1, errors, jsonOps, true);
        return errors;
    }

    /** 解析规格；校验不过抛 {@link IllegalArgumentException}（调用方要么已校验、要么按 SPEC_INVALID 兜底）。 */
    public static ResultJudgeSpec parse(String spec, JsonOps jsonOps) {
        List<String> errors = validate(spec, jsonOps);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("result_judge 非法：" + errors.get(0));
        }
        return new ResultJudgeSpec(parseNode(castMap(jsonOps.parse(spec)), 1, null, jsonOps, true), spec);
    }

    /**
     * 递归解析一层规格：先判 always，再看 all/any 分组（深度上限 {@link #MAX_DEPTH}），
     * 其余当作单个条件。{@code collecting=true} 时把错误全收集起来（校验用），否则快速失败（解析用）。
     */
    private static Node parseNode(Map<String, Object> node, int depth, List<String> errors, JsonOps jsonOps,
                                  boolean top) {
        Object opValue = node.get("op");
        if (opValue != null && !node.containsKey("path")) {
            JudgeOp op = JudgeOp.fromName(String.valueOf(opValue));
            if (op != JudgeOp.ALWAYS) {
                add(errors, top ? "顶层 op 只支持 always（条件算子请写在 all/any 里）" : "条件缺少 path（JsonPath 字符串）",
                        errors != null);
                return null;
            }
            if (node.size() != 1) {
                add(errors, "always 不允许携带 path/value/ignoreCase 等其他键：" + node.keySet(), errors != null);
                return null;
            }
            return new AlwaysNode();
        }
        boolean hasAll = node.containsKey("all");
        boolean hasAny = node.containsKey("any");
        if ((hasAll || hasAny) && depth > MAX_DEPTH) {
            // 只有「组」计入深度：条件算叶子，不额外占一层
            add(errors, "result_judge 嵌套超过 " + MAX_DEPTH + " 层", errors != null);
            return null;
        }
        // all/any 只能出现一个：两者混写没有明确定义的优先级，宁可报错也不替你猜
        if (hasAll && hasAny) {
            add(errors, "all 与 any 不能同时出现", errors != null);
            return null;
        }
        if (hasAll || hasAny) {
            for (Object key : node.keySet()) {
                if (!"all".equals(key) && !"any".equals(key)) {
                    add(errors, "未知键 " + key, errors != null);
                    return null;
                }
            }
            String key = hasAll ? "all" : "any";
            Object group = node.get(key);
            if (!(group instanceof List)) {
                add(errors, key + " 必须是数组", errors != null);
                return null;
            }
            List<?> items = (List<?>) group;
            if (items.isEmpty()) {
                add(errors, key + " 不能为空数组（要表达恒真请写 {\"op\":\"always\"}）", errors != null);
                return null;
            }
            // 组内逐条递归解析；同名的组不能再嵌同名组（多余层级没有语义）
            List<Node> children = new ArrayList<Node>();
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                if (!(item instanceof Map)) {
                    add(errors, key + "[" + i + "] 必须是对象", errors != null);
                    return null;
                }
                Map<String, Object> child = castMap(item);
                if (child.containsKey(key)) {
                    add(errors, key + " 里不能再嵌套同名的 " + key + "（多余层级没有语义）", errors != null);
                    return null;
                }
                Node childNode = parseNode(child, depth + 1, errors, jsonOps, false);
                if (childNode == null) {
                    return null;
                }
                children.add(childNode);
            }
            return new GroupNode(key, children);
        }
        // 叶子条件
        if (top) {
            add(errors, "顶层必须是 {\"op\":\"always\"} 或 {\"all\":[...]}/{\"any\":[...]}", errors != null);
            return null;
        }
        return parseCondition(node, errors, jsonOps);
    }

    /** 解析单个条件：path 必填、op 必须可识别；报错时点名写错的那个词（管理端 422 只回第一条错误）。 */
    private static Node parseCondition(Map<String, Object> node, List<String> errors, JsonOps jsonOps) {
        Object pathValue = node.get("path");
        if (!(pathValue instanceof String) || ((String) pathValue).trim().isEmpty()) {
            add(errors, "条件缺少 path（JsonPath 字符串）", errors != null);
            return null;
        }
        String path = ((String) pathValue).trim();
        JudgeOp op = JudgeOp.fromName(node.get("op") == null ? null : String.valueOf(node.get("op")));
        if (op == null || op == JudgeOp.ALWAYS) {
            // 带上写错的那个词：管理端 422 只回第一条错误，不点名的话用户得自己猜哪一行不对
            Object rawOp = node.get("op");
            add(errors, path + " 的 op 非法：" + (rawOp == null ? "（缺失）" : rawOp)
                    + "（支持 eq/ne/in/notIn/exists/notBlank/regex）", errors != null);
            return null;
        }
        boolean ignoreCase = asBoolean(node.get("ignoreCase"), path, errors);
        Object rawValue = node.get("value");
        // value 的形态由 op 决定：in/notIn 要非空标量数组，其余要么要求非空 value、要么不接受 value
        List<String> values = null;
        String value = null;
        if (op.requiresValueList()) {
            if (!(rawValue instanceof List) || ((List<?>) rawValue).isEmpty()) {
                add(errors, path + " 的 op=" + op.name().toLowerCase() + " 要求非空数组 value", errors != null);
                return null;
            }
            values = new ArrayList<String>();
            for (Object item : (List<?>) rawValue) {
                if (item instanceof Map || item instanceof List) {
                    add(errors, path + " 的 value 只能是标量数组", errors != null);
                    return null;
                }
                values.add(item == null ? null : String.valueOf(item));
            }
        } else if (op.requiresValue()) {
            if (rawValue == null || String.valueOf(rawValue).isEmpty()) {
                add(errors, path + " 的 op=" + op.name().toLowerCase() + " 要求非空 value", errors != null);
                return null;
            }
            value = String.valueOf(rawValue);
        } else if (rawValue != null) {
            add(errors, path + " 的 op=" + op.name().toLowerCase() + " 不接受 value", errors != null);
            return null;
        }
        if (op == JudgeOp.REGEX) {
            try {
                Pattern.compile(value);
            } catch (PatternSyntaxException e) {
                add(errors, path + " 的 regex 非法：" + e.getDescription(), errors != null);
                return null;
            }
        }
        // 路径合法性交给当前 JsonOps 判断（Jackson/Fastjson 支持的 JsonPath 语法并不完全一致）
        if (jsonOps != null && !jsonOps.isValidPath(path)) {
            add(errors, path + " 不是合法 JsonPath", errors != null);
            return null;
        }
        return new ConditionNode(new JudgeCondition(path, op, value, ignoreCase, values));
    }

    // ---------- 求值 ----------

    public boolean isAlways() {
        return root instanceof AlwaysNode;
    }

    /** 规格摘要（写日志、契约自检报告用）。 */
    public String describe() {
        return isAlways() ? "always" : root.describe();
    }

    /** 原始规格文本。 */
    public String getRaw() {
        return raw;
    }

    /** 求值；{@code ctx} 由调用方在 try/catch 里建立（非 JSON 响应按 NOT_JSON 归类，不在这里抛）。 */
    public Evaluation evaluate(JsonReadContext ctx) {
        Evaluation evaluation = new Evaluation();
        // 求值本身不抛异常：非 JSON 响应由调用方（RuleSpecJudge）先挡掉，这里只处理结构
        boolean matched = root.matches(ctx, evaluation);
        evaluation.setMatched(matched);
        if (!matched && evaluation.getReason() == null) {
            evaluation.setReason("未命中判定条件：" + describe());
        }
        return evaluation;
    }

    /** 求值结果（actual / missedPath 供 fail_type 分类与日志）。 */
    public static final class Evaluation {
        private boolean matched;
        private String actual;
        private String failedActual;
        private String missedPath;
        private String reason;

        public boolean isMatched() {
            return matched;
        }

        private void setMatched(boolean matched) {
            this.matched = matched;
        }

        /** 判定读到的值：失败时优先取"真正没对上的那个字段"，成功时取首个条件的值。 */
        public String getActual() {
            return failedActual != null ? failedActual : actual;
        }

        /** 第一个未命中条件里取不到值的路径（非空 → FIELD_MISSING）。 */
        public String getMissedPath() {
            return missedPath;
        }

        public String getReason() {
            return reason;
        }

        private void setReason(String reason) {
            this.reason = reason;
        }

        /**
         * 记录一次条件求值。
         *
         * <p><b>命中也要记</b>：判定成功时的 {@code actual_value} 是分支匹配
         * （{@code logic_branch_value}）和日志的唯一依据，成功却不记就等于丢了这个值。</p>
         */
        // 只记第一条失败信息（后面全失败也没意义）、第一条命中值（成功时它就是 actual_value）
        private void record(JudgeCondition condition, boolean present, String actualValue, boolean hit) {
            if (!hit) {
                // 取不到值（present=false）才算 FIELD_MISSING；取到值但不符是 VALUE_MISMATCH
                if (this.missedPath == null && !present) {
                    this.missedPath = condition.getPath();
                }
                this.failedActual = actualValue;
            } else if (this.actual == null) {
                this.actual = actualValue;
            }
        }
    }

    // ---------- 节点 ----------

    private abstract static class Node {
        abstract boolean matches(JsonReadContext ctx, Evaluation evaluation);

        abstract String describe();
    }

    private static final class AlwaysNode extends Node {
        @Override
        boolean matches(JsonReadContext ctx, Evaluation evaluation) {
            return true;
        }

        @Override
        String describe() {
            return "always";
        }
    }

    private static final class GroupNode extends Node {
        private final String key;
        private final List<Node> children;

        GroupNode(String key, List<Node> children) {
            this.key = key;
            this.children = children;
        }

        @Override
        boolean matches(JsonReadContext ctx, Evaluation evaluation) {
            boolean any = "any".equals(key);
            for (Node child : children) {
                boolean hit = child.matches(ctx, evaluation);
                if (any && hit) {
                    return true;
                }
                if (!any && !hit) {
                    return false;
                }
            }
            return !any;
        }

        @Override
        String describe() {
            StringBuilder sb = new StringBuilder(key).append('[');
            for (int i = 0; i < children.size(); i++) {
                sb.append(i == 0 ? "" : " 且 ").append(children.get(i).describe());
            }
            return sb.append(']').toString();
        }
    }

    private static final class ConditionNode extends Node {
        private final JudgeCondition condition;

        ConditionNode(JudgeCondition condition) {
            this.condition = condition;
        }

        @Override
        boolean matches(JsonReadContext ctx, Evaluation evaluation) {
            Object raw;
            try {
                raw = ctx.read(condition.getPath());
            } catch (RuntimeException e) {
                raw = null;
            }
            boolean present = raw != null || readWithoutLeafPadding(ctx, condition.getPath());
            String actual = raw == null ? null : String.valueOf(raw);
            boolean hit = condition.getOp().matches(condition, present, actual);
            evaluation.record(condition, present, actual, hit);
            return hit;
        }

        /** {@code read(path)} 用不到 leafToNull，取不到时再确认一次"是否真的没有"。 */
        private boolean readWithoutLeafPadding(JsonReadContext ctx, String path) {
            Object value = ctx.read(path, false);
            return value != null;
        }

        @Override
        String describe() {
            return condition.describe();
        }
    }

    // ---------- 工具 ----------

    private static Map<String, Object> castMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    /** 布尔型字段（如 ignoreCase）：接受布尔或 "true"/"false" 字符串，其它类型记一条错误。 */
    private static boolean asBoolean(Object value, String path, List<String> errors) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        add(errors, path + " 的 ignoreCase 必须是布尔", errors != null);
        return false;
    }

    /** 只在收集模式（且 errors 非 null）下追加错误；解析模式靠异常快速失败。 */
    private static void add(List<String> errors, String message, boolean collecting) {
        if (collecting && errors != null) {
            errors.add(message);
        }
    }
}
