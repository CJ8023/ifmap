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

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JudgeOpTest {

    private static JudgeCondition cond(JudgeOp op, String value) {
        return new JudgeCondition("$.code", op, value, false, null);
    }

    @Test
    void eqIsStrictByDefault() {
        assertTrue(JudgeOp.EQ.matches(cond(JudgeOp.EQ, "0000"), true, "0000"));
        assertFalse(JudgeOp.EQ.matches(cond(JudgeOp.EQ, "0000"), true, "SUCCESS"));
        assertFalse(JudgeOp.EQ.matches(cond(JudgeOp.EQ, "0000"), true, " 0000")); // 不 trim：严格口径
    }

    @Test
    void ignoreCaseIsOptIn() {
        JudgeCondition c = new JudgeCondition("$.code", JudgeOp.EQ, "SUCCESS", true, null);
        assertTrue(JudgeOp.EQ.matches(c, true, "success"));
    }

    @Test
    void notBlankOnlyJudgesEmptiness() {
        JudgeCondition c = cond(JudgeOp.NOT_BLANK, null);
        assertTrue(JudgeOp.NOT_BLANK.matches(c, true, "0"));     // 旧 falsy 魔法不再生效
        assertTrue(JudgeOp.NOT_BLANK.matches(c, true, "false"));
        assertFalse(JudgeOp.NOT_BLANK.matches(c, true, "   "));
        assertFalse(JudgeOp.NOT_BLANK.matches(c, false, null));  // 路径不存在
    }

    @Test
    void existsDistinguishesMissingFromNull() {
        assertTrue(JudgeOp.EXISTS.matches(cond(JudgeOp.EXISTS, null), true, null));
        assertFalse(JudgeOp.EXISTS.matches(cond(JudgeOp.EXISTS, null), false, null));
    }

    @Test
    void inAndNotInUseValueList() {
        JudgeCondition in = new JudgeCondition("$.code", JudgeOp.IN, null, false, Arrays.asList("0000", "SUCCESS"));
        assertTrue(JudgeOp.IN.matches(in, true, "0000"));
        assertFalse(JudgeOp.IN.matches(in, true, "9999"));
        JudgeCondition notIn = new JudgeCondition("$.code", JudgeOp.NOT_IN, null, false, Arrays.asList("9999"));
        assertTrue(JudgeOp.NOT_IN.matches(notIn, true, "0000"));
    }

    @Test
    void unknownOpNameIsNull() {
        assertNull(JudgeOp.fromName("startsWith"));
    }
}
