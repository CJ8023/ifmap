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
package cn.cj.ifmap.core;

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.exception.RuleArgumentException;
import cn.cj.ifmap.core.exception.RuleInvocationException;
import cn.cj.ifmap.core.exception.RuleNotFoundException;
import cn.cj.ifmap.core.exception.StartupValidationException;
import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.rule.RuleContext;
import cn.cj.ifmap.core.rule.RuleRegistry;
import cn.cj.ifmap.core.rule.builtin.BuiltinRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则注册表测试：重载、强转、覆盖、变长参数、上下文注入、逐元素映射、启动期校验。
 *
 * @author caijun
 */
public class RuleRegistryTest {

    /** 测试规则集。 */
    public static class SampleRules {

        @IfmapRule("greet")
        public String greet(String name) {
            return "hi " + name;
        }

        @IfmapRule("greet")
        public String greet(String name, String suffix) {
            return "hi " + name + suffix;
        }

        @IfmapRule("sum2")
        public Integer sum2(Integer a, Integer b) {
            return a + b;
        }

        @IfmapRule("ctxRule")
        public String ctxRule(RuleContext context, String value) {
            return context.getTenantId() + ":" + value;
        }

        @IfmapRule("varargsRule")
        public String varargsRule(String... parts) {
            return String.join("-", parts);
        }

        @IfmapRule(value = "nullable", allowNullArgs = true)
        public String nullable(String value) {
            return value == null ? "NULL" : value;
        }

        @IfmapRule("boom")
        public String boom(String value) {
            throw new RuleArgumentException("坏参数 " + value);
        }
    }

    /** 非法规则名示例。 */
    public static class BadNameRules {

        @IfmapRule("bad,name")
        public String bad(String value) {
            return value;
        }
    }

    /** 覆盖示例：把 greet(String) 换成大写实现。 */
    public static class OverrideRules {

        @IfmapRule(value = "greet", override = true)
        public String greet(String name) {
            return "HELLO " + name;
        }
    }

    private RuleRegistry registry() {
        return new RuleRegistry().register(new SampleRules());
    }

    @Test
    @DisplayName("同名不同参数个数 = 重载，按实参个数解析")
    void overloadByArity() {
        RuleRegistry registry = registry();
        assertEquals("hi 张三", registry.invoke("greet", null, "张三"));
        assertEquals("hi 张三先生", registry.invoke("greet", null, "张三", "先生"));
        assertThrows(RuleInvocationException.class, () -> registry.invoke("greet", null, "张三", "a", "b"));
    }

    @Test
    @DisplayName("参数类型不精确匹配时可强转（字符串 -> 整数）")
    void coerceStringToInteger() {
        RuleRegistry registry = registry();
        assertEquals(3, ((Number) registry.invoke("sum2", null, "1", "2")).intValue());
        assertEquals(3, ((Number) registry.invoke("sum2", null, 1, 2)).intValue());
        assertThrows(RuleInvocationException.class, () -> registry.invoke("sum2", null, "1", "abc"));
    }

    @Test
    @DisplayName("重复签名注册直接报错，提示用 override=true")
    void duplicateSignatureRejected() {
        RuleRegistry registry = registry();
        IfmapConfigException error = assertThrows(IfmapConfigException.class,
                () -> registry.register(new SampleRules()));
        assertTrue(error.getMessage().contains("override = true"), error.getMessage());
    }

    @Test
    @DisplayName("override=true 覆盖整个规则名（含其全部重载）")
    void overrideReplacesWholeName() {
        RuleRegistry registry = registry();
        registry.register(new OverrideRules());
        assertEquals("HELLO 张三", registry.invoke("greet", null, "张三"));
        assertThrows(RuleInvocationException.class, () -> registry.invoke("greet", null, "张三", "先生"));
    }

    @Test
    @DisplayName("同一规则名不允许两个 override 实现")
    void duplicateOverrideRejected() {
        RuleRegistry registry = registry();
        registry.register(new OverrideRules());
        assertThrows(IfmapConfigException.class, () -> registry.register(new OverrideRules()));
    }

    @Test
    @DisplayName("规则名非法（含逗号/空格）在注册期报错")
    void illegalRuleName() {
        RuleRegistry registry = new RuleRegistry();
        assertThrows(IfmapConfigException.class, () -> registry.register(new BadNameRules()));
    }

    @Test
    @DisplayName("调用不存在的规则抛 RuleNotFoundException")
    void unknownRule() {
        RuleRegistry registry = registry();
        assertThrows(RuleNotFoundException.class, () -> registry.invoke("farmatDate", null, "2026-03-31"));
    }

    @Test
    @DisplayName("RuleContext 作为第一个形参时自动注入")
    void contextInjected() {
        RuleRegistry registry = registry();
        RuleContext context = RuleContext.builder().tenantId("T001").build();
        assertEquals("T001:x", registry.invoke("ctxRule", context, "x"));
    }

    @Test
    @DisplayName("变长参数规则")
    void varargs() {
        RuleRegistry registry = registry();
        assertEquals("a-b-c", registry.invoke("varargsRule", null, "a", "b", "c"));
        assertEquals("", registry.invoke("varargsRule", null));
    }

    @Test
    @DisplayName("实参为 List 且无候选可匹配时，自动逐元素映射")
    void autoMapOverList() {
        RuleRegistry registry = registry();
        Object result = registry.invoke("greet", null, Arrays.asList("a", "b"));
        assertEquals(Arrays.asList("hi a", "hi b"), result);
    }

    @Test
    @DisplayName("允许 null 入参的规则会收到 null")
    void allowNullArgs() {
        RuleRegistry registry = registry();
        assertEquals("NULL", registry.invoke("nullable", null, new Object[]{null}));
        assertTrue(registry.descriptor("nullable").allowsNullArgs());
        assertFalse(registry.descriptor("greet").allowsNullArgs());
    }

    @Test
    @DisplayName("规则内部抛出的 IfmapException 原样透出")
    void ruleExceptionPropagated() {
        RuleRegistry registry = registry();
        RuleArgumentException error = assertThrows(RuleArgumentException.class,
                () -> registry.invoke("boom", null, "x"));
        assertEquals("坏参数 x", error.getMessage());
    }

    @Test
    @DisplayName("启动期校验：模板里的规则名拼错会被拦下")
    void startupValidation() {
        RuleRegistry registry = BuiltinRules.newRegistry();
        Map<String, String> templates = new LinkedHashMap<String, String>();
        templates.put("cfg-1", "{\"dueDate\":\"@FUN(farmatDate,$.dueDate,yyyy-MM-dd)\"}");
        templates.put("cfg-2", "{\"amount\":\"@FUN(numRound,$.amount,2)\"}");
        StartupValidationException error = assertThrows(StartupValidationException.class,
                () -> registry.assertRulesExist(templates));
        assertTrue(error.getMessage().contains("farmatDate"), error.getMessage());
        assertEquals(Collections.singleton("farmatDate"), error.getMissingRules().get("cfg-1"));
        assertEquals(1, error.getMissingRules().size());
    }

    @Test
    @DisplayName("内置规则注册后共 18 个规则名")
    void builtinRuleCount() {
        RuleRegistry registry = BuiltinRules.newRegistry();
        assertEquals(18, registry.getRuleNames().size(), registry.getRuleNames().toString());
        assertTrue(registry.getRuleNames().containsAll(Arrays.asList(
                "concat", "strDefault", "strTruncate", "strMask",
                "dictVal", "dictValArray",
                "dateFormat", "dateConvert", "dateAdd", "dateDiff", "dateEndOfMonth", "workdayAdd",
                "numRound", "numSum", "numOffset", "numFormat",
                "listJoin", "listOp")));
    }
}
