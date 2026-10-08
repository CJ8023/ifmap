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
package cn.cj.ifmap.core.rule;

import cn.cj.ifmap.core.util.Values;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 实参到形参的强转判定。
 *
 * <p>相对存量引擎的改进：存量实现用 {@code params[i].getClass()} 做<b>精确类型</b>匹配，
 * 只要类型不完全一致就找不到方法并静默返回 null。ifmap 改为「可强转即匹配」，
 * 并对多个候选按「精确匹配优先」打分消歧。</p>
 *
 * @author caijun
 */
final class Coercions {

    private Coercions() {
    }

    /** 装箱类型。 */
    static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) {
            return t;
        }
        if (t == int.class) {
            return Integer.class;
        }
        if (t == long.class) {
            return Long.class;
        }
        if (t == double.class) {
            return Double.class;
        }
        if (t == float.class) {
            return Float.class;
        }
        if (t == short.class) {
            return Short.class;
        }
        if (t == byte.class) {
            return Byte.class;
        }
        if (t == boolean.class) {
            return Boolean.class;
        }
        if (t == char.class) {
            return Character.class;
        }
        return t;
    }

    /** 能否强转。 */
    static boolean canCoerce(Object src, Class<?> target) {
        Class<?> t = box(target);
        if (src == null) {
            return !target.isPrimitive();
        }
        if (t == Object.class || t.isInstance(src)) {
            return true;
        }
        if (t == String.class) {
            // 只接受标量 -> String，避免把 List/Map 悄悄串化
            return src instanceof CharSequence || src instanceof Number
                    || src instanceof Boolean || src instanceof Character;
        }
        if (t == Integer.class || t == Long.class || t == Double.class
                || t == Float.class || t == Short.class || t == BigDecimal.class) {
            if (src instanceof Number) {
                return true;
            }
            return src instanceof CharSequence && isNumeric(String.valueOf(src));
        }
        if (t == Boolean.class) {
            if (src instanceof Boolean) {
                return true;
            }
            if (src instanceof CharSequence) {
                String s = String.valueOf(src).trim().toLowerCase();
                return "true".equals(s) || "false".equals(s) || "1".equals(s) || "0".equals(s)
                        || "y".equals(s) || "n".equals(s);
            }
            return false;
        }
        if (t == List.class) {
            return src instanceof List;
        }
        if (t == Map.class) {
            return src instanceof Map;
        }
        if (t == Character.class) {
            return src instanceof CharSequence && ((CharSequence) src).length() == 1;
        }
        return false;
    }

    /** 精确匹配（不做任何转换）。 */
    static boolean isExact(Object src, Class<?> target) {
        if (src == null) {
            return false;
        }
        return box(target).isInstance(src);
    }

    /** 执行强转（调用前必须先通过 {@link #canCoerce}）。 */
    @SuppressWarnings("unchecked")
    static Object coerce(Object src, Class<?> target) {
        Class<?> t = box(target);
        if (src == null || t == Object.class || t.isInstance(src)) {
            return t == Object.class && src == null ? null : src;
        }
        if (t == String.class) {
            // 与 TemplateEngine 同一口径：小数字面量不出科学计数法（否则 1.0E10 会进报文）
            return Values.stringify(src);
        }
        if (t == Integer.class) {
            return src instanceof Number ? ((Number) src).intValue() : new BigDecimal(String.valueOf(src).trim()).intValue();
        }
        if (t == Long.class) {
            return src instanceof Number ? ((Number) src).longValue() : new BigDecimal(String.valueOf(src).trim()).longValue();
        }
        if (t == Double.class) {
            return src instanceof Number ? ((Number) src).doubleValue() : new BigDecimal(String.valueOf(src).trim()).doubleValue();
        }
        if (t == Float.class) {
            return src instanceof Number ? ((Number) src).floatValue() : new BigDecimal(String.valueOf(src).trim()).floatValue();
        }
        if (t == Short.class) {
            return src instanceof Number ? ((Number) src).shortValue() : new BigDecimal(String.valueOf(src).trim()).shortValue();
        }
        if (t == BigDecimal.class) {
            return src instanceof BigDecimal ? src : src instanceof Number
                    ? new BigDecimal(src.toString()) : new BigDecimal(String.valueOf(src).trim());
        }
        if (t == Boolean.class) {
            if (src instanceof Boolean) {
                return src;
            }
            String s = String.valueOf(src).trim().toLowerCase();
            return "true".equals(s) || "1".equals(s) || "y".equals(s);
        }
        if (t == Character.class) {
            return Character.valueOf(String.valueOf(src).charAt(0));
        }
        if (t == List.class || t == Map.class) {
            return src;
        }
        return (Object) src;
    }

    private static boolean isNumeric(String s) {
        String v = s.trim();
        if (v.length() == 0) {
            return false;
        }
        try {
            new BigDecimal(v);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
