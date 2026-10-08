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

import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.model.PartnerCall;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;
import cn.cj.ifmap.core.spi.PartnerServiceGateway;
import cn.cj.ifmap.core.spi.ExecutionLogSink;
import cn.cj.ifmap.core.spi.RepositoryExecutionLogSink;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import cn.cj.ifmap.jdbc.TableNameResolver;
import cn.cj.ifmap.testkit.TestDatabases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.DefaultResourceLoader;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q10 替代交付：<b>配置库与执行日志库分离</b>（不引入多数据源框架）。
 *
 * <p>设计 Q10 的结论是「不做多数据源框架」：ifmap 只需要「配置从 A 库读、日志往 B 库写」这一件事，
 * 而这件事用<b>宿主已有的两个 DataSource</b> + <b>一行 bean 覆盖</b>就能做到 ——
 * 所有自动配置 bean 都是 {@code @ConditionalOnMissingBean}，宿主声明同类型 bean 即完全接管。</p>
 *
 * <p>本测试把「分离」钉死在三件事上：</p>
 * <ol>
 *   <li>配置只从主库读（主库有配置、日志库没有配置，链路照样跑通）；</li>
 *   <li>日志只往日志库写（日志库有 1 行、主库 0 行 —— 反向也证明没有偷偷回落到主库）；</li>
 *   <li>日志库不可用时业务不受影响（写日志失败只降级为 WARN，不打断业务，也不会张冠李戴写到主库）。</li>
 * </ol>
 *
 * <p>宿主接入写法（与 {@link #SplitDatasourceConfig} 一致）见 {@code docs/05-SpringBoot集成.md} §3.5。</p>
 *
 * <p>默认跑 H2：两个库是两块独立内存库，"物理分离"名副其实。若设了 {@code IFMAP_JDBC_URL} 跑真库，
 * 本用例退化为"同一个库内的两套表前缀"（一个 MySQL 实例只能给一个库）—— 断言与代码路径不变。</p>
 *
 * @author caijun
 */
class IfmapSplitDatasourceTest {

    /** 主库（配置）：标 {@code @Primary}，自动配置里的 {@code DataSource} 注入都走它。 */
    private static final String CONFIG_DB = "ifmap_split_config_db";
    /** 日志库（执行日志）：只有它被显式注入到覆盖后的 {@code ExecutionLogSink}。 */
    private static final String LOG_DB = "ifmap_split_log_db";
    private static final String LOG_DB_NO_TABLES = "ifmap_split_log_db_no_tables";

    /** 当前档位下该"库"实际使用的表前缀（H2 = hint 本身；真库 = itt_<hint>_<hex>_）。 */
    private static String prefixOf(String hint) {
        return TestDatabases.prefixFor(hint);
    }

    /** 宿主侧最小接入：第二个 DataSource + 覆盖默认 sink（约 20 行，零新 API）。 */
    @Configuration(proxyBeanMethods = false)
    static class SplitDatasourceConfig {

        @Bean(name = "dataSource")
        @Primary
        public DataSource configDataSource() {
            return TestDatabases.fresh(CONFIG_DB).dataSource();
        }

        @Bean(name = "logDataSource")
        public DataSource logDataSource() {
            return TestDatabases.fresh(LOG_DB).dataSource();
        }

        @Bean
        public ExecutionLogSink ifmapExecutionLogSink(@Qualifier("logDataSource") DataSource logDataSource) {
            return new RepositoryExecutionLogSink(new JdbcConfigRepository(logDataSource, prefixOf(LOG_DB)));
        }
    }

    /** 同上，但日志库指向一个「没建表」的库/前缀：用来验证日志写失败不影响业务。 */
    @Configuration(proxyBeanMethods = false)
    static class BrokenLogDatasourceConfig {

        @Bean(name = "dataSource")
        @Primary
        public DataSource configDataSource() {
            return TestDatabases.fresh(CONFIG_DB).dataSource();
        }

        @Bean(name = "logDataSource")
        public DataSource logDataSource() {
            return TestDatabases.fresh(LOG_DB_NO_TABLES).dataSource();
        }

        @Bean
        public ExecutionLogSink ifmapExecutionLogSink(@Qualifier("logDataSource") DataSource logDataSource) {
            return new RepositoryExecutionLogSink(
                    new JdbcConfigRepository(logDataSource, prefixOf(LOG_DB_NO_TABLES)));
        }
    }

    static final class EchoGateway implements PartnerServiceGateway {
        @Override
        public String exchange(PartnerCall call) {
            return "{\"code\":\"0000\",\"data\":{\"applyNo\":\"AP-SPLIT\"}}";
        }
    }

    private static ApplicationContextRunner runner(Class<?> splitConfig) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IfmapAutoConfiguration.class))
                .withUserConfiguration(splitConfig)
                .withPropertyValues("ifmap.table-prefix=" + prefixOf(CONFIG_DB))
                .withBean("echoGateway", PartnerServiceGateway.class, EchoGateway::new);
    }

    private static void insertConfig(JdbcConfigWriter writer, String interfaceNo, String successValue) {
        IfmapConfig config = new IfmapConfig();
        config.setInterfaceNo(interfaceNo);
        config.setBusiNode("apply");
        config.setInterfaceOrder(1);
        config.setInterfaceCode("IC-SPLIT");
        config.setInterfaceName("双库测试接口");
        config.setPartnerCode("CMB");
        config.setTenantId(-1L);
        config.setRequestParamTemplate("{\"acctName\":\"$.acctName\"}");
        config.setResponseParamTemplate("{\"applyNo\":\"$.data.applyNo\"}");
        config.setResultFlag("$.code");
        config.setSuccessValue(successValue);
        writer.insert(config);
    }

    /** 日志库的表由宿主自己建（自动建表只管主库）。 */
    private static void createSchema(DataSource dataSource, String prefix) {
        new IfmapSchemaInitializer(dataSource, new TableNameResolver(prefix),
                new DefaultResourceLoader()).createTablesIfAbsent();
    }

    private static List<ExecutionLog> logsOf(DataSource dataSource, String prefix, String bizId) {
        return new JdbcConfigRepository(dataSource, prefix).recentLogs("-1", bizId, 10);
    }

    @Test
    @DisplayName("配置读主库、日志写日志库：两边各只有自己那一半数据")
    void configAndLogsGoToDifferentDatabases() {
        runner(SplitDatasourceConfig.class).run(context -> {
            assertNull(context.getStartupFailure(), "上下文应正常启动");
            DataSource logDataSource = context.getBean("logDataSource", DataSource.class);
            DataSource configDataSource = context.getBean("dataSource", DataSource.class);
            createSchema(logDataSource, prefixOf(LOG_DB));

            insertConfig(context.getBean(JdbcConfigWriter.class), "BIZ_SPLIT", "0000");

            IfmapResult result = context.getBean(IfmapOrchestrator.class).execute(
                    IfmapRequest.builder().bizId("BIZ-SPLIT").operatorId("u1")
                            .put("acctName", "张三").build(),
                    "BIZ_SPLIT", "apply");

            // 1) 配置来自主库：日志库里一条配置都没有，链路照样跑通
            assertTrue(result.isSuccess(), result.getErrorMessage());
            assertEquals("AP-SPLIT", result.getData().get("applyNo"));
            assertTrue(logsOf(configDataSource, prefixOf(CONFIG_DB), "BIZ-SPLIT").isEmpty(), "主库不该有执行日志");
            assertTrue(new JdbcConfigRepository(logDataSource, prefixOf(LOG_DB))
                            .queryConfigs("-1", "BIZ_SPLIT", "apply").isEmpty(),
                    "日志库里没有配置（配置确实读的是主库）");

            // 2) 日志只写日志库：主库 0 行、日志库 1 行且已脱敏
            List<ExecutionLog> logDbLogs = logsOf(logDataSource, prefixOf(LOG_DB), "BIZ-SPLIT");
            assertEquals(1, logDbLogs.size());
            assertEquals("SUCCESS", logDbLogs.get(0).getExecutionResult());
            assertTrue(logDbLogs.get(0).getRequestParam().contains("张*"),
                    "日志库里的日志同样要脱敏：" + logDbLogs.get(0).getRequestParam());
            assertFalse(logDbLogs.get(0).getRequestParam().contains("张三"));

            // 3) 自动配置默认 sink 已被宿主 bean 接管（不会出现"两边都写"）
            assertEquals(1, context.getBeansOfType(ExecutionLogSink.class).size());
            assertTrue(context.getBean(ExecutionLogSink.class) instanceof RepositoryExecutionLogSink);
        });
    }

    @Test
    @DisplayName("日志库不可用：业务照常成功，主库也不会被顶上（只降级为 WARN）")
    void unavailableLogDatabaseDoesNotBreakBusiness() {
        runner(BrokenLogDatasourceConfig.class).run(context -> {
            DataSource configDataSource = context.getBean("dataSource", DataSource.class);
            // 刻意不给日志库建表：写入必然失败
            insertConfig(context.getBean(JdbcConfigWriter.class), "BIZ_SPLIT_FAIL", "0000");

            IfmapResult result = context.getBean(IfmapOrchestrator.class).execute(
                    IfmapRequest.builder().bizId("BIZ-SPLIT-FAIL").operatorId("u1")
                            .put("acctName", "李四").build(),
                    "BIZ_SPLIT_FAIL", "apply");

            assertTrue(result.isSuccess(), "写日志失败不能影响业务结果：" + result.getErrorMessage());
            assertEquals("AP-SPLIT", result.getData().get("applyNo"));
            assertTrue(logsOf(configDataSource, prefixOf(CONFIG_DB), "BIZ-SPLIT-FAIL").isEmpty(),
                    "日志库写失败时不许回落到主库，否则两库分离就失去意义");
        });
    }

    @Test
    @DisplayName("不覆盖 sink（单库部署）时日志仍落主库：分离是可选能力，不是必需改造")
    void singleDatabaseStillWorks() {
        final String hint = "ifmap_split_single_db";
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IfmapAutoConfiguration.class))
                .withBean("dataSource", DataSource.class, () -> TestDatabases.fresh(hint).dataSource())
                .withPropertyValues("ifmap.table-prefix=" + prefixOf(hint))
                .withBean("echoGateway", PartnerServiceGateway.class, EchoGateway::new)
                .run(context -> {
                    assertNotNull(context.getBean(ConfigRepository.class));
                    insertConfig(context.getBean(JdbcConfigWriter.class), "BIZ_SINGLE", "0000");
                    DataSource dataSource = context.getBean("dataSource", DataSource.class);

                    IfmapResult result = context.getBean(IfmapOrchestrator.class).execute(
                            IfmapRequest.builder().bizId("BIZ-SINGLE").operatorId("u1")
                                    .put("acctName", "王五").build(),
                            "BIZ_SINGLE", "apply");

                    assertTrue(result.isSuccess(), result.getErrorMessage());
                    assertEquals(1, logsOf(dataSource, prefixOf(hint), "BIZ-SINGLE").size());
                });
    }
}
