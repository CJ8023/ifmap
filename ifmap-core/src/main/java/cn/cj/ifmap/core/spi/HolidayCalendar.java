package cn.cj.ifmap.core.spi;

import java.time.LocalDate;

/**
 * 工作日历 SPI：{@code workdayAdd} 规则用它判断调休/法定节假日。
 *
 * <p>未提供时按「周一~周五为工作日、周六周日休息」的简化口径处理。</p>
 *
 * @author caijun
 */
public interface HolidayCalendar {

    /** 是否工作日。 */
    boolean isWorkday(LocalDate date);
}
