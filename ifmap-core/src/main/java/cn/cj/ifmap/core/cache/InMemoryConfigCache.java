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

import cn.cj.ifmap.core.exception.IfmapConfigException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 零依赖的内存缓存：LRU（按访问顺序淘汰）+ 惰性 TTL 过期。
 *
 * <p>取舍：配置数据"读多写少、条目少（百级）、单条加载耗时毫秒级"，因此用
 * {@code synchronized + LinkedHashMap} 换取实现简单与行为可预测，<b>不做</b>分段锁/无锁优化。
 * 命中判定在锁内、加载动作在锁外，避免一次 DB 查询阻塞其他 key 的命中；
 * 代价是同一 key 的并发未命中会重复加载（结果一致，后写胜出）。</p>
 *
 * <p>需要更高吞吐或淘汰统计可换 Caffeine 实现，接口不变。</p>
 *
 * @author caijun
 */
public final class InMemoryConfigCache implements ConfigCache {

    /** 默认最大条目数。 */
    public static final int DEFAULT_MAXIMUM_SIZE = 1000;

    /** 默认 TTL（毫秒）：60 秒。 */
    public static final long DEFAULT_TTL_MILLIS = 60_000L;

    private static final class CacheEntry {
        private final Object value;
        private final long expireAtMillis;

        private CacheEntry(Object value, long expireAtMillis) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }

        private boolean expired(long now) {
            return expireAtMillis > 0 && now >= expireAtMillis;
        }
    }

    private final int maximumSize;
    private final long ttlMillis;
    private final LinkedHashMap<String, CacheEntry> store;
    private final boolean[] lock = new boolean[0];

    private long hitCount;
    private long missCount;
    private long evictionCount;

    public InMemoryConfigCache() {
        this(DEFAULT_MAXIMUM_SIZE, DEFAULT_TTL_MILLIS);
    }

    /**
     * @param maximumSize 最大条目数，必须 &gt; 0
     * @param ttlMillis   存活毫秒数，&le; 0 表示永不过期
     */
    public InMemoryConfigCache(final int maximumSize, final long ttlMillis) {
        if (maximumSize <= 0) {
            throw new IfmapConfigException("缓存最大条目数必须大于 0，实际：" + maximumSize);
        }
        this.maximumSize = maximumSize;
        this.ttlMillis = ttlMillis;
        this.store = new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                // 必须写 super.size()：匿名类同时能看到外部类的 size()，不写 super 会被误读成"外层缓存的大小"
                if (super.size() > InMemoryConfigCache.this.maximumSize) {
                    evictionCount++;
                    return true;
                }
                return false;
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Function<String, T> loader) {
        if (key == null) {
            throw new IfmapConfigException("缓存 key 不能为 null");
        }
        if (loader == null) {
            throw new IfmapConfigException("缓存 loader 不能为 null（key=" + key + "）");
        }
        synchronized (lock) {
            long now = System.currentTimeMillis();
            CacheEntry entry = store.get(key);
            if (entry != null && !entry.expired(now)) {
                hitCount++;
                return (T) entry.value;
            }
            if (entry != null) {
                store.remove(key);
            }
            missCount++;
        }
        // 加载放在锁外：避免 DB 查询期间阻塞其他 key 的命中。
        // 代价是同一 key 并发未命中会重复加载（结果一致，最后一次写入胜出）。
        T loaded = loader.apply(key);
        synchronized (lock) {
            if (loaded != null) {
                long expireAt = ttlMillis > 0 ? System.currentTimeMillis() + ttlMillis : 0L;
                store.put(key, new CacheEntry(loaded, expireAt));
            }
        }
        return loaded;
    }

    @Override
    public void invalidate(String key) {
        if (key == null) {
            return;
        }
        synchronized (lock) {
            store.remove(key);
        }
    }

    @Override
    public void invalidateAll() {
        synchronized (lock) {
            store.clear();
        }
    }

    @Override
    public CacheStats stats() {
        synchronized (lock) {
            return new CacheStats(hitCount, missCount, evictionCount, store.size());
        }
    }

    /** 当前条目数。 */
    public int size() {
        synchronized (lock) {
            return store.size();
        }
    }

    public int getMaximumSize() {
        return maximumSize;
    }

    public long getTtlMillis() {
        return ttlMillis;
    }
}
