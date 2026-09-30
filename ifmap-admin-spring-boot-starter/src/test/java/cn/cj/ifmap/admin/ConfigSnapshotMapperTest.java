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
package cn.cj.ifmap.admin;

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照序列化 / 回滚还原 / 差异计算。
 *
 * @author caijun
 */
class ConfigSnapshotMapperTest {

    private final ConfigSnapshotMapper mapper = AdminTestSupport.snapshots();

    @Test
    @DisplayName("快照 → 还原：业务字段逐个回到原值（回滚靠的就是这条）")
    void snapshotRoundTripKeepsBusinessFields() {
        IfmapConfig config = AdminTestSupport.config("IF_A", "apply", 2);
        config.setKeyId(1001L);
        config.setStrategyName("demoStrategy");
        config.setFrontInterfaceNo("IF_B");

        String snapshot = mapper.toSnapshot(config);
        assertTrue(snapshot.contains("\"interfaceNo\":\"IF_A\""), snapshot);
        assertFalse(snapshot.contains("version"), "快照只放业务列，不放 version/del_status 这类结构列");

        IfmapConfig restored = mapper.fromSnapshot(snapshot);
        assertEquals(config.getKeyId(), restored.getKeyId());
        assertEquals(config.getTenantId(), restored.getTenantId());
        assertEquals("IF_A", restored.getInterfaceNo());
        assertEquals("CMB_IF_A", restored.getInterfaceCode());
        assertEquals("apply", restored.getBusiNode());
        assertEquals(2, ((Number) restored.getInterfaceOrder()).intValue());
        assertEquals(config.getRequestParamTemplate(), restored.getRequestParamTemplate());
        assertEquals(config.getResponseParamTemplate(), restored.getResponseParamTemplate());
        assertEquals("$.resultCode", restored.getResultFlag());
        assertEquals("0000", restored.getSuccessValue());
        assertEquals("demoStrategy", restored.getStrategyName());
        assertEquals("IF_B", restored.getFrontInterfaceNo());
        assertEquals(1, ((Number) restored.getStatus()).intValue());
    }

    @Test
    @DisplayName("差异只含变化的字段，未变化的不出现")
    void diffOnlyContainsChangedFields() {
        IfmapConfig before = AdminTestSupport.config("IF_A", "apply", 1);
        IfmapConfig after = AdminTestSupport.config("IF_A", "apply", 1);
        after.setSuccessValue("0001");
        after.setRemark("改了");

        String diff = mapper.diff(before, after);
        assertNotNull(diff);
        assertTrue(diff.contains("successValue"), diff);
        assertTrue(diff.contains("0001"), diff);
        assertTrue(diff.contains("改了"), diff);
        assertFalse(diff.contains("interfaceCode"), "未变化字段不应进 diff：" + diff);

        Map<?, ?> parsed = (Map<?, ?>) AdminTestSupport.snapshots().parseJsonOrNull(diff);
        assertNotNull(parsed);
        assertTrue(parsed.containsKey("successValue"));
        assertTrue(parsed.containsKey("remark"));
        assertEquals(2, parsed.size());
    }

    @Test
    @DisplayName("无差异返回 null（历史表 diff 允许为空）")
    void diffReturnsNullWhenIdentical() {
        IfmapConfig config = AdminTestSupport.config("IF_A", "apply", 1);
        assertNull(mapper.diff(config, AdminTestSupport.config("IF_A", "apply", 1)));
    }

    @Test
    @DisplayName("快照非法 / 为空 → 业务异常，不静默回滚成空配置")
    void invalidSnapshotFailsFast() {
        assertThrows(IfmapConfigException.class, () -> mapper.fromSnapshot(""));
        assertThrows(IfmapConfigException.class, () -> mapper.fromSnapshot("not-json"));
        assertThrows(IfmapConfigException.class, () -> mapper.fromSnapshot("[1,2,3]"));
        assertThrows(IfmapConfigException.class, () -> mapper.toSnapshot(null));
    }
}
