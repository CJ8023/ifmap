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

import cn.cj.ifmap.core.orchestrator.judgement.JudgeFailType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgementTest {

    @Test
    void successHasNoFailType() {
        Judgement j = Judgement.success("0000", "0000", "命中");
        assertTrue(j.isSuccess());
        assertNull(j.getFailType());
        assertNull(j.getStage());
    }

    @Test
    void failureCarriesStageAndType() {
        Judgement j = Judgement.failure(JudgeFailType.HTTP_5XX, Judgement.Stage.TRANSPORT, null, null, "对方 500")
                .withHttpStatus(500);
        assertFalse(j.isSuccess());
        assertEquals("HTTP_5XX", j.getFailType());
        assertEquals(Judgement.Stage.TRANSPORT, j.getStage());
        assertEquals(Integer.valueOf(500), j.getHttpStatus());
    }

    @Test
    void legacyOfFactoryStillWorks() {
        Judgement j = Judgement.of(false, "9", "0", "未命中");
        assertEquals("9", j.getActualValue());
        assertEquals("未命中", j.getReason());
        assertNull(j.getFailType());
    }

    @Test
    void httpStatusClassifies() {
        assertEquals(JudgeFailType.HTTP_4XX, JudgeFailType.forHttpStatus(404));
        assertEquals(JudgeFailType.HTTP_5XX, JudgeFailType.forHttpStatus(503));
        assertNull(JudgeFailType.forHttpStatus(204));
    }

    @Test
    void withersKeepInstanceImmutable() {
        Judgement failure = Judgement.failure(JudgeFailType.VALUE_MISMATCH, Judgement.Stage.BUSINESS, "9999", "0000", "未命中");
        Judgement enriched = failure.withErrorCode("9999").withTrackId("T-1")
                .withExtraction("余额不足", "T-2");
        assertEquals("9999", enriched.getErrorCode());
        assertEquals("T-2", enriched.getTrackId());
        assertEquals("余额不足", enriched.getMessage());
        assertNull(failure.getErrorCode());
        assertNull(failure.getTrackId());
        assertNull(failure.getMessage());
        assertFalse(enriched.isSuccess());
    }
}
