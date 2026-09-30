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
package cn.cj.ifmap.json.fastjson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FastjsonPaths} 的纯函数测试：路径语法体检、多值路径判断、多值段拆分。
 *
 * <p>这些判断决定了「什么时候返回 null、什么时候返回空列表、什么时候补 null」，
 * 是 fastjson 实现能否通过 TCK 的关键，所以单独测而不是只靠端到端断言。</p>
 *
 * @author caijun
 */
class FastjsonPathsTest {

    @Test
    @DisplayName("isValid：合法路径（含引号内带特殊字符的键）")
    void isValidAcceptsValidPaths() {
        assertTrue(FastjsonPaths.isValid("$.a.b"));
        assertTrue(FastjsonPaths.isValid("$.items[0].sku"));
        assertTrue(FastjsonPaths.isValid("$.items[*].sku"));
        assertTrue(FastjsonPaths.isValid("$..sku"));
        assertTrue(FastjsonPaths.isValid("$.items[?(@.qty > 1)].sku"));
        assertTrue(FastjsonPaths.isValid("$.items[0:3].sku"));
        assertTrue(FastjsonPaths.isValid("$['a.b']"), "引号内的点号是键名的一部分");
        assertTrue(FastjsonPaths.isValid("$['a[*]']"), "引号内的方括号是键名的一部分");
        assertTrue(FastjsonPaths.isValid("  $.a.b  "), "前后空白应被忽略");
    }

    @Test
    @DisplayName("isValid：空白、未闭合、不以 $ 开头一律 false（fastjson 的 compile 拦不住 $[?( ）")
    void isValidRejectsBrokenPaths() {
        assertFalse(FastjsonPaths.isValid(null));
        assertFalse(FastjsonPaths.isValid(""));
        assertFalse(FastjsonPaths.isValid("   "));
        assertFalse(FastjsonPaths.isValid("$[?("), "括号未闭合：fastjson 编译期不报错，必须自己拦");
        assertFalse(FastjsonPaths.isValid("$.a["), "方括号未闭合");
        assertFalse(FastjsonPaths.isValid("$."), "以点号结尾");
        assertFalse(FastjsonPaths.isValid("$..") , "以递归下降结尾");
        assertFalse(FastjsonPaths.isValid("a.b"), "必须以 $ 开头");
        assertFalse(FastjsonPaths.isValid("$['a"), "引号未闭合");
    }

    @Test
    @DisplayName("isMultiValue：通配/过滤器/切片/多选/递归下降为真，单值路径为假")
    void isMultiValueDetectsResultSets() {
        assertTrue(FastjsonPaths.isMultiValue("$.items[*].sku"));
        assertTrue(FastjsonPaths.isMultiValue("$.*"));
        assertTrue(FastjsonPaths.isMultiValue("$.items[?(@.qty > 1)].sku"));
        assertTrue(FastjsonPaths.isMultiValue("$.items[0:3].sku"));
        assertTrue(FastjsonPaths.isMultiValue("$.items[0,2].sku"));
        assertTrue(FastjsonPaths.isMultiValue("$..sku"));

        assertFalse(FastjsonPaths.isMultiValue("$.a.b"));
        assertFalse(FastjsonPaths.isMultiValue("$.items[0].sku"));
        assertFalse(FastjsonPaths.isMultiValue("$['a[*]']"), "引号内的 * 不是通配");
        assertFalse(FastjsonPaths.isMultiValue("$['a:b']"), "引号内的 : 不是切片");
    }

    @Test
    @DisplayName("boundedMultiValuePrefix：只有「有界多值段 + 属性链」才返回前缀（用于 leafToNull 补 null）")
    void boundedMultiValuePrefixOnlyForBoundedShapes() {
        assertEquals("$.items[*]", FastjsonPaths.boundedMultiValuePrefix("$.items[*].opt"));
        assertEquals("$.items[?(@.qty > 1)]",
                FastjsonPaths.boundedMultiValuePrefix("$.items[?(@.qty > 1)].sku"));
        assertEquals("$.items[*]", FastjsonPaths.boundedMultiValuePrefix("$.items[*].buyer.city"));
        assertEquals("$.items[*].tags[*]",
                FastjsonPaths.boundedMultiValuePrefix("$.items[*].tags[*].code"));

        assertNull(FastjsonPaths.boundedMultiValuePrefix("$..sku"), "递归下降的父集合无界，不补偿");
        assertNull(FastjsonPaths.boundedMultiValuePrefix("$.items[*]"), "末尾就是多值段，没有叶子");
        assertNull(FastjsonPaths.boundedMultiValuePrefix("$.a.b"), "单值路径不需要补偿");
        assertNull(FastjsonPaths.boundedMultiValuePrefix("$.items[*].tags[*]"), "叶子仍是多值段，不补偿");
    }
}
