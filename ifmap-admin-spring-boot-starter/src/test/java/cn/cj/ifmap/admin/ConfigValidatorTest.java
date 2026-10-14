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
    @DisplayName("pre/post 策略都必须在已注册策略里；注册表为空只给 warning")
    void strategyNameMustBeRegistered() {
        IfmapConfig config = AdminTestSupport.config("IF_A", "apply", 1);
        config.setPreStrategyName("notRegistered");
        ValidationResult result = validator.validate(config, true);
        assertFalse(result.isPassed());
        assertTrue(result.getErrors().toString().contains("pre_strategy_name 未注册"), result.getErrors().toString());

        config.setPreStrategyName("demoStrategy");
        ValidationResult registered = validator.validate(config, true);
        assertTrue(registered.isPassed(), String.valueOf(registered.getErrors()));

        config.setPostStrategyName("notRegistered");
        assertTrue(validator.validate(config, true).getErrors().toString().contains("post_strategy_name 未注册"),
                String.valueOf(validator.validate(config, true).getErrors()));
        config.setPostStrategyName("demoStrategy");

        ConfigValidator emptyStrategies = new ConfigValidator(null, new JacksonJsonOps(),
                new SpecialDealStrategyRegistry(), new ActionRegistry(), db.repository());
        ValidationResult warning = emptyStrategies.validate(config, true);
        assertTrue(warning.isPassed(), warning.getErrors().toString());
        assertTrue(warning.getWarnings().toString().contains("尚未注册任何特殊处理策略"),
                warning.getWarnings().toString());
    }

    @Test
    @DisplayName("唯一维度一：interface_no 必须租户内全局唯一（换个业务节点也拦）")
    void interfaceNoMustBeUniqueWithinTenant() {
        assertTrue(writer.insert(AdminTestSupport.config("IF_A", "apply", 1)) > 0);

        // 同名接口换到另一个业务节点 —— DDL 里 interface_no 是业务唯一键，管理端必须与之一致
        ValidationResult otherNode = validator.validate(AdminTestSupport.config("IF_A", "loan", 9), true);
        assertFalse(otherNode.isPassed());
        assertTrue(otherNode.getErrors().toString().contains("interface_no 在租户内必须全局唯一"),
                otherNode.getErrors().toString());
    }

    @Test
    @DisplayName("唯一维度二：interface_order 在同一 busi_node 内必须唯一")
    void interfaceOrderMustBeUniqueWithinBusiNode() {
        assertTrue(writer.insert(AdminTestSupport.config("IF_A", "apply", 1)) > 0);

        ValidationResult clash = validator.validate(AdminTestSupport.config("IF_B", "apply", 1), true);
        assertFalse(clash.isPassed());
        assertTrue(clash.getErrors().toString().contains("interface_order 在 busi_node 内必须唯一"),
                clash.getErrors().toString());

        // 同一个接口号换到别的节点用同一个顺序 → 顺序维度不冲突（接口号维度才有话说，这里换个号）
        ValidationResult otherNode = validator.validate(AdminTestSupport.config("IF_C", "loan", 1), true);
        assertTrue(otherNode.isPassed(), otherNode.getErrors().toString());
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

    @Test
    @DisplayName("池引用：指向本节点内更早步骤的接口号 → 通过")
    void poolRefToEarlierStepPasses() {
        assertTrue(writer.insert(AdminTestSupport.config("POOL_1", "apply", 1)) > 0);

        ValidationResult result = validator.validate(poolRef("POOL_2", 2, "POOL_1"), true);
        assertTrue(result.isPassed(), result.getErrors().toString());
    }

    @Test
    @DisplayName("池引用：指向后续步骤的产物 → error（本轮不可能产出，预置数据走 payload）")
    void poolRefToLaterStepIsRejected() {
        assertTrue(writer.insert(AdminTestSupport.config("POOL_3", "apply", 3)) > 0);

        ValidationResult result = validator.validate(poolRef("POOL_2", 2, "POOL_3"), true);
        assertFalse(result.isPassed(), result.getErrors().toString());
        assertTrue(result.getErrors().toString().contains("本轮不可能产出"), result.getErrors().toString());
        assertTrue(result.getErrors().toString().contains("预置数据请走 payload"), result.getErrors().toString());
    }

    @Test
    @DisplayName("池引用：自引用 → error（同序即命中「不小于本步骤」这一档）")
    void poolRefToSelfIsRejected() {
        IfmapConfig self = AdminTestSupport.config("POOL_SELF", "apply", 1);
        assertTrue(writer.insert(self) > 0);
        self.setRequestParamTemplate("{\"applyNo\":\"$.pool.POOL_SELF.applyNo\"}");

        ValidationResult result = validator.validate(self, false);
        assertFalse(result.isPassed(), result.getErrors().toString());
        assertTrue(result.getErrors().toString().contains("本轮不可能产出"), result.getErrors().toString());
    }

    @Test
    @DisplayName("池引用：不是本节点的接口号（拼错 / 跨节点复制）→ error 并列出本节点接口号")
    void poolRefToUnknownInterfaceIsRejected() {
        assertTrue(writer.insert(AdminTestSupport.config("POOL_1", "apply", 1)) > 0);

        ValidationResult result = validator.validate(poolRef("POOL_2", 2, "GP85_IF_9"), true);
        assertFalse(result.isPassed(), result.getErrors().toString());
        assertTrue(result.getErrors().toString().contains("不是业务节点 apply 的接口号"),
                result.getErrors().toString());
        assertTrue(result.getErrors().toString().contains("POOL_1"), result.getErrors().toString());
    }

    @Test
    @DisplayName("池引用：只认 $.pool. 前缀；请求侧非池路径、响应模板、空模板、裸 $.pool 都不报")
    void poolRefHonestBoundaries() {
        assertTrue(writer.insert(AdminTestSupport.config("POOL_1", "apply", 1)) > 0);

        // 非 pool 前缀：请求模板的 $. 根不只有池（前序产物 / payload），静态无从判定 -> 不报
        IfmapConfig plain = AdminTestSupport.config("POOL_2", "apply", 2);
        plain.setRequestParamTemplate("{\"a\":\"$.bizNo\",\"b\":\"$.data.x\"}");
        assertTrue(validator.validate(plain, true).isPassed(),
                validator.validate(plain, true).getErrors().toString());

        // 响应模板的根是对方原始回参：里面可以真有一个叫 pool 的业务字段 -> 判它就是误报
        IfmapConfig response = AdminTestSupport.config("POOL_3", "apply", 3);
        response.setResponseParamTemplate("{\"out\":\"$.pool.ANYTHING.x\",\"raw\":\"$.pool\"}");
        assertTrue(validator.validate(response, true).isPassed(),
                validator.validate(response, true).getErrors().toString());

        // 裸 $.pool / 通配符：取不到具体接口号 -> 不报（宁可漏报也不误报）
        IfmapConfig whole = AdminTestSupport.config("POOL_4", "apply", 4);
        whole.setRequestParamTemplate(
                "{\"a\":\"$.pool\",\"b\":\"$.pool[*].applyNo\",\"c\":\"@FUN(numRound,$.pool.POOL_9.amount,2)\"}");
        ValidationResult wholeResult = validator.validate(whole, true);
        assertFalse(wholeResult.isPassed(), wholeResult.getErrors().toString());
        // 只有 @FUN 实参里的 $.pool.POOL_9 被判定（POOL_9 不在本节点），裸 $.pool / [*] 不报
        assertEquals(1, wholeResult.getErrors().size(), wholeResult.getErrors().toString());
        assertTrue(wholeResult.getErrors().toString().contains("POOL_9"), wholeResult.getErrors().toString());

        // 空模板：完全跳过
        IfmapConfig blank = AdminTestSupport.config("POOL_5", "apply", 5);
        blank.setRequestParamTemplate("  ");
        blank.setResponseParamTemplate(null);
        assertTrue(validator.validate(blank, true).isPassed(),
                validator.validate(blank, true).getErrors().toString());
    }

    /** 造一条「请求模板里引用 $.pool.<ref>.applyNo」的配置。 */
    private static IfmapConfig poolRef(String interfaceNo, int order, String ref) {
        IfmapConfig config = AdminTestSupport.config(interfaceNo, "apply", order);
        config.setRequestParamTemplate("{\"applyNo\":\"$.pool." + ref + ".applyNo\"}");
        return config;
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
