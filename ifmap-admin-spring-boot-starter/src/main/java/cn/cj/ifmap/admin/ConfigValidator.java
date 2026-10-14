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
import cn.cj.ifmap.core.template.DslExpressions;
import cn.cj.ifmap.core.template.TemplateScanner;
import cn.cj.ifmap.core.validate.ContractValidator;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 保存前校验清单（设计 §8.3 的代码化实现）。
 *
 * <p>校验分三层，任何一层发现问题都会进 {@link ValidationResult#getErrors()}，
 * 管理端据此拒绝保存：</p>
 * <ol>
 *   <li><b>模板契约</b>：模板必须是合法 JSON；{@code @FUN} 规则必须已注册且参数可匹配重载
 *       （复用引擎的 {@link ContractValidator}，与启动期自检同一套逻辑）；</li>
 *   <li><b>引用合法性</b>：模板里的 JsonPath 语法必须合法；{@code pre_strategy_name} / {@code post_strategy_name} 必须在已注册的
 *       特殊处理策略里；</li>
 *   <li><b>结构约束</b>：{@code (tenant_id, interface_no)} 与 {@code (tenant_id, busi_node, interface_order)}
 *       两组唯一键都不能重复；分支的默认兜底 ≤ 1 条、{@code logic_branch_order} 不重复、
 *       {@code method_flag} 必须是已注册动作。</li>
 * </ol>
 *
 * @author caijun
 */
public class ConfigValidator {

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
        // 表达式错语法：旧实现用正则从原文「剜」路径，遇逗号截断 -> @sum@$.a,$.b 抽出 $.a（合法）
        // 而静默放行，线上求和成 0。改用引擎同一套 DSL 感知判定（设计 §4.4 U4-C）。
        checkExpressions(config.getRequestParamTemplate(), "request_param_template", result);
        checkExpressions(config.getResponseParamTemplate(), "response_param_template", result);
    }

    private void checkExpressions(String template, String column, ValidationResult result) {
        if (isBlank(template)) {
            return;
        }
        for (String problem : TemplateScanner.expressionProblems(template)) {
            result.error(column + " 表达式错语法：" + problem);
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
        for (String path : DslExpressions.extractPathTokens(text)) {
            if (path.length() > 2) {
                paths.add(path);
            }
        }
    }

    private void checkStrategyName(IfmapConfig config, ValidationResult result) {
        checkStrategy("pre_strategy_name", config.getPreStrategyName(), result);
        checkStrategy("post_strategy_name", config.getPostStrategyName(), result);
    }

    /** 前序 / 后序策略共用同一注册表，只有列名不同。 */
    private void checkStrategy(String column, String strategyName, ValidationResult result) {
        if (isBlank(strategyName) || specialDeals == null) {
            return;
        }
        if (specialDeals.size() == 0) {
            result.warning("尚未注册任何特殊处理策略（SpecialDealStrategy），" + column + " 无法校验");
            return;
        }
        if (!specialDeals.contains(strategyName)) {
            result.error(column + " 未注册：" + strategyName + "（已注册：" + specialDeals.keys() + "）");
        }
    }

    private List<IfmapConfig> liveConfigs(String tenantId, String interfaceNo) {
        return liveRows(new ConfigQuery()
                .setTenantId(tenantId)
                .setInterfaceNo(interfaceNo)
                .setSize(ConfigQuery.MAX_SIZE));
    }

    private List<IfmapConfig> liveRows(ConfigQuery query) {
        List<IfmapConfig> live = new ArrayList<IfmapConfig>();
        for (IfmapConfig row : repository.queryConfigs(query)) {
            if (row.getDelStatus() == null || row.getDelStatus().intValue() == 0) {
                live.add(row);
            }
        }
        return live;
    }

    private static boolean isSelf(IfmapConfig config, IfmapConfig row) {
        return config.getKeyId() != null && config.getKeyId().equals(row.getKeyId());
    }

    /**
     * 唯一维度（两条，分别对应 DDL 上的两个唯一键）：
     *
     * <ol>
     *   <li>{@code interface_no} 在租户内<b>全局唯一</b> —— DDL 注释早就宣称它是业务唯一键，
     *       且 {@code queryLogicBranches(tenantId, interfaceNo)} 没有 {@code busi_node} 也必须自洽；</li>
     *   <li>{@code interface_order} 在 {@code busi_node} 内<b>唯一</b> —— 顺序是流水线上的位置，
     *       同节点同序号只能有一条，否则「同值按 key_id 兜底」会退化成静默的不确定行为。</li>
     * </ol>
     *
     * <p>两条都排除自身（编辑场景），都只看未删除行（软删后可以重建同一个接口号/顺序）。</p>
     */
    private void checkUniqueKey(IfmapConfig config, ValidationResult result) {
        if (isBlank(config.getInterfaceNo())) {
            return;
        }
        String tenantId = asTenantId(config.getTenantId());
        for (IfmapConfig row : liveConfigs(tenantId, config.getInterfaceNo())) {
            if (!isSelf(config, row)) {
                result.error("唯一维度冲突：interface_no 在租户内必须全局唯一，已存在 (tenant_id=" + tenantId
                        + ", interface_no=" + config.getInterfaceNo()
                        + ", busi_node=" + row.getBusiNode() + ") 的配置 #" + row.getKeyId());
            }
        }
        if (isBlank(config.getBusiNode())) {
            return;
        }
        ConfigQuery query = new ConfigQuery()
                .setTenantId(tenantId)
                .setBusiNode(config.getBusiNode())
                .setSize(ConfigQuery.MAX_SIZE);
        for (IfmapConfig row : liveRows(query)) {
            if (isSelf(config, row) || !sameOrder(row.getInterfaceOrder(), config.getInterfaceOrder())) {
                continue;
            }
            result.error("唯一维度冲突：interface_order 在 busi_node 内必须唯一，已存在 (tenant_id=" + tenantId
                    + ", busi_node=" + config.getBusiNode()
                    + ", interface_order=" + config.getInterfaceOrder() + ") 的配置 #" + row.getKeyId());
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
