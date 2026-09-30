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
package cn.cj.ifmap.core;

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.spi.IdGenerator;
import cn.cj.ifmap.core.spi.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花 ID 生成器：唯一性、单调递增、workerId 校验、时钟回拨、AUTO_INCREMENT 常量。
 *
 * @author caijun
 */
class SnowflakeIdGeneratorTest {

    @Test
    @DisplayName("单线程连续生成 2 万个 ID 不重复且严格递增")
    void uniqueAndIncreasing() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1L);
        long prev = -1L;
        Set<Long> seen = new HashSet<Long>();
        for (int i = 0; i < 20000; i++) {
            long id = gen.nextId();
            assertTrue(id > prev, "ID 必须严格递增：" + id + " <= " + prev);
            assertTrue(seen.add(id), "ID 重复：" + id);
            assertTrue(id > 0, "ID 必须为正数：" + id);
            prev = id;
        }
    }

    @Test
    @DisplayName("8 线程并发各生成 5000 个 ID，无重复无异常")
    void concurrentUnique() throws Exception {
        final SnowflakeIdGenerator gen = new SnowflakeIdGenerator(7L);
        final int threads = 8;
        final int perThread = 5000;
        final Set<Long> all = java.util.Collections.synchronizedSet(new HashSet<Long>());
        final AtomicInteger errors = new AtomicInteger();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                if (!all.add(gen.nextId())) {
                                    errors.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发生成超时");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, errors.get(), "并发下出现重复 ID 或异常");
        assertEquals(threads * perThread, all.size());
    }

    @Test
    @DisplayName("workerId 必须在 [0, 1023]，越界抛异常")
    void validatesWorkerId() {
        assertThrows(IfmapConfigException.class, () -> new SnowflakeIdGenerator(-1L));
        assertThrows(IfmapConfigException.class, () -> new SnowflakeIdGenerator(1024L));
        assertNotNull(new SnowflakeIdGenerator(0L).nextId());
        assertNotNull(new SnowflakeIdGenerator(1023L).nextId());
    }

    @Test
    @DisplayName("时钟回拨超过容忍阈值（5ms）抛异常，而不是给出可能重复的 ID")
    void rejectsLargeClockBackward() {
        FakeClock clock = new FakeClock(1_000_000L, 0L);
        SnowflakeIdGenerator gen = generator(clock);
        gen.nextId();
        clock.now = 1_000_000L - 100L; // 回拨 100ms
        IfmapConfigException e = assertThrows(IfmapConfigException.class, gen::nextId);
        assertTrue(e.getMessage().contains("回拨"), e.getMessage());
    }

    @Test
    @DisplayName("小幅回拨（<=5ms）自旋等待时钟追上后继续生成，不抛异常")
    void toleratesSmallClockBackward() {
        FakeClock clock = new FakeClock(1_000_000L, 1L); // 每次读取推进 1ms，模拟时钟继续走
        SnowflakeIdGenerator gen = generator(clock);
        long a = gen.nextId();
        clock.now = 1_000_000L - 1L; // 回拨 1ms
        long b = gen.nextId();
        assertTrue(b > a, "小幅回拨后应等待追上并生成更大的 ID");
    }

    @Test
    @DisplayName("同一毫秒内序列号自增；跨毫秒序列号重置")
    void sequenceBehaviour() {
        FakeClock clock = new FakeClock(1_000_000L, 0L);
        SnowflakeIdGenerator gen = generator(clock);
        long first = gen.nextId();
        long second = gen.nextId();
        assertEquals((first & 0xFFFL) + 1L, second & 0xFFFL, "同毫秒内序列号应 +1");

        clock.now = 1_000_001L;
        long third = gen.nextId();
        assertEquals(0L, third & 0xFFFL, "跨毫秒序列号应重置为 0");
        assertTrue(third > second);
    }

    @Test
    @DisplayName("AUTO_INCREMENT 生成器返回 null（交由数据库自增）")
    void autoIncrementReturnsNull() {
        assertNull(IdGenerator.AUTO_INCREMENT.nextId());
    }

    @Test
    @DisplayName("shared() 返回同一实例（多仓储共用才不会撞序列号）")
    void sharedIsSingleton() {
        assertSame(SnowflakeIdGenerator.shared(), SnowflakeIdGenerator.shared());
    }

    /** 假时钟：{@code read()} 返回当前值后按 {@code step} 推进（step=0 表示时间静止）。 */
    private static final class FakeClock {
        private volatile long now;
        private volatile long step;

        private FakeClock(long now, long step) {
            this.now = now;
            this.step = step;
        }

        private long read() {
            long value = now;
            now = now + step;
            return value;
        }
    }

    /** 用假时钟驱动被测生成器（覆写 {@code currentTime()}，确定性覆盖回拨与序列号分支）。 */
    private static SnowflakeIdGenerator generator(final FakeClock clock) {
        return new SnowflakeIdGenerator(3L) {
            @Override
            protected long currentTime() {
                return clock.read();
            }
        };
    }
}
