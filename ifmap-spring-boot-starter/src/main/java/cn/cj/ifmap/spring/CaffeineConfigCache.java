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
package cn.cj.ifmap.spring;

import cn.cj.ifmap.core.cache.ConfigCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.function.Function;

/**
 * 基于 Caffeine 的缓存实现（classpath 存在 Caffeine 时由自动配置装配）。
 *
 * <p>与 {@code InMemoryConfigCache} 保持一致的两点语义：</p>
 * <ul>
 *   <li>{@code loader} 返回 {@code null} 时<b>不缓存</b>；</li>
 *   <li>{@code loader} 抛异常时异常原样抛出且不写入缓存。</li>
 * </ul>
 *
 * @author caijun
 */
public class CaffeineConfigCache implements ConfigCache {

    private final Cache<String, Object> cache;

    public CaffeineConfigCache(int maximumSize, Duration ttl) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .recordStats();
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            builder.expireAfterWrite(ttl);
        }
        this.cache = builder.build();
    }

    /**
     * 命中直接返回；未命中则 loader → put。两步<b>不是原子的</b>（没用 {@code cache.get(key, fn)}），
     * 并发 miss 会各查一次库、后写者胜 —— 这里刻意选简单实现：查的是本地表，重复一次代价可接受。
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Function<String, T> loader) {
        Object cached = cache.getIfPresent(key);
        if (cached != null) {
            return (T) cached;
        }
        T loaded = loader.apply(key);
        if (loaded != null) {
            cache.put(key, loaded);
        }
        return loaded;
    }

    /** 失效单个 key（null 直接忽略）。 */
    @Override
    public void invalidate(String key) {
        if (key != null) {
            cache.invalidate(key);
        }
    }

    /** 全量清空（管理端批量改配置后调用）。 */
    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** 命中率等统计快照；本实现的 cache 是<b>恒定 {@code recordStats()}</b> 的，所以永远有值。 */
    @Override
    public CacheStats stats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats s = cache.stats();
        return new CacheStats(s.hitCount(), s.missCount(), s.evictionCount(), (int) cache.estimatedSize());
    }

    /** 底层 Caffeine 实例（需要自定义淘汰策略时使用）。 */
    public Cache<String, Object> unwrap() {
        return cache;
    }
}
