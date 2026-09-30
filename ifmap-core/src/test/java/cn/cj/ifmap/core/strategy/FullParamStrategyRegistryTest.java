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
package cn.cj.ifmap.core.strategy;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link FullParamStrategyRegistry} 的通配与优先级。 */
class FullParamStrategyRegistryTest {

    @FullParam(bankCode = "CMB", busiNode = "apply")
    public static class CmbApplyFullParam implements FullParamStrategy {
        @Override
        public Map<String, Object> assemble(StrategyContext context) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("who", "cmbApply");
            return result;
        }
    }

    @FullParam(bankCode = "CMB")
    public static class CmbAnyFullParam implements FullParamStrategy {
        @Override
        public Map<String, Object> assemble(StrategyContext context) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("who", "cmbAny");
            return result;
        }
    }

    @FullParam
    public static class AnyFullParam implements FullParamStrategy {
        @Override
        public Map<String, Object> assemble(StrategyContext context) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("who", "any");
            return result;
        }
    }

    @Test
    void exactWinsOverWildcards() {
        FullParamStrategyRegistry registry = new FullParamStrategyRegistry();
        registry.register(new AnyFullParam());
        registry.register(new CmbAnyFullParam());
        registry.register(new CmbApplyFullParam());

        assertEquals("cmbApply", registry.assemble("CMB", "apply", null).get("who"));
        assertEquals("cmbAny", registry.assemble("CMB", "other", null).get("who"));
        assertEquals("any", registry.assemble("OTHER", "apply", null).get("who"));
    }

    @Test
    void emptyBankOrNodeTreatedAsWildcard() {
        FullParamStrategyRegistry registry = new FullParamStrategyRegistry();
        registry.register(new AnyFullParam());
        assertSame(registry.lookup("*", "*"), registry.lookup(null, null));
        assertEquals("any", registry.assemble(null, null, null).get("who"));
    }

    @Test
    void lookupReturnsNullWhenNothingRegistered() {
        FullParamStrategyRegistry registry = new FullParamStrategyRegistry();
        assertNull(registry.lookup("CMB", "apply"));
        assertEquals(0, registry.assemble("CMB", "apply", null).size());
        assertEquals(Collections.emptyMap(), registry.assemble("CMB", "apply", null));
    }

    @Test
    void duplicateKeyKeepsFirstAndRecordsConflict() {
        FullParamStrategyRegistry registry = new FullParamStrategyRegistry();
        CmbApplyFullParam first = new CmbApplyFullParam();
        registry.register("CMB", "apply", first);
        registry.register("CMB", "apply", new CmbApplyFullParam());

        assertSame(first, registry.lookup("CMB", "apply"));
        assertEquals(1, registry.getConflicts().size());
        assertEquals(1, registry.size());
    }

    @Test
    void keyNormalisesBlank() {
        assertEquals("*|*", FullParamStrategyRegistry.key(null, "  "));
        assertEquals("CMB|apply", FullParamStrategyRegistry.key(" CMB ", " apply "));
    }
}
