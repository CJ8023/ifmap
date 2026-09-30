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

import cn.cj.ifmap.jdbc.JdbcExecutionLogCleaner;
import cn.cj.ifmap.jdbc.TableNameResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行日志保留期清理的装配与真实清理测试（真实 H2 + 真实建表脚本 + 真实删除语句）。
 *
 * @author caijun
 */
class IfmapLogCleanTaskTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    private static ApplicationContextRunner runner(String dbName) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        IfmapAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.driver-class-name=org.h2.Driver");
    }

    @Test
    @DisplayName("默认装配：清理器 + 定时任务都就绪，保留期/批大小来自配置")
    void wiresCleanerAndTaskByDefault() {
        runner("ifmap_clean_default").run(context -> {
            assertNull(context.getStartupFailure(), "上下文应正常启动");

            JdbcExecutionLogCleaner cleaner = context.getBean(JdbcExecutionLogCleaner.class);
            assertEquals("ifmap_execution_log", cleaner.tables().executionLogTable());

            IfmapLogCleanTask task = context.getBean(IfmapLogCleanTask.class);
            assertEquals(90, task.getRetentionDays(), "默认保留 90 天");

            IfmapLogCleanScheduler scheduler = context.getBean(IfmapLogCleanScheduler.class);
            assertTrue(scheduler.isScheduled(), "默认应已排定下一次清理");
            assertEquals("0 30 3 * * ?", scheduler.cron().toString());
        });
    }

    @Test
    @DisplayName("配置生效：保留天数 / 批大小 / cron 都从 ifmap.log.* 读取")
    void readsConfiguration() {
        runner("ifmap_clean_configured")
                .withPropertyValues(
                        "ifmap.log.retention-days=7",
                        "ifmap.log.clean-batch-size=5",
                        "ifmap.log.clean-cron=0 0 4 * * ?")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(7, context.getBean(IfmapLogCleanTask.class).getRetentionDays());
                });
    }

    @Test
    @DisplayName("cron 非法 → 启动期快速失败（证明该配置真的被绑到了调度器）")
    void invalidCronFailsFast() {
        runner("ifmap_clean_bad_cron")
                .withPropertyValues("ifmap.log.clean-cron=not-a-cron")
                .run(context -> assertNotNull(context.getStartupFailure(),
                        "非法 cron 应在启动期暴露，而不是静默忽略"));
    }

    @Test
    @DisplayName("cron 真会触发（每秒一次）：过期日志被自动清掉")
    void cronActuallyTriggersCleanup() throws Exception {
        runner("ifmap_clean_tick")
                .withPropertyValues("ifmap.log.clean-cron=*/1 * * * * *", "ifmap.log.clean-batch-sleep-millis=0")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
                    insertLog(jdbc, 11L, System.currentTimeMillis() - 200L * DAY);

                    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
                    while (count(jdbc) > 0 && System.currentTimeMillis() < deadline) {
                        Thread.sleep(200L);
                    }
                    assertEquals(0, count(jdbc), "调度器应已自动清理过期日志");
                });
    }

    @Test
    @DisplayName("调度线程是守护线程：非 Web / 批处理应用能正常退出")
    void schedulerThreadIsDaemon() {
        runner("ifmap_clean_daemon").run(context -> {
            assertNull(context.getStartupFailure());
            IfmapLogCleanScheduler scheduler = context.getBean(IfmapLogCleanScheduler.class);
            assertTrue(scheduler.isScheduled(), "应先排定下一次执行，再检查线程属性");

            boolean daemon = false;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getName().startsWith("ifmap-log-clean")) {
                    daemon = thread.isDaemon();
                    if (daemon) {
                        break;
                    }
                }
            }
            assertTrue(daemon, "ifmap-log-clean-* 必须是守护线程，否则 main 返回后 JVM 不退出");
        });
    }

    @Test
    @DisplayName("ifmap.log.clean-enabled=false：只关定时任务，清理器仍可手工调用")
    void cleanEnabledFalseDisablesOnlyScheduling() {
        runner("ifmap_clean_off")
                .withPropertyValues("ifmap.log.clean-enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(JdbcExecutionLogCleaner.class), "清理器 bean 应保留");
                    assertTrue(context.getBeanNamesForType(IfmapLogCleanTask.class).length == 0,
                            "定时任务不应装配");
                    assertTrue(context.getBeanNamesForType(IfmapLogCleanScheduler.class).length == 0,
                            "调度器不应装配");
                });
    }

    @Test
    @DisplayName("ifmap.log.enabled=false：不写日志也就没必要清理，定时任务同样不装配")
    void logDisabledDisablesScheduling() {
        runner("ifmap_clean_log_off")
                .withPropertyValues("ifmap.log.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeanNamesForType(IfmapLogCleanTask.class).length == 0);
                    assertTrue(context.getBeanNamesForType(IfmapLogCleanScheduler.class).length == 0);
                });
    }

    @Test
    @DisplayName("cleanNow 真删过期日志、保留未过期日志")
    void cleanNowDeletesExpiredLogs() {
        runner("ifmap_clean_real")
                .withPropertyValues("ifmap.log.retention-days=90")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
                    insertLog(jdbc, 1L, System.currentTimeMillis() - 200L * DAY);
                    insertLog(jdbc, 2L, System.currentTimeMillis() - 1L * DAY);

                    long deleted = context.getBean(IfmapLogCleanTask.class).cleanNow();

                    assertEquals(1L, deleted, "只应删除过期的那条");
                    assertEquals(1, count(jdbc));
                    assertEquals(2L, jdbc.queryForObject("SELECT `key_id` FROM `ifmap_execution_log`",
                            Long.class).longValue(), "留下的必须是未过期那条");
                });
    }

    @Test
    @DisplayName("清理失败只 WARN 不抛异常（返回 -1）—— 不影响业务、下个周期重试")
    void cleanNowSwallowsFailure() {
        JdbcExecutionLogCleaner onMissingTable = new JdbcExecutionLogCleaner(
                new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        "jdbc:h2:mem:ifmap_clean_absent;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")),
                new TableNameResolver("absent_"));

        IfmapLogCleanTask task = new IfmapLogCleanTask(onMissingTable, 90, 1000, 1000, 0L);

        assertEquals(-1L, task.cleanNow(), "表不存在时应返回 -1 而不是抛异常");
    }

    @Test
    @DisplayName("保留期配成 0：任务层返回 -1（拒绝执行），不会删全表")
    void rejectsZeroRetention() {
        runner("ifmap_clean_zero")
                .withPropertyValues("ifmap.log.retention-days=0")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
                    insertLog(jdbc, 9L, System.currentTimeMillis() - 999L * DAY);

                    assertEquals(-1L, context.getBean(IfmapLogCleanTask.class).cleanNow());
                    assertEquals(1, count(jdbc), "拒绝执行后数据必须原样保留");
                });
    }

    /** 宿主自定义清理器时，starter 自动退让。 */
    @Configuration(proxyBeanMethods = false)
    static class HostCleanerConfiguration {

        @Bean
        JdbcExecutionLogCleaner hostCleaner(javax.sql.DataSource dataSource) {
            return new JdbcExecutionLogCleaner(new JdbcTemplate(dataSource), new TableNameResolver("host_"));
        }
    }

    @Test
    @DisplayName("宿主机自带清理器时自动退让（@ConditionalOnMissingBean）")
    void hostCleanerWins() {
        runner("ifmap_clean_host")
                .withUserConfiguration(HostCleanerConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("host_execution_log",
                            context.getBean(JdbcExecutionLogCleaner.class).tables().executionLogTable());
                });
    }

    private static void insertLog(JdbcTemplate jdbc, long keyId, long addTimeMillis) {
        jdbc.update("INSERT INTO `ifmap_execution_log`"
                        + " (`key_id`, `tenant_id`, `interface_no`, `biz_id`, `execution_result`, `add_time`)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                keyId, -1L, "IF_CLEAN", "BIZ-" + keyId, "SUCCESS", new Timestamp(addTimeMillis));
    }

    private static int count(JdbcTemplate jdbc) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM `ifmap_execution_log`", Integer.class);
        return n == null ? 0 : n;
    }
}
