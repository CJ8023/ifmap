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
package cn.cj.ifmap.core.rule.builtin;

import cn.cj.ifmap.core.exception.RuleArgumentException;
import cn.cj.ifmap.core.rule.StrictTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code concat} 收到容器实参时的两种模式（设计 §4.4 U4-A）。
 *
 * <p>为什么只测这一个规则：{@code Coercions.canCoerce} 已经挡住了「容器 → String 形参」，
 * 只有 {@code concat} 的形参是 {@code Object...}，而 {@code canCoerce(t, Object.class)} 恒为 true，
 * 于是数组会被 {@code StringBuilder.append} 悄悄串成 {@code "[01, 02]"}。</p>
 *
 * @author caijun
 */
class StringRulesStrictTypesTest {

    @Test
    @DisplayName("warn 模式：保持存量输出（List.toString()），不打断业务")
    void warnModeKeepsLegacyOutput() {
        assertEquals("[01, 02]-x", new StringRules(StrictTypes.WARN).concat(Arrays.asList("01", "02"), "-x"));
        assertEquals("[01, 02]-x", new StringRules().concat(Arrays.asList("01", "02"), "-x"),
                "无参构造必须等价于 warn，宿主源码兼容");
    }

    @Test
    @DisplayName("warn 模式：Map 实参同样提示（HashMap 的 toString 顺序不稳定，进报文等于随机）")
    void warnModeAcceptsMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("a", 1);
        assertEquals("{a=1}", new StringRules(StrictTypes.WARN).concat(map));
    }

    @Test
    @DisplayName("fail 模式：容器实参抛 RuleArgumentException，消息带规则名/下标/替代写法")
    void failModeRejectsContainer() {
        RuleArgumentException e = assertThrows(RuleArgumentException.class,
                () -> new StringRules(StrictTypes.FAIL).concat("prefix", Arrays.asList("01", "02")));

        String message = e.getMessage();
        assertTrue(message.contains("concat"), message);
        assertTrue(message.contains("第 2 个"), message);
        assertTrue(message.contains("listJoin"), message);
    }

    @Test
    @DisplayName("fail 模式：字面量数字仍是标量，不会被误判成容器，且不出科学计数法")
    void failModeKeepsScalarNumbers() {
        assertEquals("10000000000", new StringRules(StrictTypes.FAIL).concat(1.0E10));
        assertEquals("12", new StringRules(StrictTypes.FAIL).concat(1, 2), "concat 不加分隔符（要分隔符用 listJoin）");
        assertEquals("", new StringRules(StrictTypes.FAIL).concat((Object[]) null));
    }
}
