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

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonOpsHolder;
import cn.cj.ifmap.core.json.JsonReadContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FastjsonJsonOps} 实现特有行为的测试：SPI 发现、类型不泄漏、
 * 以及**与 Jackson 实现的已知差异**（差异必须被断言钉住，否则哪天变了没人发现）。
 *
 * <p>通用契约（24 条）在 {@link FastjsonJsonOpsConformanceTest} 里，本类只测差异部分。</p>
 *
 * @author caijun
 */
class FastjsonJsonOpsTest {

    private static final String ORDER_JSON = "{"
            + "\"bizNo\":\"B1\","
            + "\"buyer\":{\"sku\":\"S1\"},"
            + "\"items\":["
            + "  {\"sku\":\"P1\",\"qty\":1,\"opt\":\"A\"},"
            + "  {\"sku\":\"P2\",\"qty\":2},"
            + "  {\"sku\":\"P3\",\"qty\":3}"
            + "]}";

    private final FastjsonJsonOps jsonOps = new FastjsonJsonOps();

    @Test
    @DisplayName("SPI 自动发现：classpath 上只有本模块时，发现的实现就是 fastjson")
    void serviceLoaderDiscovery() {
        JsonOps discovered = JsonOpsHolder.get();

        assertNotNull(discovered);
        assertEquals("fastjson", discovered.name());
        assertTrue(discovered instanceof FastjsonJsonOps);
    }

    @Test
    @DisplayName("name() = fastjson（日志与诊断用）")
    void nameIsFastjson() {
        assertEquals("fastjson", jsonOps.name());
    }

    @Test
    @DisplayName("parse 结果不含任何 fastjson 类型，且键序与原文一致")
    void parseReturnsPureJdkTypes() {
        Object parsed = jsonOps.parse(ORDER_JSON);

        assertTrue(parsed instanceof LinkedHashMap, "对象必须是 JDK LinkedHashMap，而不是 JSONObject");
        Map<?, ?> map = (Map<?, ?>) parsed;
        assertEquals(Arrays.asList("bizNo", "buyer", "items"), new ArrayList<Object>(map.keySet()),
                "键序必须与原文一致（模板渲染/快照往返依赖它）");
        assertTrue(map.get("items") instanceof ArrayList, "数组必须是 JDK ArrayList，而不是 JSONArray");
        assertNoFastjsonType(parsed, "$");
    }

    @Test
    @DisplayName("含 @type 的报文：显式失败，绝不静默变成空对象（1.2.84 加固会解析成 null）")
    void autoTypeDocumentFailsExplicitly() {
        String autoTypeJson = "{\"@type\":\"java.lang.Class\",\"val\":\"x\"}";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> jsonOps.parse(autoTypeJson));
        assertTrue(ex.getMessage().contains("JSON 解析失败"), "异常信息应说明是解析失败：" + ex.getMessage());
        assertEquals("null", jsonOps.toJson(null), "只有字面量 null 才允许解析/序列化成 null");

        JsonReadContext ctx = jsonOps.readContext(autoTypeJson);
        assertThrows(IfmapConfigException.class, () -> ctx.read("$.val"));

        assertFalse(jsonOps.isJson("{\"a\":}"));
    }

    @Test
    @DisplayName("已知差异①：对象根节点上的 [*] 不展开为各 value（fastjson 原生语义，模板请写具体字段或 $.field[*]）")
    void objectRootWildcardIsNotBroadcast() {
        JsonReadContext ctx = jsonOps.readContext(ORDER_JSON);

        // jackson 实现（jayway）会返回 ["S1"]；fastjson 的 $[*] 作用在对象上返回对象本身，
        // 因此取叶子取不到 —— 契约上退化为「通配不命中 → 空列表」。
        assertEquals(new ArrayList<Object>(), ctx.read("$[*].sku"));
        // 数组上的通配不受影响（这是配置里真正会用的写法）
        assertEquals(Arrays.asList("P1", "P2", "P3"), ctx.read("$.items[*].sku"));
    }

    @Test
    @DisplayName("已知差异②：递归下降 $..x + leafToNull 不做补 null（父集合无界，各家实现顺序不同）")
    void recursiveDescentWithLeafToNullIsNotPadded() {
        JsonReadContext ctx = jsonOps.readContext(ORDER_JSON);

        assertEquals(Arrays.asList("S1", "P1", "P2", "P3"), ctx.read("$..sku"));
        assertEquals(Arrays.asList("S1", "P1", "P2", "P3"), ctx.read("$..sku", true), "不补 null，保持原样");
    }

    @Test
    @DisplayName("已知差异③：isJson 沿用 fastjson 的宽松语法（单引号 / 无引号键 / 尾部逗号都算合法）")
    void isJsonIsLenientLikeFastjson() {
        assertTrue(jsonOps.isJson("{\"a\":1}"));
        assertTrue(jsonOps.isJson("{'a':1}"), "fastjson 默认接受单引号");
        assertTrue(jsonOps.isJson("{a:1}"), "fastjson 默认接受无引号键名");
        assertTrue(jsonOps.isJson("{\"a\":1,}"), "fastjson 默认接受尾部逗号");
        assertFalse(jsonOps.isJson("不是 json"), "语法错误仍然判 false");
    }

    @Test
    @DisplayName("leafToNull：叶子是属性链（$.items[*].buyer.city）也能按父节点个数补 null")
    void leafToNullPadsPropertyChains() {
        String json = "{\"items\":[{\"buyer\":{\"city\":\"上海\"}},{\"buyer\":{}},{\"other\":1}]}";
        JsonReadContext ctx = jsonOps.readContext(json);

        assertEquals(Arrays.asList("上海"), ctx.read("$.items[*].buyer.city"), "strict：缺失的被跳过");
        assertEquals(Arrays.asList("上海", null, null), ctx.read("$.items[*].buyer.city", true));
    }

    /** 递归断言：值树里不允许出现 fastjson 的任何类型（JSONObject/JSONArray/JSONPath…）。 */
    private static void assertNoFastjsonType(Object value, String path) {
        if (value == null) {
            return;
        }
        assertFalse(value.getClass().getName().startsWith("com.alibaba."),
                "路径 " + path + " 上泄漏了 fastjson 类型：" + value.getClass().getName());
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                assertNoFastjsonType(entry.getValue(), path + "." + entry.getKey());
            }
        } else if (value instanceof Iterable) {
            int i = 0;
            for (Object element : (Iterable<?>) value) {
                assertNoFastjsonType(element, path + "[" + i++ + "]");
            }
        }
    }
}
