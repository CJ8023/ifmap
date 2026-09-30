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
package cn.cj.ifmap.core.template;

/**
 * 取值为空时的处理策略。
 *
 * <p>存量引擎在取不到值时会<b>把模板原文写进报文</b>（例如报文字段出现
 * {@code "@FUN(farmatDate,$.dueDate,yyyy-MM-dd)"} 字面量），本枚举用于显式定义正确行为。</p>
 *
 * @author caijun
 */
public enum NullPolicy {

    /** 省略该字段（默认）。 */
    SKIP_FIELD,

    /** 写入空字符串。 */
    EMPTY_STRING,

    /** 直接失败，便于提前暴露配置错误。 */
    FAIL
}
