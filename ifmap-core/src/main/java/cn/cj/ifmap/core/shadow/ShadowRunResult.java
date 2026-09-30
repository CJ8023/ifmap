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

import cn.cj.ifmap.core.model.IfmapResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次影子运行的结果。
 *
 * @author caijun
 */
public final class ShadowRunResult {

    private final boolean ran;
    private final ShadowOutcome legacy;
    private final IfmapResult shadow;
    private final String shadowError;
    private final List<ShadowFieldDiff> diffs;
    private final boolean truncated;
    private final long shadowElapsedMs;

    private ShadowRunResult(boolean ran, ShadowOutcome legacy, IfmapResult shadow, String shadowError,
                            List<ShadowFieldDiff> diffs, boolean truncated, long shadowElapsedMs) {
        this.ran = ran;
        this.legacy = legacy;
        this.shadow = shadow;
        this.shadowError = shadowError;
        this.diffs = diffs == null
                ? Collections.<ShadowFieldDiff>emptyList()
                : Collections.unmodifiableList(new ArrayList<ShadowFieldDiff>(diffs));
        this.truncated = truncated;
        this.shadowElapsedMs = shadowElapsedMs;
    }

    /** 未跑影子（开关关闭 / 未采样）。 */
    public static ShadowRunResult skipped(ShadowOutcome legacy) {
        return new ShadowRunResult(false, legacy, null, null, null, false, 0L);
    }

    /** 跑了影子。 */
    public static ShadowRunResult of(ShadowOutcome legacy, IfmapResult shadow, String shadowError,
                                     List<ShadowFieldDiff> diffs, boolean truncated, long shadowElapsedMs) {
        return new ShadowRunResult(true, legacy, shadow, shadowError, diffs, truncated, shadowElapsedMs);
    }

    /** 本次是否真的跑了影子。 */
    public boolean isRan() {
        return ran;
    }

    /** 未跑影子。 */
    public boolean isSkipped() {
        return !ran;
    }

    /** 主链路（存量链路）结果，原样返回给调用方。 */
    public ShadowOutcome getLegacy() {
        return legacy;
    }

    /** 新引擎结果；影子异常时为 null。 */
    public IfmapResult getShadow() {
        return shadow;
    }

    /** 新引擎异常信息；正常为 null。 */
    public String getShadowError() {
        return shadowError;
    }

    /** 差异列表（不可变）。 */
    public List<ShadowFieldDiff> getDiffs() {
        return diffs;
    }

    /** 差异是否因 {@code maxDiffs} 被截断。 */
    public boolean isTruncated() {
        return truncated;
    }

    /** 新引擎耗时（毫秒）：用于评估切换后的性能影响。 */
    public long getShadowElapsedMs() {
        return shadowElapsedMs;
    }

    /** 是否对齐：跑了影子、新引擎未异常、且零差异。 */
    public boolean isMatched() {
        return ran && shadowError == null && diffs.isEmpty();
    }

    @Override
    public String toString() {
        return "ShadowRunResult{ran=" + ran + ", matched=" + isMatched()
                + ", diffs=" + diffs.size() + (truncated ? "(truncated)" : "")
                + ", shadowError=" + shadowError + ", shadowElapsedMs=" + shadowElapsedMs + "}";
    }
}
