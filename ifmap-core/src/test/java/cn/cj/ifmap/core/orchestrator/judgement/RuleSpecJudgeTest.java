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

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.orchestrator.Judgement;
import cn.cj.ifmap.core.spi.ResultJudge;
import cn.cj.ifmap.core.spi.ResultJudgeContext;
import cn.cj.ifmap.core.testkit.TestConfigs;
import cn.cj.ifmap.core.testkit.TestJsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleSpecJudgeTest {

    private final JsonOps jsonOps = new TestJsonOps();
    private final RuleSpecJudge judge = new RuleSpecJudge(jsonOps);

    private IfmapConfig config(String spec) {
        IfmapConfig config = TestConfigs.config("IF", "GP81", 1, 1L);
        config.setResultJudge(spec);
        return config;
    }

    private ResultJudgeContext ctx(IfmapConfig config, String responseJson) {
        return new ResultJudgeContext(config, responseJson, jsonOps);
    }

    @Test
    void alwaysIgnoresNonJsonResponse() {
        IfmapConfig config = TestConfigs.config("IF", "GP81", 1, 1L);
        config.setResultJudge("{\"op\":\"always\"}");
        // 形态①必须真的短路：HTML/空响应也判成功，否则它不是恒真
        assertTrue(judge.judge(ctx(config, "<html>502 Bad Gateway</html>")).isSuccess());
        assertTrue(judge.judge(ctx(config, null)).isSuccess());
    }

    @Test
    void blankSpecFailsClosedWithoutThrowing() {
        IfmapConfig config = TestConfigs.config("IF", "GP81", 1, 1L);
        config.setResultJudge("");
        Judgement j = judge.judge(ctx(config, "{\"code\":\"0000\"}"));
        assertFalse(j.isSuccess());
        assertEquals("SPEC_INVALID", j.getFailType());
        assertFalse(judge.judge(ctx(config, "{}")).isSuccess());
    }

    @Test
    void nonJsonResponseIsNotJsonFailType() {
        IfmapConfig config = config("{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"}]}");
        Judgement j = judge.judge(ctx(config, "<html>"));
        assertFalse(j.isSuccess());
        assertEquals("NOT_JSON", j.getFailType());
    }

    @Test
    void missingFieldAndValueMismatchAreDistinguished() {
        IfmapConfig config = config("{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"}]}");
        assertEquals("FIELD_MISSING", judge.judge(ctx(config, "{}")).getFailType());
        assertEquals("VALUE_MISMATCH", judge.judge(ctx(config, "{\"code\":\"9999\"}")).getFailType());
    }

    @Test
    void errorCodeKeepsActualValue() {
        IfmapConfig config = config("{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"}]}");
        assertEquals("9999", judge.judge(ctx(config, "{\"code\":\"9999\"}")).getErrorCode());
    }

    @Test
    void successCarriesExpectedSummary() {
        IfmapConfig config = config("{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"}]}");
        Judgement j = judge.judge(ctx(config, "{\"code\":\"0000\"}"));
        assertTrue(j.isSuccess());
        assertTrue(j.getExpectedValue().contains("$.code"));
    }

    @Test
    void registryResolvesByNameWithDefault() {
        ResultJudgeRegistry registry = ResultJudgeRegistry.withDefaults(jsonOps);
        ResultJudge byDefault = registry.lookup(null);
        ResultJudge byName = registry.lookup("ruleSpec");
        assertEquals(judge.name(), byDefault.name());
        assertSame(byDefault, byName);
        assertNull(registry.lookup("noSuchJudge"));
    }
}
