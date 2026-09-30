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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryConfigCache} 单测。
 *
 * @author caijun
 */
class InMemoryConfigCacheTest {

    private static Function<String, String> loader(final AtomicInteger calls, final String value) {
        return new Function<String, String>() {
            @Override
            public String apply(String key) {
                calls.incrementAndGet();
                return value;
            }
        };
    }

    @Test
    @DisplayName("未命中时加载并写缓存，第二次直接命中")
    void loadsThenHits() {
        InMemoryConfigCache cache = new InMemoryConfigCache(10, 60_000L);
        AtomicInteger calls = new AtomicInteger();

        assertEquals("v1", cache.get("k1", loader(calls, "v1")));
        assertEquals("v1", cache.get("k1", loader(calls, "v1")));

        assertEquals(1, calls.get(), "loader 只应被调用一次");
        ConfigCache.CacheStats stats = cache.stats();
        assertEquals(1L, stats.getMissCount());
        assertEquals(1L, stats.getHitCount());
        assertEquals(1, stats.getSize());
    }

    @Test
    @DisplayName("不同 key 各自加载")
    void differentKeysLoadSeparately() {
        InMemoryConfigCache cache = new InMemoryConfigCache(10, 60_000L);
        AtomicInteger calls = new AtomicInteger();

        cache.get("k1", loader(calls, "v1"));
        cache.get("k2", loader(calls, "v2"));

        assertEquals(2, calls.get());
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("ttl=0 表示永不过期")
    void ttlZeroMeansNeverExpire() {
        InMemoryConfigCache cache = new InMemoryConfigCache(10, 0L);
        AtomicInteger calls = new AtomicInteger();

        cache.get("k1", loader(calls, "v1"));
        cache.get("k1", loader(calls, "v1"));

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("TTL 过期后重新加载")
    void expiredEntryReloads() throws Exception {
        InMemoryConfigCache cache = new InMemoryConfigCache(10, 30L);
        AtomicInteger calls = new AtomicInteger();

        cache.get("k1", loader(calls, "v1"));
        Thread.sleep(60L);
        cache.get("k1", loader(calls, "v1"));

        assertEquals(2, calls.get(), "TTL 过期后应重新加载");
    }

    @Test
    @DisplayName("超过最大条目数时按 LRU 淘汰最久未访问的（并计入 eviction）")
    void evictsLeastRecentlyUsed() {
        InMemoryConfigCache cache = new InMemoryConfigCache(2, 0L);
        AtomicInteger calls = new AtomicInteger();

        cache.get("k1", loader(calls, "v1"));
        cache.get("k2", loader(calls, "v2"));
        cache.get("k1", loader(calls, "v1"));   // k1 变成最近访问
        cache.get("k3", loader(calls, "v3"));   // 触发淘汰 k2

        assertEquals(2, cache.size());
        assertEquals(1L, cache.stats().getEvictionCount());

        cache.get("k1", loader(calls, "v1"));
        assertEquals(3, calls.get(), "k1 未被淘汰，不应重新加载");

        cache.get("k2", loader(calls, "v2"));
        assertEquals(4, calls.get(), "k2 已被淘汰，应重新加载");
    }

    @Test
    @DisplayName("loader 返回 null 不写缓存（下次仍会加载）")
    void nullResultIsNotCached() {
        InMemoryConfigCache cache = new InMemoryConfigCache(10, 0L);
        AtomicInteger calls = new AtomicInteger();

        assertNull(cache.get("k1", loader(calls, null)));
        assertNull(cache.get("k1", loader(calls, null)));

        assertEquals(2, calls.get());
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("loader 抛异常时异常原样抛出、不写缓存")
    void loaderExceptionPropagates() {
        InMemoryConfigCache cache = new InMemoryConfigCache(10, 0L);

        assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                cache.get("k1", new Function<String, String>() {
                    @Override
                    public String apply(String key) {
                        throw new IllegalStateException("db down");
                    }
                });
            }
        });
        assertEquals(0, cache.size());
        assertEquals(1L, cache.stats().getMissCount());
    }

    @Test
    @DisplayName("invalidate / invalidateAll 生效")
    void invalidateWorks() {
        InMemoryConfigCache cache = new InMemoryConfigCache(10, 0L);
        AtomicInteger calls = new AtomicInteger();

        cache.get("k1", loader(calls, "v1"));
        cache.get("k2", loader(calls, "v2"));
        cache.invalidate("k1");
        assertEquals(1, cache.size());

        cache.get("k1", loader(calls, "v1"));
        assertEquals(3, calls.get(), "k1 失效后应重新加载");

        cache.invalidateAll();
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("非法参数与 null key/loader 直接失败")
    void rejectsBadArguments() {
        assertThrows(IfmapConfigException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                new InMemoryConfigCache(0, 1000L);
            }
        });

        final InMemoryConfigCache cache = new InMemoryConfigCache(10, 0L);
        assertThrows(IfmapConfigException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                cache.get(null, loader(new AtomicInteger(), "v"));
            }
        });
        assertThrows(IfmapConfigException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                cache.get("k1", null);
            }
        });
    }

    @Test
    @DisplayName("默认值：1000 条 / 60 秒")
    void defaults() {
        InMemoryConfigCache cache = new InMemoryConfigCache();
        assertEquals(InMemoryConfigCache.DEFAULT_MAXIMUM_SIZE, cache.getMaximumSize());
        assertEquals(InMemoryConfigCache.DEFAULT_TTL_MILLIS, cache.getTtlMillis());
        assertTrue(InMemoryConfigCache.DEFAULT_TTL_MILLIS > 0);
    }
}
