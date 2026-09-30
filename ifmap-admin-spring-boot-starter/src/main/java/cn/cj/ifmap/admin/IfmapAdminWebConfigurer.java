package cn.cj.ifmap.admin;

import cn.cj.ifmap.admin.web.IfmapConfigAdminController;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
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

    String basePath() {
        return basePath;
    }
}
