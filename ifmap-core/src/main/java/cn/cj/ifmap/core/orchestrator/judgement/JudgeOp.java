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

    EQ, NE, IN, NOT_IN, EXISTS, NOT_BLANK, REGEX, ALWAYS;

    /** 词面 → 枚举（大小写不敏感）；未知返回 null。 */
    public static JudgeOp fromName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        for (JudgeOp op : values()) {
            if (op.name().equalsIgnoreCase(trimmed)) {
                return op;
            }
        }
        return null;
    }

    /** 该算子是否必须带 value。 */
    public boolean requiresValue() {
        return this == EQ || this == NE || this == IN || this == NOT_IN || this == REGEX;
    }

    /** 该算子是否要求 value 为数组。 */
    public boolean requiresValueList() {
        return this == IN || this == NOT_IN;
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
                return actual != null && contains(condition.getValues(), condition, actual);
            case NOT_IN:
                return actual == null || !contains(condition.getValues(), condition, actual);
            case REGEX:
                return actual != null && condition.compiledPattern().matcher(actual).matches();
            default:
                return false;
        }
    }

    private static boolean equal(JudgeCondition condition, String actual, String expected) {
        return condition.isIgnoreCase() ? expected.equalsIgnoreCase(actual) : expected.equals(actual);
    }

    private static boolean contains(List<String> values, JudgeCondition condition, String actual) {
        for (String candidate : values) {
            if (equal(condition, actual, candidate)) {
                return true;
            }
        }
        return false;
    }
}
