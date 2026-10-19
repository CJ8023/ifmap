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
import cn.cj.ifmap.core.orchestrator.Judgement;
import cn.cj.ifmap.core.spi.PartnerOutcome;
import cn.cj.ifmap.core.testkit.TestConfigs;
import cn.cj.ifmap.core.testkit.TestJsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgementPipelineTest {

    private final JudgementPipeline pipeline =
            new JudgementPipeline(ResultJudgeRegistry.withDefaults(new TestJsonOps()), new TestJsonOps());

    private IfmapConfig config(String spec) {
        IfmapConfig config = TestConfigs.config("IF", "GP81", 1, 1L);
        config.setResultJudge(spec);
        return config;
    }

    @Test
    void transportNotOkWinsOverSpec() {
        IfmapConfig config = config("{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"}]}");
        Judgement j = pipeline.judge(config, "{\"code\":\"0000\"}", PartnerOutcome.of("boom", 500));
        assertFalse(j.isSuccess());                      // 2xx 是硬门槛：非 2xx 不进规格求值
        assertEquals("HTTP_5XX", j.getFailType());
        assertEquals(Integer.valueOf(500), j.getHttpStatus());
    }

    @Test
    void timeoutAndTransportAreDistinguished() {
        IfmapConfig config = config("{\"op\":\"always\"}");
        assertEquals("TIMEOUT", pipeline.judge(config, null, PartnerOutcome.timeout("read timeout")).getFailType());
        assertEquals("TRANSPORT", pipeline.judge(config, null, PartnerOutcome.failure("connection refused")).getFailType());
    }

    @Test
    void noOutcomeMeansOldBehaviour() {
        IfmapConfig config = config("{\"op\":\"always\"}");
        assertTrue(pipeline.judge(config, null, PartnerOutcome.ofBody("{}")).isSuccess());
    }

    @Test
    void extractionFillsErrorCodeAndTrackIdWithoutChangingVerdict() {
        IfmapConfig config = config("{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"}]}");
        Judgement j = pipeline.judge(config,
                "{\"code\":\"9999\",\"message\":\"余额不足\",\"trackId\":\"T-1\"}", null);
        assertFalse(j.isSuccess());
        assertEquals("9999", j.getErrorCode());
        assertEquals("T-1", j.getTrackId());
        assertEquals("余额不足", j.getMessage());         // 抽段只补信息，不改结论
    }

    @Test
    void extractorFallsBackThroughCandidatePaths() {
        assertEquals("账户异常", ResponseExtractor.message(new TestJsonOps(), "{\"detail\":\"账户异常\"}"));
        assertNull(ResponseExtractor.message(new TestJsonOps(), "{"));
    }
}
