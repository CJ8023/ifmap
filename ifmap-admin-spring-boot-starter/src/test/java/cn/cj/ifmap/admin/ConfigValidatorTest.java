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

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.IfmapAction;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.strategy.SpecialDealStrategy;
import cn.cj.ifmap.core.strategy.SpecialDealStrategyRegistry;
import cn.cj.ifmap.core.strategy.StrategyContext;
import cn.cj.ifmap.core.validate.ContractValidator;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import cn.cj.ifmap.json.jackson.JacksonJsonOps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保存前校验清单（设计 §8.3）：每条规则一个断言。
 *
 * @author caijun
 */
class ConfigValidatorTest {

    private AdminTestSupport.Db db;
    private JdbcTemplate jdbc;
    private JdbcConfigWriter writer;
    private ConfigValidator validator;

    @BeforeEach
    void setUp() {
        db = AdminTestSupport.db("ifmap_admin_validator");
        db.createSchema();
        jdbc = db.jdbc();
        JdbcConfigRepository repository = db.repository();
        writer = db.writer();

        SpecialDealStrategyRegistry specialDeals = new SpecialDealStrategyRegistry();
        specialDeals.register("demoStrategy", new DemoStrategy());
        ActionRegistry actions = new ActionRegistry();
        actions.register(new SubmitAction());

        JacksonJsonOps jsonOps = new JacksonJsonOps();
        IfmapEngine engine = IfmapEngine.builder().jsonOps(jsonOps).build();
        validator = new ConfigValidator(new ContractValidator(engine), jsonOps, specialDeals, actions, repository);
    }

    @Test
    @DisplayName("合法配置 → 零 error")
    void cleanConfigPasses() {
        ValidationResult result = validator.validate(AdminTestSupport.config("IF_A", "apply", 1), true);
        assertTrue(result.isPassed(), String.valueOf(result.getErrors()));
        assertEquals(0, result.getErrors().size());
        assertNotNull(result.getContract());
        // 管理端校验始终把引擎契约违规当成 error（策略字段会在 /validate 响应里回给前端）
        assertTrue(result.isFailOnContractViolations());
    }

    @Test
    @DisplayName("必填字段缺失 → 逐字段报错（不让 NULL 走到 SQL）")
    void requiredFieldsAreReported() {
        IfmapConfig config = AdminTestSupport.config("IF_A", "apply", 1);
        config.setInterfaceCode(" ");
        config.setPartnerCode(null);
        ValidationResult result = validator.validate(config, true);
        assertFalse(result.isPassed());
        assertTrue(result.getErrors().toString().contains("interfaceCode"), result.getErrors().toString());
        assertTrue(result.getErrors().toString().contains("partnerCode"), result.getErrors().toString());
    }

    @Test
    @DisplayName("模板不是合法 JSON / @FUN 未注册 → error（复用引擎契约校验）")
    void templateContractIsChecked() {
        IfmapConfig broken = AdminTestSupport.config("IF_A", "apply", 1);
        broken.setRequestParamTemplate("{not-json");
        ValidationResult jsonResult = validator.validate(broken, true);
        assertFalse(jsonResult.isPassed());
        assertTrue(jsonResult.getErrors().toString().contains("模板非法"), jsonResult.getErrors().toString());
        assertTrue(jsonResult.isFailOnContractViolations());

        IfmapConfig unknownRule = AdminTestSupport.config("IF_B", "apply", 1);
        unknownRule.setRequestParamTemplate("{\"x\":\"@FUN(notExists,$.a)\"}");
        ValidationResult ruleResult = validator.validate(unknownRule, true);
        assertFalse(ruleResult.isPassed());
        assertTrue(ruleResult.getErrors().toString().contains("notExists"), ruleResult.getErrors().toString());
    }

    @Test
    @DisplayName("模板里的 JsonPath 语法非法 → error（保存前拦住，别等线上）")
    void invalidJsonPathIsReported() {
        IfmapConfig config = AdminTestSupport.config("IF_A", "apply", 1);
        config.setResponseParamTemplate("{\"x\":\"$.list[0\"}");
        ValidationResult result = validator.validate(config, true);
        assertFalse(result.isPassed());
        assertTrue(result.getErrors().toString().contains("JsonPath 语法非法"), result.getErrors().toString());
    }

    @Test
    @DisplayName("表达式错语法 @sum@$.a,$.b → error（旧正则遇逗号截断，会静默放行）")
    void badExpressionIsReported() {
        IfmapConfig config = AdminTestSupport.config("IF_A", "apply", 1);
        config.setResponseParamTemplate("{\"s\":\"@sum@$.a,$.b\"}");

        ValidationResult result = validator.validate(config, true);

        assertFalse(result.isPassed(), String.valueOf(result.getErrors()));
        assertTrue(result.getErrors().toString().contains("$.a,$.b"), result.getErrors().toString());
    }

    @Test
    @DisplayName("未闭合的 @FUN( 同样在保存前被拒")
    void unclosedFunIsReported() {
        IfmapConfig config = AdminTestSupport.config("IF_A", "apply", 1);
        config.setResponseParamTemplate("{\"s\":\"@FUN(concat,$.a\"}");

        ValidationResult result = validator.validate(config, true);

        assertFalse(result.isPassed(), String.valueOf(result.getErrors()));
        assertTrue(result.getErrors().toString().contains("concat"), result.getErrors().toString());
    }

    @Test
    @DisplayName("strategy_name 必须在已注册策略里；注册表为空只给 warning")
    void strategyNameMustBeRegistered() {
        IfmapConfig config = AdminTestSupport.config("IF_A", "apply", 1);
        config.setStrategyName("notRegistered");
        ValidationResult result = validator.validate(config, true);
        assertFalse(result.isPassed());
        assertTrue(result.getErrors().toString().contains("strategy_name 未注册"), result.getErrors().toString());

        config.setStrategyName("demoStrategy");
        ValidationResult registered = validator.validate(config, true);
        assertTrue(registered.isPassed(), String.valueOf(registered.getErrors()));

        ConfigValidator emptyStrategies = new ConfigValidator(null, new JacksonJsonOps(),
                new SpecialDealStrategyRegistry(), new ActionRegistry(), db.repository());
        ValidationResult warning = emptyStrategies.validate(config, true);
        assertTrue(warning.isPassed(), warning.getErrors().toString());
        assertTrue(warning.getWarnings().toString().contains("尚未注册任何特殊处理策略"),
                warning.getWarnings().toString());
    }

    @Test
    @DisplayName("前置接口：不存在 → error；指向自己 → error；成环 → error")
    void frontInterfaceChainIsChecked() {
        IfmapConfig missing = AdminTestSupport.config("IF_A", "apply", 1);
        missing.setFrontInterfaceNo("IF_NOT_EXISTS");
        ValidationResult missingResult = validator.validate(missing, true);
        assertTrue(missingResult.getErrors().toString().contains("前置接口不存在"),
                missingResult.getErrors().toString());

        IfmapConfig self = AdminTestSupport.config("IF_A", "apply", 1);
        self.setFrontInterfaceNo("IF_A");
        assertTrue(validator.validate(self, true).getErrors().toString().contains("不能指向自己"));

        // 落库：IF_A → IF_B → IF_A 的环
        IfmapConfig a = AdminTestSupport.config("IF_A", "apply", 1);
        a.setFrontInterfaceNo("IF_B");
        assertTrue(writer.insert(a) > 0);
        IfmapConfig b = AdminTestSupport.config("IF_B", "apply", 1);
        b.setFrontInterfaceNo("IF_A");
        assertTrue(writer.insert(b) > 0);

        IfmapConfig edit = AdminTestSupport.config("IF_A", "apply", 1);
        edit.setKeyId(a.getKeyId());
        edit.setFrontInterfaceNo("IF_B");
        ValidationResult cyclic = validator.validate(edit, false);
        assertTrue(cyclic.getErrors().toString().contains("成环"), cyclic.getErrors().toString());
    }

    @Test
    @DisplayName("唯一维度冲突 → error，且自身更新不算冲突")
    void uniqueKeyIsChecked() {
        IfmapConfig existing = AdminTestSupport.config("IF_A", "apply", 1);
        assertTrue(writer.insert(existing) > 0);

        IfmapConfig duplicate = AdminTestSupport.config("IF_A", "apply", 1);
        duplicate.setKeyId(null);
        ValidationResult result = validator.validate(duplicate, true);
        assertFalse(result.isPassed());
        assertTrue(result.getErrors().toString().contains("唯一维度冲突"), result.getErrors().toString());

        existing.setRemark("自我更新");
        ValidationResult selfUpdate = validator.validate(existing, false);
        assertTrue(selfUpdate.isPassed(), String.valueOf(selfUpdate.getErrors()));
    }

    @Test
    @DisplayName("唯一维度冲突：顺序号 ≥128（超出 Integer 缓存）也必须能查出来")
    void uniqueKeyDetectsConflictBeyondIntegerCache() {
        IfmapConfig existing = AdminTestSupport.config("IF_BIG", "apply", 200);
        assertTrue(writer.insert(existing) > 0);

        IfmapConfig duplicate = AdminTestSupport.config("IF_BIG", "apply", 200);
        duplicate.setKeyId(null);
        ValidationResult result = validator.validate(duplicate, true);
        assertFalse(result.isPassed(),
                "顺序号 200 的重复配置必须报冲突（Integer 引用比较会漏判，因为 200 不在 -128..127 缓存里）");
        assertTrue(result.getErrors().toString().contains("唯一维度冲突"), result.getErrors().toString());
    }

    @Test
    @DisplayName("分支集合：默认兜底 ≤1、order 不重复、value 不能脱离 flag、method_flag 必须已注册")
    void branchSetIsChecked() {
        assertTrue(writer.insert(AdminTestSupport.branch("IF_A", "submit", "默认1", "", "", 1)) > 0);
        assertTrue(writer.insert(AdminTestSupport.branch("IF_A", "submit", "默认2", "", "", 2)) > 0);
        assertTrue(writer.insert(
                AdminTestSupport.branch("IF_A", "unknownAction", "顺序重复", "loanResult", "0000", 1)) > 0);
        assertTrue(writer.insert(AdminTestSupport.branch("IF_A", "submit", "值无flag", "", "0001", 3)) > 0);

        ValidationResult result = validator.validateBranches("1", "IF_A");
        String errors = result.getErrors().toString();
        assertFalse(result.isPassed());
        assertTrue(errors.contains("默认兜底分支最多 1 条"), errors);
        assertTrue(errors.contains("分支顺序重复"), errors);
        assertTrue(errors.contains("没有 logic_branch_flag"), errors);
        assertTrue(errors.contains("method_flag 未注册"), errors);
    }

    @Test
    @DisplayName("单条分支校验：同名/同顺序会被拒")
    void branchRowIsChecked() {
        assertTrue(writer.insert(AdminTestSupport.branch("IF_A", "submit", "命中", "loanResult", "0000", 1)) > 0);

        ValidationResult duplicateName = validator.validateBranch(
                AdminTestSupport.branch("IF_A", "submit", "命中", "loanResult", "0000", 5), true);
        assertFalse(duplicateName.isPassed());
        assertTrue(duplicateName.getErrors().toString().contains("已存在同名分支")
                        || duplicateName.getErrors().toString().contains("分支顺序已被占用"),
                duplicateName.getErrors().toString());

        ValidationResult clean = validator.validateBranch(
                AdminTestSupport.branch("IF_A", "submit", "新分支", "loanResult", "0000", 9), true);
        assertTrue(clean.isPassed(), clean.getErrors().toString());
    }

    @Test
    @DisplayName("删除后重建同维度配置：软删的 deleted_seq 让唯一键不冲突")
    void deletedConfigDoesNotBlockRecreate() {
        IfmapConfig existing = AdminTestSupport.config("IF_A", "apply", 1);
        writer.insert(existing);
        assertTrue(writer.softDelete(existing.getKeyId(), "tester", "req-1"));

        IfmapConfig recreated = AdminTestSupport.config("IF_A", "apply", 1);
        ValidationResult result = validator.validate(recreated, true);
        assertTrue(result.isPassed(), result.getErrors().toString());
    }

    // ---------------------------------------------------------------- 测试用策略

    @IfmapAction("submit")
    static class SubmitAction implements IfmapActionHandler {
        @Override
        public void execute(StrategyContext context) {
            context.putParam("handled", Boolean.TRUE);
        }
    }

    static class DemoStrategy implements SpecialDealStrategy {
        @Override
        public Map<String, Object> apply(StrategyContext context) {
            return new LinkedHashMap<String, Object>();
        }
    }
}
