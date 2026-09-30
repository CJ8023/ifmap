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
package cn.cj.ifmap.core.cache;

import java.util.function.Function;

/**
 * 配置缓存 SPI：引擎侧只依赖本接口，不强绑任何缓存实现。
 *
 * <p>缓存 key <b>必须</b>包含租户维度（见 {@link CachingConfigRepository}），否则会跨租户串配置。</p>
 *
 * <p>实现：{@link InMemoryConfigCache}（零依赖默认实现）、{@code CaffeineConfigCache}
 * （Spring Boot starter 在 classpath 存在 Caffeine 时装配）。</p>
 *
 * @author caijun
 */
public interface ConfigCache {

    /**
     * 取缓存；未命中（或已过期）时调用 {@code loader} 加载并写入缓存。
     *
     * <p>{@code loader} 抛异常时<b>不写缓存</b>，异常原样抛出。</p>
     */
    <T> T get(String key, Function<String, T> loader);

    /** 主动失效单个 key（管理端保存/删除配置后调用）。 */
    void invalidate(String key);

    /** 清空。 */
    void invalidateAll();

    /** 运行统计（命中/未命中/淘汰/当前条目数）。 */
    CacheStats stats();

    /** 缓存统计快照。 */
    final class CacheStats {

        private final long hitCount;
        private final long missCount;
        private final long evictionCount;
        private final int size;

        public CacheStats(long hitCount, long missCount, long evictionCount, int size) {
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.evictionCount = evictionCount;
            this.size = size;
        }

        public long getHitCount() {
            return hitCount;
        }

        public long getMissCount() {
            return missCount;
        }

        public long getEvictionCount() {
            return evictionCount;
        }

        public int getSize() {
            return size;
        }

        @Override
        public String toString() {
            return "CacheStats{hit=" + hitCount + ", miss=" + missCount
                    + ", eviction=" + evictionCount + ", size=" + size + '}';
        }
    }
}
