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
package cn.cj.ifmap.core.orchestrator.judgement;

import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonReadContext;

import java.util.Arrays;
import java.util.List;

/**
 * ③ 抽取段：按<b>固定候选路径</b>抽对方业务消息与链路 ID（不新增配置列，设计 §3.6）。
 *
 * <p>只补信息、<b>不改判定结论</b>：响应非 JSON、路径全取不到都返回 {@code null}，不抛异常。</p>
 *
 * @author caijun
 */
public final class ResponseExtractor {

    private static final List<String> MESSAGE_PATHS =
            Arrays.asList("$.message", "$.msg", "$.error", "$.detail", "$.errorMessage");
    private static final List<String> TRACK_ID_PATHS =
            Arrays.asList("$.trackId", "$.track_id", "$.requestId", "$.serialNo");
    /** 消息落库上限（超出截断，避免把整段回参写进日志）。 */
    private static final int MESSAGE_MAX = 200;

    private ResponseExtractor() {
    }

    /** 对方业务消息（第一个命中的候选路径，超长截断）。 */
    public static String message(JsonOps jsonOps, String responseJson) {
        return first(jsonOps, responseJson, MESSAGE_PATHS, MESSAGE_MAX);
    }

    /** 对方链路 ID（第一个命中的候选路径，不截断）。 */
    public static String trackId(JsonOps jsonOps, String responseJson) {
        return first(jsonOps, responseJson, TRACK_ID_PATHS, 0);
    }

    private static String first(JsonOps jsonOps, String responseJson, List<String> paths, int maxLength) {
        if (jsonOps == null || responseJson == null || responseJson.trim().isEmpty()) {
            return null;
        }
        JsonReadContext ctx;
        try {
            ctx = jsonOps.readContext(responseJson);
        } catch (RuntimeException e) {
            return null;
        }
        for (String path : paths) {
            Object value;
            try {
                value = ctx.read(path, false);
            } catch (RuntimeException e) {
                continue;
            }
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (maxLength > 0 && text.length() > maxLength) {
                return text.substring(0, maxLength);
            }
            return text;
        }
        return null;
    }
}
