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

import cn.cj.ifmap.core.exception.RuleArgumentException;
import cn.cj.ifmap.core.rule.RuleContext;
import cn.cj.ifmap.core.rule.RuleRegistry;
import cn.cj.ifmap.core.rule.StrictTypes;
import cn.cj.ifmap.core.rule.builtin.BuiltinRules;
import cn.cj.ifmap.core.rule.builtin.DateRules;
import cn.cj.ifmap.core.rule.builtin.DictRules;
import cn.cj.ifmap.core.rule.builtin.ListRules;
import cn.cj.ifmap.core.rule.builtin.NumberRules;
import cn.cj.ifmap.core.rule.builtin.StringRules;
import cn.cj.ifmap.core.spi.HolidayCalendar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 内置规则单元测试（直接调方法，不经注册表）。
 *
 * @author caijun
 */
class BuiltinRulesTest {

    private final StringRules strings = new StringRules();
    private final DictRules dicts = new DictRules();
    private final DateRules dates = new DateRules();
    private final NumberRules numbers = new NumberRules();
    private final ListRules lists = new ListRules();

    @Test
    @DisplayName("concat：null 按空串")
    void concat() {
        assertEquals("ab", strings.concat("a", null, "b"));
        assertEquals("", strings.concat());
    }

    @Test
    @DisplayName("strDefault / strTruncate / strMask")
    void stringRules() {
        assertEquals("未知", strings.strDefault(null, "未知"));
        assertEquals("未知", strings.strDefault("", "未知"));
        assertEquals("x", strings.strDefault("x", "未知"));
        assertEquals("abc", strings.strTruncate("abcdef", 3));
        assertEquals("abc...", strings.strTruncate("abcdef", 3, "..."));
        assertEquals("3201************34", strings.strMask("320123199001011234", "ID_CARD"));
        assertEquals("138******00", strings.strMask("13800138000", "MOBILE"));
        assertEquals("张*", strings.strMask("张三", "NAME"));
        // 19 位卡号，保留头 4 尾 3 → 中间 12 个星（文档 docs/03 §2 的示例值由此保证）
        assertEquals("6222************123", strings.strMask("6222021234567890123", "BANK_CARD"));
        // 邮箱保留域名：首字符 + (本地部分长度-1) 个星 + @域名
        assertEquals("z*******@x.com", strings.strMask("zhangsan@x.com", "EMAIL"));
        // 长度不足时：NAME(保留 1+0) 仍会打星；保留位数之和 >= 长度才原样返回（如 NAME 跑 1 位串）
        assertEquals("a**", strings.strMask("abc", "NAME"));
        assertEquals("a", strings.strMask("a", "NAME"));
        // 未知 type 原样返回（便于模板平滑迁移）
        assertEquals("abc", strings.strMask("abc", "NOT_A_TYPE"));
    }

    @Test
    @DisplayName("dictVal：冒号分隔、值含横线不被截断、未命中走默认值")
    void dictVal() {
        assertEquals("增值税专用发票", dicts.dictVal("01", "01:增值税专用发票;02:增值税普通发票"));
        assertEquals("其他", dicts.dictVal("09", "01:专票;02:普票", "其他"));
        assertNull(dicts.dictVal("09", "01:专票"));
        assertEquals("2026-03-31", dicts.dictVal("A", "A:2026-03-31"));
        assertEquals("待处理-已受理", dicts.dictVal("A", "A:待处理-已受理"));
        assertEquals("待处理", dicts.dictVal("1", "1-待处理"));
    }

    @Test
    @DisplayName("dictVal：表达式非法时报明确错误")
    void dictValInvalid() {
        assertThrows(RuleArgumentException.class, () -> dicts.dictVal("1", ""));
        assertThrows(RuleArgumentException.class, () -> dicts.dictVal("1", "没有分隔符"));
    }

    @Test
    @DisplayName("dictValArray：逐元素翻译")
    void dictValArray() {
        List<Object> result = dicts.dictValArray(Arrays.asList("01", "02", "09"), "01:专票;02:普票", "其他");
        assertEquals(Arrays.asList("专票", "普票", "其他"), result);
        assertEquals(Collections.emptyList(), dicts.dictValArray(null, "01:专票"));
    }

    @Test
    @DisplayName("dateFormat：宽松识别多种源格式")
    void dateFormat() {
        assertEquals("20260331", dates.dateFormat("2026-03-31", "yyyyMMdd"));
        assertEquals("20260331", dates.dateFormat("2026/03/31", "yyyyMMdd"));
        assertEquals("20260331", dates.dateFormat("20260331", "yyyyMMdd"));
        assertEquals("2026-03-31", dates.dateFormat("2026-03-31 10:20:30", "yyyy-MM-dd"));
        assertNull(dates.dateFormat("不是日期", "yyyyMMdd"));
        assertNull(dates.dateFormat(null, "yyyyMMdd"));
    }

    @Test
    @DisplayName("dateConvert / dateAdd / dateDiff / dateEndOfMonth")
    void dateRules() {
        assertEquals("2026-03-31", dates.dateConvert("20260331", "yyyyMMdd", "yyyy-MM-dd"));
        assertEquals("2026-04-03", dates.dateAdd("2026-03-31", 3));
        assertEquals("2026-04-30", dates.dateAdd("2026-03-31", 1, "MONTH"));
        assertEquals("3", dates.dateDiff("2026-03-31", "2026-04-03"));
        assertEquals("2026-03-31", dates.dateEndOfMonth("2026-03-15", "yyyy-MM-dd"));
        assertEquals("2026-02-28", dates.dateEndOfMonth("2026-02-10", "yyyy-MM-dd"));
    }

    @Test
    @DisplayName("workdayAdd：跳过周末；上下文给日历则按日历")
    void workdayAdd() {
        // 2026-03-31 是周二，+3 个工作日 -> 2026-04-03（周五）
        assertEquals("20260403", dates.workdayAdd(RuleContext.empty(), "2026-03-31", 3, "yyyyMMdd"));

        final HolidayCalendar allHoliday = new HolidayCalendar() {
            @Override
            public boolean isWorkday(LocalDate date) {
                return date.getDayOfWeek() == DayOfWeek.MONDAY;
            }
        };
        RuleContext context = RuleContext.builder()
                .attribute(RuleContext.ATTR_HOLIDAY_CALENDAR, allHoliday)
                .build();
        // 只有周一算工作日：2026-03-31(二) 之后的下一个工作日是 2026-04-06(一)
        assertEquals("20260406", dates.workdayAdd(context, "2026-03-31", 1, "yyyyMMdd"));
    }

    @Test
    @DisplayName("numRound / numSum / numOffset / numFormat")
    void numberRules() {
        assertEquals("100.46", numbers.numRound("100.455", 2));
        assertEquals("100.45", numbers.numRound("100.455", 2, "DOWN"));
        assertEquals("301", numbers.numSum(Arrays.asList("100.5", "200.25", "0.25")));
        assertEquals("300.75", numbers.numSum(Arrays.asList("100.5", "200.25"), 2, "HALF_UP"));
        assertEquals("3", numbers.numOffset("2", 1));
        assertEquals("1.01", numbers.numFormat("101", "CENT_TO_YUAN"));
        assertEquals("10100", numbers.numFormat("101", "YUAN_TO_CENT"));
        assertEquals("1,234.50", numbers.numFormat("1234.5", "THOUSAND"));
        assertThrows(RuleArgumentException.class, () -> numbers.numFormat("1", "NO_SUCH_STYLE"));
    }

    @Test
    @DisplayName("listJoin / listOp")
    void listRules() {
        assertEquals("a/b", lists.listJoin(Arrays.asList("a", "b"), "/"));
        // DISTINCT 保持首次出现顺序，不排序
        assertEquals(Arrays.asList("b", "a"), lists.listOp(Arrays.asList("b", "a", "b"), "DISTINCT"));
        assertEquals(Arrays.asList("a", "b", "c"), lists.listOp(Arrays.asList("b", "c", "a"), "SORT"));
        assertEquals(Arrays.asList("c", "b", "a"), lists.listOp(Arrays.asList("b", "c", "a"), "SORT_DESC"));
        assertEquals(Arrays.asList("c", "b", "a"), lists.listOp(Arrays.asList("a", "b", "c"), "REVERSE"));
        assertEquals(Arrays.asList("2", "10"), lists.listOp(Arrays.asList("10", "2"), "SORT"));
        assertEquals("10", lists.listOp(Arrays.asList("2", "10"), "MAX"));
        assertEquals("2", lists.listOp(Arrays.asList("2", "10"), "MIN"));
        assertEquals(Arrays.asList("a", "b"), lists.listOp(Arrays.asList("a", "b", "c"), "LIMIT", "2"));
    }

    @Test
    @DisplayName("registerTo 单参重载 = WARN：源码兼容，concat 收容器仍返回旧输出")
    void registerToDefaultKeepsWarn() {
        RuleRegistry registry = BuiltinRules.registerTo(new RuleRegistry());
        assertEquals("[01, 02]", registry.invoke("concat", null, Arrays.asList("01", "02")));
    }

    @Test
    @DisplayName("registerTo 双参重载可切 FAIL：concat 收容器抛 RuleArgumentException")
    void registerToFailRejectsContainer() {
        RuleRegistry registry = BuiltinRules.registerTo(new RuleRegistry(), StrictTypes.FAIL);
        RuleArgumentException e = assertThrows(RuleArgumentException.class,
                () -> registry.invoke("concat", null, Arrays.asList("01", "02")));
        // 消息必须能指出"改用哪个规则"，否则排查的人只知道错、不知道怎么写
        assertEquals(true, e.getMessage().contains("listJoin"));
        assertEquals(true, e.getMessage().contains("第 1 个"));
    }

    @Test
    @DisplayName("注册表调用：List 入参自动逐元素 + 上下文注入")
    void throughRegistry() {
        RuleRegistry registry = BuiltinRules.newRegistry();
        Object mapped = registry.invoke("dateFormat", null, Arrays.asList("2026-03-31", "2026-04-01"), "yyyyMMdd");
        assertEquals(Arrays.asList("20260331", "20260401"), mapped);
        assertEquals("20260403", registry.invoke("workdayAdd", RuleContext.empty(), "2026-03-31", 3, "yyyyMMdd"));
    }
}
