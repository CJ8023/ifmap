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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用<b>独立守护线程</b>按 cron 驱动 {@link IfmapLogCleanTask}。
 *
 * <p>为什么不用 {@code @EnableScheduling} + {@code @Scheduled}（这是个踩过的坑）：</p>
 * <ol>
 *   <li><b>自动配置不该打开全局开关</b>：{@code @EnableScheduling} 会把宿主的调度设施一起点着
 *       （Spring 会创建 {@code ScheduledAnnotationBeanPostProcessor} 并注册 TaskScheduler），
 *       与"引了 starter 只多一个日志清理"的预期不符。</li>
 *   <li><b>非 Web / 批处理应用会卡住不退出</b>：Spring 在没有 TaskScheduler bean 时用
 *       {@code Executors.newSingleThreadScheduledExecutor()} 建<b>非守护</b>线程，
 *       {@code main()} 返回后 JVM 仍不会退出 —— 实测：本仓库的 {@code ifmap-demo-spring-boot3}
 *       加上 {@code @EnableScheduling} 后 {@code java -jar} 永不退出（超时被杀），
 *       而它原本是"跑完自然退出 exit=0"的示例。</li>
 * </ol>
 *
 * <p>因此这里自己建一个单线程守护调度器：应用想退出就退出（守护线程不阻塞 JVM），
 * 池随上下文关闭而关闭。宿主要把清理纳入自己的调度体系（如 xxl-job / ShedLock / 分布式选举），
 * 把 {@code ifmap.log.clean-enabled} 关掉并自行调用 {@code IfmapLogCleanTask#cleanNow()} 即可。</p>
 *
 * @author caijun
 */
public class IfmapLogCleanScheduler implements SmartInitializingSingleton, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(IfmapLogCleanScheduler.class);

    private final IfmapLogCleanTask task;
    private final CronExpression cron;
    private final ScheduledExecutorService executor;

    private volatile ScheduledFuture<?> future;
    private volatile boolean closed;

    public IfmapLogCleanScheduler(IfmapLogCleanTask task, String cron) {
        if (task == null) {
            throw new IllegalArgumentException("IfmapLogCleanTask 不能为空");
        }
        this.task = task;
        // 非法表达式在构造期就抛 IllegalArgumentException → 启动期快速失败，不会静默不执行
        this.cron = CronExpression.parse(cron);
        this.executor = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
    }

    /** 上下文装配完成后排定第一次执行（此时不会再有 bean 创建设置的干扰）。 */
    @Override
    public void afterSingletonsInstantiated() {
        scheduleNext();
        LOG.info("ifmap 执行日志定时清理已启动（cron={}，保留 {} 天）", cron, task.getRetentionDays());
    }

    /** 当前是否已排定下一次执行（便于自检/测试）。 */
    public boolean isScheduled() {
        ScheduledFuture<?> current = future;
        return !closed && current != null && !current.isDone();
    }

    /** 调度表达式（启动期已校验）。 */
    public CronExpression cron() {
        return cron;
    }

    @Override
    public void destroy() {
        closed = true;
        executor.shutdownNow();
    }

    private void scheduleNext() {
        if (closed) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = cron.next(now);
        if (next == null) {
            LOG.warn("ifmap 执行日志清理：cron [{}] 之后没有下一次执行时间，定时清理就此停止", cron);
            return;
        }
        long delayMillis = Math.max(0L, Duration.between(now, next).toMillis());
        future = executor.schedule(this::runThenReschedule, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void runThenReschedule() {
        try {
            task.cleanNow();
        } catch (Throwable t) {
            // cleanNow 自身已吞掉 RuntimeException；这里兜底非受检错误，绝不让调度线程死掉
            LOG.warn("ifmap 执行日志清理发生未预期异常（调度继续）：{}", t.getMessage(), t);
        } finally {
            scheduleNext();
        }
    }

    /** 守护线程工厂：不阻塞 JVM 退出。 */
    private static final class DaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ifmap-log-clean-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
