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
package cn.cj.ifmap.json.jackson;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.exception.RuleNotFoundException;
import cn.cj.ifmap.core.template.NullPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板 DSL 端到端测试（真 JSON + 真 JsonPath）。
 *
 * @author caijun
 */
class TemplateEngineTest {

    private static final String SOURCE = "{"
            + "\"orderNo\":\"PO001\","
            + "\"amount\":100,"
            + "\"amounts\":[1,2.5],"
            + "\"dueDate\":\"2026-03-31\","
            + "\"invoiceTypes\":[\"01\",\"02\"],"
            + "\"items\":[{\"sku\":\"A\",\"qty\":1,\"price\":\"10.5\"},{\"sku\":\"B\"}],"
            + "\"applicant\":{\"name\":\"张三\"},"
            + "\"remark\":null"
            + "}";

    private final IfmapEngine engine = IfmapEngine.createDefault();
    private final JacksonJsonOps jsonOps = new JacksonJsonOps();

    @SuppressWarnings("unchecked")
    private Map<String, Object> render(String template) {
        return (Map<String, Object>) jsonOps.parse(engine.render(template, SOURCE));
    }

    @Test
    @DisplayName("基础取值：路径取值、字面量常量、数字统一转字符串、流水号、取不到则省略字段")
    void basics() {
        Map<String, Object> out = render("{\"no\":\"$.orderNo\",\"amount\":\"$.amount\","
                + "\"bizNode\":\"GP81\",\"seqNo\":\"$.seqNo\",\"missing\":\"$.notExist\"}");
        assertEquals("PO001", out.get("no"));
        assertEquals("100", out.get("amount"));
        assertEquals("GP81", out.get("bizNode"));
        assertTrue(String.valueOf(out.get("seqNo")).matches("\\d{20}"), String.valueOf(out.get("seqNo")));
        assertFalse(out.containsKey("missing"));
    }

    @Test
    @DisplayName("空值策略：EMPTY_STRING 落空串、FAIL 直接抛异常")
    void nullPolicy() {
        IfmapEngine emptyString = IfmapEngine.builder().nullPolicy(NullPolicy.EMPTY_STRING).build();
        Map<String, Object> out = (Map<String, Object>) jsonOps.parse(
                emptyString.render("{\"missing\":\"$.notExist\"}", SOURCE));
        assertEquals("", out.get("missing"));

        IfmapEngine fail = IfmapEngine.builder().nullPolicy(NullPolicy.FAIL).build();
        IfmapConfigException error = assertThrows(IfmapConfigException.class,
                () -> fail.render("{\"missing\":\"$.notExist\"}", SOURCE));
        assertTrue(error.getMessage().contains("missing"), error.getMessage());
    }

    @Test
    @DisplayName("@FUN：普通调用、参数嵌套 @FUN、List 入参自动逐元素")
    void funCalls() {
        Map<String, Object> out = render("{\"d\":\"@FUN(dateFormat,$.dueDate,yyyyMMdd)\","
                + "\"nested\":\"@FUN(concat,@FUN(dateFormat,$.dueDate,yyyy),/,@FUN(dateFormat,$.dueDate,MM))\","
                + "\"mapped\":\"@FUN(strTruncate,$.invoiceTypes,1)\","
                + "\"dict\":\"@FUN(dictVal,$.invoiceTypes,01:专票;02:普票)\"}");
        assertEquals("20260331", out.get("d"));
        assertEquals("2026/03", out.get("nested"));
        assertEquals(Arrays.asList("0", "0"), out.get("mapped"));
        assertEquals(Arrays.asList("专票", "普票"), out.get("dict"));
    }

    @Test
    @DisplayName("@FUN：参数取不到值时按空值策略处理，不抛异常、不回写模板原文")
    void funWithNullArg() {
        Map<String, Object> out = render("{\"d\":\"@FUN(dateFormat,$.notExist,yyyyMMdd)\"}");
        assertFalse(out.containsKey("d"));
    }

    @Test
    @DisplayName("@array 后缀：标量包成数组、列表原样、对象按列转行、通配缺失位跳过")
    void arraySuffix() {
        Map<String, Object> out = render("{\"one@array\":\"$.orderNo\","
                + "\"list@array\":\"$.invoiceTypes\","
                + "\"rows@array\":{\"code\":\"$.items[*].sku\",\"qty\":\"$.items[*].qty\"}}");
        assertEquals(Arrays.asList("PO001"), out.get("one"));
        assertEquals(Arrays.asList("01", "02"), out.get("list"));
        List<?> rows = (List<?>) out.get("rows");
        assertEquals(2, rows.size());
        Map<?, ?> first = (Map<?, ?>) rows.get(0);
        assertEquals("A", first.get("code"));
        assertEquals(Integer.valueOf(1), first.get("qty"));
        // 第二个元素缺 qty（通配缺失位为 null）-> 该字段被跳过
        assertEquals("B", ((Map<?, ?>) rows.get(1)).get("code"));
        assertFalse(((Map<?, ?>) rows.get(1)).containsKey("qty"));
    }

    @Test
    @DisplayName("@array@：按源数组逐元素展开，每个元素成为子模板的源报文")
    void arrayAtExpand() {
        Map<String, Object> out = render("{\"rows@array@$.items\":"
                + "{\"sku\":\"$.sku\",\"label\":\"@FUN(concat,$.sku,报)\"}}");
        List<?> rows = (List<?>) out.get("rows");
        assertEquals(2, rows.size());
        assertEquals("A", ((Map<?, ?>) rows.get(0)).get("sku"));
        assertEquals("A报", ((Map<?, ?>) rows.get(0)).get("label"));
        assertEquals("B报", ((Map<?, ?>) rows.get(1)).get("label"));
    }

    @Test
    @DisplayName("@array@：数据源不存在时输出空数组（存量会回写模板原文）")
    void arrayAtMissingSource() {
        Map<String, Object> out = render("{\"rows@array@$.notExist\":{\"sku\":\"$.sku\"}}");
        assertEquals(0, ((List<?>) out.get("rows")).size());
    }

    @Test
    @DisplayName("@and@ 展平合并、@or@ 取第一个非空、@concat@ 逗号拼接、@append@ 直接拼接")
    void multiValueTokens() {
        Map<String, Object> out = render("{\"and\":\"$.orderNo@and@$.invoiceTypes\","
                + "\"or\":\"$.notExist@or@$.orderNo\","
                + "\"concat\":\"$.orderNo@concat@$.dueDate\","
                + "\"append\":\"$.orderNo@append@-X\","
                + "\"orEmpty\":\"$.notExist@or@$.alsoNotExist\"}");
        assertEquals(Arrays.asList("PO001", "01", "02"), out.get("and"));
        assertEquals("PO001", out.get("or"));
        assertEquals("PO001,2026-03-31", out.get("concat"));
        assertEquals("PO001-X", out.get("append"));
        assertFalse(out.containsKey("orEmpty"));
    }

    @Test
    @DisplayName("@sum@ 求和：单值、数组、非数值按 0")
    void sum() {
        Map<String, Object> out = render("{\"s1\":\"@sum@$.amount\",\"s2\":\"@sum@$.amounts\"}");
        assertEquals("100", String.valueOf(out.get("s1")));
        assertEquals("3.5", String.valueOf(out.get("s2")));
    }

    @Test
    @DisplayName("通配路径：结果直接是数组")
    void wildcardPath() {
        Map<String, Object> out = render("{\"skus\":\"$.items[*].sku\"}");
        assertEquals(Arrays.asList("A", "B"), out.get("skus"));
    }

    @Test
    @DisplayName("规则名拼写错误：渲染期立刻抛 RuleNotFoundException（存量是静默 null）")
    void unknownRuleFailsFast() {
        // 存量生产配置里真实存在的两个坑
        assertThrows(RuleNotFoundException.class,
                () -> engine.render("{\"d\":\"@FUN(farmatDate,$.dueDate,yyyy-MM-dd)\"}", SOURCE));
        assertThrows(RuleNotFoundException.class,
                () -> engine.render("{\"path\":\"@FUN(cebFilePathJoin,$.orderNo)\"}", SOURCE));
    }

    @Test
    @DisplayName("模板列出全部内置规则后仍能正确渲染（冒烟）")
    void allBuiltinRulesSmoke() {
        String template = "{"
                + "\"concat\":\"@FUN(concat,$.orderNo,-,X)\","
                + "\"strDefault\":\"@FUN(strDefault,$.notExist,默认)\","
                + "\"strTruncate\":\"@FUN(strTruncate,$.orderNo,2)\","
                + "\"strMask\":\"@FUN(strMask,$.orderNo,NAME)\","
                + "\"dictVal\":\"@FUN(dictVal,$.invoiceTypes,01:专票;02:普票)\","
                + "\"dictValArray\":\"@FUN(dictValArray,$.invoiceTypes,01:专票;02:普票)\","
                + "\"dateFormat\":\"@FUN(dateFormat,$.dueDate,yyyyMMdd)\","
                + "\"dateConvert\":\"@FUN(dateConvert,$.dueDate,yyyy-MM-dd,yyyyMMdd)\","
                + "\"dateAdd\":\"@FUN(dateAdd,$.dueDate,1,MONTH,yyyy-MM-dd)\","
                + "\"dateDiff\":\"@FUN(dateDiff,$.dueDate,2026-04-03)\","
                + "\"dateEndOfMonth\":\"@FUN(dateEndOfMonth,$.dueDate,yyyy-MM-dd)\","
                + "\"workdayAdd\":\"@FUN(workdayAdd,$.dueDate,1,yyyy-MM-dd)\","
                + "\"numRound\":\"@FUN(numRound,$.amount,2)\","
                + "\"numSum\":\"@FUN(numSum,$.amounts,2,HALF_UP)\","
                + "\"numOffset\":\"@FUN(numOffset,$.amount,1)\","
                + "\"numFormat\":\"@FUN(numFormat,$.amount,THOUSAND)\","
                + "\"listJoin\":\"@FUN(listJoin,$.invoiceTypes,/)\","
                + "\"listOp\":\"@FUN(listOp,$.invoiceTypes,DISTINCT)\""
                + "}";
        Map<String, Object> out = render(template);
        assertEquals(18, out.size());
        assertEquals("PO001-X", out.get("concat"));
        assertEquals("默认", out.get("strDefault"));
        assertEquals("2026-04-30", out.get("dateAdd"));
        assertEquals("2026-03-31", out.get("dateEndOfMonth"));
        assertEquals("100.00", out.get("numRound"));
        assertEquals("3.50", out.get("numSum"));
        assertEquals("01/02", out.get("listJoin"));
    }
}
