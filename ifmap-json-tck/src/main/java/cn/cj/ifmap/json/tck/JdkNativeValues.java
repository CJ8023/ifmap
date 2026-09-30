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
package cn.cj.ifmap.json.tck;

import java.util.List;

/**
 * 把一个值递归断言为「JDK 原生结构」：{@code Map} / {@code List} / {@code String} /
 * {@code Boolean} / {@code Number} / {@code null}。
 *
 * <p>这是 {@code JsonOps} 的核心契约之一：ifmap 的公开 API 与 SPI 边界上
 * **不允许出现任何 JSON 库类型**（{@code JsonNode} / {@code JSONObject} / {@code JSONArray}…），
 * 否则宿主代码会被迫依赖具体 JSON 库，换库就等于改业务代码。</p>
 *
 * @author caijun
 */
final class JdkNativeValues {

    private JdkNativeValues() {
    }

    /** 递归断言；不通过时抛出带路径的断言错误。 */
    static void assertJdkNative(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            return;
        }
        if (value instanceof java.util.Map) {
            for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) value).entrySet()) {
                assertJdkNative(entry.getKey(), path + "." + entry.getKey());
                assertJdkNative(entry.getValue(), path + "." + entry.getKey());
            }
            return;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                assertJdkNative(list.get(i), path + "[" + i + "]");
            }
            return;
        }
        throw new AssertionError("路径 " + path + " 上是非 JDK 原生类型：" + value.getClass().getName()
                + "（JsonOps 实现必须返回 JDK 原生结构，不能泄漏 JSON 库类型）");
    }
}
