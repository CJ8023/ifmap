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

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.orchestrator.Judgement;
import cn.cj.ifmap.core.orchestrator.judgement.RuleSpecJudge;
import cn.cj.ifmap.core.spi.ResultJudge;
import cn.cj.ifmap.core.spi.ResultJudgeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定结论一致性 TCK：同一批 {@code (result_judge, 响应报文)} 在任意 {@link JsonOps} 实现下
 * 必须给出<b>逐条相同</b>的结论与失败归因。
 *
 * <p><b>为什么单开一套</b>：{@code JsonOps} 的取值语义差异（{@code 10.00} 解析成
 * {@code 10.0} 还是 {@code BigDecimal("10.00")}、数组下标怎么写、数字比较用字符串还是数值）
 * 在换库当刻不会报错，只会让某个资方的判定在灰度环境里悄悄反过来 —— 那是"成功的单子被判失败"
 * 或更糟的"失败的单子被判成功"。断言写成<b>绝对值</b>（期望 true/false + 归因），
 * 而不是"两边互相比"，这样新增实现时不需要两两配对。</p>
 *
 * <p>用法与 {@link JsonOpsConformanceTestBase} 一致：写一个空壳类覆盖 {@link #jsonOps()}。</p>
 *
 * @author caijun
 */
public abstract class JudgeConsistencyTestBase {

    /** 被测实现。 */
    protected abstract JsonOps jsonOps();

    private ResultJudge judge() {
        return new RuleSpecJudge(jsonOps());
    }

    private Judgement judge(String spec, String response) {
        IfmapConfig config = new IfmapConfig();
        config.setResultJudge(spec);
        return judge().judge(new ResultJudgeContext(config, response, jsonOps()));
    }

    @Test
    @DisplayName("字符串等值：命中 / 未命中 / 缺字段 / 非 JSON，四种结论与归因都固定")
    void stringEqualityOutcomes() {
        Judgement hit = judge("{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"}]}",
                "{\"code\":\"0000\"}");
        assertTrue(hit.isSuccess(), hit.getReason());
        assertEquals("0000", hit.getActualValue());

        Judgement miss = judge("{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"}]}",
                "{\"code\":\"9999\"}");
        assertFalse(miss.isSuccess());
        assertEquals("VALUE_MISMATCH", miss.getFailType());
        assertEquals("9999", miss.getActualValue(), "未命中也要记下取到的值（否则线上只能靠猜）");

        Judgement missing = judge("{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"}]}",
                "{\"other\":\"x\"}");
        assertFalse(missing.isSuccess());
        assertEquals("FIELD_MISSING", missing.getFailType());

        Judgement notJson = judge("{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"}]}",
                "<html>gateway error</html>");
        assertFalse(notJson.isSuccess());
        assertEquals("NOT_JSON", notJson.getFailType());
    }

    @Test
    @DisplayName("小数尾零：10.00 必须与规格里的 \"10.00\" 判等（解析成 Double 会退化成 10.0）")
    void trailingZeroDecimalIsPreserved() {
        Judgement hit = judge("{\"all\":[{\"path\":\"$.amount\",\"op\":\"eq\",\"value\":\"10.00\"}]}",
                "{\"amount\":10.00}");
        assertTrue(hit.isSuccess(), "尾零丢了就等于没对上：取到 " + hit.getActualValue());
        assertEquals("10.00", hit.getActualValue(), "取到的值要保留源文字的小数位");

        Judgement miss = judge("{\"all\":[{\"path\":\"$.amount\",\"op\":\"eq\",\"value\":\"10.00\"}]}",
                "{\"amount\":10.01}");
        assertFalse(miss.isSuccess());
        assertEquals("VALUE_MISMATCH", miss.getFailType());
    }

    @Test
    @DisplayName("in 数组：标量数组逐元素比较，数字元素两边同形态（10.0 ∈ [\"10.0\"]）")
    void inListComparesEachElement() {
        Judgement hit = judge("{\"all\":[{\"path\":\"$.state\",\"op\":\"in\",\"value\":[\"INIT\",\"0000\"]}]}",
                "{\"state\":\"0000\"}");
        assertTrue(hit.isSuccess(), hit.getReason());

        Judgement numberHit = judge("{\"all\":[{\"path\":\"$.amount\",\"op\":\"in\",\"value\":[\"10.0\",\"20.0\"]}]}",
                "{\"amount\":10.0}");
        assertTrue(numberHit.isSuccess(), "数字元素的两侧形态必须一致：" + numberHit.getActualValue());

        Judgement miss = judge("{\"all\":[{\"path\":\"$.state\",\"op\":\"in\",\"value\":[\"INIT\"]}]}",
                "{\"state\":\"DONE\"}");
        assertFalse(miss.isSuccess());
        assertEquals("VALUE_MISMATCH", miss.getFailType());
    }

    @Test
    @DisplayName("数组路径与 any 组：下标取值与「任一命中」的结论在两种实现下一致")
    void arrayPathAndAnyGroup() {
        Judgement first = judge("{\"all\":[{\"path\":\"$.items[0].sku\",\"op\":\"eq\",\"value\":\"S1\"}]}",
                "{\"items\":[{\"sku\":\"S1\"},{\"sku\":\"S2\"}]}");
        assertTrue(first.isSuccess(), first.getReason());

        String anySpec = "{\"any\":["
                + "{\"path\":\"$.result.code\",\"op\":\"eq\",\"value\":\"0000\"},"
                + "{\"path\":\"$.result.status\",\"op\":\"eq\",\"value\":\"SUCCESS\"}]}";
        assertTrue(judge(anySpec, "{\"result\":{\"code\":\"9999\",\"status\":\"SUCCESS\"}}").isSuccess());
        assertFalse(judge(anySpec, "{\"result\":{\"code\":\"9999\",\"status\":\"FAIL\"}}").isSuccess());
    }

    @Test
    @DisplayName("ignoreCase 与 always：大小写不敏感只在显式声明时生效，always 不看报文")
    void ignoreCaseAndAlways() {
        String spec = "{\"all\":[{\"path\":\"$.msg\",\"op\":\"eq\",\"value\":\"SUCCESS\",\"ignoreCase\":true}]}";
        assertTrue(judge(spec, "{\"msg\":\"success\"}").isSuccess());
        assertFalse(judge("{\"all\":[{\"path\":\"$.msg\",\"op\":\"eq\",\"value\":\"SUCCESS\"}]}",
                "{\"msg\":\"success\"}").isSuccess(), "不声明 ignoreCase 时必须严格比较（默认口径）");

        Judgement always = judge("{\"op\":\"always\"}", "{\"anything\":1}");
        assertTrue(always.isSuccess());
        assertNotNull(always.getReason());
    }

    @Test
    @DisplayName("规格非法：不抛异常，统一按 SPEC_INVALID 失败（换 JSON 库也不能变成抛错）")
    void invalidSpecFailsClosed() {
        Judgement empty = judge("", "{}");
        assertFalse(empty.isSuccess());
        assertEquals("SPEC_INVALID", empty.getFailType());

        Judgement unknownOp = judge("{\"all\":[{\"path\":\"$.code\",\"op\":\"between\",\"value\":\"1\"}]}", "{}");
        assertFalse(unknownOp.isSuccess());
        assertEquals("SPEC_INVALID", unknownOp.getFailType());
    }
}
