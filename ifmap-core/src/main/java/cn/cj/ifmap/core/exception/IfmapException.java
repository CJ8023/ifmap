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
 * ifmap 基础异常（unchecked）。
 *
 * <p>所有 ifmap 抛出的异常都继承本类，便于宿主统一兜底。</p>
 *
 * @author caijun
 */
public class IfmapException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IfmapException(String message) {
        super(message);
    }

    public IfmapException(String message, Throwable cause) {
        super(message, cause);
    }
}
