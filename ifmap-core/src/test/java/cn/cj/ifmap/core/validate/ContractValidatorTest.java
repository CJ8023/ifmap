package cn.cj.ifmap.core.validate;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.testkit.TestConfigs;
import cn.cj.ifmap.core.testkit.TestJsonOps;
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
}
