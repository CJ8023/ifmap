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
package cn.cj.ifmap.core.orchestrator.judgement;

import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.testkit.TestJsonOps;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultJudgeSpecTest {

    private final JsonOps jsonOps = new TestJsonOps();

    private List<String> invalid(String spec) {
        return ResultJudgeSpec.validate(spec, jsonOps);
    }

    @Test
    void alwaysIsTheOnlyWayToSayTrue() {
        assertTrue(invalid("{\"op\":\"always\"}").isEmpty());
        assertTrue(ResultJudgeSpec.parse("{\"op\":\"always\"}", jsonOps).isAlways());
        // 形态②：空组合一律非法，不给它"数学上恒真"的解释
        assertFalse(invalid("").isEmpty());
        assertFalse(invalid("{}").isEmpty());
        assertFalse(invalid("{\"all\":[]}").isEmpty());
        assertFalse(invalid("{\"any\":[]}").isEmpty());
    }

    @Test
    void alwaysMustNotCarryPathOrValue() {
        assertFalse(invalid("{\"op\":\"always\",\"path\":\"$.code\"}").isEmpty());
        assertFalse(invalid("{\"op\":\"always\",\"value\":\"0000\"}").isEmpty());
    }

    @Test
    void unknownOpAndEmptyValueListAreRejected() {
        assertFalse(invalid("{\"all\":[{\"path\":\"$.code\",\"op\":\"startsWith\",\"value\":\"0\"}]}").isEmpty());
        assertFalse(invalid("{\"all\":[{\"path\":\"$.code\",\"op\":\"in\",\"value\":[]}]}").isEmpty());
        assertFalse(invalid("{\"all\":[{\"path\":\"$.code\",\"op\":\"exists\",\"value\":\"x\"}]}").isEmpty());
        assertFalse(invalid("{\"all\":[{\"path\":\"\",\"op\":\"eq\",\"value\":\"0\"}]}").isEmpty());
        assertFalse(invalid("{\"all\":[{\"op\":\"eq\",\"value\":\"0\"}]}").isEmpty());
        assertFalse(invalid("{\"all\":[{\"path\":\"$.code\",\"op\":\"regex\",\"value\":\"[\"}]}").isEmpty());
    }

    @Test
    void acceptsWireNamesUsedByDocsAndAdmin() {
        // 管理端下拉框与文档都写 camelCase；解析层必须认，否则选了就等于落一份非法规格
        assertTrue(invalid("{\"all\":[{\"path\":\"$.data.riskLevel\",\"op\":\"notBlank\"}]}").isEmpty());
        assertTrue(invalid("{\"all\":[{\"path\":\"$.data.riskLevel\",\"op\":\"notIn\",\"value\":[\"H\",\"R\"]}]}").isEmpty());
        ResultJudgeSpec notBlank = ResultJudgeSpec.parse(
                "{\"all\":[{\"path\":\"$.data.riskLevel\",\"op\":\"notBlank\"}]}", jsonOps);
        assertTrue(notBlank.evaluate(jsonOps.readContext("{\"data\":{\"riskLevel\":\"A\"}}")).isMatched());
        assertFalse(notBlank.evaluate(jsonOps.readContext("{\"data\":{\"riskLevel\":\"  \"}}")).isMatched());
        ResultJudgeSpec notIn = ResultJudgeSpec.parse(
                "{\"all\":[{\"path\":\"$.data.riskLevel\",\"op\":\"notIn\",\"value\":[\"H\",\"R\"]}]}", jsonOps);
        assertTrue(notIn.evaluate(jsonOps.readContext("{\"data\":{\"riskLevel\":\"A\"}}")).isMatched());
        assertFalse(notIn.evaluate(jsonOps.readContext("{\"data\":{\"riskLevel\":\"H\"}}")).isMatched());
    }

    @Test
    void nestedGroupsUpToThreeLevels() {
        String ok = "{\"any\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"},"
                + "{\"all\":[{\"path\":\"$.state\",\"op\":\"eq\",\"value\":\"1\"},"
                + "{\"any\":[{\"path\":\"$.sub\",\"op\":\"eq\",\"value\":\"A\"}]}]}]}";
        assertTrue(invalid(ok).isEmpty());
        // 第 4 层：非法
        String tooDeep = "{\"any\":[{\"all\":[{\"any\":[{\"all\":[{\"path\":\"$.x\",\"op\":\"exists\"}]}]}]}]}";
        assertFalse(invalid(tooDeep).isEmpty());
        assertTrue(invalid(tooDeep).get(0).contains("嵌套"));
    }

    @Test
    void allAndAnyAreMutuallyExclusive() {
        assertFalse(invalid("{\"all\":[{\"path\":\"$.a\",\"op\":\"exists\"}],\"any\":[]}").isEmpty());
    }

    @Test
    void evaluateReportsFirstMiss() {
        ResultJudgeSpec spec = ResultJudgeSpec.parse(
                "{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"},"
                        + "{\"path\":\"$.state\",\"op\":\"eq\",\"value\":\"1\"}]}", jsonOps);
        ResultJudgeSpec.Evaluation hit = spec.evaluate(jsonOps.readContext("{\"code\":\"0000\",\"state\":\"1\"}"));
        assertTrue(hit.isMatched());

        ResultJudgeSpec.Evaluation miss = spec.evaluate(jsonOps.readContext("{\"code\":\"0000\",\"state\":\"9\"}"));
        assertFalse(miss.isMatched());
        assertEquals("9", miss.getActual());
        // 取到了值只是不相等 → 不是 FIELD_MISSING（missedPath 只表示"取不到值"）
        assertNull(miss.getMissedPath());
    }

    @Test
    void missingPathIsReportedAsMissedPath() {
        ResultJudgeSpec spec = ResultJudgeSpec.parse("{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0\"}]}", jsonOps);
        ResultJudgeSpec.Evaluation ev = spec.evaluate(jsonOps.readContext("{}"));
        assertFalse(ev.isMatched());
        assertEquals("$.code", ev.getMissedPath());
        assertNull(ev.getActual());
    }

    @Test
    void anyModeShortCircuits() {
        ResultJudgeSpec spec = ResultJudgeSpec.parse(
                "{\"any\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"},"
                        + "{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"SUCCESS\"}]}", jsonOps);
        assertTrue(spec.evaluate(jsonOps.readContext("{\"code\":\"SUCCESS\"}")).isMatched());
        assertFalse(spec.evaluate(jsonOps.readContext("{\"code\":\"9999\"}")).isMatched());
    }

    @Test
    void branchOnlyOpsAreRejectedInResultJudge() {
        // strategy / judged 是分支专用：放进 result_judge 等于"判定引用自己的判定结果"
        assertFalse(invalid("{\"any\":[{\"op\":\"strategy\",\"value\":\"PAY_SUCCESS\"}]}").isEmpty());
        assertFalse(invalid("{\"any\":[{\"op\":\"judged\",\"value\":[\"SUCCESS\"]}]}").isEmpty());
        assertTrue(invalid("{\"any\":[{\"op\":\"strategy\",\"value\":\"X\"}]}").get(0).contains("分支专用"));
    }

    @Test
    void describeIsHumanReadable() {
        ResultJudgeSpec spec = ResultJudgeSpec.parse(
                "{\"all\":[{\"path\":\"$.code\",\"op\":\"in\",\"value\":[\"0000\",\"S\"]}]}", jsonOps);
        assertTrue(spec.describe().contains("$.code"));
        assertTrue(spec.describe().contains("0000"));
    }
}
