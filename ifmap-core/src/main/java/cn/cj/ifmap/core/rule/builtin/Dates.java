package cn.cj.ifmap.core.rule.builtin;

import cn.cj.ifmap.core.exception.RuleArgumentException;
import cn.cj.ifmap.core.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 日期解析/格式化内部工具：对常见格式做宽松解析。
 *
 * @author caijun
 */
final class Dates {

    private static final Logger log = LoggerFactory.getLogger(Dates.class);

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = new ArrayList<DateTimeFormatter>();
    private static final List<DateTimeFormatter> DATE_FORMATTERS = new ArrayList<DateTimeFormatter>();

    static {
        DATE_TIME_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        DATE_TIME_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        DATE_TIME_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        DATE_TIME_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        DATE_TIME_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));
        DATE_TIME_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        DATE_TIME_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        DATE_TIME_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        DATE_TIME_FORMATTERS.add(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyyMMdd"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyy-MM"));
        DATE_FORMATTERS.add(DateTimeFormatter.ofPattern("yyyyMM"));
        DATE_FORMATTERS.add(DateTimeFormatter.BASIC_ISO_DATE);
        DATE_FORMATTERS.add(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private Dates() {
    }

    /** 宽松解析；无法解析返回 null（只记 WARN，不中断报文渲染）。 */
    static LocalDateTime parseOrNull(String value) {
        if (Text.isBlank(value)) {
            return null;
        }
        String v = value.trim();
        for (DateTimeFormatter f : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(v, f);
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一个格式
            }
        }
        for (DateTimeFormatter f : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(v, f).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一个格式
            }
        }
        if (v.matches("\\d{10}")) {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(v)), ZoneId.systemDefault());
        }
        if (v.matches("\\d{13}")) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(v)), ZoneId.systemDefault());
        }
        log.warn("日期 [{}] 无法按任何已知格式解析，按取值为空处理", value);
        return null;
    }

    /** 按显式格式解析；失败抛 {@link RuleArgumentException}。 */
    static LocalDateTime parseStrict(String value, String pattern, String what) {
        if (Text.isBlank(value)) {
            return null;
        }
        if (Text.isBlank(pattern)) {
            throw new RuleArgumentException(what + " 的格式不能为空");
        }
        try {
            return LocalDateTime.parse(value.trim(), DateTimeFormatter.ofPattern(pattern.trim()));
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(value.trim(), DateTimeFormatter.ofPattern(pattern.trim())).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                throw new RuleArgumentException("日期 [" + value + "] 无法按格式 [" + pattern + "] 解析");
            }
        }
    }

    static String format(LocalDateTime time, String pattern) {
        if (time == null) {
            return null;
        }
        String p = Text.isBlank(pattern) ? "yyyy-MM-dd" : pattern.trim();
        return DateTimeFormatter.ofPattern(p).format(time);
    }

    /** 解析时间单位，兼容常见缩写。 */
    static ChronoUnit parseUnit(String unit, String what) {
        if (Text.isBlank(unit)) {
            return ChronoUnit.DAYS;
        }
        String u = unit.trim().toUpperCase();
        if ("D".equals(u) || "DAY".equals(u) || "DAYS".equals(u) || "DATE".equals(u)) {
            return ChronoUnit.DAYS;
        }
        if ("M".equals(u) || "MONTH".equals(u) || "MONTHS".equals(u)) {
            return ChronoUnit.MONTHS;
        }
        if ("Y".equals(u) || "YEAR".equals(u) || "YEARS".equals(u)) {
            return ChronoUnit.YEARS;
        }
        if ("H".equals(u) || "HOUR".equals(u) || "HOURS".equals(u)) {
            return ChronoUnit.HOURS;
        }
        if ("MI".equals(u) || "MINUTE".equals(u) || "MINUTES".equals(u)) {
            return ChronoUnit.MINUTES;
        }
        if ("S".equals(u) || "SECOND".equals(u) || "SECONDS".equals(u)) {
            return ChronoUnit.SECONDS;
        }
        if ("W".equals(u) || "WEEK".equals(u) || "WEEKS".equals(u)) {
            return ChronoUnit.WEEKS;
        }
        throw new RuleArgumentException(what + " 的时间单位 [" + unit + "] 不支持，"
                + "可用 DAY / MONTH / YEAR / HOUR / MINUTE / SECOND / WEEK");
    }
}
