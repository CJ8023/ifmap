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

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.cache.CachingConfigRepository;
import cn.cj.ifmap.core.cache.ConfigCache;
import cn.cj.ifmap.core.cache.InMemoryConfigCache;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;
import cn.cj.ifmap.core.rule.RuleRegistry;
import cn.cj.ifmap.core.rule.builtin.BuiltinRules;
import cn.cj.ifmap.core.spi.BankServiceGateway;
import cn.cj.ifmap.core.spi.ClockProvider;
import cn.cj.ifmap.core.spi.DefaultLogMasker;
import cn.cj.ifmap.core.spi.ExecutionLogSink;
import cn.cj.ifmap.core.spi.HeaderTenantResolver;
import cn.cj.ifmap.core.spi.IdGenerator;
import cn.cj.ifmap.core.spi.LogMasker;
import cn.cj.ifmap.core.spi.RepositoryExecutionLogSink;
import cn.cj.ifmap.core.spi.SnowflakeIdGenerator;
import cn.cj.ifmap.core.spi.SystemClockProvider;
import cn.cj.ifmap.core.spi.TenantResolver;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.CallbackRegistry;
import cn.cj.ifmap.core.strategy.FullParamStrategyRegistry;
import cn.cj.ifmap.core.strategy.LogicBranchStrategyRegistry;
import cn.cj.ifmap.core.strategy.SpecialDealStrategyRegistry;
import cn.cj.ifmap.core.validate.ContractValidator;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;
import cn.cj.ifmap.jdbc.JdbcExecutionLogCleaner;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import cn.cj.ifmap.jdbc.TableNameResolver;
import cn.cj.ifmap.json.jackson.JacksonJsonOps;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashSet;

/**
 * ifmap 自动配置：引入本 starter 且 classpath 有 DataSource 即自动装配
 * {@link IfmapEngine} / {@link ConfigRepository} / 建表 / 缓存。
 *
 * <p>所有 bean 都是 {@code @ConditionalOnMissingBean}：宿主机声明同类型 bean 即可完全接管。</p>
 *
 * <p>关闭方式：{@code ifmap.enabled=false}。</p>
 *
 * @author caijun
 */
@AutoConfiguration
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@ConditionalOnClass(IfmapEngine.class)
@ConditionalOnProperty(prefix = "ifmap", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IfmapProperties.class)
public class IfmapAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TableNameResolver ifmapTableNameResolver(IfmapProperties properties) {
        return new TableNameResolver(properties.getTablePrefix());
    }

    /** 内置规则预注册（引擎侧用 {@code builtins(false)}，避免与自动收集的宿主机规则重复注册）。 */
    @Bean
    @ConditionalOnMissingBean
    public RuleRegistry ifmapRuleRegistry() {
        return BuiltinRules.registerTo(new RuleRegistry());
    }

    /** 必须是 static：BeanPostProcessor 需要早于普通 bean 实例化。 */
    @Bean
    public static IfmapRuleRegistrar ifmapRuleRegistrar() {
        return new IfmapRuleRegistrar();
    }

    /** 必须是 static：BeanPostProcessor 需要早于普通 bean 实例化。 */
    @Bean
    public static IfmapStrategyRegistrar ifmapStrategyRegistrar() {
        return new IfmapStrategyRegistrar();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(JacksonJsonOps.class)
    public JsonOps ifmapJsonOps() {
        return new JacksonJsonOps();
    }

    @Bean
    @ConditionalOnMissingBean
    public IfmapEngine ifmapEngine(RuleRegistry registry, JsonOps jsonOps, IfmapProperties properties) {
        return IfmapEngine.builder()
                .registry(registry)
                .jsonOps(jsonOps)
                .builtins(false)
                .nullPolicy(properties.getNullPolicy())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdGenerator ifmapIdGenerator(IfmapProperties properties) {
        if (IfmapProperties.IdStrategy.AUTO_INCREMENT == properties.getIdStrategy()) {
            return IdGenerator.AUTO_INCREMENT;
        }
        Long workerId = properties.getWorkerId();
        // workerId 显式配置时才是新实例；否则复用 JVM 级单例（各自 new 会因序列号独立而重复 ID）
        return workerId == null ? SnowflakeIdGenerator.shared() : new SnowflakeIdGenerator(workerId);
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantResolver ifmapTenantResolver(IfmapProperties properties) {
        return new HeaderTenantResolver(properties.getTenantHeader(), properties.getDefaultTenantId());
    }

    @Bean
    @ConditionalOnMissingBean
    public LogMasker ifmapLogMasker(IfmapProperties properties) {
        IfmapProperties.Log log = properties.getLog();
        return new DefaultLogMasker(new LinkedHashSet<String>(log.getMaskFields()),
                new LinkedHashSet<String>(log.getExcludeFields()));
    }

    @Bean
    @ConditionalOnMissingBean
    public ClockProvider ifmapClockProvider() {
        return new SystemClockProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpecialDealStrategyRegistry ifmapSpecialDealStrategyRegistry() {
        return new SpecialDealStrategyRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public FullParamStrategyRegistry ifmapFullParamStrategyRegistry() {
        return new FullParamStrategyRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public LogicBranchStrategyRegistry ifmapLogicBranchStrategyRegistry() {
        return new LogicBranchStrategyRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionRegistry ifmapActionRegistry(IfmapProperties properties) {
        ActionRegistry registry = new ActionRegistry();
        registry.setFailOnMissingAction(properties.getOrchestrator().isFailOnMissingAction());
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public CallbackRegistry ifmapCallbackRegistry() {
        return new CallbackRegistry();
    }

    /** 部署前契约自检（由管理端调用，不在启动时扫库）。 */
    @Bean
    @ConditionalOnMissingBean
    public ContractValidator ifmapContractValidator(IfmapEngine engine) {
        return new ContractValidator(engine);
    }

    /** 执行编排（需要 ConfigRepository；无 DataSource 时不装配）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(ConfigRepository.class)
    static class OrchestrationConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "ifmap.log", name = "enabled", havingValue = "true", matchIfMissing = true)
        public ExecutionLogSink ifmapExecutionLogSink(ConfigRepository repository) {
            return new RepositoryExecutionLogSink(repository);
        }

        @Bean
        @ConditionalOnMissingBean
        public IfmapOrchestrator ifmapOrchestrator(IfmapEngine engine, JsonOps jsonOps,
                                                   ConfigRepository repository,
                                                   TenantResolver tenantResolver,
                                                   SpecialDealStrategyRegistry specialDeals,
                                                   FullParamStrategyRegistry fullParams,
                                                   LogicBranchStrategyRegistry logicBranches,
                                                   ActionRegistry actions,
                                                   CallbackRegistry callbacks,
                                                   ObjectProvider<BankServiceGateway> gatewayProvider,
                                                   ObjectProvider<ExecutionLogSink> logSinkProvider,
                                                   LogMasker logMasker,
                                                   ClockProvider clock,
                                                   IfmapProperties properties) {
            IfmapProperties.Orchestrator options = properties.getOrchestrator();
            return IfmapOrchestrator.builder()
                    .engine(engine)
                    .jsonOps(jsonOps)
                    .repository(repository)
                    .tenantResolver(tenantResolver)
                    .specialDeals(specialDeals)
                    .fullParams(fullParams)
                    .logicBranches(logicBranches)
                    .actions(actions)
                    .callbacks(callbacks)
                    .gateway(gatewayProvider.getIfAvailable())
                    .logSink(logSinkProvider.getIfAvailable())
                    .logMasker(logMasker)
                    .clock(clock)
                    .stopOnFailure(options.isStopOnFailure())
                    .failOnMissingStrategy(options.isFailOnMissingStrategy())
                    .truncateThreshold(toThreshold(properties.getLog().getTruncateThreshold()))
                    .build();
        }

        private static int toThreshold(long value) {
            return value <= 0L ? Integer.MAX_VALUE : (int) Math.min(value, (long) Integer.MAX_VALUE);
        }
    }

    /** 缓存：classpath 有 Caffeine 用 Caffeine，否则退回 core 的零依赖实现。 */    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "ifmap.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class CacheConfiguration {

        @Bean
        @ConditionalOnMissingBean(ConfigCache.class)
        @ConditionalOnClass(name = "com.github.benmanes.caffeine.cache.Cache")
        public ConfigCache ifmapCaffeineConfigCache(IfmapProperties properties) {
            IfmapProperties.Cache cache = properties.getCache();
            return new CaffeineConfigCache(cache.getMaximumSize(), cache.getTtl());
        }

        @Bean
        @ConditionalOnMissingBean(ConfigCache.class)
        public ConfigCache ifmapInMemoryConfigCache(IfmapProperties properties) {
            IfmapProperties.Cache cache = properties.getCache();
            return new InMemoryConfigCache(cache.getMaximumSize(),
                    cache.getTtl() == null ? 0L : cache.getTtl().toMillis());
        }
    }

    /** 启动建表（幂等）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnClass(JdbcTemplate.class)
    @ConditionalOnProperty(prefix = "ifmap.ddl", name = "auto", havingValue = "true", matchIfMissing = true)
    static class DdlConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public IfmapSchemaInitializer ifmapSchemaInitializer(DataSource dataSource, TableNameResolver tables,
                                                             ResourceLoader resourceLoader) {
            return new IfmapSchemaInitializer(dataSource, tables, resourceLoader);
        }
    }

    /** 执行日志保留期清理器（设计 §6.4 方案 A）：bean 常驻，定时任务可选。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnClass(JdbcTemplate.class)
    static class LogCleanConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public JdbcExecutionLogCleaner ifmapExecutionLogCleaner(DataSource dataSource, TableNameResolver tables) {
            return new JdbcExecutionLogCleaner(new JdbcTemplate(dataSource), tables);
        }
    }

    /**
     * 定时清理执行日志。
     *
     * <p>三重条件：写了执行日志（{@code ifmap.log.enabled}）→ 开了定时清理（{@code ifmap.log.clean-enabled}）
     * → 有 JDBC 数据源（默认全开，见设计 §6.4 ADR-10：开源产品装完即用，不该要求先建分区）。</p>
     *
     * <p><b>刻意不用 {@code @EnableScheduling}</b>：自动配置打开宿主的全局调度设施属于越界，而且 Spring 在无
     * TaskScheduler bean 时会创建<b>非守护</b>线程，导致非 Web / 批处理应用 {@code main()} 返回后 JVM 不退出
     * （本仓库 demo 实测过）。这里用自带守护线程的 {@link IfmapLogCleanScheduler} 驱动，应用该退出就退出。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnClass(JdbcTemplate.class)
    @ConditionalOnProperty(prefix = "ifmap.log", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "ifmap.log", name = "clean-enabled", havingValue = "true",
            matchIfMissing = true)
    static class LogCleanSchedulingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public IfmapLogCleanTask ifmapLogCleanTask(JdbcExecutionLogCleaner cleaner, IfmapProperties properties) {
            IfmapProperties.Log log = properties.getLog();
            return new IfmapLogCleanTask(cleaner, log.getRetentionDays(), log.getCleanBatchSize(),
                    log.getCleanMaxBatches(), log.getCleanBatchSleepMillis());
        }

        @Bean
        @ConditionalOnMissingBean
        public IfmapLogCleanScheduler ifmapLogCleanScheduler(IfmapLogCleanTask task, IfmapProperties properties) {
            return new IfmapLogCleanScheduler(task, properties.getLog().getCleanCron());
        }
    }

    /** JDBC 仓储（写操作由 JdbcConfigWriter 承担，读写分离不影响既有 API）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnClass(JdbcTemplate.class)
    static class RepositoryConfiguration {

        @Bean
        @ConditionalOnMissingBean(ConfigRepository.class)
        public ConfigRepository ifmapConfigRepository(DataSource dataSource, TableNameResolver tables,
                                                      IdGenerator idGenerator,
                                                      ObjectProvider<ConfigCache> cacheProvider,
                                                      ObjectProvider<IfmapSchemaInitializer> schemaInitializers) {
            // 先让建表 bean 完成（若启用），否则仓储可能在表还不存在时被业务提前使用
            schemaInitializers.getIfAvailable();
            JdbcConfigRepository repository =
                    new JdbcConfigRepository(new JdbcTemplate(dataSource), tables, idGenerator);
            ConfigCache cache = cacheProvider.getIfAvailable();
            return cache == null ? repository : new CachingConfigRepository(repository, cache);
        }

        @Bean
        @ConditionalOnMissingBean
        public JdbcConfigWriter ifmapConfigWriter(DataSource dataSource, TableNameResolver tables,
                                                  IdGenerator idGenerator) {
            return new JdbcConfigWriter(new JdbcTemplate(dataSource), tables, idGenerator);
        }
    }
}
