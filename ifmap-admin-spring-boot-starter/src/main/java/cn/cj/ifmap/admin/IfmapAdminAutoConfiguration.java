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
package cn.cj.ifmap.admin;

import cn.cj.ifmap.admin.spi.IfmapEnumProvider;
import cn.cj.ifmap.admin.web.IfmapBranchAdminController;
import cn.cj.ifmap.admin.web.IfmapConfigAdminController;
import cn.cj.ifmap.admin.web.IfmapMetaAdminController;
import cn.cj.ifmap.admin.web.IfmapUiController;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;
import cn.cj.ifmap.core.rule.RuleRegistry;
import cn.cj.ifmap.core.spi.IdGenerator;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.CallbackRegistry;
import cn.cj.ifmap.core.strategy.FullParamStrategyRegistry;
import cn.cj.ifmap.core.strategy.LogicBranchStrategyRegistry;
import cn.cj.ifmap.core.strategy.StrategyHookRegistry;
import cn.cj.ifmap.core.validate.ContractValidator;
import cn.cj.ifmap.jdbc.JdbcConfigHistoryRepository;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import cn.cj.ifmap.jdbc.TableNameResolver;
import cn.cj.ifmap.spring.IfmapAutoConfiguration;
import com.fasterxml.jackson.databind.JsonSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ifmap 管理端自动配置（设计 §8）。
 *
 * <p>三重门控，误开不了：</p>
 * <ol>
 *   <li>{@code ifmap.admin.enabled=true}（<b>默认 false</b>）；</li>
 *   <li>classpath 有 Web（{@code DispatcherServlet}）与 {@link JdbcTemplate}；</li>
 *   <li>有 {@link DataSource}（管理端直接读写配置表，不做"非 JDBC 仓储"的管理端）。</li>
 * </ol>
 *
 * <p>所有 bean 都是 {@code @ConditionalOnMissingBean}：宿主机要用自己的 Controller/Service 覆盖即可。</p>
 *
 * @author caijun
 */
@AutoConfiguration
@AutoConfigureAfter(IfmapAutoConfiguration.class)
@ConditionalOnClass({DispatcherServlet.class, JdbcTemplate.class, IfmapOrchestrator.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ifmap.admin", name = "enabled", havingValue = "true")
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(IfmapAdminProperties.class)
public class IfmapAdminAutoConfiguration {

    /**
     * 管理端专用的 JDBC 仓储：直接读库（含停用/已删除行），<b>绕开引擎的缓存</b>。
     *
     * <p>引擎用的是可能被 {@code CachingConfigRepository} 包装过的 {@link ConfigRepository}；
     * 管理端必须看到库里的真相，因此单独建一个不做缓存的实例。它被包在
     * {@link AdminConfigRepository} 里注册，以免多出一个 {@code ConfigRepository} 候选。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public AdminConfigRepository ifmapAdminConfigRepository(DataSource dataSource, TableNameResolver tables,
                                                            IdGenerator idGenerator) {
        return new AdminConfigRepository(new JdbcConfigRepository(new JdbcTemplate(dataSource), tables, idGenerator));
    }

    /** 变更历史仓储（写历史 + 查历史 / 回滚）。 */
    @Bean
    @ConditionalOnMissingBean
    public JdbcConfigHistoryRepository ifmapConfigHistoryRepository(DataSource dataSource,
                                                                    TableNameResolver tables,
                                                                    IdGenerator idGenerator) {
        return new JdbcConfigHistoryRepository(new JdbcTemplate(dataSource), tables, idGenerator);
    }

    /** 快照序列化（历史表的 snapshot/diff）。 */
    @Bean
    @ConditionalOnMissingBean
    public ConfigSnapshotMapper ifmapConfigSnapshotMapper(JsonOps jsonOps) {
        return new ConfigSnapshotMapper(jsonOps);
    }

    /** 保存前校验（设计 §8.3）。 */
    @Bean
    @ConditionalOnMissingBean
    public ConfigValidator ifmapAdminConfigValidator(ObjectProvider<ContractValidator> contractValidatorProvider,
                                                     JsonOps jsonOps,
                                                     StrategyHookRegistry strategyHooks,
                                                     ActionRegistry actions,
                                                     AdminConfigRepository repository) {
        return new ConfigValidator(contractValidatorProvider.getIfAvailable(), jsonOps, strategyHooks,
                actions, repository.get());
    }

    /** 全量巡检。 */
    @Bean
    @ConditionalOnMissingBean
    public ConfigAuditor ifmapConfigAuditor(AdminConfigRepository repository, ConfigValidator validator,
                                            IfmapAdminProperties properties) {
        return new ConfigAuditor(repository.get(), validator, properties.getAuditMaxConfigs());
    }

    /** 管理端业务编排。 */
    @Bean
    @ConditionalOnMissingBean
    public ConfigAdminService ifmapConfigAdminService(JdbcConfigWriter writer,
                                                      AdminConfigRepository repository,
                                                      JdbcConfigHistoryRepository historyRepository,
                                                      ConfigSnapshotMapper snapshots,
                                                      ConfigValidator validator,
                                                      ConfigAuditor auditor,
                                                      ObjectProvider<IfmapOrchestrator> orchestratorProvider,
                                                      ObjectProvider<ConfigRepository> engineRepositoryProvider,
                                                      IfmapAdminProperties properties) {
        return new ConfigAdminService(writer, repository.get(), historyRepository, snapshots, validator, auditor,
                orchestratorProvider, engineRepositoryProvider, properties);
    }

    /** 统一路径前缀。 */
    @Bean
    @ConditionalOnMissingBean
    public IfmapAdminWebConfigurer ifmapAdminWebConfigurer(IfmapAdminProperties properties) {
        return new IfmapAdminWebConfigurer(properties.normalizedBasePath());
    }

    /**
     * 字典目录 = 框架自带（已注册的前/后序策略名、启用/停用）+ 宿主机 SPI（可选，同名 key 覆盖）。
     *
     * <p>用 {@link ObjectProvider} 取而不是 {@code @ConditionalOnBean}：page 需要的是
     * "有则用、无则空"，而不是两个不同的 bean 定义。</p>
     *
     * <p>为什么要把策略名内置：前序策略的可选值本来就是 {@code StrategyHookRegistry} 里注册了什么，
     * 之前这份清单只给「元数据」页当标签展示，表单里的下拉永远是空的 —— 宿主机不额外抄一份
     * {@code strategyName} 字典就根本填不进去。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public IfmapEnumCatalog ifmapEnumCatalog(ObjectProvider<IfmapEnumProvider> enumProviderProvider,
                                            StrategyHookRegistry strategyHooks) {
        return IfmapEnumCatalog.of(enumProviderProvider.getIfAvailable(),
                IfmapBuiltinEnums.of(strategyHooks.keys()));
    }

    /**
     * 大雪片 ID 一律以字符串下发（见 {@link JsSafeLongSerializer}）。
     *
     * <p>不这么做就有一个很难查的坑：{@code keyId} 以 number 下发 → 浏览器用 double 解析时
     * {@code 899855950645559296} 变成 {@code 899855950645559300} → 页面点那一行就报「配置不存在」。</p>
     *
     * <p>用小整数不受影响（tenantId / version / 计数仍是 number），所以这条定制器不会把
     * 整个管理端契约改成「全是字符串」。宿主机若也用这个 {@code ObjectMapper} 输出自己的 id，
     * 受到的也只是「本来就会精度丢失」的那批值。</p>
     */
    @Bean
    @ConditionalOnClass({Jackson2ObjectMapperBuilderCustomizer.class, JsonSerializer.class})
    public Jackson2ObjectMapperBuilderCustomizer ifmapAdminJsSafeLongCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, new JsSafeLongSerializer())
                .serializerByType(Long.TYPE, new JsSafeLongSerializer());
    }

    /** 可视化页面入口（静态页 + 302）。 */
    @Bean
    @ConditionalOnMissingBean
    public IfmapUiController ifmapUiController(IfmapAdminProperties properties) {
        return new IfmapUiController(properties.normalizedBasePath());
    }

    @Bean
    @ConditionalOnMissingBean
    public IfmapConfigAdminController ifmapConfigAdminController(ConfigAdminService service) {
        return new IfmapConfigAdminController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    public IfmapBranchAdminController ifmapBranchAdminController(ConfigAdminService service) {
        return new IfmapBranchAdminController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    public IfmapMetaAdminController ifmapMetaAdminController(ConfigAdminService service, RuleRegistry rules,
                                                             StrategyHookRegistry strategyHooks,
                                                             FullParamStrategyRegistry fullParams,
                                                             LogicBranchStrategyRegistry logicBranches,
                                                             ActionRegistry actions,
                                                             CallbackRegistry callbacks,
                                                             IfmapEnumCatalog enums) {
        return new IfmapMetaAdminController(service, rules, strategyHooks, fullParams, logicBranches, actions,
                callbacks, enums);
    }

    @Bean
    @ConditionalOnMissingBean
    public IfmapAdminExceptionHandler ifmapAdminExceptionHandler() {
        return new IfmapAdminExceptionHandler();
    }
}
