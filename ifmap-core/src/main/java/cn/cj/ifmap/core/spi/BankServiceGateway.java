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

import cn.cj.ifmap.core.model.BankCall;

/**
 * 出网调用 SPI：由宿主机实现（各银行 SDK / HTTP 客户端 / 加解密都在这里）。
 *
 * <p>引擎<b>不做</b>任何网络与加密（设计 §2.3「明确不做的事」）。实现方应把
 * IO/超时/非 2xx 包装成 {@link cn.cj.ifmap.core.exception.IfmapRemoteException}（可重试）。</p>
 *
 * @author caijun
 */
public interface BankServiceGateway {

    /**
     * 发起调用并返回响应原文（JSON 文本）。
     *
     * @param call 本次调用入参（配置 + 上下文 + 渲染后的报文）
     * @return 响应原文，允许为 null（表示无响应体）
     */
    String exchange(BankCall call);
}
