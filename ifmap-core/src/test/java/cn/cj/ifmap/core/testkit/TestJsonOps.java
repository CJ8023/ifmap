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
package cn.cj.ifmap.core.testkit;

import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonReadContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用 JsonOps：手写迷你 JSON 解析/序列化，<b>不引入任何 JSON 库</b>
 * （core 的测试同样保持零第三方依赖）。
 *
 * <p>支持对象 / 数组 / 字符串 / 数字 / 布尔 / null，以及 {@code $.a.b} 与 {@code $.list[0].x} 两种路径形态。</p>
 *
 * @author caijun
 */
public final class TestJsonOps implements JsonOps {

    @Override
    public String name() {
        return "test";
    }

    @Override
    public Object parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("JSON 尾部有多余字符：" + json);
        }
        return value;
    }

    @Override
    public String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    @Override
    public JsonReadContext readContext(final String json) {
        final Object root = parse(json);
        return new JsonReadContext() {
            @Override
            public Object read(String path, boolean leafToNull) {
                return resolve(root, path);
            }

            @Override
            public Object read(String path) {
                return resolve(root, path);
            }
        };
    }

    @Override
    public boolean isJson(String text) {
        try {
            parse(text);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Object resolve(Object root, String path) {
        if (root == null || path == null || path.trim().isEmpty()) {
            return root;
        }
        String normalized = path.trim();
        if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        Object current = root;
        for (String token : tokenize(normalized)) {
            if (current == null) {
                return null;
            }
            if (token.startsWith("[") && token.endsWith("]")) {
                int index = Integer.parseInt(token.substring(1, token.length() - 1));
                if (!(current instanceof List) || index < 0 || index >= ((List<?>) current).size()) {
                    return null;
                }
                current = ((List<?>) current).get(index);
                continue;
            }
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(token);
        }
        return current;
    }

    /** 把 {@code a.b[0].c} 拆成 {@code a} / {@code b} / {@code [0]} / {@code c}。 */
    static List<String> tokenize(String path) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
            } else if (c == '[') {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
                int end = path.indexOf(']', i);
                if (end < 0) {
                    throw new IllegalArgumentException("路径缺少 ]：" + path);
                }
                tokens.add(path.substring(i, end + 1));
                i = end;
            } else {
                token.append(c);
            }
        }
        if (token.length() > 0) {
            tokens.add(token.toString());
        }
        return tokens;
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                write(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else if (value instanceof CharSequence) {
            writeString(sb, value.toString());
        } else {
            sb.append(value);
        }
    }

    private static void writeString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    /** 迷你递归下降解析器。 */
    static final class Parser {

        private final String json;
        private int pos;

        Parser(String json) {
            this.json = json;
        }

        boolean atEnd() {
            return pos >= json.length();
        }

        void skipWhitespace() {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char c = json.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (json.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (json.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (json.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            pos++;
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue());
                skipWhitespace();
                char c = json.charAt(pos);
                pos++;
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("对象中出现非法字符 " + c + " @" + pos);
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<Object>();
            pos++;
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = json.charAt(pos);
                pos++;
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("数组中出现非法字符 " + c + " @" + pos);
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = json.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char escaped = json.charAt(pos++);
                    if (escaped == 'n') {
                        sb.append('\n');
                    } else if (escaped == 't') {
                        sb.append('\t');
                    } else {
                        sb.append(escaped);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            while (pos < json.length() && "-+.eE0123456789".indexOf(json.charAt(pos)) >= 0) {
                pos++;
            }
            String text = json.substring(start, pos);
            if (text.isEmpty()) {
                throw new IllegalArgumentException("非法 JSON 值 @" + pos);
            }
            if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
                try {
                    return Long.valueOf(text);
                } catch (NumberFormatException e) {
                    return text;
                }
            }
            return Double.valueOf(text);
        }

        private void expect(char expected) {
            skipWhitespace();
            if (atEnd() || json.charAt(pos) != expected) {
                throw new IllegalArgumentException("期望字符 " + expected + " @" + pos);
            }
            pos++;
        }
    }
}
