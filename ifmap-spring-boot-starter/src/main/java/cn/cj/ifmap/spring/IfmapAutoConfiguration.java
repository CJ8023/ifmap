package cn.cj.ifmap.spring;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.cache.CachingConfigRepository;
import cn.cj.ifmap.core.cache.ConfigCache;
import cn.cj.ifmap.core.cache.InMemoryConfigCache;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.rule.RuleRegistry;
import cn.cj.ifmap.core.rule.builtin.BuiltinRules;
import cn.cj.ifmap.core.spi.IdGenerator;
import cn.cj.ifmap.core.spi.SnowflakeIdGenerator;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;
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

    /** 缓存：classpath 有 Caffeine 用 Caffeine，否则退回 core 的零依赖实现。 */
    @Configuration(proxyBeanMethods = false)
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
