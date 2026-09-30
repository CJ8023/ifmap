package cn.cj.ifmap.json.jackson;

import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonOpsHolder;
import cn.cj.ifmap.core.json.JsonReadContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JacksonJsonOps 测试：SPI 发现、类型转换、JsonPath 全语法（通配/递归/过滤/切片/函数）。
 *
 * @author caijun
 */
class JacksonJsonOpsTest {

    private final JacksonJsonOps jsonOps = new JacksonJsonOps();

    @Test
    @DisplayName("SPI 自动发现默认 JsonOps 实现")
    void serviceLoaderDiscovery() {
        JsonOps discovered = JsonOpsHolder.get();
        assertNotNull(discovered);
        assertEquals("jackson", discovered.name());
    }

    @Test
    @DisplayName("parse/toJson 往返，且 toJson(null) 输出 null")
    void parseAndToJson() {
        Object parsed = jsonOps.parse("{\"a\":1,\"b\":[1,2]}");
        assertTrue(parsed instanceof Map);
        assertEquals("{\"a\":1,\"b\":[1,2]}", jsonOps.toJson(parsed));
        assertEquals("null", jsonOps.toJson(null));
        assertTrue(jsonOps.isJson("{}"));
        assertFalse(jsonOps.isJson("不是 json"));
    }

    @Test
    @DisplayName("JsonPath：常规路径、缺失路径、数字/布尔/嵌套结构转 JDK 类型")
    void readBasics() {
        String json = "{\"amount\":100,\"flag\":true,\"applicant\":{\"name\":\"张三\"},\"items\":[{\"sku\":\"A\"}]}";
        JsonReadContext ctx = jsonOps.readContext(json);
        assertEquals("100", String.valueOf(ctx.read("$.amount")));
        assertEquals(Boolean.TRUE, ctx.read("$.flag"));
        assertEquals("张三", ((Map<?, ?>) ctx.read("$.applicant")).get("name"));
        assertEquals(1, ((List<?>) ctx.read("$.items")).size());
        assertNull(ctx.read("$.notExist"));
        assertNull(ctx.read("$.applicant.notExist"));
    }

    @Test
    @DisplayName("JsonPath：通配 + 递归下降 + 过滤 + 切片 + length() 函数")
    void readAdvanced() {
        String json = "{\"items\":[{\"sku\":\"A\",\"qty\":1},{\"sku\":\"B\",\"qty\":2}],"
                + "\"nested\":{\"deep\":{\"sku\":\"C\"}}}";
        JsonReadContext ctx = jsonOps.readContext(json);
        assertEquals(Arrays.asList("A", "B", "C"), ctx.read("$..sku"));
        assertEquals(Arrays.asList("B"), ctx.read("$.items[?(@.sku=='B')].sku"));
        assertEquals(1, ((List<?>) ctx.read("$.items[0:1]")).size());
        assertEquals("2", String.valueOf(ctx.read("$.items.length()")));
        assertEquals("B", ctx.read("$.items[-1].sku"));
    }

    @Test
    @DisplayName("JsonPath：通配取到叶子缺失时按需补 null（leafToNull）")
    void leafToNull() {
        String json = "{\"items\":[{\"sku\":\"A\",\"qty\":1},{\"sku\":\"B\"}]}";
        JsonReadContext ctx = jsonOps.readContext(json);
        List<?> withNull = (List<?>) ctx.read("$.items[*].qty", true);
        assertEquals(2, withNull.size());
        assertNull(withNull.get(1));
        // 不开 leafToNull 时，json-path 会直接丢掉取不到的叶子（只剩 [1]），
        // 这也是存量引擎「数组越取越短」的原因
        assertEquals(Arrays.asList(1), ctx.read("$.items[*].qty", false));
    }
}
