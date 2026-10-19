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
        config.setJudgeName("ruleSpec");
        config.setPreStrategyName("demoStrategy");
        config.setPostStrategyName("auditStrategy");

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
        assertEquals("{\"all\":[{\"path\":\"$.resultCode\",\"op\":\"eq\",\"value\":\"0000\"}]}",
                restored.getResultJudge());
        assertEquals("ruleSpec", restored.getJudgeName());
        assertEquals("demoStrategy", restored.getPreStrategyName());
        assertEquals("auditStrategy", restored.getPostStrategyName());
        assertEquals(1, ((Number) restored.getStatus()).intValue());
    }

    @Test
    @DisplayName("老快照（result_flag/success_value）能读成等价新规格：in + 忽略大小写，多值按 ; 或 , 切")
    void legacySnapshotWithFlagAndSuccessValueIsConverted() {
        String legacy = "{\"keyId\":1001,\"interfaceNo\":\"IF_A\",\"interfaceCode\":\"CMB_IF_A\","
                + "\"interfaceName\":\"接口A\",\"busiNode\":\"apply\",\"partnerCode\":\"CMB\","
                + "\"interfaceOrder\":1,\"resultFlag\":\"$.resultCode\",\"successValue\":\"0000;success,OK\"}";

        IfmapConfig restored = mapper.fromSnapshot(legacy);

        assertEquals("{\"all\":[{\"path\":\"$.resultCode\",\"op\":\"in\","
                        + "\"value\":[\"0000\",\"success\",\"OK\"],\"ignoreCase\":true}]}",
                restored.getResultJudge(), "老 success_value 是多值（; , 分隔）、大小写不敏感，转换要保住这两个语义");
        assertEquals("", restored.getJudgeName(), "老快照没有判定器概念 → 空串 = 默认 ruleSpec");
    }

    @Test
    @DisplayName("老快照没有 result_flag（空或缺失）→ 转成 always，而不是留空导致回滚后无法判定")
    void legacySnapshotWithoutFlagBecomesAlways() {
        String blankFlag = "{\"keyId\":1001,\"interfaceNo\":\"IF_A\",\"resultFlag\":\"\""
                + ",\"successValue\":\"0000\"}";
        assertEquals("{\"op\":\"always\"}", mapper.fromSnapshot(blankFlag).getResultJudge());

        String noColumns = "{\"keyId\":1001,\"interfaceNo\":\"IF_A\"}";
        assertEquals("{\"op\":\"always\"}", mapper.fromSnapshot(noColumns).getResultJudge());
    }

    @Test
    @DisplayName("新快照只写新键：resultFlag/successValue 不再出现（老字段不再被写回）")
    void newSnapshotDropsLegacyKeys() {
        String snapshot = mapper.toSnapshot(AdminTestSupport.config("IF_A", "apply", 1));

        assertTrue(snapshot.contains("\"resultJudge\""), snapshot);
        assertFalse(snapshot.contains("resultFlag"), "新快照不应再写老键：" + snapshot);
        assertFalse(snapshot.contains("successValue"), "新快照不应再写老键：" + snapshot);
    }

    @Test
    @DisplayName("差异只含变化的字段，未变化的不出现")
    void diffOnlyContainsChangedFields() {
        IfmapConfig before = AdminTestSupport.config("IF_A", "apply", 1);
        IfmapConfig after = AdminTestSupport.config("IF_A", "apply", 1);
        after.setResultJudge("{\"all\":[{\"path\":\"$.resultCode\",\"op\":\"eq\",\"value\":\"0001\"}]}");
        after.setRemark("改了");

        String diff = mapper.diff(before, after);
        assertNotNull(diff);
        assertTrue(diff.contains("resultJudge"), diff);
        assertTrue(diff.contains("0001"), diff);
        assertTrue(diff.contains("改了"), diff);
        assertFalse(diff.contains("interfaceCode"), "未变化字段不应进 diff：" + diff);

        Map<?, ?> parsed = (Map<?, ?>) AdminTestSupport.snapshots().parseJsonOrNull(diff);
        assertNotNull(parsed);
        assertTrue(parsed.containsKey("resultJudge"));
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
