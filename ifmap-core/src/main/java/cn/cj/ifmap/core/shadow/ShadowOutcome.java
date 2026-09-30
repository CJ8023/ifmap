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
package cn.cj.ifmap.core.shadow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 归一化后的"一条链路的结果"：**成败判定 + 判定标识 + 可比字段集**。
 *
 * <p>存量链路与新链路的数据结构通常不同，直接比对象是比不出东西的。影子运行的做法是
 * 两侧各写一个"抽取器"，把各自的结果**投影成同一组 key**，再逐 key 比对
 * （见 {@link ShadowExtractor} 与 {@link ShadowComparer}）。</p>
 *
 * <p>字段集必须是**扁平且语义稳定**的：不要放 {@code traceId}、耗时、时间戳这类天然不同的值 ——
 * 这类字段请用 {@link ShadowOptions#getIgnoreKeys()} 排除。</p>
 *
 * @author caijun
 */
public final class ShadowOutcome {

    private static final Map<String, Object> EMPTY = Collections.emptyMap();

    private final boolean success;
    private final String resultFlag;
    private final Map<String, Object> fields;
    private final String message;

    private ShadowOutcome(boolean success, String resultFlag, Map<String, Object> fields, String message) {
        this.success = success;
        this.resultFlag = resultFlag;
        this.fields = fields == null || fields.isEmpty()
                ? EMPTY
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(fields));
        this.message = message;
    }

    /** 成功 + 仅字段集。 */
    public static ShadowOutcome of(Map<String, Object> fields) {
        return new ShadowOutcome(true, null, fields, null);
    }

    /** 成功判定 + 判定标识 + 字段集。 */
    public static ShadowOutcome of(boolean success, String resultFlag, Map<String, Object> fields) {
        return new ShadowOutcome(success, resultFlag, fields, null);
    }

    /**
     * 成功判定 + 判定标识 + 字段集 + 说明。
     *
     * <p>{@code message} 只用于诊断，<b>不参与比对</b>：失败原因两侧措辞天然不同，
     * 拿它比对只会制造假差异。</p>
     */
    public static ShadowOutcome of(boolean success, String resultFlag, Map<String, Object> fields, String message) {
        return new ShadowOutcome(success, resultFlag, fields, message);
    }

    /** 失败（无字段可比）。 */
    public static ShadowOutcome failure(String message) {
        return new ShadowOutcome(false, null, null, message);
    }

    public boolean isSuccess() {
        return success;
    }

    /** 判定标识：新引擎侧即 {@code IfmapResult.matchedBranch}（对应 {@code result_flag} 的命中值）。 */
    public String getResultFlag() {
        return resultFlag;
    }

    /** 可比字段集（不可变副本）。 */
    public Map<String, Object> getFields() {
        return fields;
    }

    /** 失败说明（可空）。 */
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "ShadowOutcome{success=" + success + ", resultFlag=" + resultFlag
                + ", fields=" + fields.keySet() + "}";
    }
}
