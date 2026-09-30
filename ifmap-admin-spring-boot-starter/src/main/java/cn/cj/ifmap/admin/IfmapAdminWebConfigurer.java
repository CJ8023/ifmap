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

import cn.cj.ifmap.admin.web.IfmapConfigAdminController;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.method.HandlerTypePredicate;

/**
 * 给所有管理端 Controller 统一挂路径前缀（{@code ifmap.admin.base-path}）。
 *
 * <p>为什么用 {@code addPathPrefix} 而不是在每个 {@code @RequestMapping} 里写死：
 * 前缀是可配置的，而注解是编译期常量；这里统一加前缀可以让"改前缀"只改配置。</p>
 *
 * @author caijun
 */
public class IfmapAdminWebConfigurer implements WebMvcConfigurer {

    /** 静态页面的 classpath 位置（打包进 jar，不依赖外部 CDN 或宿主机静态资源目录）。 */
    static final String UI_LOCATION = "classpath:/META-INF/ifmap-admin-ui/";

    private final String basePath;

    public IfmapAdminWebConfigurer(String basePath) {
        this.basePath = basePath;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        if (basePath == null || basePath.isEmpty()) {
            return;
        }
        configurer.addPathPrefix(basePath,
                HandlerTypePredicate.forBasePackageClass(IfmapConfigAdminController.class));
        // 保持 Spring Boot 3 的 PathPattern 解析器（更严格、更快）；此处显式声明以免被宿主覆盖成 AntPathMatcher
        configurer.setPatternParser(new PathPatternParser());
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 只挂 /ui/**，前缀与 Controller 一致；关缓存是为了让升级 ifmap 后页面立刻是新的
        // （管理端本来就是低频访问，不需要缓存优化，反而最怕"看到的是上个版本的页面"）
        registry.addResourceHandler(uiPathPattern())
                .addResourceLocations(UI_LOCATION)
                .setCacheControl(CacheControl.noCache());
    }

    /** 静态资源路径：{@code {base-path}/ui/**}（前缀为空时退化成 {@code /ui/**}）。 */
    String uiPathPattern() {
        return (basePath == null ? "" : basePath) + "/ui/**";
    }

    String basePath() {
        return basePath;
    }
}
