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
 * 日志脱敏 SPI（P1-12）。
 *
 * <p>入库前调用一次；实现须保证<b>幂等</b>（重复脱敏结果不变）与<b>不抛异常</b>
 * （脱敏失败不允许影响业务，失败时应原样返回并自行记 WARN）。</p>
 *
 * @author caijun
 */
public interface LogMasker {

    /** 返回脱敏后的报文；入参为 null 时返回 null。 */
    String mask(String json);
}
