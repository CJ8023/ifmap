package cn.cj.ifmap.json.jackson;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.exception.StartupValidationException;
import cn.cj.ifmap.core.rule.RuleContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门面测试：启动期模板校验、规则清单、上下文透传。
 *
 * @author caijun
 */
class IfmapEngineTest {

    private final IfmapEngine engine = IfmapEngine.createDefault();

    @Test
    @DisplayName("默认引擎：JsonOps 自动发现 + 18 个内置规则")
    void defaultEngine() {
        assertEquals("jackson", engine.getJsonOps().name());
        assertEquals(18, engine.getRegistry().getRuleNames().size());
    }

    @Test
    @DisplayName("启动期校验：把运行期静默失败提前成启动失败，并列出模板标识与规则名")
    void startupValidation() {
        StartupValidationException error = assertThrows(StartupValidationException.class,
                () -> engine.validateTemplates(Collections.singletonMap(
                        "czb-apply-01",
                        "{\"dueDate\":\"@FUN(farmatDate,$.dueDate,yyyy-MM-dd)\"}")));
        assertTrue(error.getMessage().contains("czb-apply-01"), error.getMessage());
        assertEquals(Collections.singleton("farmatDate"), error.getMissingRules().get("czb-apply-01"));
    }

    @Test
    @DisplayName("模板合法时校验通过（不抛异常）")
    void startupValidationPass() {
        engine.validateTemplate("ok", "{\"d\":\"@FUN(dateFormat,$.dueDate,yyyy-MM-dd)\"}");
    }

    @Test
    @DisplayName("renderToMap 返回 JDK Map；上下文可被规则读取")
    void renderToMapWithContext() {
        RuleContext context = RuleContext.builder().tenantId("T001").interfaceNo("IF001").build();
        Map<String, Object> out = engine.renderToMap("{\"d\":\"@FUN(dateFormat,$.dueDate,yyyyMMdd)\"}",
                "{\"dueDate\":\"2026-03-31\"}", context);
        assertEquals("20260331", out.get("d"));
    }

    @Test
    @DisplayName("describeRules 输出 Markdown 表格，行数 = 2 + 规则方法数")
    void describeRules() {
        String markdown = engine.describeRules();
        String[] lines = markdown.split("\n");
        assertEquals(2 + engine.getRegistry().getDescriptors().size(), lines.length);
        assertTrue(markdown.contains("| `dictVal` |"), markdown);
    }
}
