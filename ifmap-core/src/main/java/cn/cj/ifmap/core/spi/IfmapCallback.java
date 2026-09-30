package cn.cj.ifmap.core.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 回调处理器声明的接口号（对齐 {@code ifmap_config.interface_no}）。
 *
 * <pre>{@code
 * @IfmapCallback("CB_CMB_LOAN")
 * public class CmbLoanCallback implements IfmapCallbackHandler { ... }
 * }</pre>
 *
 * @author caijun
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IfmapCallback {

    /** 接口号，必填。 */
    String value();
}
