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

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.model.IfmapRequest;

/**
 * 条件参数解析 SPI：把存量代码里"某银行某节点硬编码取某值"的逻辑外置（P1-11）。
 *
 * <p>默认实现 {@link AttributeConditionValueResolver} 支持
 * {@code payload:x} / {@code attr:x} / {@code header:x} 三种前缀与裸键。</p>
 *
 * @author caijun
 */
public interface ConditionValueResolver {

    /** 解析条件值；无法解析返回 null。 */
    Object resolve(String condition, IfmapRequest request, IfmapConfig config);
}
