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
import cn.cj.ifmap.core.spi.ClockProvider;
import cn.cj.ifmap.core.spi.SystemClockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 影子运行入口（迁移方案 M3）：**同一笔业务双跑，比对差异，差异只记录不影响主链路**。
 *
 * <p>硬契约（都有测试守着）：</p>
 * <ol>
 *   <li><b>主链路优先</b>：先执行存量链路并把它的结果原样带回；存量链路抛异常时<b>原样向上抛</b>，
 *       且影子根本不执行（不为注定失败的业务多跑一遍）。</li>
 *   <li><b>影子绝不外泄</b>：新引擎抛异常只记录到 {@link ShadowRunResult#getShadowError()}，
 *       调用方拿不到异常；差异出口（{@link ShadowDiffSink}）抛异常同样只记 warn。</li>
 *   <li><b>可采样</b>：生产用 5% ~ 20% 采样先看假差异与性能影响，再逐步放大到全量。</li>
 * </ol>
 *
 * <p>用法（迁移期典型形态）：</p>
 *
 * <pre>{@code
 * ShadowRunner shadow = new ShadowRunner(
 *         ShadowEngine.of(ifmapOrchestrator),      // 新链路（legacyRawResponse 非空时不出网）
 *         legacyTarget,                            // 存量链路适配器
 *         ShadowDiffSink.logging(),
 *         ShadowOptions.builder().sampleRate(0.1d)
 *                 .ignoreKeys("traceId", "requestId", "elapsedMs").build());
 *
 * ShadowRunResult result = shadow.run(request);
 * return result.getLegacy();                       // 业务照旧走存量结果
 * }</pre>
 *
 * <p>影子期建议给新链路单独准备一个 {@link cn.cj.ifmap.core.orchestrator.IfmapOrchestrator} 实例，
 * 并把它接到<b>空实现的执行日志出口</b>上，避免影子记录污染生产执行日志表。</p>
 *
 * @author caijun
 */
public final class ShadowRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ShadowRunner.class);

    private final ShadowEngine shadowEngine;
    private final ShadowTarget legacyTarget;
    private final ShadowExtractor extractor;
    private final ShadowDiffSink sink;
    private final ShadowOptions options;
    private final ClockProvider clock;
    private final ShadowComparer comparer;

    public ShadowRunner(ShadowEngine shadowEngine, ShadowTarget legacyTarget,
                        ShadowDiffSink sink, ShadowOptions options) {
        this(shadowEngine, legacyTarget, ShadowExtractor.defaults(), sink, options, SystemClockProvider.INSTANCE);
    }

    public ShadowRunner(ShadowEngine shadowEngine, ShadowTarget legacyTarget, ShadowExtractor extractor,
                        ShadowDiffSink sink, ShadowOptions options) {
        this(shadowEngine, legacyTarget, extractor, sink, options, SystemClockProvider.INSTANCE);
    }

    public ShadowRunner(ShadowEngine shadowEngine, ShadowTarget legacyTarget, ShadowExtractor extractor,
                        ShadowDiffSink sink, ShadowOptions options, ClockProvider clock) {
        this.shadowEngine = Objects.requireNonNull(shadowEngine, "shadowEngine 不能为空");
        this.legacyTarget = Objects.requireNonNull(legacyTarget, "legacyTarget 不能为空");
        this.extractor = extractor == null ? ShadowExtractor.defaults() : extractor;
        this.sink = sink == null ? ShadowDiffSink.noop() : sink;
        this.options = options == null ? ShadowOptions.defaults() : options;
        this.clock = clock == null ? SystemClockProvider.INSTANCE : clock;
        this.comparer = new ShadowComparer(this.options);
    }

    /**
     * 跑一次影子运行。
     *
     * @param request 业务请求
     * @return 影子运行结果（其中 {@link ShadowRunResult#getLegacy()} 是主链路结果）
     */
    public ShadowRunResult run(ShadowRequest request) {
        Objects.requireNonNull(request, "request 不能为空");

        // 1. 主链路：异常原样抛，影子不执行
        ShadowOutcome legacy = legacyTarget.execute(request);
        if (!options.shouldRun()) {
            return ShadowRunResult.skipped(legacy);
        }

        // 2. 新链路：任何异常都只记录（影子绝不改变主链路行为）
        long start = clock.currentTimeMillis();
        IfmapResult shadow = null;
        String shadowError = null;
        try {
            shadow = shadowEngine.execute(request);
        } catch (RuntimeException e) {
            shadowError = describe(e);
        } catch (Error e) {
            shadowError = describe(e);
        }
        long elapsed = clock.currentTimeMillis() - start;

        // 3. 比对 + 截断 + 上报
        List<ShadowFieldDiff> diffs = shadowError != null
                ? java.util.Collections.singletonList(ShadowFieldDiff.shadowError(shadowError))
                : comparer.compare(legacy, extractor.extract(shadow));
        boolean truncated = diffs.size() > options.getMaxDiffs();
        if (truncated) {
            diffs = diffs.subList(0, options.getMaxDiffs());
        }
        for (ShadowFieldDiff diff : diffs) {
            notifySink(diff);
        }
        if (shadowError != null) {
            LOG.warn("ifmap 影子运行失败（不影响业务）：{}", shadowError);
        }
        return ShadowRunResult.of(legacy, shadow, shadowError, diffs, truncated, elapsed);
    }

    private void notifySink(ShadowFieldDiff diff) {
        try {
            sink.onDiff(diff);
        } catch (RuntimeException e) {
            LOG.warn("ifmap 影子差异出口异常（不影响业务）：{}", e.getMessage());
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
