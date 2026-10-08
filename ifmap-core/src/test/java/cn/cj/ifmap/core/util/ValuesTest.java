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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Values} 单测：报文形态的唯一出口。
 *
 * @author caijun
 */
class ValuesTest {

    // ---------------- stringify：标量 -> 文本 ----------------

    @Test
    @DisplayName("stringify：BigDecimal 保留源文字小数位（不出现科学计数法）")
    void stringifyKeepsScale() {
        assertEquals("10.00", Values.stringify(new BigDecimal("10.00")));
        assertEquals("0.100", Values.stringify(new BigDecimal("0.100")));
        assertEquals("12345678.90", Values.stringify(new BigDecimal("12345678.90")));
        // BigDecimal("1.0E+10") 的 scale 是 -10，toString() 会出科学计数法，必须走 toPlainString
        assertEquals("10000000000", Values.stringify(new BigDecimal("1.0E+10")));
        // BigDecimal 没有带符号的零
        assertEquals("0.00", Values.stringify(new BigDecimal("-0.00")));
    }

    @Test
    @DisplayName("stringify：Double/Float 去科学计数法（位数 = Double.toString 的位数，故尾零不可恢复）")
    void stringifyDouble() {
        assertEquals("12345678.9", Values.stringify(Double.valueOf(1.23456789E7)));
        assertEquals("10000000000", Values.stringify(Double.valueOf(1.0E10)));
        // Double.toString(1.0E-6) == "1.0E-6"，展开后就是 0.0000010（保留那个有效数字 0）
        assertEquals("0.0000010", Values.stringify(Double.valueOf(1.0E-6)));
        assertEquals("0.0", Values.stringify(Double.valueOf(-0.0d)));
        assertEquals("0.1", Values.stringify(Float.valueOf(0.1f)).substring(0, 3));
        assertTrue(Values.stringify(Float.valueOf(1.0E10f)).indexOf('E') < 0);
    }

    @Test
    @DisplayName("stringify：整数族 / 布尔 / null / 字符序列")
    void stringifyScalars() {
        assertEquals("100", Values.stringify(Integer.valueOf(100)));
        assertEquals("100", Values.stringify(Long.valueOf(100L)));
        assertEquals("5", Values.stringify(Short.valueOf((short) 5)));
        assertEquals("7", Values.stringify(Byte.valueOf((byte) 7)));
        assertEquals("12345678901234567890", Values.stringify(new BigInteger("12345678901234567890")));
        assertEquals("true", Values.stringify(Boolean.TRUE));
        assertEquals("false", Values.stringify(Boolean.FALSE));
        assertEquals("", Values.stringify(null));
        assertEquals("abc", Values.stringify("abc"));
        assertEquals("abc", Values.stringify(new StringBuilder("abc")));
        assertEquals("X", Values.stringify(Character.valueOf('X')));
    }

    @Test
    @DisplayName("stringify：容器一律抛异常（禁止 List.toString() 静默进报文）")
    void stringifyRejectsContainers() {
        IllegalArgumentException onList = assertThrows(IllegalArgumentException.class,
                () -> Values.stringify(Arrays.asList("01", "02")));
        assertTrue(onList.getMessage().contains("listJoin"), onList.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> Values.stringify(mapOf("a", "b")));
        // 容器判定必须在内层，不能被 CharSequence/Number 分支拦走
        assertThrows(IllegalArgumentException.class,
                () -> Values.stringify(new ArrayList<Object>()));
    }

    // ---------------- plainify：递归规整，类型不变 ----------------

    @Test
    @DisplayName("plainify：BigDecimal 原样（scale 已保），Double/Float 转成 plain 的 BigDecimal")
    void plainifyScalars() {
        BigDecimal dec = new BigDecimal("1.50");
        assertSame(dec, Values.plainify(dec));

        Object plain = Values.plainify(Double.valueOf(1.0E10));
        assertTrue(plain instanceof BigDecimal, "Double 要变成 BigDecimal（JSON 层仍是 number）");
        assertEquals("10000000000", ((BigDecimal) plain).toPlainString());

        assertNull(Values.plainify(null));
        assertSame("s", Values.plainify("s"));
        assertSame(Boolean.TRUE, Values.plainify(Boolean.TRUE));
        assertSame(Integer.valueOf(3), Values.plainify(Integer.valueOf(3)));
    }

    @Test
    @DisplayName("plainify：List/Map 递归、保序、且不改序列化类型")
    void plainifyRecurses() {
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("c", Double.valueOf(1.0E10));
        List<Object> items = new ArrayList<Object>(Arrays.asList(new BigDecimal("1.50"), Double.valueOf(2.25d), nested));

        Object out = Values.plainify(items);
        assertTrue(out instanceof List, "JSON 层类型不变：仍是数组");
        List<?> list = (List<?>) out;
        assertEquals(3, list.size());
        assertEquals("1.50", ((BigDecimal) list.get(0)).toPlainString());
        assertEquals("2.25", ((BigDecimal) list.get(1)).toPlainString());
        assertTrue(list.get(2) instanceof Map, "JSON 层类型不变：仍是对象");

        Map<?, ?> outMap = (Map<?, ?>) list.get(2);
        assertEquals("10000000000", ((BigDecimal) outMap.get("c")).toPlainString());

        // 保序：键顺序影响报文字节
        Map<String, Object> ordered = new LinkedHashMap<String, Object>();
        ordered.put("b", Double.valueOf(2.0d));
        ordered.put("a", Double.valueOf(1.0d));
        Map<?, ?> outOrdered = (Map<?, ?>) Values.plainify(ordered);
        assertEquals(Arrays.asList("b", "a"), new ArrayList<Object>(outOrdered.keySet()));
    }

    // ---------------- toPlainDecimal ----------------

    @Test
    @DisplayName("toPlainDecimal：BigDecimal 原样；Double 走 valueOf（不用 new BigDecimal(double)）")
    void toPlainDecimal() {
        BigDecimal dec = new BigDecimal("10.00");
        assertSame(dec, Values.toPlainDecimal(dec));
        assertEquals("10.0", Values.toPlainDecimal(Double.valueOf(10.0d)).toPlainString());
        // new BigDecimal(0.1d) 会得到 0.1000000000000000055511151231257827021181583404541015625
        assertEquals("0.1", Values.toPlainDecimal(Double.valueOf(0.1d)).toPlainString());
        assertEquals("5", Values.toPlainDecimal(Integer.valueOf(5)).toPlainString());
        assertThrows(IllegalArgumentException.class, () -> Values.toPlainDecimal(null));
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(k, v);
        return m;
    }
}
