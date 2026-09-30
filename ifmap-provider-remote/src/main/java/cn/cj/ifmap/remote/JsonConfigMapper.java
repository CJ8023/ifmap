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
package cn.cj.ifmap.remote;

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.json.JsonOps;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 远端 JSON → {@link IfmapConfig} / {@link LogicBranchConfig} 的映射器。
 *
 * <p><b>键名容忍</b>：先归一化键名（去掉 {@code _} / {@code -} 后转小写）再匹配，因此
 * {@code interfaceNo}、{@code interface_no}、{@code INTERFACE-NO} 等价 —— 远端服务与 ifmap 的
 * 命名风格不一致（一个 camelCase、一个 snake_case）无需改造任何一侧；未识别的键直接忽略
 * （远端可以自由加字段）。</p>
 *
 * <p><b>缺失字段按建表 DDL 的默认值补齐</b>，与 {@code JdbcConfigRepository} 读出来的对象逐字段一致：
 * {@code interface_order / version / del_status / deleted_seq → 0}、{@code status → 1}、
 * {@code result_flag / success_value / strategy_name / remark → ''}。唯一例外是
 * {@link #toBranches} 的 {@code logic_branch_order}：远端通常不返回该列，而存量语义是
 * 「<b>按返回顺序</b>生效」，所以按下标（从 1 起）补齐，而不是一律填 0 退化成靠主键兜底。</p>
 *
 * <p><b>必填字段按 DDL 判定</b>：必填 = 建表脚本里 {@code NOT NULL} 且<b>没有</b> {@code DEFAULT} 的列
 * （配置：{@code interface_no / interface_code / interface_name / busi_node / bank_code}；
 * 分支：{@code interface_no / logic_branch_name}）。缺一个就抛 {@link IfmapConfigException}
 * 并说明是第几条、缺哪个字段 —— 不留 null 让引擎在后面某个环节莫名报错。</p>
 *
 * <p><b>数字与时间</b>：数字列容忍 JSON 数字与数字字符串（远端 VO 里 {@code keyId / interfaceOrder}
 * 是 String，这是真实存在的写法），小数、非法串、超 {@code int} 范围一律失败；时间列容忍
 * ISO-8601（{@code 2026-01-02T03:04:05}）与常见库格式（{@code 2026-01-02 03:04:05}，可带毫秒）。</p>
 *
 * @author caijun
 */
public final class JsonConfigMapper {

    /** 整数文本的形状体检（不依赖 {@code Integer.parseInt} 的宽松行为）。 */
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");

    private JsonConfigMapper() {
    }

    /**
     * 远端响应体 → 接口配置列表（<b>不排序、不筛选</b>，由 {@link RemoteConfigRepository} 按契约处理）。
     *
     * @param jsonOps          解析用的 JSON 实现（一般直接复用引擎的那个 bean）
     * @param json             响应体原文；null / 空白按空列表处理（远端「查不到」常返回空体）
     * @param defaultTenantId  落库租户；null 按单租户 {@code -1} 处理。远端报文里的同名键<b>不</b>参与，
     *                         以调用方查询的租户为准（避免远端返回串租的数据）
     */
    public static List<IfmapConfig> toConfigs(JsonOps jsonOps, String json, Long defaultTenantId) {
        List<Map<String, Object>> rows = rows(jsonOps, json);
        List<IfmapConfig> configs = new ArrayList<IfmapConfig>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            configs.add(toConfig(rows.get(i), tenantOf(defaultTenantId), source(i, "接口配置")));
        }
        return configs;
    }

    /** 远端响应体 → 逻辑分支列表（不排序、不筛选）。 */
    public static List<LogicBranchConfig> toBranches(JsonOps jsonOps, String json, Long defaultTenantId) {
        List<Map<String, Object>> rows = rows(jsonOps, json);
        List<LogicBranchConfig> branches = new ArrayList<LogicBranchConfig>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            branches.add(toBranch(rows.get(i), tenantOf(defaultTenantId), source(i, "逻辑分支配置"), i + 1));
        }
        return branches;
    }

    private static IfmapConfig toConfig(Map<String, Object> row, Long tenantId, String source) {
        IfmapConfig c = new IfmapConfig();
        c.setTenantId(tenantId);
        c.setKeyId(number(row, "keyId", source));
        c.setInterfaceNo(required(row, "interfaceNo", source));
        c.setInterfaceCode(required(row, "interfaceCode", source));
        c.setInterfaceName(required(row, "interfaceName", source));
        c.setBusiNode(required(row, "busiNode", source));
        c.setBankCode(required(row, "bankCode", source));
        c.setProjectCode(text(row, "projectCode"));
        c.setBankName(text(row, "bankName"));
        c.setFinancingMode(text(row, "financingMode"));
        c.setFrontInterfaceNo(text(row, "frontInterfaceNo"));
        c.setInterfaceOrder(integer(row, "interfaceOrder", source, 0));
        c.setRequestParamTemplate(text(row, "requestParamTemplate"));
        c.setResponseParamTemplate(text(row, "responseParamTemplate"));
        c.setResultFlag(orEmpty(row, "resultFlag"));
        c.setSuccessValue(orEmpty(row, "successValue"));
        c.setStrategyName(orEmpty(row, "strategyName"));
        c.setStatus(integer(row, "status", source, 1));
        c.setVersion(integer(row, "version", source, 0));
        c.setRemark(orEmpty(row, "remark"));
        c.setDelStatus(integer(row, "delStatus", source, 0));
        c.setDeletedSeq(number(row, "deletedSeq", source, 0L));
        c.setAddUserId(orEmpty(row, "addUserId"));
        c.setAddTime(time(row, "addTime", source));
        c.setAddRequestId(orEmpty(row, "addRequestId"));
        c.setModifyUserId(orEmpty(row, "modifyUserId"));
        c.setModifyTime(time(row, "modifyTime", source));
        c.setModifyRequestId(orEmpty(row, "modifyRequestId"));
        return c;
    }

    private static LogicBranchConfig toBranch(Map<String, Object> row, Long tenantId, String source, int index) {
        LogicBranchConfig b = new LogicBranchConfig();
        b.setTenantId(tenantId);
        b.setKeyId(number(row, "keyId", source));
        b.setInterfaceNo(required(row, "interfaceNo", source));
        b.setLogicBranchName(required(row, "logicBranchName", source));
        b.setMethodFlag(text(row, "methodFlag"));
        b.setLogicBranchFlag(orEmpty(row, "logicBranchFlag"));
        b.setLogicBranchValue(orEmpty(row, "logicBranchValue"));
        // 缺列按下标补齐：存量引擎「按返回顺序生效」，全填 0 会退化成按主键兜底
        b.setLogicBranchOrder(integer(row, "logicBranchOrder", source, index));
        b.setRemark(orEmpty(row, "remark"));
        b.setDelStatus(integer(row, "delStatus", source, 0));
        b.setDeletedSeq(number(row, "deletedSeq", source, 0L));
        return b;
    }

    /** 取出顶层数组并逐条归一化键名；空体/null 元素/非对象元素分别有明确行为。 */
    private static List<Map<String, Object>> rows(JsonOps jsonOps, String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        if (jsonOps == null) {
            throw new IfmapConfigException("JsonOps 不能为空");
        }
        Object parsed;
        try {
            parsed = jsonOps.parse(json);
        } catch (RuntimeException e) {
            throw new IfmapConfigException("远端响应体不是合法 JSON：" + e.getMessage(), e);
        }
        if (parsed == null) {
            return Collections.emptyList();
        }
        if (!(parsed instanceof List)) {
            throw new IfmapConfigException("远端响应体不是 JSON 数组，实际是 " + typeName(parsed) + "：" + abbreviate(json));
        }
        List<?> raw = (List<?>) parsed;
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Object element = raw.get(i);
            if (!(element instanceof Map)) {
                throw new IfmapConfigException("远端响应体第 " + (i + 1) + " 条不是 JSON 对象，实际是 "
                        + (element == null ? "null" : typeName(element)));
            }
            rows.add(normalize((Map<?, ?>) element));
        }
        return rows;
    }

    /**
     * 键名归一化：去掉 {@code _} / {@code -} 并转小写。
     *
     * <p>同名不同写法同时出现时<b>后出现的覆盖前者</b>（JSON 对象里同键重复时本来就是后覆盖前，
     * 这里只是把「写法不同的同名字段」也纳入同一规则）。</p>
     */
    static String normalizeKey(String key) {
        String trimmed = key.trim();
        StringBuilder normalized = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '_' || ch == '-') {
                continue;
            }
            normalized.append(Character.toLowerCase(ch));
        }
        return normalized.toString();
    }

    private static Map<String, Object> normalize(Map<?, ?> row) {
        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : row.entrySet()) {
            normalized.put(normalizeKey(String.valueOf(entry.getKey())), entry.getValue());
        }
        return normalized;
    }

    /** 文本列：缺失/JSON null → null；数字、布尔转文本；对象/数组直接失败（避免出现 {@code {a=1}} 这种 Java toString）。 */
    private static String text(Map<String, Object> row, String field) {
        Object value = row.get(normalizeKey(field));
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new IfmapConfigException("字段 " + field + " 应为文本，实际是 " + typeName(value));
    }

    /** {@code NOT NULL DEFAULT ''} 的文本列：缺失 → 空串。 */
    private static String orEmpty(Map<String, Object> row, String field) {
        String value = text(row, field);
        return value == null ? "" : value;
    }

    /** 必填文本列：缺失或空白 → 失败。 */
    private static String required(Map<String, Object> row, String field, String source) {
        String value = text(row, field);
        if (value == null || value.trim().isEmpty()) {
            throw new IfmapConfigException(source + "缺少必填字段：" + field);
        }
        return value;
    }

    /** 整数列：缺失 → 默认值。 */
    private static Integer integer(Map<String, Object> row, String field, String source, int defaultValue) {
        Long value = number(row, field, source);
        if (value == null) {
            return Integer.valueOf(defaultValue);
        }
        try {
            return Integer.valueOf(Math.toIntExact(value.longValue()));
        } catch (ArithmeticException e) {
            throw new IfmapConfigException(source + "字段 " + field + " 超出整数范围：" + value, e);
        }
    }

    /** 长整数列：缺失 → null。 */
    private static Long number(Map<String, Object> row, String field, String source) {
        Object value = row.get(normalizeKey(field));
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            BigDecimal decimal;
            try {
                decimal = new BigDecimal(value.toString());
            } catch (NumberFormatException e) {
                throw new IfmapConfigException(source + "字段 " + field + " 不是合法数字：" + value, e);
            }
            return toLong(decimal, field, source);
        }
        if (value instanceof String) {
            String trimmed = ((String) value).trim();
            if (!INTEGER.matcher(trimmed).matches()) {
                throw new IfmapConfigException(source + "字段 " + field + " 不是整数：" + value);
            }
            return Long.valueOf(trimmed);
        }
        throw new IfmapConfigException(source + "字段 " + field + " 应为数字，实际是 " + typeName(value));
    }

    /** 长整数列：缺失 → 默认值。 */
    private static Long number(Map<String, Object> row, String field, String source, long defaultValue) {
        Long value = number(row, field, source);
        return value == null ? Long.valueOf(defaultValue) : value;
    }

    private static Long toLong(BigDecimal decimal, String field, String source) {
        if (decimal.stripTrailingZeros().scale() > 0) {
            throw new IfmapConfigException(source + "字段 " + field + " 不是整数：" + decimal.toPlainString());
        }
        try {
            return Long.valueOf(decimal.longValueExact());
        } catch (ArithmeticException e) {
            throw new IfmapConfigException(source + "字段 " + field + " 超出长整数范围：" + decimal.toPlainString(), e);
        }
    }

    /** 时间列：缺失 → null；容忍 ISO-8601 与 {@code yyyy-MM-dd HH:mm:ss[.SSS]}。 */
    private static LocalDateTime time(Map<String, Object> row, String field, String source) {
        String value = text(row, field);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String iso = value.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            throw new IfmapConfigException(source + "字段 " + field + " 不是合法时间（期望 ISO-8601 或 "
                    + "yyyy-MM-dd HH:mm:ss）：" + value, e);
        }
    }

    private static Long tenantOf(Long tenantId) {
        return tenantId == null ? Long.valueOf(-1L) : tenantId;
    }

    private static String source(int index, String what) {
        return "远端响应体第 " + (index + 1) + " 条" + what;
    }

    private static String typeName(Object value) {
        if (value instanceof Map) {
            return "JSON 对象";
        }
        if (value instanceof List) {
            return "JSON 数组";
        }
        return value.getClass().getSimpleName();
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...(已截断)";
    }
}
