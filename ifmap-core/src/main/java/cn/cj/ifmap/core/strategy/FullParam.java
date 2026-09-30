package cn.cj.ifmap.core.strategy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明主参数组包策略的适用维度（与存量 {@code @FullParam} 语义一致）。
 *
 * <p>{@code bankCode} / {@code busiNode} 任一项写 {@code *} 或留空表示通配；
 * 匹配优先级：精确 &gt; (bank,*) &gt; (*,busiNode) &gt; (*,*)。</p>
 *
 * @author caijun
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FullParam {

    /** 银行编码，{@code *} 表示不限。 */
    String bankCode() default "*";

    /** 业务节点，{@code *} 表示不限。 */
    String busiNode() default "*";
}
