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

import cn.cj.ifmap.core.spi.LogMasker;

/**
 * 影子差异出口：宿主把差异落到日志、监控、消息队列或比对库都从这走。
 *
 * <p>{@link ShadowRunner} 保证：<b>出口抛异常不会影响业务</b>，只会记一条 warn。</p>
 *
 * @author caijun
 */
@FunctionalInterface
public interface ShadowDiffSink {

    /** 处理一条差异。 */
    void onDiff(ShadowFieldDiff diff);

    /** 什么也不做（性能压测、单测常用）。 */
    static ShadowDiffSink noop() {
        return new ShadowDiffSink() {
            @Override
            public void onDiff(ShadowFieldDiff diff) {
                // 有意为空
            }
        };
    }

    /** 打一条 warn 日志（默认用 {@code DefaultLogMasker} 脱敏）。 */
    static ShadowDiffSink logging() {
        return new LoggingShadowDiffSink();
    }

    /** 打一条 warn 日志，用指定脱敏器。 */
    static ShadowDiffSink logging(LogMasker masker) {
        return new LoggingShadowDiffSink(masker);
    }
}
