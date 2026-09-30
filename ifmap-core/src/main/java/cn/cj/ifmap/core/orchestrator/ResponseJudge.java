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
package cn.cj.ifmap.core.orchestrator;

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.json.JsonReadContext;
import cn.cj.ifmap.core.json.JsonOps;

/**
 * 结果判定：按 {@code result_flag}（JsonPath）取响应值，与 {@code success_value} 比较。
 *
 * <p>成功值支持多值（{@code ;} / {@code ,} 分隔，任一命中即成功），大小写不敏感、忽略首尾空白。
 * {@code result_flag} 为空时默认成功；{@code success_value} 为空时按"非空且非 false/0"判定。</p>
 *
 * @author caijun
 */
public final class ResponseJudge {

    private static final String[] MULTI_VALUE_SEPARATORS = new String[] {";", ","};

    private final JsonOps jsonOps;

    public ResponseJudge(JsonOps jsonOps) {
        if (jsonOps == null) {
            throw new IllegalArgumentException("jsonOps 不能为 null");
        }
        this.jsonOps = jsonOps;
    }

    /** 判定；任何解析异常都折算为"判定失败"而不是抛出（判定不是业务异常）。 */
    public Judgement evaluate(IfmapConfig config, String responseJson) {
        String flag = trimToNull(config.getResultFlag());
        String expected = trimToNull(config.getSuccessValue());
        if (flag == null) {
            return Judgement.of(true, null, expected, "result_flag 为空，默认成功");
        }
        if (responseJson == null || responseJson.trim().isEmpty()) {
            return Judgement.of(false, null, expected, "响应为空，无法取 " + flag);
        }
        Object value;
        try {
            JsonReadContext context = jsonOps.readContext(responseJson);
            value = context.read(flag, false);
        } catch (RuntimeException e) {
            return Judgement.of(false, null, expected, "响应解析失败：" + e.getMessage());
        }
        String actual = value == null ? null : String.valueOf(value);
        if (expected == null) {
            boolean ok = actual != null && !isFalsy(actual);
            return Judgement.of(ok, actual, null, ok ? "响应值非空即成功" : "响应值为空/false/0");
        }
        if (actual == null) {
            return Judgement.of(false, null, expected, "响应中取不到判定字段 " + flag);
        }
        for (String candidate : split(expected)) {
            if (candidate.equalsIgnoreCase(actual.trim())) {
                return Judgement.of(true, actual, expected, "命中成功值 " + candidate);
            }
        }
        return Judgement.of(false, actual, expected, "未命中成功值");
    }

    private static String[] split(String expected) {
        String[] parts = new String[] {expected};
        for (String separator : MULTI_VALUE_SEPARATORS) {
            String[] next = new String[0];
            for (String part : parts) {
                String[] split = part.split(java.util.regex.Pattern.quote(separator));
                String[] merged = new String[next.length + split.length];
                System.arraycopy(next, 0, merged, 0, next.length);
                System.arraycopy(split, 0, merged, next.length, split.length);
                next = merged;
            }
            parts = next;
        }
        java.util.List<String> result = new java.util.ArrayList<String>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.toArray(new String[0]);
    }

    private static boolean isFalsy(String value) {
        String v = value.trim();
        return "false".equalsIgnoreCase(v) || "0".equals(v) || "null".equalsIgnoreCase(v);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
