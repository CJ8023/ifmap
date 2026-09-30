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
