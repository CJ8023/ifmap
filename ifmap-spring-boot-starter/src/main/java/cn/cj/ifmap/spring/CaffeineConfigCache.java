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

    @Override
    public void invalidate(String key) {
        if (key != null) {
            cache.invalidate(key);
        }
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }

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
