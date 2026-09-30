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
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.testkit.TestConfigs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 执行顺序：启用过滤 / interface_order 排序 / 前置接口拓扑排序 + 环检测。 */
class InterfaceOrderingTest {

    @Test
    void sortsByOrderThenKeyId() {
        IfmapConfig c3 = TestConfigs.config("C", "GP81", 3, 30L);
        IfmapConfig c1 = TestConfigs.config("A", "GP81", 1, 10L);
        IfmapConfig c2 = TestConfigs.config("B", "GP81", 1, 20L);

        List<String> ordered = names(InterfaceOrdering.sort(Arrays.asList(c3, c2, c1)));
        assertEquals(Arrays.asList("A", "B", "C"), ordered);
    }

    @Test
    void nullOrderGoesLast() {
        IfmapConfig noOrder = TestConfigs.config("Z", "GP81", 1, 1L);
        noOrder.setInterfaceOrder(null);
        List<String> ordered = names(InterfaceOrdering.sort(
                Arrays.asList(noOrder, TestConfigs.config("A", "GP81", 9, 2L))));
        assertEquals(Arrays.asList("A", "Z"), ordered);
    }

    @Test
    void disabledConfigsAreFiltered() {
        IfmapConfig disabled = TestConfigs.config("B", "GP81", 2, 2L);
        disabled.setStatus(Integer.valueOf(0));
        IfmapConfig deleted = TestConfigs.config("C", "GP81", 3, 3L);
        deleted.setDelStatus(Integer.valueOf(1));

        List<String> ordered = names(InterfaceOrdering.plan(
                Arrays.asList(TestConfigs.config("A", "GP81", 1, 1L), disabled, deleted)));
        assertEquals(Arrays.asList("A"), ordered);
    }

    @Test
    void frontInterfaceExecutesFirstEvenWhenOrderIsLater() {
        IfmapConfig main = TestConfigs.config("MAIN", "GP81", 1, 10L);
        main.setFrontInterfaceNo("PRE");
        IfmapConfig pre = TestConfigs.config("PRE", "GP81", 9, 90L);

        List<String> ordered = names(InterfaceOrdering.plan(Arrays.asList(main, pre)));
        assertEquals(Arrays.asList("PRE", "MAIN"), ordered);
    }

    @Test
    void cycleIsRejectedWithPath() {
        IfmapConfig a = TestConfigs.config("A", "GP81", 1, 1L);
        a.setFrontInterfaceNo("B");
        IfmapConfig b = TestConfigs.config("B", "GP81", 2, 2L);
        b.setFrontInterfaceNo("A");

        IfmapConfigException e = assertThrows(IfmapConfigException.class,
                () -> InterfaceOrdering.plan(Arrays.asList(a, b)));
        assertTrue(e.getMessage().contains("循环依赖"), e.getMessage());
        assertTrue(e.getMessage().contains("A"), e.getMessage());
        assertTrue(e.getMessage().contains("B"), e.getMessage());
    }

    @Test
    void unknownFrontInterfaceIsIgnored() {
        IfmapConfig main = TestConfigs.config("MAIN", "GP81", 1, 1L);
        main.setFrontInterfaceNo("NOT_PRESENT");
        assertEquals(Arrays.asList("MAIN"), names(InterfaceOrdering.plan(Arrays.asList(main))));
    }

    @Test
    void topologicalKeepsUnrelatedOrder() {
        IfmapConfig a = TestConfigs.config("A", "GP81", 1, 1L);
        IfmapConfig b = TestConfigs.config("B", "GP81", 2, 2L);
        IfmapConfig c = TestConfigs.config("C", "GP81", 3, 3L);
        c.setFrontInterfaceNo("A");
        // 深度优先：A 已因顺序先访问，C 的前置已满足，故 B、C 的相对顺序保持不变
        assertEquals(Arrays.asList("A", "B", "C"), names(InterfaceOrdering.topological(Arrays.asList(a, b, c))));
    }

    private static List<String> names(List<IfmapConfig> configs) {
        List<String> result = new ArrayList<String>();
        for (IfmapConfig config : configs) {
            result.add(config.getInterfaceNo());
        }
        return result;
    }
}
