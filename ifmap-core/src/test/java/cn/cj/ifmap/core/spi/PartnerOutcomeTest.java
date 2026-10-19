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
package cn.cj.ifmap.core.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartnerOutcomeTest {

    @Test
    void ofBodyHasNoStatusInformation() {
        PartnerOutcome outcome = PartnerOutcome.ofBody("{\"code\":\"0000\"}");
        assertEquals("{\"code\":\"0000\"}", outcome.getBody());
        assertNull(outcome.getStatusCode());
        assertFalse(outcome.isTimedOut());
        assertNull(outcome.getMessage());
    }

    @Test
    void ofCarriesStatusAndMessage() {
        PartnerOutcome outcome = PartnerOutcome.of("boom", 500);
        assertEquals(Integer.valueOf(500), outcome.getStatusCode());
        assertEquals("boom", outcome.getMessage());
        assertFalse(outcome.isTimedOut());
        assertNull(outcome.getBody());
    }

    @Test
    void timeoutIsItsOwnFlag() {
        PartnerOutcome outcome = PartnerOutcome.timeout("read timeout");
        assertTrue(outcome.isTimedOut());
        assertEquals("read timeout", outcome.getMessage());
        assertNull(outcome.getStatusCode());
    }

    @Test
    void failureIsTransportWithoutStatus() {
        PartnerOutcome outcome = PartnerOutcome.failure("connection refused");
        assertFalse(outcome.isTimedOut());
        assertNull(outcome.getStatusCode());
        assertEquals("connection refused", outcome.getMessage());
        assertNull(outcome.getBody());
    }
}
