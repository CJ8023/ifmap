package cn.cj.ifmap.core.strategy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明逻辑分支策略的注册 key（对应 {@code logic_branch_flag}）。
 *
 * @author caijun
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LogicBranch {

    /** 注册 key（{@code logic_branch_flag}）。 */
    String value();
}
