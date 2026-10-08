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

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.json.JsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板渲染的「数字保真」一致性测试套件：{@link JsonOps} 之上还有一层
 * {@code TemplateEngine} 的归一化，换 JSON 实现时同样必须一致。
 *
 * <p>为什么 TCK 里还要有这一套：{@code JsonOps} 契约正确不代表渲染结果正确 ——
 * 取值后引擎会做一次「数字转文本」，那道归一化若走 {@code String.valueOf}，
 * 换库就会在报文里看到 {@code 1.0E10} / {@code 10.0} 这类差异。
 * 断言一律用<b>硬字面量</b>（不是 {@code doubleValue()}），否则测不出尾零。</p>
 *
 * <p>用法与 {@link JsonOpsConformanceTestBase} 相同：实现模块里写个空壳子类，重写
 * {@link #jsonOps()} 即可。</p>
 *
 * @author caijun
 */
public abstract class TemplateFidelityTestBase {

    /** 被测 JSON 实现（每次调用都返回同一实例也可）。 */
    protected abstract JsonOps jsonOps();

    private IfmapEngine engine() {
        return IfmapEngine.builder().jsonOps(jsonOps()).build();
    }

    private Map<String, Object> render(String template, String source) {
        return engine().renderToMap(template, source, null);
    }

    // ---------------------------------------------------------------- $.path 取数

    @Test
    @DisplayName("$.path 取小数：尾零保留（10.00 不能变成 10.0）")
    void pathKeepsTrailingZeros() {
        Map<String, Object> out = render("{\"amount\":\"$.amount\",\"rate\":\"$.rate\"}",
                "{\"amount\":10.00,\"rate\":0.100}");

        assertEquals("10.00", String.valueOf(out.get("amount")));
        assertEquals("0.100", String.valueOf(out.get("rate")));
    }

    @Test
    @DisplayName("$.path 取小数：科学计数法在渲染期就展开（报文里不允许出现 E）")
    void pathNeverScientific() {
        Map<String, Object> out = render("{\"a\":\"$.a\"}", "{\"a\":1.0E+10}");

        assertFalse(String.valueOf(out.get("a")).contains("E"), "渲染输出：" + out.get("a"));
        assertEquals("10000000000", String.valueOf(out.get("a")));
    }

    @Test
    @DisplayName("数组里的数字：只改写法不改类型（仍是 JSON number，不加引号）")
    void containerValuesStayNumbers() {
        Map<String, Object> out = render("{\"items\":\"$.items\"}", "{\"items\":[1.50,2.25]}");

        List<?> items = assertInstanceOf(List.class, out.get("items"));
        assertInstanceOf(Number.class, items.get(0), "数组元素不能被转成字符串");
        assertEquals("1.50", String.valueOf(items.get(0)));
        assertEquals("2.25", String.valueOf(items.get(1)));
    }

    // ---------------------------------------------------------------- @sum@

    @Test
    @DisplayName("@sum@ 返回字符串（与 numSum 口径一致），且保留操作数最大小数位")
    void sumReturnsString() {
        Map<String, Object> out = render("{\"s\":\"$.a@sum@$.b\",\"t\":\"@sum@$.a\"}",
                "{\"a\":10.00,\"b\":5}");

        assertInstanceOf(String.class, out.get("s"), "@sum@ 必须返回字符串，实际：" + out.get("s"));
        assertEquals("15.00", out.get("s"));
        assertEquals("10.00", out.get("t"));
    }

    @Test
    @DisplayName("@sum@ 对数组逐元素累加，结果同样是字符串")
    void sumOverListReturnsString() {
        Map<String, Object> out = render("{\"s\":\"@sum@$.amounts\"}", "{\"amounts\":[1,2.5]}");

        assertInstanceOf(String.class, out.get("s"));
        assertEquals("3.5", out.get("s"));
    }

    @Test
    @DisplayName("@sum@ 与 numSum 的数值口径一致（位数可显式控制的是 numSum）")
    void sumAgreesWithNumSum() {
        Map<String, Object> out = render(
                "{\"raw\":\"@sum@$.amounts\",\"rounded\":\"@FUN(numSum,$.amounts,2,HALF_UP)\"}",
                "{\"amounts\":[1,2.5]}");

        assertEquals("3.5", out.get("raw"));
        assertEquals("3.50", out.get("rounded"));
    }

    @Test
    @DisplayName("@sum@ 遇非数值文本按 0 累加（默认 warn 模式保持存量行为，不打断业务）")
    void sumTreatsNonNumericAsZero() {
        Map<String, Object> out = render("{\"s\":\"$.amount@sum@$.flag\"}",
                "{\"amount\":10.00,\"flag\":true}");

        assertEquals("10.00", out.get("s"));
    }

    @Test
    @DisplayName("@sum@ 操作数缺失按 0 累加（缺失即 0 是求和表达式的常规用法，两种模式都不报）")
    void sumTreatsMissingAsZero() {
        Map<String, Object> out = render("{\"s\":\"$.amount@sum@$.notExist\"}", "{\"amount\":10.00}");

        assertEquals("10.00", out.get("s"));
    }

    // ---------------------------------------------------------------- 中缀拼接

    @Test
    @DisplayName("@concat@ / @append@ 拼接数字：同样不出科学计数法")
    void concatNeverScientific() {
        Map<String, Object> out = render("{\"c\":\"$.a@concat@$.b\",\"p\":\"$.a@append@X\"}",
                "{\"a\":1.0E+10,\"b\":0.5}");

        assertEquals("10000000000,0.5", out.get("c"));
        assertEquals("10000000000X", out.get("p"));
    }

    @Test
    @DisplayName("@and@ 合并后的数组元素是标量文本（转成字符串是既定语义），同样无科学计数法")
    void andMergedOperandsArePlainText() {
        Map<String, Object> out = render("{\"and\":\"$.a@and@$.b\"}", "{\"a\":1.0E+10,\"b\":[2,3]}");

        List<?> and = assertInstanceOf(List.class, out.get("and"));
        assertEquals(3, and.size());
        assertEquals("10000000000", String.valueOf(and.get(0)));
        String text = engine().render("{\"and\":\"$.a@and@$.b\"}", "{\"a\":1.0E+10,\"b\":[2,3]}");
        assertFalse(text.contains("E"), "渲染文本：" + text);
    }

    // ---------------------------------------------------------------- 与 DDL/报文形态约定一致

    @Test
    @DisplayName("渲染文本整体：数值仍是 JSON number，只有 @sum@ 是带引号的字符串")
    void renderedJsonKeepsTypes() {
        String json = engine().render(
                "{\"amount\":\"$.amount\",\"items\":\"$.items\",\"sum\":\"$.amount@sum@$.bonus\"}",
                "{\"amount\":10.00,\"bonus\":1.5,\"items\":[1.50,2.25]}");

        assertTrue(json.contains("\"amount\":\"10.00\""), json);
        assertTrue(json.contains("\"sum\":\"11.50\""), json);
        assertTrue(json.contains("\"items\":[1.50,2.25]"), json);
    }
}
