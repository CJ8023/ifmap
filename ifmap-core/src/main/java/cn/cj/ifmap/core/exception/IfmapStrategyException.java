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
 * 策略/动作相关异常：策略未注册、动作缺失、注册歧义等。
 *
 * <p>对应设计 §7.6：<b>不再静默</b> —— 存量引擎里"策略找不到就跳过"的行为被取消。</p>
 *
 * @author caijun
 */
public class IfmapStrategyException extends IfmapException {

    private static final long serialVersionUID = 1L;

    public IfmapStrategyException(String message) {
        super(message);
    }

    public IfmapStrategyException(String message, Throwable cause) {
        super(message, cause);
    }
}
