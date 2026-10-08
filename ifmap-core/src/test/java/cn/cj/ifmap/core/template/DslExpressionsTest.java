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
package cn.cj.ifmap.core.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DslExpressions} 单测：DSL 表达式的结构解析（一处实现，渲染期与管理端校验共用）。
 *
 * @author caijun
 */
class DslExpressionsTest {

    private static final String SUM = "@sum@";

    @Test
    @DisplayName("isFun / funName：只认完整闭合的 @FUN(...)")
    void isFunAndName() {
        assertTrue(DslExpressions.isFun("@FUN(concat,$.a,-,X)"));
        assertTrue(DslExpressions.isFun("@FUN( concat , $.a )"));
        assertFalse(DslExpressions.isFun("@FUN(concat,$.a"), "缺少右括号不算函数（否则会被当字面量原样输出）");
        assertFalse(DslExpressions.isFun("$.a"));
        assertFalse(DslExpressions.isFun(""));

        assertEquals("concat", DslExpressions.funName("@FUN(concat,$.a,-,X)"));
        assertEquals("concat", DslExpressions.funName("@FUN( concat , $.a )"));
    }

    @Test
    @DisplayName("funArgs：顶层逗号切分、嵌套 @FUN 不切、空实参保留")
    void funArgs() {
        assertEquals(Arrays.asList("$.a", "-", "X"),
                DslExpressions.funArgs("@FUN(concat,$.a,-,X)"));
        assertEquals(Arrays.asList("@FUN(listJoin,$.a,-)", "$.x"),
                DslExpressions.funArgs("@FUN(concat,@FUN(listJoin,$.a,-),$.x)"));
        assertEquals(Arrays.asList("$.x", ""),
                DslExpressions.funArgs("@FUN(strDefault,$.x,)"), "空实参必须保留占位，不能被丢掉");
        assertEquals(Arrays.asList("$.a"), DslExpressions.funArgs("@FUN(numRound,$.a,2)").subList(0, 1));
    }

    @Test
    @DisplayName("hasInfix：只看括号外的中缀运算符（@FUN 参数里的中缀不算本层）")
    void hasInfix() {
        assertTrue(DslExpressions.hasInfix("$.a@sum@$.b", SUM));
        assertTrue(DslExpressions.hasInfix("@sum@$.a", SUM), "前缀写法也是合法的 @sum@ 用法");
        assertFalse(DslExpressions.hasInfix("@FUN(concat,$.a@sum@$.b)", SUM),
                "括号内的 @sum@ 属于内层实参，本层不是中缀表达式");
        assertFalse(DslExpressions.hasInfix("$.a", SUM));
    }

    @Test
    @DisplayName("infixOperands：按中缀运算符切分并 trim")
    void infixOperands() {
        assertEquals(Arrays.asList("$.a", "$.b"), DslExpressions.infixOperands("$.a@sum@$.b", SUM));
        assertEquals(Arrays.asList("", "$.amount"), DslExpressions.infixOperands("@sum@$.amount", SUM));
        assertEquals(Arrays.asList("$.a", "$.b", "$.c"),
                DslExpressions.infixOperands("$.a@sum@$.b@sum@$.c", SUM));
    }

    @Test
    @DisplayName("isExpression / isLiteral：区分模板表达式与字面量")
    void expressionOrLiteral() {
        assertTrue(DslExpressions.isExpression("$.a"));
        assertTrue(DslExpressions.isExpression("@sum@$.a"));
        assertTrue(DslExpressions.isExpression("@FUN(concat,$.a)"));
        assertFalse(DslExpressions.isExpression("常量文本"));
        assertFalse(DslExpressions.isExpression("-"));

        assertTrue(DslExpressions.isLiteral("常量文本"));
        assertFalse(DslExpressions.isLiteral("$.a"));
        assertFalse(DslExpressions.isLiteral(null), "null 既不是表达式也不是字面量");
        assertFalse(DslExpressions.isLiteral(""));
    }

    @Test
    @DisplayName("extractPathTokens：抽出表达式里引用的全部 $.path（在逗号/括号/中缀处截断）")
    void extractPathTokens() {
        assertEquals(Arrays.asList("$.items[*].sku"), DslExpressions.extractPathTokens("$.items[*].sku"));
        assertEquals(Arrays.asList("$.a", "$.b"), DslExpressions.extractPathTokens("$.a@concat@$.b"));
        assertEquals(Arrays.asList("$.dueDate"),
                DslExpressions.extractPathTokens("@FUN(dateConvert,$.dueDate,yyyy-MM-dd,yyyyMMdd)"),
                "格式串不能被误当成路径");
        assertEquals(Arrays.asList("$.a", "$.b"),
                DslExpressions.extractPathTokens("@FUN(concat,$.a,-,$.b)"));
        assertTrue(DslExpressions.extractPathTokens("没有路径").isEmpty());
        assertTrue(DslExpressions.extractPathTokens("@FUN(numRound,$.a,2)").contains("$.a"));
    }

    @Test
    @DisplayName("operandProblems：@sum@$.a,$.b 这类错语法必须被识别出来")
    void operandProblems() {
        assertEquals(1, DslExpressions.operandProblems("@sum@$.a,$.b").size(),
                "逗号是中缀表达式的错语法，必须报出来（否则静默求和成 0）");
        assertTrue(DslExpressions.operandProblems("@sum@$.a,$.b").get(0).contains("$."),
                "报错信息要带上出错的表达式片段");

        assertEquals(1, DslExpressions.operandProblems("@FUN(concat,$.a").size(),
                "@FUN( 未闭合必须报出来（否则整串被当字面量原样进报文）");

        assertTrue(DslExpressions.operandProblems("@sum@$.a@sum@$.b").isEmpty());
        assertTrue(DslExpressions.operandProblems("@FUN(concat,$.a,-,$.b)").isEmpty(),
                "@FUN 的逗号是合法参数分隔，不能误报");
        assertTrue(DslExpressions.operandProblems("$.a").isEmpty());
        assertTrue(DslExpressions.operandProblems("常量").isEmpty());
        assertTrue(DslExpressions.operandProblems("@FUN(strDefault,$.x,)").isEmpty(), "空实参合法");
    }

    @Test
    @DisplayName("operandProblems：顶层逗号把表达式切成多段（含 @array@ key 形态）")
    void operandProblemsTopLevelComma() {
        assertEquals(1, DslExpressions.operandProblems("$.a,$.b").size(),
                "顶层逗号会让整串变成一个非法路径 -> 取值为空（静默）");
        assertEquals(1, DslExpressions.operandProblems("rows@array@$.a,$.b").size(),
                "@array@ 形态的 key 里路径藏在后半段，同样要查");
        assertEquals(1, DslExpressions.operandProblems("@array@$.a,$.b").size());
        assertTrue(DslExpressions.operandProblems("$.a").isEmpty());
        assertTrue(DslExpressions.operandProblems("$.items[*].sku").isEmpty());
    }

    @Test
    @DisplayName("operandProblems：字面量里的逗号不是错语法（否则存量常量字段全被拦）")
    void operandProblemsIgnoresLiteralCommas() {
        assertTrue(DslExpressions.operandProblems("前缀,后缀").isEmpty());
        assertTrue(DslExpressions.operandProblems("").isEmpty());
        assertTrue(DslExpressions.operandProblems(null).isEmpty());
        assertTrue(DslExpressions.operandProblems("rows@array").isEmpty(), "@array 后缀是 key 语义，不是表达式");
    }
}
