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
package cn.cj.ifmap.json.fastjson;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.Feature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * fastjson 类型 ↔ JDK 原生类型的转换（{@code JsonOps} 契约：返回值里不允许出现 JSON 库类型）。
 *
 * @author caijun
 */
final class FastjsonValues {

    /** 解析失败时日志/异常里最多展示的原文长度。 */
    private static final int ABBREVIATE_LIMIT = 200;

    private FastjsonValues() {
    }

    static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /**
     * JSON 文本 → fastjson 对象（{@code JSONObject} / {@code JSONArray} / 标量）。
     *
     * <p>额外做一件 fastjson 不会自己做的事：<b>把「静默解析成 null」变成显式失败</b>。
     * 1.2.84 的安全加固遇到 {@code @type} 自动类型信息时会直接返回 null（不抛异常），
     * 若放过它，含该字段的报文会被悄悄当成空对象 —— 正是本项目最忌讳的「静默取空值」。
     * 唯一合法的「解析出 null」是原文就是字面量 {@code null}。</p>
     *
     * <p>用 {@link Feature#OrderedField} 解析：保证 {@code Map} 的键序与原文一致
     * （模板渲染顺序、快照/指纹的可重复性都依赖它）。</p>
     */
    static Object parseDocument(String json) {
        Object value = JSON.parse(json, Feature.OrderedField);
        if (value == null && !"null".equals(json.trim())) {
            throw new JSONException("JSON 解析结果为 null（疑似含 @type 自动类型信息，fastjson 会拒绝该类报文）："
                    + abbreviate(json));
        }
        return value;
    }

    /** 递归转成 JDK 原生结构：{@code Map → LinkedHashMap}、{@code Collection/数组 → ArrayList}。 */
    static Object toJdk(Object value) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), toJdk(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof Collection) {
            Collection<?> collection = (Collection<?>) value;
            List<Object> copy = new ArrayList<Object>(collection.size());
            for (Object element : collection) {
                copy.add(toJdk(element));
            }
            return copy;
        }
        if (value instanceof Object[]) {
            Object[] array = (Object[]) value;
            List<Object> copy = new ArrayList<Object>(array.length);
            for (Object element : array) {
                copy.add(toJdk(element));
            }
            return copy;
        }
        return value;
    }

    /** 截断长文本，异常信息里别把整个报文打出来。 */
    static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= ABBREVIATE_LIMIT ? text : text.substring(0, ABBREVIATE_LIMIT) + "...(已截断)";
    }
}
