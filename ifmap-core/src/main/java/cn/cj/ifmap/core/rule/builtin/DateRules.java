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

import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.rule.RuleContext;
import cn.cj.ifmap.core.spi.HolidayCalendar;
import cn.cj.ifmap.core.util.Text;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 内置日期规则。
 *
 * <p>约定：无法解析的日期返回 {@code null}（只记 WARN），由 {@code NullPolicy} 决定字段去留，
 * <b>不会</b>把模板原文写进报文。</p>
 *
 * @author caijun
 */
public class DateRules {

    /** 日期格式化（宽松识别源格式）。 */
    @IfmapRule(value = "dateFormat",
            desc = "日期格式化，源格式宽松识别",
            example = "@FUN(dateFormat,$.dueDate,yyyy-MM-dd)")
    public String dateFormat(String value, String pattern) {
        return Dates.format(Dates.parseOrNull(value), pattern);
    }

    /** 日期格式化，默认 yyyy-MM-dd。 */
    @IfmapRule(value = "dateFormat",
            desc = "日期格式化，默认 yyyy-MM-dd",
            example = "@FUN(dateFormat,$.dueDate)")
    public String dateFormat(String value) {
        return dateFormat(value, "yyyy-MM-dd");
    }

    /** 按显式源格式转换。 */
    @IfmapRule(value = "dateConvert",
            desc = "按显式源格式解析后按目标格式输出",
            example = "@FUN(dateConvert,$.dueDate,yyyyMMdd,yyyy-MM-dd)")
    public String dateConvert(String value, String sourcePattern, String pattern) {
        return Dates.format(Dates.parseStrict(value, sourcePattern, "dateConvert"), pattern);
    }

    /** 日期加减（天）。 */
    @IfmapRule(value = "dateAdd",
            desc = "日期加减，默认按天",
            example = "@FUN(dateAdd,$.dueDate,3)")
    public String dateAdd(String value, Integer amount) {
        return dateAdd(value, amount, "DAY", "yyyy-MM-dd");
    }

    /** 日期加减（指定单位）。 */
    @IfmapRule(value = "dateAdd",
            desc = "日期加减，unit 取 DAY / MONTH / YEAR / HOUR / MINUTE / SECOND",
            example = "@FUN(dateAdd,$.dueDate,1,MONTH)")
    public String dateAdd(String value, Integer amount, String unit) {
        return dateAdd(value, amount, unit, "yyyy-MM-dd");
    }

    /** 日期加减（指定单位与输出格式）。 */
    @IfmapRule(value = "dateAdd",
            desc = "日期加减，可指定单位与输出格式",
            example = "@FUN(dateAdd,$.dueDate,1,MONTH,yyyyMMdd)")
    public String dateAdd(String value, Integer amount, String unit, String pattern) {
        LocalDateTime time = Dates.parseOrNull(value);
        if (time == null) {
            return null;
        }
        ChronoUnit chronoUnit = Dates.parseUnit(unit, "dateAdd");
        int delta = amount == null ? 0 : amount;
        return Dates.format(time.plus(delta, chronoUnit), pattern);
    }

    /** 日期差（天）。 */
    @IfmapRule(value = "dateDiff",
            desc = "日期差，默认按天（后者减前者）",
            example = "@FUN(dateDiff,$.startDate,$.endDate)")
    public String dateDiff(String begin, String end) {
        return dateDiff(begin, end, "DAY");
    }

    /** 日期差（指定单位）。 */
    @IfmapRule(value = "dateDiff",
            desc = "日期差，unit 取 DAY / MONTH / YEAR / HOUR / MINUTE / SECOND",
            example = "@FUN(dateDiff,$.startDate,$.endDate,MONTH)")
    public String dateDiff(String begin, String end, String unit) {
        LocalDateTime a = Dates.parseOrNull(begin);
        LocalDateTime b = Dates.parseOrNull(end);
        if (a == null || b == null) {
            return null;
        }
        return String.valueOf(Dates.parseUnit(unit, "dateDiff").between(a, b));
    }

    /** 当月最后一天。 */
    @IfmapRule(value = "dateEndOfMonth",
            desc = "取所在月最后一天",
            example = "@FUN(dateEndOfMonth,$.dueDate,yyyy-MM-dd)")
    public String dateEndOfMonth(String value, String pattern) {
        LocalDateTime time = Dates.parseOrNull(value);
        if (time == null) {
            return null;
        }
        LocalDate lastDay = time.toLocalDate().withDayOfMonth(time.toLocalDate().lengthOfMonth());
        return Dates.format(lastDay.atStartOfDay(), pattern);
    }

    /** 工作日加减（简化口径：跳过周六周日）。 */
    @IfmapRule(value = "workdayAdd",
            desc = "工作日加减，默认跳过周六周日；上下文提供 HolidayCalendar 时按节假日日历",
            example = "@FUN(workdayAdd,$.dueDate,3)")
    public String workdayAdd(RuleContext context, String value, Integer amount) {
        return workdayAdd(context, value, amount, "yyyy-MM-dd");
    }

    /** 工作日加减（指定输出格式）。 */
    @IfmapRule(value = "workdayAdd",
            desc = "工作日加减并指定输出格式",
            example = "@FUN(workdayAdd,$.dueDate,3,yyyyMMdd)")
    public String workdayAdd(RuleContext context, String value, Integer amount, String pattern) {
        LocalDate date = null;
        LocalDateTime time = Dates.parseOrNull(value);
        if (time != null) {
            date = time.toLocalDate();
        }
        if (date == null) {
            return null;
        }
        HolidayCalendar calendar = context == null ? null : context.getHolidayCalendar();
        int delta = amount == null ? 0 : amount;
        int step = delta >= 0 ? 1 : -1;
        int remaining = Math.abs(delta);
        LocalDate cursor = date;
        int guard = 0;
        int maxGuard = Math.abs(delta) * 5 + 10;
        while (remaining > 0 && guard++ < maxGuard) {
            cursor = cursor.plusDays(step);
            if (isWorkday(cursor, calendar)) {
                remaining--;
            }
        }
        return Dates.format(cursor.atStartOfDay(), pattern);
    }

    private static boolean isWorkday(LocalDate date, HolidayCalendar calendar) {
        if (calendar != null) {
            return calendar.isWorkday(date);
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
    }

    /** 是否为工作日（供宿主复用）。 */
    public static boolean workday(LocalDate date, HolidayCalendar calendar) {
        return isWorkday(date, calendar);
    }

    /** 空值判断的便捷方法，避免规则里重复判空。 */
    static String nvl(String value) {
        return Text.trimToNull(value);
    }
}
