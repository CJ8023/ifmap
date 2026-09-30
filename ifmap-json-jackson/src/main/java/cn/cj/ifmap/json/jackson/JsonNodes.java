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
package cn.cj.ifmap.json.jackson;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jackson {@link JsonNode} 到 JDK 原生类型的转换。
 *
 * <p>这一层是「公开 API 不出现 JSON 库类型」的关键：JsonPath 用 Jackson 求值会得到
 * {@code JsonNode}，必须在这里一次性转成 {@code Map} / {@code List} / {@code String} /
 * {@code Number} / {@code Boolean}，否则 {@code JsonNode.toString()} 会带引号并污染报文。</p>
 *
 * @author caijun
 */
final class JsonNodes {

    private JsonNodes() {
    }

    static Object toJdk(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                map.put(entry.getKey(), toJdk(entry.getValue()));
            }
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<Object>(node.size());
            for (JsonNode item : node) {
                list.add(toJdk(item));
            }
            return list;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            if (node.canConvertToInt()) {
                return node.intValue();
            }
            if (node.canConvertToLong()) {
                return node.longValue();
            }
            return node.bigIntegerValue();
        }
        if (node.isFloatingPointNumber()) {
            BigDecimal decimal = node.decimalValue();
            return decimal == null ? node.doubleValue() : decimal;
        }
        if (node.isBinary()) {
            return node.asText();
        }
        return node.asText();
    }
}
