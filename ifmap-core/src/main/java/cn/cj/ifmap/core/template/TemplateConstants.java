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
 * 模板 DSL 常量（与存量引擎逐字对齐，保证模板可平滑迁移）。
 *
 * @author caijun
 */
public final class TemplateConstants {

    /** 数组标识符（用在 key 上）：值统一转成数组。 */
    public static final String KEY_ARRAY = "@array";

    /** 数组标识符（用在 key 上，后接 JsonPath）：按源数组逐元素展开子模板。 */
    public static final String KEY_ARRAYS = "@array@";

    /** 多路取值合并为数组（用在 value 上）。 */
    public static final String SEPARATOR_AND = "@and@";

    /** 多路取值取第一个非空（用在 value 上）。 */
    public static final String SEPARATOR_OR = "@or@";

    /** 多路取值用半角逗号拼接（用在 value 上）。 */
    public static final String SEPARATOR_CONCAT = "@concat@";

    /** 多路取值直接拼接、无分隔符（用在 value 上）。 */
    public static final String SEPARATOR_APPEND = "@append@";

    /** 多路取值按数值求和（用在 value 上）。 */
    public static final String SEPARATOR_SUM = "@sum@";

    /** 函数前缀。 */
    public static final String FUN_PREFIX = "@FUN(";

    /** 函数后缀。 */
    public static final String FUN_SUFFIX = ")";

    /** JsonPath 前缀。 */
    public static final String PATH_PREFIX = "$.";

    /** 内置流水号取值。 */
    public static final String PATH_SEQ_NO = "$.seqNo";

    /** 函数参数分隔符。 */
    public static final String ARG_SEPARATOR = ",";

    private TemplateConstants() {
    }
}
