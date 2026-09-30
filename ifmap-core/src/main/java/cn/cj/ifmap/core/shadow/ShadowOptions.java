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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

/**
 * 影子运行选项。
 *
 * @author caijun
 */
public final class ShadowOptions {

    /** 采样用的默认种子：固定种子 => 同一批流量的采样结果可复现（便于复盘"为什么这笔没跑影子"）。 */
    public static final long DEFAULT_RANDOM_SEED = 20260930L;

    /** 单次运行最多上报多少条差异（超出即截断并置 {@code truncated}）。 */
    public static final int DEFAULT_MAX_DIFFS = 20;

    private final boolean enabled;
    private final double sampleRate;
    private final long randomSeed;
    private final Set<String> ignoreKeys;
    private final boolean numberTolerant;
    private final boolean trimStrings;
    private final int maxDiffs;
    private final Random random;

    private ShadowOptions(Builder builder) {
        this.enabled = builder.enabled;
        this.sampleRate = builder.sampleRate;
        this.randomSeed = builder.randomSeed;
        this.ignoreKeys = Collections.unmodifiableSet(new LinkedHashSet<String>(builder.ignoreKeys));
        this.numberTolerant = builder.numberTolerant;
        this.trimStrings = builder.trimStrings;
        this.maxDiffs = builder.maxDiffs;
        this.random = new Random(builder.randomSeed);
    }

    /** 默认选项：开启、全量采样、数字宽容比对、字符串去空格、最多上报 {@value #DEFAULT_MAX_DIFFS} 条差异。 */
    public static ShadowOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 是否应该跑影子（开关 + 采样）。线程安全：{@link Random} 自身线程安全。 */
    public boolean shouldRun() {
        if (!enabled) {
            return false;
        }
        if (sampleRate >= 1.0d) {
            return true;
        }
        if (sampleRate <= 0.0d) {
            return false;
        }
        return random.nextDouble() < sampleRate;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public long getRandomSeed() {
        return randomSeed;
    }

    /** 不参与比对的字段名（如 traceId / requestId / 时间戳）。 */
    public Set<String> getIgnoreKeys() {
        return ignoreKeys;
    }

    public boolean isIgnored(String key) {
        return ignoreKeys.contains(key);
    }

    /** {@code true}（默认）：{@code 1} 与 {@code 1.0} 视为相同 —— 避免因两侧序列化类型不同产生海量假差异。 */
    public boolean isNumberTolerant() {
        return numberTolerant;
    }

    /** {@code true}（默认）：字符串去首尾空格后比对。 */
    public boolean isTrimStrings() {
        return trimStrings;
    }

    public int getMaxDiffs() {
        return maxDiffs;
    }

    /** 建造者。 */
    public static final class Builder {

        private boolean enabled = true;
        private double sampleRate = 1.0d;
        private long randomSeed = DEFAULT_RANDOM_SEED;
        private Set<String> ignoreKeys = new LinkedHashSet<String>();
        private boolean numberTolerant = true;
        private boolean trimStrings = true;
        private int maxDiffs = DEFAULT_MAX_DIFFS;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** 采样率，取值 {@code [0,1]}；生产建议 0.05 ~ 0.2，先把假差异和性能影响摸清楚。 */
        public Builder sampleRate(double sampleRate) {
            if (sampleRate < 0.0d || sampleRate > 1.0d) {
                throw new IllegalArgumentException("sampleRate 必须在 [0,1] 之间：" + sampleRate);
            }
            this.sampleRate = sampleRate;
            return this;
        }

        public Builder randomSeed(long randomSeed) {
            this.randomSeed = randomSeed;
            return this;
        }

        public Builder ignoreKeys(String... keys) {
            this.ignoreKeys.addAll(Arrays.asList(keys));
            return this;
        }

        public Builder ignoreKeys(Collection<String> keys) {
            if (keys != null) {
                this.ignoreKeys.addAll(keys);
            }
            return this;
        }

        public Builder numberTolerant(boolean numberTolerant) {
            this.numberTolerant = numberTolerant;
            return this;
        }

        public Builder trimStrings(boolean trimStrings) {
            this.trimStrings = trimStrings;
            return this;
        }

        public Builder maxDiffs(int maxDiffs) {
            if (maxDiffs <= 0) {
                throw new IllegalArgumentException("maxDiffs 必须为正数：" + maxDiffs);
            }
            this.maxDiffs = maxDiffs;
            return this;
        }

        public ShadowOptions build() {
            return new ShadowOptions(this);
        }
    }
}
