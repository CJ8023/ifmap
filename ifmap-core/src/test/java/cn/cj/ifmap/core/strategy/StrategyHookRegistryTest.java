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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link StrategyHookRegistry} 的三 key 注册与歧义记录。 */
class StrategyHookRegistryTest {

    /** 类名用于"宽松兜底 key"断言，必须是 public 顶层类（注解/实例化都可见）。 */
    public static class CmbIfmapStrategyHook implements IfmapStrategyHook {
        @Override
        public Map<String, Object> apply(StrategyContext context) {
            return Collections.emptyMap();
        }
    }

    public static class OtherIfmapStrategyHook implements IfmapStrategyHook {
        @Override
        public Map<String, Object> apply(StrategyContext context) {
            return Collections.emptyMap();
        }
    }

    @Test
    void resolvesByBeanNameWithoutAliasHit() {
        StrategyHookRegistry registry = new StrategyHookRegistry();
        CmbIfmapStrategyHook strategy = new CmbIfmapStrategyHook();
        registry.register("cmbIfmapStrategyHook", strategy);

        assertSame(strategy, registry.lookup("cmbIfmapStrategyHook"));
        assertTrue(registry.getAliasHits().isEmpty(), "bean 名精确命中不应记宽松命中");
        assertEquals(1, registry.size());
    }

    @Test
    void resolvesBySimpleNameAsLooseKey() {
        StrategyHookRegistry registry = new StrategyHookRegistry();
        CmbIfmapStrategyHook strategy = new CmbIfmapStrategyHook();
        registry.register("cmbIfmapStrategyHook", strategy);

        assertSame(strategy, registry.lookup("CmbIfmapStrategyHook"));
        assertEquals(1, registry.getAliasHits().size());
        assertTrue(registry.getAliasHits().contains("CmbIfmapStrategyHook"));
    }

    @Test
    void resolvesByDecapitalizedSimpleName() {
        StrategyHookRegistry registry = new StrategyHookRegistry();
        CmbIfmapStrategyHook strategy = new CmbIfmapStrategyHook();
        registry.register("beanname", strategy);

        assertSame(strategy, registry.lookup("cmbIfmapStrategyHook"));
        assertEquals(1, registry.getAliasHits().size());
    }

    @Test
    void unknownNameReturnsNull() {
        StrategyHookRegistry registry = new StrategyHookRegistry();
        registry.register("a", new CmbIfmapStrategyHook());
        assertNull(registry.lookup("notExist"));
        assertFalse(registry.contains("notExist"));
    }

    @Test
    void keepsFirstWhenTwoStrategiesCollideOnAlias() {
        StrategyHookRegistry registry = new StrategyHookRegistry();
        CmbIfmapStrategyHook first = new CmbIfmapStrategyHook();
        OtherIfmapStrategyHook second = new OtherIfmapStrategyHook();
        // 两个不同策略注册到同一个 bean 名 -> 歧义，保留先注册者
        registry.register("sameBeanName", first);
        registry.register("sameBeanName", second);

        assertSame(first, registry.lookup("sameBeanName"));
        assertTrue(registry.getConflicts().containsKey("sameBeanName"));
    }

    @Test
    void keysContainAllThreeForms() {
        StrategyHookRegistry registry = new StrategyHookRegistry();
        registry.register("cmbIfmapStrategyHook", new CmbIfmapStrategyHook());

        assertTrue(registry.keys().contains("cmbIfmapStrategyHook"));
        assertTrue(registry.keys().contains("CmbIfmapStrategyHook"));
    }

    @Test
    void decapitalizeLooseOnlyTouchesFirstChar() {
        assertEquals("aBC", StrategyHookRegistry.decapitalizeLoose("ABC"));
        assertEquals("abc", StrategyHookRegistry.decapitalizeLoose("abc"));
        assertNull(StrategyHookRegistry.decapitalizeLoose(null));
        assertEquals("", StrategyHookRegistry.decapitalizeLoose(""));
    }
}
