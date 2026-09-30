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

import cn.cj.ifmap.admin.web.IfmapBranchAdminController;
import cn.cj.ifmap.admin.web.IfmapConfigAdminController;
import cn.cj.ifmap.admin.web.IfmapMetaAdminController;
import cn.cj.ifmap.jdbc.JdbcConfigHistoryRepository;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;
import cn.cj.ifmap.spring.IfmapAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端装配门控：默认关闭、缺 DataSource 不装配、开启后全量可用。
 *
 * @author caijun
 */
class IfmapAdminAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class, WebMvcAutoConfiguration.class,
                    IfmapAutoConfiguration.class, IfmapAdminAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:ifmap_admin_autoconfig;MODE=MySQL;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=");

    @Test
    @DisplayName("默认关闭：一个管理端 bean 都不装配（能改线上配置的端点必须显式打开）")
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ConfigAdminService.class);
            assertThat(context).doesNotHaveBean(IfmapConfigAdminController.class);
            assertThat(context).doesNotHaveBean(IfmapAdminExceptionHandler.class);
        });
    }

    @Test
    @DisplayName("显式打开：service / 三个控制器 / 异常处理 / 前缀配置器齐全，且自带 JDBC 仓储")
    void enabledRegistersEverything() {
        runner.withPropertyValues("ifmap.admin.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(ConfigAdminService.class);
            assertThat(context).hasSingleBean(IfmapConfigAdminController.class);
            assertThat(context).hasSingleBean(IfmapBranchAdminController.class);
            assertThat(context).hasSingleBean(IfmapMetaAdminController.class);
            assertThat(context).hasSingleBean(IfmapAdminExceptionHandler.class);
            assertThat(context).hasSingleBean(IfmapAdminWebConfigurer.class);
            assertThat(context).hasSingleBean(AdminConfigRepository.class);
            assertThat(context).hasSingleBean(JdbcConfigHistoryRepository.class);
            // 管理端自己的仓储是"裸 JDBC"视图；同时不能污染引擎侧的 ConfigRepository 唯一性
            assertThat(context.getBean(AdminConfigRepository.class).get())
                    .isInstanceOf(JdbcConfigRepository.class);
            assertThat(context.getBeanNamesForType(cn.cj.ifmap.core.config.ConfigRepository.class)).hasSize(1);
        });
    }

    @Test
    @DisplayName("base-path 可配置，规范化后前后都带 /")
    void basePathIsConfigurable() {
        runner.withPropertyValues("ifmap.admin.enabled=true", "ifmap.admin.base-path=ops/ifmap").run(context -> {
            IfmapAdminProperties properties = context.getBean(IfmapAdminProperties.class);
            assertThat(properties.normalizedBasePath()).isEqualTo("/ops/ifmap");
            assertThat(context.getBean(IfmapAdminWebConfigurer.class)).isNotNull();
        });
    }

    @Test
    @DisplayName("开启但没 Web 容器（非 servlet）→ 不装配，避免非 web 应用起不来")
    void noServletEnvironment() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, IfmapAutoConfiguration.class,
                        IfmapAdminAutoConfiguration.class))
                .withPropertyValues(
                        "ifmap.admin.enabled=true",
                        "spring.datasource.url=jdbc:h2:mem:ifmap_admin_noweb;MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=")
                .run(context -> assertThat(context).doesNotHaveBean(ConfigAdminService.class));
    }

    @Test
    @DisplayName("宿主自定义 ConfigAdminService → 管理端让位（@ConditionalOnMissingBean）")
    void hostBeanWins() {
        runner.withPropertyValues("ifmap.admin.enabled=true")
                .withUserConfiguration(CustomServiceConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(IfmapAdminProperties.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomServiceConfiguration {

        @Bean
        ConfigAdminService customService(
                cn.cj.ifmap.jdbc.JdbcConfigWriter writer, AdminConfigRepository repository,
                JdbcConfigHistoryRepository historyRepository,
                cn.cj.ifmap.core.config.ConfigRepository engineRepository) {
            return new ConfigAdminService(writer, repository.get(), historyRepository,
                    new ConfigSnapshotMapper(new cn.cj.ifmap.json.jackson.JacksonJsonOps()),
                    null, null, null, null, new IfmapAdminProperties());
        }
    }
}
