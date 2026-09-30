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
package cn.cj.ifmap.json.tck;

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonReadContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonOps} 实现一致性测试套件（TCK）。
 *
 * <p><b>怎么用</b>：实现自己的 {@code JsonOps}（Gson / fastjson / 自研）后，写一个空壳测试类继承本类：</p>
 *
 * <pre>{@code
 * class GsonJsonOpsConformanceTest extends JsonOpsConformanceTestBase {
 *     @Override
 *     protected JsonOps jsonOps() {
 *         return new GsonJsonOps();
 *     }
 * }
 * }</pre>
 *
 * <p>且测试依赖只需一项：</p>
 *
 * <pre>{@code
 * <dependency>
 *   <groupId>cn.cj</groupId>
 *   <artifactId>ifmap-json-tck</artifactId>
 *   <version>...</version>
 *   <scope>test</scope>
 * </dependency>
 * }</pre>
 *
 * <p><b>为什么要 TCK</b>：ifmap 的模板 DSL、`@FUN` 规则、编排器全靠 {@code JsonOps} 取值，
 * 而"取值语义"很容易在换实现时悄悄变掉 —— 例如路径不存在时返回 {@code null} 还是抛异常、
 * 数字解析成 {@code Integer} 还是 {@code Double}、是否把库自己的节点类型泄漏出去。
 * 这些差异在换库当刻不会报错，只会在某个资方接口上报文少一个字段。本套件把这些语义固化成断言。</p>
 *
 * @author caijun
 */
public abstract class JsonOpsConformanceTestBase {

    /** 被测实现（每次调用都返回同一实例也可）。 */
    protected abstract JsonOps jsonOps();

    private JsonOps ops() {
        JsonOps ops = jsonOps();
        assertNotNull(ops, "jsonOps() 不能返回 null");
        return ops;
    }

    // ---------------------------------------------------------------- 元信息

    @Test
    @DisplayName("name() 非空（用于日志与诊断，如 jackson / gson）")
    void nameIsNotBlank() {
        String name = ops().name();
        assertNotNull(name, "name() 不能为 null");
        assertFalse(name.trim().isEmpty(), "name() 不能为空白");
    }

    // ---------------------------------------------------------------- parse

    @Test
    @DisplayName("parse 对象 → JDK Map，且递归不含任何 JSON 库类型")
    void parseObjectGivesJdkMap() {
        Object value = ops().parse("{\"bizNo\":\"B1\",\"items\":[{\"sku\":\"S1\",\"qty\":2}],\"ok\":true}");

        Map<?, ?> map = assertInstanceOf(Map.class, value, "parse 对象必须返回 Map");
        assertEquals("B1", map.get("bizNo"));
        assertEquals(Boolean.TRUE, map.get("ok"));
        JdkNativeValues.assertJdkNative(value, "$");
    }

    @Test
    @DisplayName("parse 数组 → JDK List")
    void parseArrayGivesJdkList() {
        Object value = ops().parse("[1,\"two\",false,null]");

        List<?> list = assertInstanceOf(List.class, value, "parse 数组必须返回 List");
        assertEquals(4, list.size());
        assertNull(list.get(3), "JSON 的 null 元素必须映射为 Java null，不能是自定义的空节点对象");
        JdkNativeValues.assertJdkNative(value, "$");
    }

    @Test
    @DisplayName("parse 标量 → JDK String / Number / Boolean / null")
    void parseScalarsGivesJdkTypes() {
        assertEquals("x", ops().parse("\"x\""));
        assertEquals(Boolean.FALSE, ops().parse("false"));
        assertNull(ops().parse("null"));
        assertInstanceOf(Number.class, ops().parse("123"), "数字必须解析为 Number");
    }

    @Test
    @DisplayName("parse null / 空白 → null（不报错）")
    void parseNullOrBlankGivesNull() {
        assertNull(ops().parse(null));
        assertNull(ops().parse(""));
        assertNull(ops().parse("   "));
    }

    @Test
    @DisplayName("parse 非法 JSON → 抛运行时异常（不静默返回 null）")
    void parseInvalidJsonFails() {
        // 契约：非法 JSON 必须显式失败。存量引擎"静默取空值"的老路不能再走。
        assertThrows(RuntimeException.class, () -> ops().parse("{\"a\":}"));
    }

    @Test
    @DisplayName("大整数与小数：parse 后按数值比较仍成立（不因类型不同而丢精度）")
    void parseKeepsNumericValue() {
        Map<?, ?> map = assertInstanceOf(Map.class, ops().parse("{\"big\":123456789012,\"dec\":1.5}"));

        assertEquals(123456789012L, ((Number) map.get("big")).longValue());
        assertEquals(1.5d, ((Number) map.get("dec")).doubleValue(), 1e-9);
    }

    // ---------------------------------------------------------------- toJson

    @Test
    @DisplayName("toJson(null) → 字面量 null")
    void toJsonNullGivesNullLiteral() {
        assertEquals("null", ops().toJson(null));
    }

    @Test
    @DisplayName("toJson → parse 往返保持结构（含嵌套、null 值键）")
    void toJsonRoundTripKeepsStructure() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("bizNo", "B1");
        source.put("amount", 12.34d);
        source.put("count", 3);
        source.put("flag", true);
        source.put("nullable", null);
        source.put("items", listOf("a", "b"));
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("deep", listOf(1, 2));
        source.put("nested", nested);

        Object parsed = ops().parse(ops().toJson(source));

        Map<?, ?> map = assertInstanceOf(Map.class, parsed);
        assertTrue(map.containsKey("nullable"), "显式 null 值的键在往返后必须仍存在（不能把键删掉）");
        assertNull(map.get("nullable"));
        assertEquals(12.34d, ((Number) map.get("amount")).doubleValue(), 1e-9);
        assertEquals(3, ((Number) map.get("count")).intValue());
        assertEquals(listOf("a", "b"), map.get("items"));
        JdkNativeValues.assertJdkNative(parsed, "$");
    }

    @Test
    @DisplayName("toJson 正确转义引号 / 反斜杠 / 换行 / 制表符 / 中文 / emoji，且能原样解析回来")
    void toJsonEscapesSpecials() {
        List<String> raw = new ArrayList<String>();
        raw.add("he said \"hi\"");
        raw.add("back\\slash");
        raw.add("line1\nline2\ttabbed");
        raw.add("中文与 emoji 🙂");

        Object parsed = ops().parse(ops().toJson(raw));

        assertEquals(raw, assertInstanceOf(List.class, parsed), "转义后的文本必须能原样解析回来");
    }

    // ---------------------------------------------------------------- isJson

    @Test
    @DisplayName("isJson：合法为 true；空白 / 非法为 false（不抛异常）")
    void isJsonDetectsValidity() {
        assertTrue(ops().isJson("{\"a\":1}"), "对象应判定为合法");
        assertTrue(ops().isJson("[1,2]"));
        assertTrue(ops().isJson("\"text\""));
        assertFalse(ops().isJson("{\"a\":}"), "语法错误的 JSON 必须判为 false");
        assertFalse(ops().isJson(""), "空白必须判为 false");
        assertFalse(ops().isJson("   "));
        assertFalse(ops().isJson(null), "null 必须判为 false");
    }

    // ---------------------------------------------------------------- isValidPath

    @Test
    @DisplayName("isValidPath：合法路径 true；空白 / 非法表达式 false（不抛异常）")
    void isValidPathDetectsSyntax() {
        assertTrue(ops().isValidPath("$.a.b"));
        assertTrue(ops().isValidPath("$.items[0].sku"));
        assertTrue(ops().isValidPath("$.items[*].sku"));
        assertTrue(ops().isValidPath("$..sku"));
        assertTrue(ops().isValidPath("$.items[?(@.qty > 1)].sku"));
        assertFalse(ops().isValidPath(""), "空白必须判为 false");
        assertFalse(ops().isValidPath("   "));
        assertFalse(ops().isValidPath(null), "null 必须判为 false");
        assertFalse(ops().isValidPath("$[?("), "括号未闭合的表达式必须判为 false");
    }

    // ---------------------------------------------------------------- readContext

    @Test
    @DisplayName("readContext：根路径 / 嵌套对象")
    void readContextNestedObject() {
        JsonReadContext ctx = readContext(ORDER_JSON);

        assertEquals("B1", ctx.read("$.bizNo"));
        assertEquals("S1", ctx.read("$.buyer.sku"));
        assertEquals("深层", ctx.read("$.buyer.address.city"));
    }

    @Test
    @DisplayName("readContext：数组下标与切片")
    void readContextArrayIndexAndSlice() {
        JsonReadContext ctx = readContext(ORDER_JSON);

        assertEquals("P1", ctx.read("$.items[0].sku"));
        assertEquals("P2", ctx.read("$.items[1].sku"));
        assertEquals(listOf("P1", "P2", "P3"), ctx.read("$.items[0:3].sku"));
    }

    @Test
    @DisplayName("readContext：通配符 * 汇总成列表")
    void readContextWildcard() {
        JsonReadContext ctx = readContext(ORDER_JSON);

        assertEquals(listOf("P1", "P2", "P3"), ctx.read("$.items[*].sku"));
    }

    @Test
    @DisplayName("readContext：递归下降 ..")
    void readContextRecursiveDescent() {
        JsonReadContext ctx = readContext(ORDER_JSON);

        assertEquals(listOf("S1", "P1", "P2", "P3"), ctx.read("$..sku"));
    }

    @Test
    @DisplayName("readContext：过滤器 [?(@.x > 1)]（存量引擎依赖的路径语义，必须保留）")
    void readContextFilter() {
        JsonReadContext ctx = readContext(ORDER_JSON);

        assertEquals(listOf("P2", "P3"), ctx.read("$.items[?(@.qty > 1)].sku"));
        assertEquals(listOf("P3"), ctx.read("$.items[?(@.sku == 'P3')].sku"));
    }

    @Test
    @DisplayName("readContext：路径不存在 → null（唯一允许的\"正常空值\"；过滤器不命中则是空列表，两者语义不同）")
    void readMissingPathGivesNull() {
        JsonReadContext ctx = readContext(ORDER_JSON);

        assertNull(ctx.read("$.notExists"));
        assertNull(ctx.read("$.buyer.notExists"));
        assertNull(ctx.read("$.items[9].sku"));
        assertNull(ctx.read("$.items[0].notExists"));
        assertNull(ctx.read(""), "空路径返回 null");
        assertNull(ctx.read(null), "null 路径返回 null");

        // 区分清楚：绝对路径不存在 = null；过滤器/通配不命中 = 空列表。
        // 列表类规则（dictValArray / listJoin）拿到的就是空列表，绝不能为此抛异常打断编排。
        assertEquals(listOf(), ctx.read("$.items[?(@.qty > 999)].sku"));
        assertEquals(listOf(), ctx.read("$.items[?(@.qty > 999)].sku", true));
        assertEquals(listOf(), ctx.read("$[*].notExists"));
    }

    @Test
    @DisplayName("readContext：leafToNull=true 时通配路径为缺失叶子补 null（保证列表等长、下标对齐）")
    void readContextLeafToNullAlignsLeaves() {
        JsonReadContext ctx = readContext(ORDER_JSON);

        // items[1] 没有 opt 字段：strict 会把它"跳过"，leafToNull 会补 null 占位
        assertEquals(listOf("A"), ctx.read("$.items[*].opt"));
        assertEquals(listOf("A", null, null), ctx.read("$.items[*].opt", true));
    }

    @Test
    @DisplayName("readContext：null 源报文按空对象处理（不抛异常）")
    void readContextOnNullJsonBehavesAsEmptyObject() {
        JsonReadContext ctx = ops().readContext(null);

        assertNull(ctx.read("$.anything"));
    }

    @Test
    @DisplayName("readContext：数字按 Number 取回，字符串按 String 取回（不做隐式类型转换）")
    void readContextKeepsTypes() {
        JsonReadContext ctx = readContext(ORDER_JSON);

        assertInstanceOf(Number.class, ctx.read("$.items[0].qty"), "数字必须是 Number");
        assertEquals(1, ((Number) ctx.read("$.items[0].qty")).intValue());
        assertEquals(2, ((Number) ctx.read("$.items[1].qty")).intValue());
        assertEquals("2", ctx.read("$.items[0].qtyText"), "JSON 字符串不能被当成数字");
    }

    @Test
    @DisplayName("readContext：同一上下文可重复取值且结果稳定（解析一次、多次取数）")
    void readContextIsReusable() {
        JsonReadContext ctx = readContext(ORDER_JSON);

        for (int i = 0; i < 3; i++) {
            assertEquals("B1", ctx.read("$.bizNo"));
            assertEquals(listOf("P1", "P2", "P3"), ctx.read("$.items[*].sku"));
            assertEquals(listOf("P1", "P2", "P3"), ctx.read("$.items[*].sku", true));
        }
    }

    @Test
    @DisplayName("readContext：路径表达式非法 → 显式失败（不能静默当成\"路径不存在\"）")
    void readContextInvalidPathExpressionFails() {
        JsonReadContext ctx = readContext(ORDER_JSON);

        // 契约来源：存量引擎因为把任何取值异常都吞成 null，导致配置写错后报文只丢失字段、不报错，
        // 排查成本极高。ifmap 要求：只有 PathNotFound 算正常空值，其余一律显式抛 IfmapConfigException。
        assertThrows(IfmapConfigException.class, () -> ctx.read("$[?("), "未闭合的过滤器表达式必须显式失败");
    }

    @Test
    @DisplayName("readContext：数组作为根节点")
    void readContextArrayRoot() {
        JsonReadContext ctx = ops().readContext("[{\"sku\":\"P1\"},{\"sku\":\"P2\"}]");

        assertEquals("P2", ctx.read("$[1].sku"));
        assertEquals(listOf("P1", "P2"), ctx.read("$[*].sku"));
    }

    // ---------------------------------------------------------------- 测试数据

    private static final String ORDER_JSON = "{"
            + "\"bizNo\":\"B1\","
            + "\"buyer\":{\"sku\":\"S1\",\"address\":{\"city\":\"深层\"}},"
            + "\"items\":["
            + "  {\"sku\":\"P1\",\"qty\":1,\"opt\":\"A\",\"qtyText\":\"2\"},"
            + "  {\"sku\":\"P2\",\"qty\":2,\"qtyText\":\"3\"},"
            + "  {\"sku\":\"P3\",\"qty\":3,\"qtyText\":\"4\"}"
            + "]}";

    private JsonReadContext readContext(String json) {
        return ops().readContext(json);
    }

    private static List<Object> listOf(Object... values) {
        List<Object> list = new ArrayList<Object>();
        for (Object value : values) {
            list.add(value);
        }
        return list;
    }
}
