package cn.cj.ifmap.core.strategy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个逻辑分支动作的注册 key：{@code @IfmapAction("repay")} 实现类。
 *
 * <p>{@code value} 必须与 {@code ifmap_logic_branch_config.method_flag} 一致；
 * 注解写在类上，类需实现 {@link IfmapActionHandler}。</p>
 *
 * @author caijun
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IfmapAction {

    /** 注册 key（{@code method_flag}）。 */
    String value();
}
