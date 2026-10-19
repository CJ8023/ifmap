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
package cn.cj.ifmap.core.validate;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.testkit.TestConfigs;
import cn.cj.ifmap.core.testkit.TestJsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 契约自检：把"DB 模板引用了不存在的规则"提前到部署前。 */
class ContractValidatorTest {

    /** 宿主自定义规则（public static 便于反射调用）。 */
    public static class HostRules {
        @IfmapRule("hostRule")
        public String hostRule(String value) {
            return value.trim();
        }
    }

    private final IfmapEngine engine = IfmapEngine.builder().jsonOps(new TestJsonOps())
            .register(new HostRules()).build();
    private final ContractValidator validator = new ContractValidator(engine);

    @Test
    void cleanWhenAllRulesRegistered() {
        IfmapConfig config = TestConfigs.config("IF_A", "GP81", 1, 1L);
        config.setRequestParamTemplate("{\"a\":\"@FUN(hostRule,$.a)\"}");
        config.setResponseParamTemplate("{\"b\":\"@FUN(dateFormat,$.b,yyyyMMdd)\"}");

        ContractReport report = validator.validate(Collections.singletonList(config));
        assertTrue(report.isClean());
        assertTrue(report.toMarkdown().contains("零违规"));
    }

    @Test
    void detectsTypoRuleNamesWithReport() {
        IfmapConfig config = TestConfigs.config("IF_B", "GP81", 2, 2L);
        config.setRequestParamTemplate("{\"d\":\"@FUN(farmatDate,$.d,yyyyMMdd)\"}");
        config.setResponseParamTemplate("{\"b\":\"@FUN(cebFilePathJoin,$.a,$.b)\"}");

        ContractReport report = validator.validate(Collections.singletonList(config));
        assertFalse(report.isClean());
        assertEquals(2, report.size());
        assertTrue(report.toMarkdown().contains("farmatDate"));
        assertTrue(report.toMarkdown().contains("cebFilePathJoin"));
        assertEquals(ContractReport.Violation.KIND_REQUEST, report.getViolations().get(0).getTemplateKind());
    }

    @Test
    void blankTemplatesAreSkipped() {
        IfmapConfig config = TestConfigs.config("IF_C", "GP81", 3, 3L);
        config.setRequestParamTemplate("  ");
        assertTrue(validator.validate(Collections.singletonList(config)).isClean());
        assertTrue(validator.validate(null).isClean());
        assertTrue(validator.validate(Arrays.asList((IfmapConfig) null)).isClean());
    }

    @Test
    void nonJsonTemplateIsReportedNotThrown() {
        IfmapConfig config = TestConfigs.config("IF_D", "GP81", 4, 4L);
        config.setRequestParamTemplate("{not json");
        ContractReport report = validator.validate(Collections.singletonList(config));
        assertFalse(report.isClean());
        assertTrue(report.toMarkdown().contains("模板非法"), report.toMarkdown());
    }

    @Test
    @DisplayName("表达式错语法（@sum@$.a,$.b / 未闭合 @FUN(）也要报违规，且启动自检与管理端共用同一套")
    void detectsExpressionProblems() {
        IfmapConfig config = TestConfigs.config("IF_C", "GP81", 3, 3L);
        config.setRequestParamTemplate("{\"s\":\"@sum@$.a,$.b\"}");
        config.setResponseParamTemplate("{\"t\":\"@FUN(concat,$.a\"}");

        ContractReport report = validator.validate(Collections.singletonList(config));

        assertFalse(report.isClean(), report.toMarkdown());
        String md = report.toMarkdown();
        assertTrue(md.contains("$.a,$.b"), md);
        assertTrue(md.contains("@FUN(concat,$.a"), md);
    }

    @Test
    @DisplayName("判定规格非法要报违规：空 / all:[] / 未知 op 各有明确原因（复用 ResultJudgeSpec.validate，不另写一套）")
    void invalidJudgeSpecIsReported() {
        IfmapConfig blank = TestConfigs.config("IF_E", "GP81", 5, 5L);
        blank.setResultJudge("");
        IfmapConfig emptyGroup = TestConfigs.config("IF_F", "GP81", 6, 6L);
        emptyGroup.setResultJudge("{\"all\":[]}");
        IfmapConfig unknownOp = TestConfigs.config("IF_G", "GP81", 7, 7L);
        unknownOp.setResultJudge("{\"all\":[{\"path\":\"$.code\",\"op\":\"between\",\"value\":\"1\"}]}");

        ContractReport report = validator.validate(Arrays.asList(blank, emptyGroup, unknownOp));

        assertEquals(3, report.judgeSpecViolations().size(), report.toMarkdown());
        String md = report.toMarkdown();
        assertTrue(md.contains("result_judge 不能为空"), md);
        assertTrue(md.contains("不能为空数组"), md);
        assertTrue(md.contains("between"), md);
    }

    @Test
    @DisplayName("always 不计违规，但要计入「不判定接口数」；统计段要出现在 Markdown 里")
    void alwaysConfigsAreCountedAsStatistics() {
        IfmapConfig always = TestConfigs.config("IF_H", "GP81", 8, 8L);
        always.setResultJudge("{\"op\":\"always\"}");
        IfmapConfig conditional = TestConfigs.config("IF_I", "GP81", 9, 9L);
        conditional.setResultJudge("{\"all\":[{\"path\":\"$.code\",\"op\":\"eq\",\"value\":\"0000\"}]}");

        ContractReport report = validator.validate(Arrays.asList(always, conditional));

        assertTrue(report.isClean(), report.toMarkdown());
        assertEquals(1, report.alwaysCount());
        assertEquals(2, report.totalConfigs());
        String md = report.toMarkdown();
        assertTrue(md.contains("判定形态统计"), md);
        assertTrue(md.contains("不判定（always）：1"), md);
    }

    @Test
    @DisplayName("表达式错语法与缺规则同时存在时，两类违规都要报（不能只报一类）")
    void reportsBothMissingRuleAndExpressionProblem() {
        IfmapConfig config = TestConfigs.config("IF_D", "GP81", 4, 4L);
        config.setRequestParamTemplate("{\"a\":\"@FUN(noSuchRule,$.a)\",\"s\":\"@sum@$.x,$.y\"}");

        ContractReport report = validator.validate(Collections.singletonList(config));

        assertFalse(report.isClean());
        String md = report.toMarkdown();
        assertTrue(md.contains("noSuchRule"), md);
        assertTrue(md.contains("$.x,$.y"), md);
    }
}
