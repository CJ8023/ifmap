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
import cn.cj.ifmap.json.jackson.JacksonJsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonConfigMapper} 的映射契约：键名容忍、缺失按 DDL 默认值补齐、必填缺失失败、数字/时间体检。
 *
 * <p>用默认的 Jackson 实现做 {@link JsonOps}：被测代码只依赖 SPI，换 fastjson 也跑同一批断言
 * （fastjson 会把数字解析成 {@code BigDecimal}，本测试的「数字与数字串都认」用例正好覆盖两条路径）。</p>
 *
 * @author caijun
 */
class JsonConfigMapperTest {

    private static final JsonOps JSON = new JacksonJsonOps();

    /** 一份「合法最小」报文：只给 DDL 里必填的 5 列。 */
    private static final String MINIMAL = "{\"interfaceNo\":\"IF_A\",\"interfaceCode\":\"CODE_A\","
            + "\"interfaceName\":\"接口A\",\"busiNode\":\"APPLY\",\"bankCode\":\"BANK_A\"}";

    @Test
    @DisplayName("camelCase 与 snake_case 两种写法映射出完全相同的对象")
    void camelCaseAndSnakeCaseAreEquivalent() {
        String camel = "[{\"keyId\":1001,\"interfaceNo\":\"IF_A\",\"interfaceCode\":\"CODE_A\",\"interfaceName\":\"接口A\","
                + "\"projectCode\":\"P1\",\"busiNode\":\"APPLY\",\"bankCode\":\"BANK_A\",\"bankName\":\"甲行\","
                + "\"financingMode\":\"0\",\"frontInterfaceNo\":\"IF_0\",\"interfaceOrder\":3,"
                + "\"requestParamTemplate\":\"{\\\"a\\\":\\\"@a\\\"}\",\"responseParamTemplate\":\"@resp\","
                + "\"resultFlag\":\"$.code\",\"successValue\":\"0000|SUCCESS\",\"strategyName\":\"strategyA\","
                + "\"status\":1,\"version\":2,\"remark\":\"备注\",\"delStatus\":0,\"deletedSeq\":0,"
                + "\"addTime\":\"2026-01-02 03:04:05\"}]";
        String snake = camel
                .replace("\"keyId\"", "\"key_id\"")
                .replace("\"interfaceNo\"", "\"interface_no\"")
                .replace("\"interfaceCode\"", "\"interface_code\"")
                .replace("\"interfaceName\"", "\"interface_name\"")
                .replace("\"projectCode\"", "\"project_code\"")
                .replace("\"busiNode\"", "\"busi_node\"")
                .replace("\"bankCode\"", "\"bank_code\"")
                .replace("\"bankName\"", "\"bank_name\"")
                .replace("\"financingMode\"", "\"financing_mode\"")
                .replace("\"frontInterfaceNo\"", "\"front_interface_no\"")
                .replace("\"interfaceOrder\"", "\"interface_order\"")
                .replace("\"requestParamTemplate\"", "\"request_param_template\"")
                .replace("\"responseParamTemplate\"", "\"response_param_template\"")
                .replace("\"resultFlag\"", "\"result_flag\"")
                .replace("\"successValue\"", "\"success_value\"")
                .replace("\"strategyName\"", "\"strategy_name\"")
                .replace("\"delStatus\"", "\"del_status\"")
                .replace("\"deletedSeq\"", "\"deleted_seq\"")
                .replace("\"addTime\"", "\"add_time\"");

        IfmapConfig fromCamel = only(JsonConfigMapper.toConfigs(JSON, camel, null));
        IfmapConfig fromSnake = only(JsonConfigMapper.toConfigs(JSON, snake, null));

        assertEquals(Long.valueOf(1001L), fromCamel.getKeyId());
        assertEquals("IF_A", fromCamel.getInterfaceNo());
        assertEquals("CODE_A", fromCamel.getInterfaceCode());
        assertEquals("接口A", fromCamel.getInterfaceName());
        assertEquals("P1", fromCamel.getProjectCode());
        assertEquals("APPLY", fromCamel.getBusiNode());
        assertEquals("BANK_A", fromCamel.getBankCode());
        assertEquals("甲行", fromCamel.getBankName());
        assertEquals("0", fromCamel.getFinancingMode());
        assertEquals("IF_0", fromCamel.getFrontInterfaceNo());
        assertEquals(Integer.valueOf(3), fromCamel.getInterfaceOrder());
        assertEquals("{\"a\":\"@a\"}", fromCamel.getRequestParamTemplate());
        assertEquals("@resp", fromCamel.getResponseParamTemplate());
        assertEquals("$.code", fromCamel.getResultFlag());
        assertEquals("0000|SUCCESS", fromCamel.getSuccessValue());
        assertEquals("strategyA", fromCamel.getStrategyName());
        assertEquals(Integer.valueOf(2), fromCamel.getVersion());
        assertEquals("备注", fromCamel.getRemark());
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), fromCamel.getAddTime());
        assertEquals(-1L, fromCamel.getTenantId().longValue());

        assertEquals(fromCamel.getKeyId(), fromSnake.getKeyId());
        assertEquals(fromCamel.getInterfaceOrder(), fromSnake.getInterfaceOrder());
        assertEquals(fromCamel.getRequestParamTemplate(), fromSnake.getRequestParamTemplate());
        assertEquals(fromCamel.getResultFlag(), fromSnake.getResultFlag());
        assertEquals(fromCamel.getAddTime(), fromSnake.getAddTime());
        assertTrue(fromSnake.enabled());
    }

    @Test
    @DisplayName("缺失列按建表 DDL 默认值补齐（与 JdbcConfigRepository 读出的对象一致）")
    void missingColumnsFallBackToDdlDefaults() {
        IfmapConfig config = only(JsonConfigMapper.toConfigs(JSON, "[" + MINIMAL + "]", null));

        assertEquals(Integer.valueOf(0), config.getInterfaceOrder());
        assertEquals(Integer.valueOf(1), config.getStatus());
        assertEquals(Integer.valueOf(0), config.getVersion());
        assertEquals(Integer.valueOf(0), config.getDelStatus());
        assertEquals(Long.valueOf(0L), config.getDeletedSeq());
        assertEquals("", config.getResultFlag());
        assertEquals("", config.getSuccessValue());
        assertEquals("", config.getStrategyName());
        assertEquals("", config.getRemark());
        assertEquals("", config.getAddUserId());
        assertEquals("", config.getAddRequestId());
        assertEquals("", config.getModifyUserId());
        assertEquals("", config.getModifyRequestId());
        assertNull(config.getKeyId());
        assertNull(config.getProjectCode());
        assertNull(config.getRequestParamTemplate());
        assertNull(config.getAddTime());
        assertTrue(config.enabled());
    }

    @Test
    @DisplayName("入参租户优先于报文里的 tenantId（远端可能串租，查询租户才是权威）")
    void tenantIdArgumentWins() {
        String body = "[" + MINIMAL.replace("}", ",\"tenantId\":999}") + "]";
        assertEquals(7L, only(JsonConfigMapper.toConfigs(JSON, body, Long.valueOf(7L))).getTenantId().longValue());
        assertEquals(-1L, only(JsonConfigMapper.toConfigs(JSON, body, null)).getTenantId().longValue());
    }

    @Test
    @DisplayName("必填列缺失或空白 → 明确失败，并指出第几条、缺哪个字段")
    void requiredColumnMissingFails() {
        String missingBankCode = "[{\"interfaceNo\":\"IF_A\",\"interfaceCode\":\"C\",\"interfaceName\":\"N\",\"busiNode\":\"B\"}]";
        IfmapConfigException error = assertThrows(IfmapConfigException.class,
                () -> JsonConfigMapper.toConfigs(JSON, missingBankCode, null));
        assertTrue(error.getMessage().contains("bankCode"), error.getMessage());
        assertTrue(error.getMessage().contains("第 1 条"), error.getMessage());

        String blankInterfaceNo = "[" + MINIMAL.replace("\"IF_A\"", "\"   \"") + "]";
        assertThrows(IfmapConfigException.class, () -> JsonConfigMapper.toConfigs(JSON, blankInterfaceNo, null));

        String secondBroken = "[" + MINIMAL + "," + MINIMAL.replace("\"接口A\"", "null") + "]";
        IfmapConfigException second = assertThrows(IfmapConfigException.class,
                () -> JsonConfigMapper.toConfigs(JSON, secondBroken, null));
        assertTrue(second.getMessage().contains("第 2 条"), second.getMessage());
    }

    @Test
    @DisplayName("数字列容忍 JSON 数字与数字字符串（远端 VO 里 keyId/interfaceOrder 就是 String）")
    void numbersAcceptBothJsonNumbersAndNumericStrings() {
        String body = "[{\"interfaceNo\":\"IF_A\",\"interfaceCode\":\"C\",\"interfaceName\":\"N\",\"busiNode\":\"B\","
                + "\"bankCode\":\"K\",\"keyId\":\"1001\",\"interfaceOrder\":\"3\",\"status\":\"0\"}]";
        IfmapConfig config = only(JsonConfigMapper.toConfigs(JSON, body, null));
        assertEquals(Long.valueOf(1001L), config.getKeyId());
        assertEquals(Integer.valueOf(3), config.getInterfaceOrder());
        assertEquals(Integer.valueOf(0), config.getStatus());
        assertFalse(config.enabled());
    }

    @Test
    @DisplayName("小数、非法串、非数字类型 → 失败（不静默截断）")
    void invalidNumbersFail() {
        assertThrows(IfmapConfigException.class, () -> JsonConfigMapper.toConfigs(JSON, withOrder("1.5"), null));
        assertThrows(IfmapConfigException.class, () -> JsonConfigMapper.toConfigs(JSON, withOrder("abc"), null));
        assertThrows(IfmapConfigException.class, () -> JsonConfigMapper.toConfigs(JSON, withOrder("{}"), null));
        assertThrows(IfmapConfigException.class, () -> JsonConfigMapper.toConfigs(JSON, withOrder("99999999999"), null));
    }

    @Test
    @DisplayName("时间列容忍 ISO-8601 与 yyyy-MM-dd HH:mm:ss（带毫秒也行），非法时间失败")
    void dateAcceptsIsoAndSpaceSeparated() {
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5),
                only(JsonConfigMapper.toConfigs(JSON, withTime("2026-01-02T03:04:05"), null)).getAddTime());
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123000000),
                only(JsonConfigMapper.toConfigs(JSON, withTime("2026-01-02 03:04:05.123"), null)).getAddTime());
        assertThrows(IfmapConfigException.class, () -> JsonConfigMapper.toConfigs(JSON, withTime("2026/01/02"), null));
    }

    @Test
    @DisplayName("未识别的键直接忽略（远端可以自由加字段）")
    void unknownKeysAreIgnored() {
        String body = "[" + MINIMAL.replace("}", ",\"brandNewColumn\":\"x\",\"another_one\":123}") + "]";
        assertEquals("IF_A", only(JsonConfigMapper.toConfigs(JSON, body, null)).getInterfaceNo());
    }

    @Test
    @DisplayName("文本列收到对象/数组 → 失败（不允许变成 {a=1} 这种 Java toString）")
    void objectInTextFieldFails() {
        String body = "[" + MINIMAL.replace("}", ",\"projectCode\":{\"a\":1}}") + "]";
        IfmapConfigException error = assertThrows(IfmapConfigException.class,
                () -> JsonConfigMapper.toConfigs(JSON, body, null));
        assertTrue(error.getMessage().contains("projectCode"), error.getMessage());
    }

    @Test
    @DisplayName("空体 / 空白 / JSON null / 空数组 → 空列表；非数组或非法 JSON → 失败")
    void bodyShapeHandling() {
        assertTrue(JsonConfigMapper.toConfigs(JSON, null, null).isEmpty());
        assertTrue(JsonConfigMapper.toConfigs(JSON, "", null).isEmpty());
        assertTrue(JsonConfigMapper.toConfigs(JSON, "   ", null).isEmpty());
        assertTrue(JsonConfigMapper.toConfigs(JSON, "null", null).isEmpty());
        assertTrue(JsonConfigMapper.toConfigs(JSON, "[]", null).isEmpty());

        assertThrows(IfmapConfigException.class, () -> JsonConfigMapper.toConfigs(JSON, "{}", null));
        assertThrows(IfmapConfigException.class, () -> JsonConfigMapper.toConfigs(JSON, "\"text\"", null));
        assertThrows(IfmapConfigException.class, () -> JsonConfigMapper.toConfigs(JSON, "[1,2]", null));
        assertThrows(IfmapConfigException.class, () -> JsonConfigMapper.toConfigs(JSON, "[null]", null));
        assertThrows(IfmapConfigException.class, () -> JsonConfigMapper.toConfigs(JSON, "{\"a\":", null));
    }

    @Test
    @DisplayName("逻辑分支：远端不给 logic_branch_order 时按下标补齐，保全「按返回顺序生效」语义")
    void branchesFallBackToIndexOrder() {
        String body = "[{\"keyId\":11,\"interfaceNo\":\"IF_A\",\"logicBranchName\":\"兜底\",\"logicBranchFlag\":\"\"},"
                + "{\"keyId\":12,\"interfaceNo\":\"IF_A\",\"logicBranchName\":\"甲行\",\"logicBranchFlag\":\"$.bankCode\","
                + "\"logicBranchValue\":\"A|B\",\"methodFlag\":\"methodA\"},"
                + "{\"keyId\":13,\"interfaceNo\":\"IF_A\",\"logicBranchName\":\"乙行\",\"logicBranchFlag\":\"$.bankCode\","
                + "\"logicBranchOrder\":9}]";
        List<LogicBranchConfig> branches = JsonConfigMapper.toBranches(JSON, body, Long.valueOf(3L));

        assertEquals(3, branches.size());
        assertEquals(Integer.valueOf(1), branches.get(0).getLogicBranchOrder());
        assertEquals(Integer.valueOf(2), branches.get(1).getLogicBranchOrder());
        assertEquals(Integer.valueOf(9), branches.get(2).getLogicBranchOrder());

        assertEquals("", branches.get(0).getLogicBranchFlag());
        assertNull(branches.get(0).getMethodFlag());
        assertTrue(branches.get(0).isDefaultBranch());
        assertEquals("methodA", branches.get(1).getMethodFlag());
        assertFalse(branches.get(1).isDefaultBranch());
        assertEquals("", branches.get(2).getLogicBranchValue());
        assertEquals("", branches.get(2).getRemark());
        assertEquals(Integer.valueOf(0), branches.get(0).getDelStatus());
        assertEquals(Long.valueOf(0L), branches.get(0).getDeletedSeq());
        assertEquals(3L, branches.get(0).getTenantId().longValue());
    }

    @Test
    @DisplayName("逻辑分支：缺 interface_no 或 logic_branch_name → 失败")
    void branchRequiredColumnsMissingFail() {
        String noName = "[{\"interfaceNo\":\"IF_A\",\"logicBranchFlag\":\"$.a\"}]";
        IfmapConfigException error = assertThrows(IfmapConfigException.class,
                () -> JsonConfigMapper.toBranches(JSON, noName, null));
        assertTrue(error.getMessage().contains("logicBranchName"), error.getMessage());
        assertThrows(IfmapConfigException.class,
                () -> JsonConfigMapper.toBranches(JSON, "[{\"logicBranchName\":\"x\"}]", null));
    }

    private static String withOrder(String orderValue) {
        return "[" + MINIMAL.replace("}", ",\"interfaceOrder\":" + orderValue + "}") + "]";
    }

    private static String withTime(String timeValue) {
        return "[" + MINIMAL.replace("}", ",\"addTime\":\"" + timeValue + "\"}") + "]";
    }

    private static IfmapConfig only(List<IfmapConfig> configs) {
        assertEquals(1, configs.size());
        IfmapConfig config = configs.get(0);
        assertNotNull(config);
        return config;
    }
}
