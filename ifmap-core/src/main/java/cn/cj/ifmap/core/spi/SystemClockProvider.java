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

/**
 * 默认时间源：系统时钟。
 *
 * @author caijun
 */
public class SystemClockProvider implements ClockProvider {

    /** 共享实例（无状态，可安全复用）。 */
    public static final SystemClockProvider INSTANCE = new SystemClockProvider();

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
