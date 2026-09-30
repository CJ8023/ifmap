package cn.cj.ifmap.spring;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.cache.CachingConfigRepository;
import cn.cj.ifmap.core.cache.ConfigCache;
import cn.cj.ifmap.core.cache.InMemoryConfigCache;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.rule.RuleRegistry;
import cn.cj.ifmap.core.spi.IdGenerator;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import cn.cj.ifmap.jdbc.TableNameResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IfmapAutoConfiguration} 集成测试：真实 H2 + 真实建表脚本 + 真实引擎。
 *
 * <p>脚本、建表器、仓储、引擎全部是生产实现：H2 用 {@code MODE=MySQL} 打开，
 * 表尾 MySQL 表选项由 {@link IfmapSchemaInitializer} 的生产逻辑自行剥离（非测试替身）。</p>
 *
 * @author caijun
 */
class IfmapAutoConfigurationTest {

    /** 宿主机规则类（模拟业务方通过 @Component 声明的规则 bean）。 */
    public static class HostRules {

        @IfmapRule(value = "hostUpper", desc = "测试用大写规则", example = "abc -> ABC")
        public String hostUpper(String value) {
            return value == null ? null : value.toUpperCase();
        }
    }

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
    @DisplayName("默认装配：建表 + 引擎 + 缓存装饰的仓储 + 写入器")
    void wiresByDefault() {
        runner("ifmap_ac_default").run(context -> {
            assertNull(context.getStartupFailure(), "上下文应正常启动");

            assertEquals(TableNameResolver.DEFAULT_PREFIX, context.getBean(TableNameResolver.class).prefix());
            assertNotNull(context.getBean(IfmapEngine.class));
            assertNotNull(context.getBean(RuleRegistry.class));
            assertNotNull(context.getBean(JdbcConfigWriter.class));

            ConfigRepository repository = context.getBean(ConfigRepository.class);
            assertInstanceOf(CachingConfigRepository.class, repository, "默认应装配缓存装饰器");
            assertInstanceOf(JdbcConfigRepository.class, ((CachingConfigRepository) repository).delegate());
            assertNotNull(context.getBean(ConfigCache.class));

            // 建表：4 张表都真实建出来了
            IfmapSchemaInitializer initializer = context.getBean(IfmapSchemaInitializer.class);
            assertTrue(initializer.tableExists("ifmap_config"));
            assertTrue(initializer.tableExists("ifmap_logic_branch_config"));
            assertTrue(initializer.tableExists("ifmap_execution_log"));
            assertTrue(initializer.tableExists("ifmap_config_history"));

            // 内置规则可用（builtins(false) + registry 预注册，不应重复注册失败）
            IfmapEngine engine = context.getBean(IfmapEngine.class);
            assertTrue(engine.getRegistry().contains("strMask"));
            assertEquals("{\"v\":\"a**\"}", engine.render("{\"v\":\"@FUN(strMask,abc,NAME)\"}", "{}"));
        });
    }

    @Test
    @DisplayName("ifmap.enabled=false 时整体退让")
    void backsOffWhenDisabled() {
        runner("ifmap_ac_disabled")
                .withPropertyValues("ifmap.enabled=false")
                .run(context -> {
                    assertEquals(0, context.getBeansOfType(IfmapEngine.class).size());
                    assertEquals(0, context.getBeansOfType(ConfigRepository.class).size());
                    assertEquals(0, context.getBeansOfType(IfmapSchemaInitializer.class).size());
                });
    }

    @Test
    @DisplayName("宿主机自定义 ConfigRepository 时完全接管（不做缓存包装）")
    void userRepositoryWins() {
        ConfigRepository custom = new ConfigRepository() {
            @Override
            public List<IfmapConfig> queryConfigs(String tenantId, String interfaceNo, String busiNode) {
                return Collections.emptyList();
            }

            @Override
            public Optional<IfmapConfig> findConfig(String tenantId, String interfaceNo, String busiNode, int order) {
                return Optional.empty();
            }

            @Override
            public List<LogicBranchConfig> queryLogicBranches(String tenantId, String interfaceNo) {
                return Collections.emptyList();
            }

            @Override
            public void saveExecutionLog(ExecutionLog log) {
            }
        };
        runner("ifmap_ac_custom_repo")
                .withBean(ConfigRepository.class, () -> custom)
                .run(context -> assertSame(custom, context.getBean(ConfigRepository.class)));
    }

    @Test
    @DisplayName("ifmap.ddl.auto=false 时不建表（表由 DBA 预先建好），但仓储照常装配")
    void ddlAutoFalseSkipsCreation() {
        runner("ifmap_ac_no_ddl")
                .withPropertyValues("ifmap.ddl.auto=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, context.getBeansOfType(IfmapSchemaInitializer.class).size());
                    assertInstanceOf(CachingConfigRepository.class, context.getBean(ConfigRepository.class));
                });
    }

    @Test
    @DisplayName("ifmap.cache.enabled=false 时不包装、不建缓存 bean")
    void cacheDisabled() {
        runner("ifmap_ac_no_cache")
                .withPropertyValues("ifmap.cache.enabled=false")
                .run(context -> {
                    assertEquals(0, context.getBeansOfType(ConfigCache.class).size());
                    assertInstanceOf(JdbcConfigRepository.class, context.getBean(ConfigRepository.class));
                });
    }

    @Test
    @DisplayName("ifmap.table-prefix 生效")
    void customPrefix() {
        runner("ifmap_ac_prefix")
                .withPropertyValues("ifmap.table-prefix=bankint_")
                .run(context -> {
                    assertEquals("bankint_", context.getBean(TableNameResolver.class).prefix());
                    IfmapSchemaInitializer initializer = context.getBean(IfmapSchemaInitializer.class);
                    assertTrue(initializer.tableExists("bankint_config"));
                    assertFalse(initializer.tableExists("ifmap_config"));
                });
    }

    @Test
    @DisplayName("宿主机的 @IfmapRule bean 被自动收集进注册表")
    void collectsHostRules() {
        runner("ifmap_ac_rules")
                .withBean("myHostRules", HostRules.class, HostRules::new)
                .run(context -> {
                    RuleRegistry registry = context.getBean(RuleRegistry.class);
                    assertTrue(registry.contains("hostUpper"), "宿主规则应被自动注册");
                    String json = context.getBean(IfmapEngine.class).render("{\"v\":\"@FUN(hostUpper,abc)\"}", "{}");
                    assertTrue(json.contains("ABC"), "宿主机规则应可直接在模板里调用，实际输出：" + json);

                    IfmapRuleRegistrar registrar = context.getBean(IfmapRuleRegistrar.class);
                    assertEquals(Collections.singletonList("myHostRules"), registrar.registeredBeanNames());
                });
    }

    @Test
    @DisplayName("ifmap.id-strategy=auto-increment 时用数据库自增")
    void autoIncrementIdStrategy() {
        runner("ifmap_ac_id")
                .withPropertyValues("ifmap.id-strategy=auto-increment")
                .run(context -> assertNull(context.getBean(IdGenerator.class).nextId()));
    }

    @Test
    @DisplayName("ifmap.worker-id 生效（显式配置时新建雪花样实例）")
    void explicitWorkerId() {
        runner("ifmap_ac_worker")
                .withPropertyValues("ifmap.worker-id=7")
                .run(context -> assertNotNull(context.getBean(IdGenerator.class).nextId()));
    }

    @Test
    @DisplayName("null-policy 透传到引擎：skip-field 丢字段 / empty-string 写空串")
    void nullPolicyIsApplied() {
        runner("ifmap_ac_null_skip").run(context -> assertEquals("{}",
                context.getBean(IfmapEngine.class).render("{\"v\":\"$.notExist\"}", "{}")));

        runner("ifmap_ac_null_empty")
                .withPropertyValues("ifmap.null-policy=empty-string")
                .run(context -> assertEquals("{\"v\":\"\"}",
                        context.getBean(IfmapEngine.class).render("{\"v\":\"$.notExist\"}", "{}")));
    }

    @Test
    @DisplayName("classpath 有 Caffeine 用它，没有则退回 core 的 InMemoryConfigCache")
    void caffeineOptional() {
        runner("ifmap_ac_caffeine").run(context ->
                assertInstanceOf(CaffeineConfigCache.class, context.getBean(ConfigCache.class)));

        runner("ifmap_ac_no_caffeine")
                .withClassLoader(new FilteredClassLoader("com.github.benmanes.caffeine"))
                .run(context -> assertInstanceOf(InMemoryConfigCache.class, context.getBean(ConfigCache.class)));
    }

    @Test
    @DisplayName("缓存装饰器走通「查库 → 命中」全链路（H2 真库）")
    void cacheRoundTrip() {
        runner("ifmap_ac_roundtrip").run(context -> {
            ConfigRepository repository = context.getBean(ConfigRepository.class);
            JdbcConfigWriter writer = context.getBean(JdbcConfigWriter.class);

            IfmapConfig config = new IfmapConfig();
            config.setInterfaceNo("BIZ_APPLY");
            config.setBusiNode("apply");
            config.setInterfaceOrder(1);
            config.setInterfaceCode("IC001");
            config.setInterfaceName("测试接口");
            config.setBankCode("CMB");
            config.setTenantId(1001L);
            writer.insert(config);

            assertEquals(1, repository.queryConfigs("1001", "BIZ_APPLY", "apply").size());
            assertEquals(0, repository.queryLogicBranches("1001", "BIZ_APPLY").size());
            assertTrue(context.getBean(ConfigCache.class).stats().getMissCount() >= 1L);
        });
    }

    /** 宿主机自定义 engine 的覆盖点。 */
    @Configuration(proxyBeanMethods = false)
    static class UserConfiguration {

        static final IfmapEngine CUSTOM = IfmapEngine.builder().builtins(false).build();

        @Bean
        public IfmapEngine myEngine() {
            return CUSTOM;
        }
    }

    @Test
    @DisplayName("宿主机自定义 IfmapEngine 时自动配置退让")
    void userEngineWins() {
        runner("ifmap_ac_user_engine")
                .withUserConfiguration(UserConfiguration.class)
                .run(context -> assertSame(UserConfiguration.CUSTOM, context.getBean(IfmapEngine.class)));
    }
}
