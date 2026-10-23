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

import java.util.List;

/**
 * 判定算子：<b>闭合集合</b>，出现未知值即规格非法（不静默降级）。
 *
 * <p>比较口径（D6/D7）：默认严格相等、不 trim 实际值；需要忽略大小写必须显式
 * {@code ignoreCase:true}；{@code notBlank} 只判空（{@code 0} / {@code false} 算非空）。</p>
 *
 * @author caijun
 */
public enum JudgeOp {

    EQ, NE, IN, NOT_IN, EXISTS, NOT_BLANK, REGEX, ALWAYS,

    /**
     * 分支专用：交 {@code @LogicBranch} 策略求值，{@code value} 是策略键（无 path）。
     *
     * @see #requiresPath()
     */
    STRATEGY,

    /**
     * 分支专用：与 {@code result_judge} 的 {@code actualValue} 做<b>归属</b>判定
     * （{@code actualValue ∈ value}，无 path）—— 即旧 {@code logic_branch_value} 的确切语义。
     *
     * <p>刻意不做成 {@code eq} 的别名：{@code ne} 在"取不到值"时反而命中，会把旧版
     * "没有判定值 ⇒ 不命中"翻成"命中"，分支就串了。</p>
     */
    JUDGED;

    /**
     * 词面 → 枚举（大小写不敏感、忽略下划线）；未知返回 null。
     *
     * <p>接线词面以文档/管理端为准的 camelCase（{@code notBlank}、{@code notIn}）为规范形态，
     * 同时兼容枚举名形态（{@code not_blank}、{@code NOT_IN}）—— 早期按枚举名写下的存量规格
     * 不能因为改名就被判成非法规格。</p>
     */
    public static JudgeOp fromName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim().replace("_", "");
        for (JudgeOp op : values()) {
            if (op.name().replace("_", "").equalsIgnoreCase(normalized)) {
                return op;
            }
        }
        return null;
    }

    /**
     * 该算子是否必须带 path。
     *
     * <p>分支专用算子读的是"别处的输入"（策略注册表 / 判定值），不是报文原文，
     * 因此没有 path；带了 path 反而是歧义（到底读哪个？），解析期直接判非法。</p>
     */
    public boolean requiresPath() {
        return this != STRATEGY && this != JUDGED;
    }

    /** 该算子是否必须带 value（标量）。 */
    public boolean requiresValue() {
        return this == EQ || this == NE || this == IN || this == NOT_IN || this == REGEX
                || this == STRATEGY;
    }

    /** 该算子是否要求 value 为数组。 */
    public boolean requiresValueList() {
        return this == IN || this == NOT_IN || this == JUDGED;
    }

    /**
     * 求值。
     *
     * @param present 路径是否取到了值（取到 {@code null} 也算 true）
     * @param actual  取到的值的字符串形态（{@code null} 表示无值）
     */
    public boolean matches(JudgeCondition condition, boolean present, String actual) {
        switch (this) {
            case EXISTS:
                return present;
            case NOT_BLANK:
                return actual != null && !actual.trim().isEmpty();
            case EQ:
                return actual != null && equal(condition, actual, condition.getValue());
            case NE:
                // 取不到值也算"不等于"——规格要表达"必须没有这个字段"请用 EXISTS 取反
                return actual == null || !equal(condition, actual, condition.getValue());
            case IN:
            case JUDGED:
                // 同一个"归属"语义：actual 是判定值（JUDGED 由调用方从 Judgement 取），取不到值即不命中
                return actual != null && contains(condition.getValues(), condition, actual);
            case NOT_IN:
                return actual == null || !contains(condition.getValues(), condition, actual);
            case REGEX:
                return actual != null && condition.compiledPattern().matcher(actual).matches();
            case STRATEGY:
                // 策略算子由 BranchJudge 在调用 matches 之前分派；走到这里说明调用方用错了算子
                return false;
            default:
                return false;
        }
    }

    /** 标量相等：{@code ignoreCase} 时忽略大小写，否则逐字符相等。 */
    private static boolean equal(JudgeCondition condition, String actual, String expected) {
        // 两边都是字符串比较（判定值已在解析阶段 String.valueOf 化）：数值比较不做精度转换，与存量口径一致
        return condition.isIgnoreCase() ? expected.equalsIgnoreCase(actual) : expected.equals(actual);
    }

    /** in/notIn 的公共判定：候选值里任意一个命中即 true（命中语义与 eq 共用 {@link #equal}）。 */
    private static boolean contains(List<String> values, JudgeCondition condition, String actual) {
        for (String candidate : values) {
            if (equal(condition, actual, candidate)) {
                return true;
            }
        }
        return false;
    }
}
