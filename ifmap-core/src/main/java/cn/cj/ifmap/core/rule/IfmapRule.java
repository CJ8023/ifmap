package cn.cj.ifmap.core.rule;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个方法注册为模板可调用的规则（{@code @FUN(规则名, 参数...)}）。
 *
 * <p>用法与存量引擎的约定保持一致：注解写在 <b>方法</b> 上，一个类可以承载多个规则方法，
 * 同名方法即构成重载（按实参个数与类型解析）。</p>
 *
 * <pre>{@code
 * public class MyRules {
 *     @IfmapRule(value = "dateFormat", desc = "日期格式化", example = "@FUN(dateFormat,$.date,yyyy-MM-dd)")
 *     public String dateFormat(String value, String pattern) { ... }
 * }
 * }</pre>
 *
 * <p>方法若把 {@link RuleContext} 声明为<b>第一个参数</b>，调用时由框架自动注入，模板里不用写。</p>
 *
 * @author caijun
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IfmapRule {

    /** 规则名，即 {@code @FUN(name,...)} 里的 name。 */
    String value();

    /** 说明，用于生成规则清单文档。 */
    String desc() default "";

    /** 示例，用于生成规则清单文档。 */
    String example() default "";

    /**
     * 是否覆盖同名规则。
     *
     * <p>默认 false：同名规则再注册会直接抛异常（防止不同来源的规则静默互相覆盖）。
     * 宿主需要替换内置实现时显式置 true。</p>
     */
    boolean override() default false;

    /**
     * 是否允许实际参数为 null。
     *
     * <p>默认 false：参数取不到值时交给 {@code NullPolicy} 处理
     * （默认省略该字段），<b>不会</b>再把 {@code @FUN(...)} 模板原文写进报文。
     * 像 {@code strDefault(v, def)} 这类规则需要显式置 true。</p>
     */
    boolean allowNullArgs() default false;
}
