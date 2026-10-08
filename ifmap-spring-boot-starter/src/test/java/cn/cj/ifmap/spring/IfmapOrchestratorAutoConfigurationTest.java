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

import cn.cj.ifmap.core.cache.CachingConfigRepository;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.model.PartnerCall;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;
import cn.cj.ifmap.core.spi.PartnerServiceGateway;
import cn.cj.ifmap.core.spi.ExecutionLogSink;
import cn.cj.ifmap.core.spi.HeaderTenantResolver;
import cn.cj.ifmap.core.spi.IfmapCallback;
import cn.cj.ifmap.core.spi.IfmapCallbackHandler;
import cn.cj.ifmap.core.spi.TenantResolver;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.CallbackRegistry;
import cn.cj.ifmap.core.strategy.FullParam;
import cn.cj.ifmap.core.strategy.FullParamStrategy;
import cn.cj.ifmap.core.strategy.FullParamStrategyRegistry;
import cn.cj.ifmap.core.strategy.IfmapAction;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.strategy.LogicBranch;
import cn.cj.ifmap.core.strategy.LogicBranchStrategy;
import cn.cj.ifmap.core.strategy.LogicBranchStrategyRegistry;
import cn.cj.ifmap.core.strategy.SpecialDealStrategy;
import cn.cj.ifmap.core.spi.LogMasker;
import cn.cj.ifmap.core.strategy.SpecialDealStrategyRegistry;
import cn.cj.ifmap.core.strategy.StrategyContext;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import cn.cj.ifmap.testkit.TestDatabases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W4 自动装配测试：策略自动收集 + 编排器 + 租户解析 + 脱敏 + 执行日志落库（H2 真库）。
 *
 * @author caijun
 */
class IfmapOrchestratorAutoConfigurationTest {

    /** 一个 bean 同时实现 4 类策略接口（模拟存量 ECC 里的策略聚合类）。 */
    @FullParam(partnerCode = "CMB", busiNode = "apply")
    @LogicBranch("loanResult")
    @IfmapAction("submitApply")
    public static class AllInOneStrategy
            implements SpecialDealStrategy, FullParamStrategy, LogicBranchStrategy, IfmapActionHandler {

        @Override
        public Map<String, Object> apply(StrategyContext context) {
            Map<String, Object> extra = new LinkedHashMap<String, Object>();
            extra.put("fromSpecialDeal", "YES");
            return extra;
        }

        @Override
        public Map<String, Object> assemble(StrategyContext context) {
            Map<String, Object> full = new LinkedHashMap<String, Object>();
            full.put("fromFullParam", "YES");
            return full;
        }

        @Override
        public boolean match(StrategyContext context) {
            return "0000".equals(context.get("code"));
        }

        @Override
        public void execute(StrategyContext context) {
            context.putParam("actionRan", Boolean.TRUE);
        }
    }

    /** 声明了回调但忘记加注解 → 启动期快速失败。 */
    public static class UnannotatedCallback implements IfmapCallbackHandler {
        @Override
        public IfmapResult handle(IfmapRequest request, String rawBody) {
            return IfmapResult.success(Collections.<String, Object>emptyMap());
        }
    }

    @IfmapCallback("CB_CMB")
    public static class CmbCallback implements IfmapCallbackHandler {
        @Override
        public IfmapResult handle(IfmapRequest request, String rawBody) {
            return IfmapResult.success(Collections.<String, Object>singletonMap("echo", rawBody));
        }
    }

    static final class EchoGateway implements PartnerServiceGateway {
        @Override
        public String exchange(PartnerCall call) {
            return "{\"code\":\"0000\",\"data\":{\"applyNo\":\"" + "AP9" + "\"}}";
        }
    }

    private static ApplicationContextRunner runner(String dbName) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        IfmapAutoConfiguration.class))
                // 默认 H2；设了 IFMAP_JDBC_URL 就走真 MySQL
                .withPropertyValues(TestDatabases.springPropertyArray(dbName));
    }

    @Test
    @DisplayName("默认装配：策略注册表 / 编排器 / 租户解析 / 脱敏 / 日志 sink 全部就位")
    void wiresOrchestrationByDefault() {
        runner("ifmap_orc_default").run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(SpecialDealStrategyRegistry.class));
            assertNotNull(context.getBean(FullParamStrategyRegistry.class));
            assertNotNull(context.getBean(LogicBranchStrategyRegistry.class));
            assertNotNull(context.getBean(ActionRegistry.class));
            assertNotNull(context.getBean(CallbackRegistry.class));
            assertNotNull(context.getBean(ExecutionLogSink.class));
            assertNotNull(context.getBean(IfmapOrchestrator.class));
            assertTrue(context.getBean(TenantResolver.class) instanceof HeaderTenantResolver);
        });
    }

    @Test
    @DisplayName("宿主机策略 bean 被自动收集：按 bean 名 / @FullParam / @LogicBranch / @IfmapAction / @IfmapCallback")
    void collectsHostStrategies() {
        runner("ifmap_orc_collect")
                .withBean("allInOneStrategy", AllInOneStrategy.class, AllInOneStrategy::new)
                .withBean("cmbCallback", CmbCallback.class, CmbCallback::new)
                .run(context -> {
                    SpecialDealStrategyRegistry specialDeals = context.getBean(SpecialDealStrategyRegistry.class);
                    assertTrue(specialDeals.contains("allInOneStrategy"), "strategy_name 应按 bean 名直接可用");

                    FullParamStrategyRegistry fullParams = context.getBean(FullParamStrategyRegistry.class);
                    assertTrue(fullParams.contains("CMB", "apply"));
                    assertNotNull(fullParams.lookup("CMB", "apply"));

                    LogicBranchStrategyRegistry branches = context.getBean(LogicBranchStrategyRegistry.class);
                    assertTrue(branches.keys().contains("loanResult"), "分支 key 应来自 @LogicBranch");

                    ActionRegistry actions = context.getBean(ActionRegistry.class);
                    assertTrue(actions.contains("submitApply"));

                    assertTrue(context.getBean(CallbackRegistry.class).contains("CB_CMB"));
                    assertEquals("hi", context.getBean(CallbackRegistry.class)
                            .dispatch("CB_CMB", IfmapRequest.empty(), "hi").getData().get("echo"));

                    IfmapStrategyRegistrar registrar = context.getBean(IfmapStrategyRegistrar.class);
                    assertTrue(registrar.registeredBeanNames().contains("allInOneStrategy"));
                    assertTrue(registrar.registeredBeanNames().contains("cmbCallback"));
                });
    }

    @Test
    @DisplayName("策略 bean 缺注解 → 启动期快速失败（不把问题留到线上）")
    void missingAnnotationFailsFast() {
        runner("ifmap_orc_unannotated")
                .withBean("unannotatedCallback", UnannotatedCallback.class, UnannotatedCallback::new)
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "缺 @IfmapCallback 应导致启动失败");
                    assertTrue(context.getStartupFailure().getMessage().contains("@IfmapCallback"),
                            context.getStartupFailure().getMessage());
                });
    }

    @Test
    @DisplayName("编排器端到端：配置 → 渲染 → 宿主机网关 → 判定 → 执行日志落库（脱敏）")
    void orchestratorEndToEnd() {
        runner("ifmap_orc_e2e")
                .withBean("echoGateway", PartnerServiceGateway.class, EchoGateway::new)
                .run(context -> {
                    JdbcConfigWriter writer = context.getBean(JdbcConfigWriter.class);
                    IfmapConfig config = new IfmapConfig();
                    config.setInterfaceNo("BIZ_APPLY");
                    config.setBusiNode("apply");
                    config.setInterfaceOrder(1);
                    config.setInterfaceCode("IC001");
                    config.setInterfaceName("测试接口");
                    config.setPartnerCode("CMB");
                    config.setTenantId(-1L);
                    config.setRequestParamTemplate("{\"acctName\":\"$.acctName\"}");
                    config.setResponseParamTemplate("{\"applyNo\":\"$.data.applyNo\"}");
                    config.setResultFlag("$.code");
                    config.setSuccessValue("0000");
                    writer.insert(config);

                    IfmapResult result = context.getBean(IfmapOrchestrator.class).execute(
                            IfmapRequest.builder().bizId("BIZ-E2E").operatorId("u1")
                                    .put("acctName", "张三").build(),
                            "BIZ_APPLY", "apply");

                    assertTrue(result.isSuccess(), result.getErrorMessage());
                    assertEquals("AP9", result.getData().get("applyNo"));

                    JdbcConfigRepository repository = (JdbcConfigRepository)
                            ((CachingConfigRepository) context.getBean(ConfigRepository.class)).delegate();
                    java.util.List<ExecutionLog> logs = repository.recentLogs("-1", "BIZ-E2E", 10);
                    assertEquals(1, logs.size());
                    assertEquals("SUCCESS", logs.get(0).getExecutionResult());
                    assertTrue(logs.get(0).getRequestParam().contains("张*"),
                            "日志须脱敏：" + logs.get(0).getRequestParam());
                    assertFalse(logs.get(0).getRequestParam().contains("张三"));
                });
    }

    @Test
    @DisplayName("ifmap.log.enabled=false 时不写日志（无 sink bean），编排器仍可用")
    void logDisabled() {
        runner("ifmap_orc_no_log")
                .withPropertyValues("ifmap.log.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, context.getBeansOfType(ExecutionLogSink.class).size());
                    assertNotNull(context.getBean(IfmapOrchestrator.class));
                });
    }

    @Test
    @DisplayName("租户解析与脱敏字段可由配置覆盖")
    void tenantAndMaskAreConfigurable() {
        runner("ifmap_orc_tenant")
                .withPropertyValues("ifmap.tenant-header=X-Org", "ifmap.default-tenant-id=0")
                .run(context -> {
                    TenantResolver resolver = context.getBean(TenantResolver.class);
                    assertEquals("0", resolver.resolve(IfmapRequest.empty()));
                    assertEquals("9", resolver.resolve(IfmapRequest.builder().header("X-Org", "9").build()));
                });

        runner("ifmap_orc_mask")
                .withPropertyValues("ifmap.log.exclude-fields=acctName")
                .run(context -> assertEquals("{\"acctName\":\"***\"}",
                        context.getBean(LogMasker.class).mask("{\"acctName\":\"张三\"}")));
    }

    @Test
    @DisplayName("宿主机的策略注册表/编排器可完全接管（@ConditionalOnMissingBean）")
    void userBeansWin() {
        runner("ifmap_orc_user")
                .withBean(ActionRegistry.class, ActionRegistry::new)
                .run(context -> assertSame(ActionRegistry.class,
                        context.getBean(ActionRegistry.class).getClass()));
    }

    /** 宿主机自定义编排器。 */
    @Configuration(proxyBeanMethods = false)
    static class UserOrchestratorConfig {

        static final IfmapOrchestrator CUSTOM = IfmapOrchestrator.builder()
                .engine(cn.cj.ifmap.core.IfmapEngine.builder().builtins(false).build())
                .repository(new JdbcConfigRepository(
                        TestDatabases.fresh("ifmap_orc_custom").dataSource()))
                .tenantResolver(new HeaderTenantResolver())
                .build();

        @Bean
        public IfmapOrchestrator myOrchestrator() {
            return CUSTOM;
        }
    }

    @Test
    @DisplayName("宿主机自定义 IfmapOrchestrator 时自动配置退让")
    void userOrchestratorWins() {
        runner("ifmap_orc_user_orc")
                .withUserConfiguration(UserOrchestratorConfig.class)
                .run(context -> assertSame(UserOrchestratorConfig.CUSTOM,
                        context.getBean(IfmapOrchestrator.class)));
    }
}
