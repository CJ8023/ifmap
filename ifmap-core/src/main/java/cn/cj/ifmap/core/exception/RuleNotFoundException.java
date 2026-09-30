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
 * 规则不存在。{@code @FUN(name,...)} 中的 name 未注册。
 *
 * <p>与存量引擎的关键差异：存量实现在规则不存在时静默返回 {@code null}，
 * 导致错误直到生产报文校验才暴露；ifmap 在<b>启动期</b>就抛出本异常。</p>
 *
 * @author caijun
 */
public class RuleNotFoundException extends IfmapConfigException {

    private static final long serialVersionUID = 1L;

    public RuleNotFoundException(String message) {
        super(message);
    }
}
