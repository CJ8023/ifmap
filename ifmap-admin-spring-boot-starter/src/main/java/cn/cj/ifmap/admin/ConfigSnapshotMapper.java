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

import cn.cj.ifmap.core.config.CallDirection;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.json.JsonOps;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置快照的序列化 / 反序列化 / 差异计算（`ifmap_config_history.snapshot` 与 `diff`）。
 *
 * <p>设计取舍：<b>不用 Bean 反射序列化</b>，而是显式列出业务列（把 {@code IfmapConfig} 与
 * {@code Map} 互转，再交给 {@link JsonOps} 输出）。这样做换来三个确定性：</p>
 * <ol>
 *   <li>快照只含业务列，没有 {@code LocalDateTime} / {@code getClass} 之类的"序列化噪声"，
 *       人读历史时直接就是配置内容；</li>
 *   <li>不依赖 Jackson 的 JSR-310 模块是否在 classpath，也不受其日期格式变化影响；</li>
 *   <li>回滚是"逐字段显式赋值"，不会因为反序列化静默丢字段。</li>
 * </ol>
 *
 * @author caijun
 */
public class ConfigSnapshotMapper {

    private final JsonOps jsonOps;

    public ConfigSnapshotMapper(JsonOps jsonOps) {
        if (jsonOps == null) {
            throw new IfmapConfigException("JsonOps 不能为空");
        }
        this.jsonOps = jsonOps;
    }

    /** 生成配置快照（JSON 文本，字段顺序固定）。 */
    public String toSnapshot(IfmapConfig config) {
        if (config == null) {
            throw new IfmapConfigException("配置不能为空");
        }
        return jsonOps.toJson(toMap(config));
    }

    /** 把快照还原为配置对象（只还原业务列 + keyId/tenantId）。 */
    public IfmapConfig fromSnapshot(String snapshot) {
        if (snapshot == null || snapshot.trim().isEmpty()) {
            throw new IfmapConfigException("历史快照为空，无法回滚");
        }
        Object parsed;
        try {
            parsed = jsonOps.parse(snapshot);
        } catch (RuntimeException e) {
            throw new IfmapConfigException("历史快照不是合法 JSON，无法回滚：" + e.getMessage(), e);
        }
        if (!(parsed instanceof Map)) {
            throw new IfmapConfigException("历史快照不是 JSON 对象，无法回滚：" + snapshot);
        }
        Map<?, ?> map = (Map<?, ?>) parsed;
        IfmapConfig config = new IfmapConfig();
        config.setKeyId(asLong(map.get("keyId")));
        config.setTenantId(asLong(map.get("tenantId")));
        config.setInterfaceNo(asString(map.get("interfaceNo")));
        config.setInterfaceCode(asString(map.get("interfaceCode")));
        config.setProjectCode(asString(map.get("projectCode")));
        config.setInterfaceName(asString(map.get("interfaceName")));
        config.setBusiNode(asString(map.get("busiNode")));
        config.setPartnerCode(asString(map.get("partnerCode")));
        config.setPartnerName(asString(map.get("partnerName")));
        config.setFinancingMode(asString(map.get("financingMode")));
        config.setInterfaceOrder(asInt(map.get("interfaceOrder")));
        // 老快照没有该键：按 OUTBOUND 还原（列默认值同口径，回滚不会把方向改坏）
        config.setCallDirection(CallDirection.fromName(asString(map.get("callDirection"))));
        config.setRequestParamTemplate(asString(map.get("requestParamTemplate")));
        config.setResponseParamTemplate(asString(map.get("responseParamTemplate")));
        config.setResultJudge(resolveResultJudge(map));
        // judge_name 是 NOT NULL DEFAULT ''：老快照没有该键，回滚出来的对象要和库里读出来的一致（空串而不是 null）
        String judgeName = asString(map.get("judgeName"));
        config.setJudgeName(judgeName == null ? "" : judgeName);
        config.setPreStrategyName(asString(map.get("preStrategyName")));
        config.setPostStrategyName(asString(map.get("postStrategyName")));
        config.setStatus(asInt(map.get("status")));
        config.setRemark(asString(map.get("remark")));
        return config;
    }

    /**
     * 判定规格：新快照读 {@code resultJudge}；<b>老快照</b>（只有 {@code resultFlag}/{@code successValue}）
     * 现算成等价新规格 —— <b>只读不写回</b>，历史行保持原样（回滚不该顺手改历史，审计要能看出"当时写的什么"）。
     *
     * <p>语义对齐（老口径见已删除的 {@code ResponseJudge}）：</p>
     * <ul>
     *   <li>{@code resultFlag} 为空 → 老口径"默认成功" ⇒ {@code {"op":"always"}}；</li>
     *   <li>{@code successValue} 有值 → 老口径"多值、{@code ;}/{@code ,} 分隔、任一命中、忽略大小写"
     *       ⇒ {@code {"all":[{path, op:"in", value:[...], ignoreCase:true}]}}；</li>
     *   <li>{@code successValue} 为空 → 老口径"非空且非 false/0 即成功"，新规格没有 falsy 概念，
     *       取最接近的 {@code notBlank}（比 {@code exists} 严：空串判失败）。这条只在回滚极老的
     *       历史行时才会走到，宽松/从严的差异会体现在回滚后的判定上，已在此注明。</li>
     * </ul>
     */
    String resolveResultJudge(Map<?, ?> map) {
        String judge = asString(map.get("resultJudge"));
        if (judge != null && !judge.trim().isEmpty()) {
            return judge;
        }
        String flag = trimToNull(asString(map.get("resultFlag")));
        if (flag == null) {
            return "{\"op\":\"always\"}";
        }
        Map<String, Object> condition = new LinkedHashMap<String, Object>();
        condition.put("path", flag);
        java.util.List<String> values = splitLegacyValues(asString(map.get("successValue")));
        if (values.isEmpty()) {
            condition.put("op", "notBlank");
        } else {
            condition.put("op", "in");
            condition.put("value", values);
            condition.put("ignoreCase", Boolean.TRUE);
        }
        Map<String, Object> all = new LinkedHashMap<String, Object>();
        all.put("all", java.util.Collections.singletonList(condition));
        return jsonOps.toJson(all);
    }

    /** 老 {@code success_value} 的多值切分（{@code ;} / {@code ,}，去空白、丢空段 —— 与老实现一致）。 */
    static java.util.List<String> splitLegacyValues(String expected) {
        java.util.List<String> values = new java.util.ArrayList<String>();
        String text = trimToNull(expected);
        if (text == null) {
            return values;
        }
        for (String part : text.split("[;,]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 计算两个版本的字段差异。
     *
     * @return {@code {"字段":{"before":..,"after":..}}}；无差异返回 {@code null}
     */
    public String diff(IfmapConfig before, IfmapConfig after) {
        if (after == null) {
            return null;
        }
        Map<String, Object> oldValues = before == null ? new LinkedHashMap<String, Object>() : toMap(before);
        Map<String, Object> newValues = toMap(after);
        Map<String, Object> changed = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            Object oldValue = oldValues.get(entry.getKey());
            if (!equals(oldValue, entry.getValue())) {
                Map<String, Object> pair = new LinkedHashMap<String, Object>();
                pair.put("before", oldValue);
                pair.put("after", entry.getValue());
                changed.put(entry.getKey(), pair);
            }
        }
        return changed.isEmpty() ? null : jsonOps.toJson(changed);
    }

    /** 配置 → 有序 Map（快照口径：业务列 + keyId/tenantId）。 */
    static Map<String, Object> toMap(IfmapConfig config) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("keyId", config.getKeyId());
        map.put("tenantId", config.getTenantId());
        map.put("interfaceNo", config.getInterfaceNo());
        map.put("interfaceCode", config.getInterfaceCode());
        map.put("projectCode", config.getProjectCode());
        map.put("interfaceName", config.getInterfaceName());
        map.put("busiNode", config.getBusiNode());
        map.put("partnerCode", config.getPartnerCode());
        map.put("partnerName", config.getPartnerName());
        map.put("financingMode", config.getFinancingMode());
        map.put("interfaceOrder", config.getInterfaceOrder());
        map.put("callDirection", (config.getCallDirection() == null
                ? CallDirection.OUTBOUND : config.getCallDirection()).name());
        map.put("requestParamTemplate", config.getRequestParamTemplate());
        map.put("responseParamTemplate", config.getResponseParamTemplate());
        map.put("resultJudge", config.getResultJudge());
        map.put("judgeName", config.getJudgeName());
        map.put("preStrategyName", config.getPreStrategyName());
        map.put("postStrategyName", config.getPostStrategyName());
        map.put("status", config.getStatus());
        map.put("remark", config.getRemark());
        return map;
    }

    /** 把 JSON 文本解析为结构，供管理端直接返回给前端（解析失败返回 null）。 */
    public Object parseJsonOrNull(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return jsonOps.parse(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean equals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer asInt(Object value) {
        Long number = asLong(value);
        return number == null ? null : Integer.valueOf(number.intValue());
    }
}
