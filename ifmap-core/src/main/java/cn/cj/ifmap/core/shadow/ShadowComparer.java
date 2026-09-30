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
package cn.cj.ifmap.core.shadow;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 比对两条链路的归一化结果，产出**有序、可复现**的差异列表。
 *
 * @author caijun
 */
public final class ShadowComparer {

    private final ShadowOptions options;

    public ShadowComparer(ShadowOptions options) {
        this.options = options == null ? ShadowOptions.defaults() : options;
    }

    /**
     * 比对。返回顺序固定：先成败判定、再判定标识、最后按字段名升序的字段差异
     * （固定顺序才能让日志与报表直接 diff，而不是每次顺序都不一样）。
     */
    public List<ShadowFieldDiff> compare(ShadowOutcome legacy, ShadowOutcome shadow) {
        if (legacy == null || shadow == null) {
            throw new IllegalArgumentException("legacy / shadow 不能为空");
        }
        List<ShadowFieldDiff> diffs = new ArrayList<ShadowFieldDiff>();
        if (legacy.isSuccess() != shadow.isSuccess()) {
            diffs.add(ShadowFieldDiff.successMismatch(legacy.isSuccess(), shadow.isSuccess()));
        }
        if (!valuesEqual(legacy.getResultFlag(), shadow.getResultFlag())) {
            diffs.add(ShadowFieldDiff.resultFlagMismatch(legacy.getResultFlag(), shadow.getResultFlag()));
        }
        TreeSet<String> keys = new TreeSet<String>();
        keys.addAll(legacy.getFields().keySet());
        keys.addAll(shadow.getFields().keySet());
        for (String key : keys) {
            if (options.isIgnored(key)) {
                continue;
            }
            boolean inLegacy = legacy.getFields().containsKey(key);
            boolean inShadow = shadow.getFields().containsKey(key);
            if (!inLegacy) {
                diffs.add(ShadowFieldDiff.missingInLegacy(key, shadow.getFields().get(key)));
            } else if (!inShadow) {
                diffs.add(ShadowFieldDiff.missingInShadow(key, legacy.getFields().get(key)));
            } else if (!valuesEqual(legacy.getFields().get(key), shadow.getFields().get(key))) {
                diffs.add(ShadowFieldDiff.valueMismatch(key,
                        legacy.getFields().get(key), shadow.getFields().get(key)));
            }
        }
        return diffs;
    }

    /** 递归比较：嵌套 {@code Map} / {@code List} 里的数字同样按宽容规则比。 */
    private boolean valuesEqual(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Number && right instanceof Number) {
            return numbersEqual((Number) left, (Number) right);
        }
        if (left instanceof String && right instanceof String) {
            return stringsEqual((String) left, (String) right);
        }
        if (left instanceof Map && right instanceof Map) {
            return mapsEqual((Map<?, ?>) left, (Map<?, ?>) right);
        }
        if (left instanceof List && right instanceof List) {
            return listsEqual((List<?>) left, (List<?>) right);
        }
        return left.equals(right);
    }

    private boolean numbersEqual(Number left, Number right) {
        if (!options.isNumberTolerant()) {
            return left.equals(right);
        }
        BigDecimal leftDecimal = decimal(left);
        BigDecimal rightDecimal = decimal(right);
        if (leftDecimal == null || rightDecimal == null) {
            // NaN / Infinity 之类无法用 BigDecimal 表示的值：退回 double 比较
            return left.doubleValue() == right.doubleValue();
        }
        return leftDecimal.compareTo(rightDecimal) == 0;
    }

    private boolean stringsEqual(String left, String right) {
        return options.isTrimStrings() ? left.trim().equals(right.trim()) : left.equals(right);
    }

    private boolean mapsEqual(Map<?, ?> left, Map<?, ?> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (Map.Entry<?, ?> entry : left.entrySet()) {
            if (!right.containsKey(entry.getKey())) {
                return false;
            }
            if (!valuesEqual(entry.getValue(), right.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private boolean listsEqual(List<?> left, List<?> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!valuesEqual(left.get(i), right.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static BigDecimal decimal(Number value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(value.longValue());
        }
        double asDouble = value.doubleValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
            return null;
        }
        return new BigDecimal(value.toString());
    }
}
