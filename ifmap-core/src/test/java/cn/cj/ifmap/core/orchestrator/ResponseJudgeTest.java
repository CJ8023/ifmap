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
package cn.cj.ifmap.core.orchestrator;

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.testkit.TestConfigs;
import cn.cj.ifmap.core.testkit.TestJsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 结果判定：result_flag + success_value（含多值、缺字段、空 flag）。 */
class ResponseJudgeTest {

    private final ResponseJudge judge = new ResponseJudge(new TestJsonOps());

    @Test
    void blankFlagMeansSuccess() {
        Judgement judgement = judge.evaluate(config(null, null), "{}");
        assertTrue(judgement.isSuccess());
        assertTrue(judgement.getReason().contains("默认成功"));
    }

    @Test
    void matchesSingleSuccessValue() {
        Judgement judgement = judge.evaluate(config("$.code", "0000"), "{\"code\":\"0000\"}");
        assertTrue(judgement.isSuccess());
        assertEquals("0000", judgement.getActualValue());
    }

    @Test
    void matchesAnyOfMultipleSuccessValues() {
        IfmapConfig config = config("$.code", "0000;SUCCESS,OK");
        assertTrue(judge.evaluate(config, "{\"code\":\"SUCCESS\"}").isSuccess());
        assertTrue(judge.evaluate(config, "{\"code\":\"ok\"}").isSuccess());
        assertFalse(judge.evaluate(config, "{\"code\":\"9999\"}").isSuccess());
    }

    @Test
    void numericAndBooleanValuesAreNormalisedToString() {
        assertTrue(judge.evaluate(config("$.code", "1"), "{\"code\":1}").isSuccess());
        assertTrue(judge.evaluate(config("$.ok", "true"), "{\"ok\":true}").isSuccess());
    }

    @Test
    void missingFieldFails() {
        Judgement judgement = judge.evaluate(config("$.code", "0000"), "{\"other\":\"0000\"}");
        assertFalse(judgement.isSuccess());
        assertTrue(judgement.getReason().contains("取不到判定字段"));
    }

    @Test
    void emptyResponseFails() {
        assertFalse(judge.evaluate(config("$.code", "0000"), null).isSuccess());
        assertFalse(judge.evaluate(config("$.code", "0000"), "  ").isSuccess());
    }

    @Test
    void blankExpectedValueMeansNonNullIsSuccess() {
        IfmapConfig config = config("$.code", null);
        assertTrue(judge.evaluate(config, "{\"code\":\"X\"}").isSuccess());
        assertFalse(judge.evaluate(config, "{\"code\":\"false\"}").isSuccess());
        assertFalse(judge.evaluate(config, "{\"code\":\"0\"}").isSuccess());
        assertFalse(judge.evaluate(config, "{\"code\":null}").isSuccess());
    }

    @Test
    void unparsableResponseIsFailureNotException() {
        Judgement judgement = judge.evaluate(config("$.code", "0000"), "<html>gateway error</html>");
        assertFalse(judgement.isSuccess());
        assertTrue(judgement.getReason().contains("响应解析失败"));
    }

    private static IfmapConfig config(String resultFlag, String successValue) {
        IfmapConfig config = TestConfigs.config("IF", "GP81", 1, 1L);
        config.setResultFlag(resultFlag);
        config.setSuccessValue(successValue);
        return config;
    }
}
