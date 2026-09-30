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

import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;

/**
 * 银行回调 SPI：替换存量 {@code IfmapConfigApplication} 里的 17 个回调方法。
 *
 * <p>按 {@code interfaceNo} 注册（见 {@code CallbackRegistry}），引擎值此一个入口。</p>
 *
 * @author caijun
 */
public interface IfmapCallbackHandler {

    /** 处理回调；返回值表示处理结果（宿主机自定义语义）。 */
    IfmapResult handle(IfmapRequest request, String rawBody);
}
