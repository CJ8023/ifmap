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
package cn.cj.ifmap.jdbc;

import cn.cj.ifmap.core.exception.IfmapConfigException;

/**
 * JDBC 取值归一化工具。
 *
 * <p><b>为什么需要它</b>：表里 {@code result_flag}/{@code success_value}/{@code strategy_name}/{@code remark}
 * 等列都是 {@code NOT NULL DEFAULT ''}，而 SQL 默认值**只在 INSERT 省略该列时**生效。若语句显式列出该列
 * 却绑定 {@code null}，MySQL（默认 {@code STRICT_TRANS_TABLES}）会直接报
 * {@code Column 'xxx' cannot be null}，H2 报 {@code NULL not allowed for column}。
 * 因此写入前必须把 {@code null} 归一化为空串，不能依赖列的 DEFAULT。</p>
 *
 * @author caijun
 */
final class JdbcValues {

    private JdbcValues() {
    }

    /** {@code null} → {@code ""}（用于 {@code NOT NULL DEFAULT ''} 列）。 */
    static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 必填字符串校验（非空校验先于 DB 约束，给出可定位的错误信息）。 */
    static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IfmapConfigException("配置缺少必填字段：" + field);
        }
        return value;
    }

    /** {@code null} → 默认值。 */
    static int orDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    /** {@code null} → 默认值。 */
    static long orDefault(Long value, long defaultValue) {
        return value == null ? defaultValue : value;
    }
}
