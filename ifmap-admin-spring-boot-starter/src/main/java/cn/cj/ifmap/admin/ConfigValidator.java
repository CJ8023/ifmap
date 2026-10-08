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

import cn.cj.ifmap.core.config.ConfigQuery;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.SpecialDealStrategyRegistry;
import cn.cj.ifmap.core.validate.ContractValidator;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 保存前校验清单（设计 §8.3 的代码化实现）。
 *
 * <p>校验分三层，任何一层发现问题都会进 {@link ValidationResult#getErrors()}，
 * 管理端据此拒绝保存：</p>
 * <ol>
 *   <li><b>模板契约</b>：模板必须是合法 JSON；{@code @FUN} 规则必须已注册且参数可匹配重载
 *       （复用引擎的 {@link ContractValidator}，与启动期自检同一套逻辑）；</li>
 *   <li><b>引用合法性</b>：模板里的 JsonPath 语法必须合法；{@code strategy_name} 必须在已注册的
 *       特殊处理策略里；{@code front_interface_no} 必须存在且不成环；</li>
 *   <li><b>结构约束</b>：{@code (tenant_id, interface_no, busi_node, interface_order)} 不能重复；
 *       分支的默认兜底 ≤ 1 条、{@code logic_branch_order} 不重复、{@code method_flag} 必须是已注册动作。</li>
 * </ol>
 *
 * @author caijun
 */
public class ConfigValidator {

    /** 前置接口链最大深度（与编排器护栏一致）。 */
    private static final int MAX_FRONT_DEPTH = 64;

    /** 模板里的 JsonPath 片段（{@code $.a.b} / {@code $.list[0].x} / {@code $.a.*}）。 */
    private static final Pattern JSON_PATH = Pattern.compile("\\$\\.[A-Za-z0-9_\\[\\]\\.\\*\\?']*");

    private final ContractValidator contractValidator;
    private final JsonOps jsonOps;
    private final SpecialDealStrategyRegistry specialDeals;
    private final ActionRegistry actions;
    private final JdbcConfigRepository repository;

    public ConfigValidator(ContractValidator contractValidator, JsonOps jsonOps,
                           SpecialDealStrategyRegistry specialDeals, ActionRegistry actions,
                           JdbcConfigRepository repository) {
        if (jsonOps == null) {
            throw new IllegalArgumentException("jsonOps 不能为空");
        }
        if (repository == null) {
            throw new IllegalArgumentException("repository 不能为空");
        }
        this.contractValidator = contractValidator;
        this.jsonOps = jsonOps;
        this.specialDeals = specialDeals;
        this.actions = actions;
        this.repository = repository;
    }

    /**
     * 校验一条接口配置。
     *
     * @param creating true=新增（唯一性校验会排除自身 keyId）
     */
    public ValidationResult validate(IfmapConfig config, boolean creating) {
        ValidationResult result = new ValidationResult();
        if (config == null) {
            return result.error("配置不能为空");
        }
        required(result, "interfaceNo", config.getInterfaceNo());
        required(result, "interfaceCode", config.getInterfaceCode());
        required(result, "interfaceName", config.getInterfaceName());
        required(result, "busiNode", config.getBusiNode());
        required(result, "partnerCode", config.getPartnerCode());

        checkTemplateContract(config, result);
        checkJsonPaths(config, result);
        checkStrategyName(config, result);
        checkFrontChain(config, result);
        checkUniqueKey(config, result);
        return result;
    }

    /** 校验逻辑分支集合（默认兜底 ≤ 1、顺序唯一、动作已注册）。 */
    public ValidationResult validateBranches(String tenantId, String interfaceNo) {
        ValidationResult result = new ValidationResult();
        if (isBlank(interfaceNo)) {
            return result.error("interfaceNo 不能为空");
        }
        List<LogicBranchConfig> rows = repository.queryLogicBranches(tenantId, interfaceNo, false);
        if (rows.isEmpty()) {
            return result.warning("接口 " + interfaceNo + " 尚未配置逻辑分支");
        }
        int defaultCount = 0;
        Set<Integer> orders = new LinkedHashSet<Integer>();
        for (LogicBranchConfig row : rows) {
            if (isDefaultBranch(row)) {
                defaultCount++;
            }
            if (!orders.add(row.getLogicBranchOrder())) {
                result.error("分支顺序重复：logic_branch_order=" + row.getLogicBranchOrder()
                        + "（分支 " + row.getLogicBranchName() + "）");
            }
            if (!isBlank(row.getLogicBranchValue()) && isBlank(row.getLogicBranchFlag())) {
                result.error("分支 " + row.getLogicBranchName() + " 配了 logic_branch_value 却没有 logic_branch_flag");
            }
            checkMethodFlag(row, result);
        }
        if (defaultCount > 1) {
            result.error("默认兜底分支最多 1 条（logic_branch_flag/value 均为空），当前 " + defaultCount + " 条");
        }
        return result;
    }

    /** 校验单条分支（新增/修改时调用）。 */
    public ValidationResult validateBranch(LogicBranchConfig branch, boolean creating) {
        ValidationResult result = new ValidationResult();
        if (branch == null) {
            return result.error("分支不能为空");
        }
        required(result, "interfaceNo", branch.getInterfaceNo());
        // method_flag 允许为空：默认兜底分支就是"flag/value 皆空、不需要动作"的那种
        required(result, "logicBranchName", branch.getLogicBranchName());
        if (!isBlank(branch.getLogicBranchValue()) && isBlank(branch.getLogicBranchFlag())) {
            result.error("配了 logic_branch_value 就必须配 logic_branch_flag");
        }
        checkMethodFlag(branch, result);
        List<LogicBranchConfig> rows = repository.queryLogicBranches(
                asTenantId(branch.getTenantId()), branch.getInterfaceNo(), false);
        for (LogicBranchConfig row : rows) {
            if (!creating && branch.getKeyId() != null && branch.getKeyId().equals(row.getKeyId())) {
                continue;
            }
            if (branch.getLogicBranchOrder() != null
                    && branch.getLogicBranchOrder().equals(row.getLogicBranchOrder())) {
                result.error("分支顺序已被占用：logic_branch_order=" + branch.getLogicBranchOrder()
                        + "（分支 " + row.getLogicBranchName() + "）");
            }
            if (branch.getMethodFlag() != null && branch.getMethodFlag().equals(row.getMethodFlag())
                    && branch.getLogicBranchName() != null && branch.getLogicBranchName().equals(row.getLogicBranchName())) {
                result.error("已存在同名分支：(method_flag=" + branch.getMethodFlag()
                        + ", logic_branch_name=" + branch.getLogicBranchName() + ")");
            }
            // 单条也会造成集合级违规：默认兜底分支全接口只能有 1 条（否则引擎取首个命中者，
            // 第二条永远不会生效，属于"配了但没用"的静默陷阱）
            if (isDefaultBranch(branch) && isDefaultBranch(row)) {
                result.error("默认兜底分支最多 1 条（logic_branch_flag/value 均为空），已存在："
                        + row.getLogicBranchName());
            }
        }
        return result;
    }

    private void checkTemplateContract(IfmapConfig config, ValidationResult result) {
        if (contractValidator != null) {
            result.contract(contractValidator.validate(Collections.singletonList(config)))
                    .failOnContractViolations();
            return;
        }
        // 巡检时可能没有引擎（无 ContractValidator）：至少把"模板不是合法 JSON"拦下来，
        // 这类脏数据在历史库里真实存在（手改 SQL / 旧版本写入），不拦就会带到线上首次调用才炸。
        checkTemplateJson(config.getRequestParamTemplate(), "request_param_template", result);
        checkTemplateJson(config.getResponseParamTemplate(), "response_param_template", result);
    }

    /** 模板必须是合法 JSON（可为空）。 */
    private void checkTemplateJson(String template, String column, ValidationResult result) {
        if (template == null || template.trim().isEmpty()) {
            return;
        }
        try {
            jsonOps.parse(template);
        } catch (RuntimeException e) {
            result.error(column + " 不是合法 JSON：" + e.getMessage());
        }
    }

    /** 模板里出现的 JsonPath 必须是合法表达式（Save 前拦截，别等到线上运行才炸）。 */
    private void checkJsonPaths(IfmapConfig config, ValidationResult result) {
        Set<String> paths = new LinkedHashSet<String>();
        collectPaths(config.getRequestParamTemplate(), paths);
        collectPaths(config.getResponseParamTemplate(), paths);
        for (String path : paths) {
            if (!jsonOps.isValidPath(path)) {
                result.error("JsonPath 语法非法：" + path);
            }
        }
    }

    private void collectPaths(String template, Set<String> paths) {
        if (isBlank(template)) {
            return;
        }
        Object parsed;
        try {
            parsed = jsonOps.parse(template);
        } catch (RuntimeException e) {
            // 模板非法 JSON 已由契约校验报出，这里不重复报错
            return;
        }
        walk(parsed, paths);
    }

    private void walk(Object node, Set<String> paths) {
        if (node instanceof java.util.Map) {
            for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) node).entrySet()) {
                if (entry.getKey() != null) {
                    // "@array@" 形态：字段名@array@$.path —— 路径藏在 key 里
                    collectFromText(String.valueOf(entry.getKey()), paths);
                }
                walk(entry.getValue(), paths);
            }
        } else if (node instanceof java.util.List) {
            for (Object item : (java.util.List<?>) node) {
                walk(item, paths);
            }
        } else if (node instanceof String) {
            collectFromText((String) node, paths);
        }
    }

    private void collectFromText(String text, Set<String> paths) {
        if (text == null || text.indexOf('$') < 0) {
            return;
        }
        Matcher matcher = JSON_PATH.matcher(text);
        while (matcher.find()) {
            String path = matcher.group();
            // 去掉尾部的孤立点（例如 "$." 出现在纯文本里）
            while (path.endsWith(".") || path.endsWith("$")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.length() > 2) {
                paths.add(path);
            }
        }
    }

    private void checkStrategyName(IfmapConfig config, ValidationResult result) {
        String strategyName = config.getStrategyName();
        if (isBlank(strategyName) || specialDeals == null) {
            return;
        }
        if (specialDeals.size() == 0) {
            result.warning("尚未注册任何特殊处理策略（SpecialDealStrategy），strategy_name 无法校验");
            return;
        }
        if (!specialDeals.contains(strategyName)) {
            result.error("strategy_name 未注册：" + strategyName + "（已注册：" + specialDeals.keys() + "）");
        }
    }

    /** 前置接口必须存在，且整条链不能成环 / 不能过深。 */
    private void checkFrontChain(IfmapConfig config, ValidationResult result) {
        String front = config.getFrontInterfaceNo();
        if (isBlank(front)) {
            return;
        }
        String self = config.getInterfaceNo();
        if (front.equals(self)) {
            result.error("前置接口不能指向自己：" + front);
            return;
        }
        String tenantId = asTenantId(config.getTenantId());
        Set<String> visited = new LinkedHashSet<String>();
        if (!isBlank(self)) {
            visited.add(self);
        }
        String current = front;
        List<String> chain = new ArrayList<String>();
        int depth = 0;
        while (!isBlank(current)) {
            chain.add(current);
            if (!visited.add(current)) {
                result.error("前置接口链成环：" + chain);
                return;
            }
            if (++depth > MAX_FRONT_DEPTH) {
                result.error("前置接口链过深（>" + MAX_FRONT_DEPTH + "）：" + chain);
                return;
            }
            List<IfmapConfig> rows = liveConfigs(tenantId, current);
            if (rows.isEmpty()) {
                result.error("前置接口不存在或已删除：" + current);
                return;
            }
            current = rows.get(0).getFrontInterfaceNo();
        }
    }

    private List<IfmapConfig> liveConfigs(String tenantId, String interfaceNo) {
        ConfigQuery query = new ConfigQuery()
                .setTenantId(tenantId)
                .setInterfaceNo(interfaceNo)
                .setSize(ConfigQuery.MAX_SIZE);
        List<IfmapConfig> rows = repository.queryConfigs(query);
        List<IfmapConfig> live = new ArrayList<IfmapConfig>();
        for (IfmapConfig row : rows) {
            if (row.getDelStatus() == 0) {
                live.add(row);
            }
        }
        return live;
    }

    /** 唯一维度：同 (tenant, interface_no, busi_node, interface_order) 不能有第二条未删除配置。 */
    private void checkUniqueKey(IfmapConfig config, ValidationResult result) {
        if (isBlank(config.getInterfaceNo()) || isBlank(config.getBusiNode())) {
            return;
        }
        ConfigQuery query = new ConfigQuery()
                .setTenantId(asTenantId(config.getTenantId()))
                .setInterfaceNo(config.getInterfaceNo())
                .setBusiNode(config.getBusiNode())
                .setSize(ConfigQuery.MAX_SIZE);
        List<IfmapConfig> rows = repository.queryConfigs(query);
        for (IfmapConfig row : rows) {
            if (row.getDelStatus() != null && row.getDelStatus() != 0) {
                continue;
            }
            boolean sameOrder = sameOrder(row.getInterfaceOrder(), config.getInterfaceOrder());
            boolean sameRow = config.getKeyId() != null && config.getKeyId().equals(row.getKeyId());
            if (sameOrder && !sameRow) {
                result.error("唯一维度冲突：已存在 (tenant_id=" + asTenantId(config.getTenantId())
                        + ", interface_no=" + config.getInterfaceNo()
                        + ", busi_node=" + config.getBusiNode()
                        + ", interface_order=" + config.getInterfaceOrder() + ") 的配置 #" + row.getKeyId());
            }
        }
    }

    /**
     * 顺序号相等判断。
     *
     * <p>{@code interface_order} 是包装类型 {@code Integer}，**绝不能写成 {@code a == b}**：
     * {@code ==} 比的是引用，只对 {@code -128..127} 的缓存实例碰巧成立，
     * 一旦顺序号 ≥ 128 就会漏判冲突（"唯一键已存在"校验形同虚设）。</p>
     */
    private static boolean sameOrder(Integer left, Integer right) {
        return left == null ? right == null : left.equals(right);
    }

    private void checkMethodFlag(LogicBranchConfig row, ValidationResult result) {
        String methodFlag = row.getMethodFlag();
        if (isBlank(methodFlag) || actions == null) {
            return;
        }
        if (actions.size() == 0) {
            result.warning("尚未注册任何动作（@IfmapAction），method_flag 无法校验：" + methodFlag);
            return;
        }
        if (!actions.contains(methodFlag)) {
            result.error("method_flag 未注册：" + methodFlag + "（已注册：" + actions.keys() + "）");
        }
    }

    private static boolean isDefaultBranch(LogicBranchConfig row) {
        return isBlank(row.getLogicBranchFlag()) || isBlank(row.getLogicBranchValue());
    }

    private static void required(ValidationResult result, String field, String value) {
        if (isBlank(value)) {
            result.error(field + " 不能为空");
        }
    }

    private static String asTenantId(Long tenantId) {
        return tenantId == null ? "-1" : String.valueOf(tenantId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
