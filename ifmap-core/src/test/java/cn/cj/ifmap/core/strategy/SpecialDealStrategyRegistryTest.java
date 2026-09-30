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

/** {@link SpecialDealStrategyRegistry} 的三 key 注册与歧义记录。 */
class SpecialDealStrategyRegistryTest {

    /** 类名用于"宽松兜底 key"断言，必须是 public 顶层类（注解/实例化都可见）。 */
    public static class CmbSpecialDealStrategy implements SpecialDealStrategy {
        @Override
        public Map<String, Object> apply(StrategyContext context) {
            return Collections.emptyMap();
        }
    }

    public static class OtherSpecialDealStrategy implements SpecialDealStrategy {
        @Override
        public Map<String, Object> apply(StrategyContext context) {
            return Collections.emptyMap();
        }
    }

    @Test
    void resolvesByBeanNameWithoutAliasHit() {
        SpecialDealStrategyRegistry registry = new SpecialDealStrategyRegistry();
        CmbSpecialDealStrategy strategy = new CmbSpecialDealStrategy();
        registry.register("cmbSpecialDealStrategy", strategy);

        assertSame(strategy, registry.lookup("cmbSpecialDealStrategy"));
        assertTrue(registry.getAliasHits().isEmpty(), "bean 名精确命中不应记宽松命中");
        assertEquals(1, registry.size());
    }

    @Test
    void resolvesBySimpleNameAsLooseKey() {
        SpecialDealStrategyRegistry registry = new SpecialDealStrategyRegistry();
        CmbSpecialDealStrategy strategy = new CmbSpecialDealStrategy();
        registry.register("cmbSpecialDealStrategy", strategy);

        assertSame(strategy, registry.lookup("CmbSpecialDealStrategy"));
        assertEquals(1, registry.getAliasHits().size());
        assertTrue(registry.getAliasHits().contains("CmbSpecialDealStrategy"));
    }

    @Test
    void resolvesByDecapitalizedSimpleName() {
        SpecialDealStrategyRegistry registry = new SpecialDealStrategyRegistry();
        CmbSpecialDealStrategy strategy = new CmbSpecialDealStrategy();
        registry.register("beanname", strategy);

        assertSame(strategy, registry.lookup("cmbSpecialDealStrategy"));
        assertEquals(1, registry.getAliasHits().size());
    }

    @Test
    void unknownNameReturnsNull() {
        SpecialDealStrategyRegistry registry = new SpecialDealStrategyRegistry();
        registry.register("a", new CmbSpecialDealStrategy());
        assertNull(registry.lookup("notExist"));
        assertFalse(registry.contains("notExist"));
    }

    @Test
    void keepsFirstWhenTwoStrategiesCollideOnAlias() {
        SpecialDealStrategyRegistry registry = new SpecialDealStrategyRegistry();
        CmbSpecialDealStrategy first = new CmbSpecialDealStrategy();
        OtherSpecialDealStrategy second = new OtherSpecialDealStrategy();
        // 两个不同策略注册到同一个 bean 名 -> 歧义，保留先注册者
        registry.register("sameBeanName", first);
        registry.register("sameBeanName", second);

        assertSame(first, registry.lookup("sameBeanName"));
        assertTrue(registry.getConflicts().containsKey("sameBeanName"));
    }

    @Test
    void keysContainAllThreeForms() {
        SpecialDealStrategyRegistry registry = new SpecialDealStrategyRegistry();
        registry.register("cmbSpecialDealStrategy", new CmbSpecialDealStrategy());

        assertTrue(registry.keys().contains("cmbSpecialDealStrategy"));
        assertTrue(registry.keys().contains("CmbSpecialDealStrategy"));
    }

    @Test
    void decapitalizeLooseOnlyTouchesFirstChar() {
        assertEquals("aBC", SpecialDealStrategyRegistry.decapitalizeLoose("ABC"));
        assertEquals("abc", SpecialDealStrategyRegistry.decapitalizeLoose("abc"));
        assertNull(SpecialDealStrategyRegistry.decapitalizeLoose(null));
        assertEquals("", SpecialDealStrategyRegistry.decapitalizeLoose(""));
    }
}
