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
package cn.cj.ifmap.core.exception;

/**
 * 出网调用失败异常（可重试）。
 *
 * <p>宿主机的 {@code BankServiceGateway} 实现应把 IO/超时/非 2xx 包装成本异常，
 * 引擎据此区分"配置错"（不可重试）与"通道错"（可重试）。</p>
 *
 * @author caijun
 */
public class IfmapRemoteException extends IfmapException {

    private static final long serialVersionUID = 1L;

    public IfmapRemoteException(String message) {
        super(message);
    }

    public IfmapRemoteException(String message, Throwable cause) {
        super(message, cause);
    }
}
