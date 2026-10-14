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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 执行顺序：启用过滤 + interface_order 升序（同值 key_id 兜底）。 */
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
    void enabledFiltersDisabledAndDeleted() {
        IfmapConfig disabled = TestConfigs.config("B", "GP81", 2, 2L);
        disabled.setStatus(Integer.valueOf(0));
        IfmapConfig deleted = TestConfigs.config("C", "GP81", 3, 3L);
        deleted.setDelStatus(Integer.valueOf(1));

        List<String> ordered = names(InterfaceOrdering.sort(InterfaceOrdering.enabled(
                Arrays.asList(TestConfigs.config("A", "GP81", 1, 1L), disabled, deleted))));
        assertEquals(Arrays.asList("A"), ordered);
    }

    @Test
    void enabledKeepsRowsWithNullStatusAndNullDelStatus() {
        // null 视为启用/未删除：老行（DDL 之前建的）不会因为字段没填就消失
        IfmapConfig legacy = TestConfigs.config("A", "GP81", 1, 1L);
        legacy.setStatus(null);
        legacy.setDelStatus(null);

        assertEquals(Arrays.asList("A"), names(InterfaceOrdering.enabled(Arrays.asList(legacy))));
    }

    @Test
    void emptyInputYieldsEmptyPlan() {
        assertEquals(0, InterfaceOrdering.sort(InterfaceOrdering.enabled(null)).size());
        assertEquals(0, InterfaceOrdering.sort(InterfaceOrdering.enabled(
                Arrays.<IfmapConfig>asList())).size());
    }

    private static List<String> names(List<IfmapConfig> configs) {
        List<String> result = new ArrayList<String>();
        for (IfmapConfig config : configs) {
            result.add(config.getInterfaceNo());
        }
        return result;
    }
}
