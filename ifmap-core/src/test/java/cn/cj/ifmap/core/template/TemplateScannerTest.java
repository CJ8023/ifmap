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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模板静态扫描：规则名 + 表达式错语法（设计 §4.4 U4-C）。
 *
 * @author caijun
 */
class TemplateScannerTest {

    @Test
    @DisplayName("ruleNames：抽出 @FUN 引用的规则名，去重保序")
    void ruleNames() {
        assertEquals(2, TemplateScanner.ruleNames(
                "{\"a\":\"@FUN(concat,$.a,$.b)\",\"b\":\"@FUN( concat ,$.c)\",\"c\":\"@FUN(numRound,$.d,2)\"}")
                .size());
    }

    @Test
    @DisplayName("expressionProblems：干净模板零问题")
    void cleanTemplateHasNoProblems() {
        assertTrue(TemplateScanner.expressionProblems(
                "{\"a\":\"@FUN(concat,$.x,$.y)\",\"b\":\"$.z\",\"c\":\"常量,含逗号\"}").isEmpty());
        assertTrue(TemplateScanner.expressionProblems("{\"a\":\"@FUN(strDefault,$.x,)\"}").isEmpty(), "空实参合法");
        assertTrue(TemplateScanner.expressionProblems(null).isEmpty());
        assertTrue(TemplateScanner.expressionProblems("").isEmpty());
    }

    @Test
    @DisplayName("expressionProblems：@sum@$.a,$.b 必须报出来（否则线上静默求和成 0）")
    void reportsCommaInInfixOperand() {
        List<String> problems = TemplateScanner.expressionProblems("{\"s\":\"@sum@$.a,$.b\"}");

        assertEquals(1, problems.size(), String.valueOf(problems));
        assertTrue(problems.get(0).contains("$.a,$.b"), problems.get(0));
    }

    @Test
    @DisplayName("expressionProblems：未闭合的 @FUN( 必须报出来（否则整串当字面量进报文）")
    void reportsUnclosedFun() {
        assertEquals(1, TemplateScanner.expressionProblems("{\"s\":\"@FUN(concat,$.a\"}").size());
        assertTrue(TemplateScanner.expressionProblems("{\"s\":\"@FUN(farmatDate,$.a,yyyy)\"}").isEmpty(),
                "规则名拼写错误不归本方法管，由 assertRulesExist 负责");
    }

    @Test
    @DisplayName("expressionProblems：@array@ 形态的 key 也要扫（路径藏在 key 里）")
    void reportsProblemsInsideArrayKeys() {
        List<String> problems = TemplateScanner.expressionProblems("{\"rows@array@$.a,$.b\":{\"v\":\"$.v\"}}");

        assertEquals(1, problems.size(), String.valueOf(problems));
        assertTrue(problems.get(0).contains("$.a,$.b"), problems.get(0));
        assertTrue(TemplateScanner.expressionProblems("{\"rows@array@$.items[*]\":{\"v\":\"$.v\"}}").isEmpty());
    }

    @Test
    @DisplayName("expressionProblems：同一个错语法出现多次只报一次")
    void deduplicatesProblems() {
        assertEquals(1, TemplateScanner.expressionProblems(
                "{\"a\":\"@sum@$.x,$.y\",\"b\":\"@sum@$.x,$.y\"}").size());
    }
}
