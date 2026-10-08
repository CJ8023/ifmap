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
package cn.cj.ifmap.core.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报文形态的统一出口：把 JDK 值规整成「可安全写进报文」的形态。
 *
 * <p>存在的理由：历史上「值 -> 文本」散落在 {@code TemplateEngine} / {@code Text} / {@code Coercions}
 * / {@code StringRules} 各处，各自用 {@code String.valueOf}，于是同一份模板配不同 JSON 实现会产出
 * 不同报文（尾零丢失、科学计数法、{@code List.toString()} 静默进报文）。现在只有这一处定义口径。</p>
 *
 * <p>三条口径：</p>
 * <ul>
 *   <li>小数 <b>保留源文字的小数位</b>（{@code 10.00} 就是 {@code "10.00"}，不是 {@code "10.0"}），
 *       且永不出现科学计数法；</li>
 *   <li>数组/对象在 JSON 层 <b>保持原类型</b>（数组还是数组、对象还是对象），但内部的数字同样去科学计数法；</li>
 *   <li>「把数组/对象转成文本」是明确禁止的 —— 必须由调用方先决定怎么展开，不允许静默 {@code List.toString()}。</li>
 * </ul>
 *
 * @author caijun
 */
public final class Values {

    private Values() {
    }

    /**
     * 标量 -> 报文文本。
     *
     * <p>{@code BigDecimal} 走 {@code toPlainString()}（<b>保留源文字小数位</b>）；
     * {@code Double}/{@code Float} 走 {@code BigDecimal.valueOf(d).toPlainString()} ——
     * 即「把 {@code Double.toString} 的 E 记法展开」，位数等于 {@code Double.toString} 的位数
     * （{@code 10.0} -> {@code "10.0"}、{@code 1.0E-6} -> {@code "0.0000010"}；
     * 尾零在 {@code Double} 里<b>本来就无法恢复</b>，故不承诺）；
     * 布尔输出 {@code "true"}/{@code "false"}；{@code null} 输出空串。</p>
     *
     * @param v 待转换值
     * @return 报文文本
     * @throws IllegalArgumentException 传入数组或对象（容器必须由调用方显式展开）
     */
    public static String stringify(Object v) {
        if (v instanceof Map || v instanceof Collection || v instanceof Object[]) {
            throw new IllegalArgumentException("不支持把数组/对象直接转成文本（实际类型 " + v.getClass().getName()
                    + "）：数组请用 listJoin / dictValArray 规则或 key 后缀 @array 列转行，对象请取具体字段");
        }
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).toPlainString();
        }
        if (v instanceof Double || v instanceof Float) {
            return toPlainDecimal((Number) v).toPlainString();
        }
        if (v instanceof Number || v instanceof Boolean || v instanceof CharSequence || v instanceof Character) {
            return v.toString();
        }
        return String.valueOf(v);
    }

    /**
     * 递归规整为「报文安全形态」：JSON 层类型不变（数组仍是数组、对象仍是对象），
     * 但内部的 {@code Double}/{@code Float} 换成保字面量的小数、且去科学计数法。
     *
     * @param v 任意值（可为 null）
     * @return 规整后的值；数组/对象返回新的保序结构，其它类型原样返回
     */
    public static Object plainify(Object v) {
        if (v == null || v instanceof BigDecimal) {
            return v;
        }
        if (v instanceof Double || v instanceof Float) {
            return toPlainDecimal((Number) v);
        }
        if (v instanceof List) {
            List<?> list = (List<?>) v;
            List<Object> out = new ArrayList<Object>(list.size());
            for (Object item : list) {
                out.add(plainify(item));
            }
            return out;
        }
        if (v instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) v;
            Map<Object, Object> out = new LinkedHashMap<Object, Object>(Math.max(4, map.size() * 2));
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(entry.getKey(), plainify(entry.getValue()));
            }
            return out;
        }
        return v;
    }

    /**
     * 数值 -> 十进制小数。
     *
     * <p>{@code BigDecimal} 原样返回（scale 已保留）；{@code Double} 走 {@code BigDecimal.valueOf}
     * （即 {@code new BigDecimal(Double.toString(d))}，<b>不是</b> {@code new BigDecimal(double)}，
     * 后者会带出 {@code 0.1000000000000000055511151231257827} 这种二进制误差尾巴）。</p>
     *
     * @param n 数值
     * @return 十进制小数
     * @throws IllegalArgumentException 入参为 null
     */
    public static BigDecimal toPlainDecimal(Number n) {
        if (n == null) {
            throw new IllegalArgumentException("数值不能为空");
        }
        if (n instanceof BigDecimal) {
            return (BigDecimal) n;
        }
        if (n instanceof Float) {
            return new BigDecimal(Float.toString((Float) n));
        }
        if (n instanceof Double) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(n.toString());
    }
}
