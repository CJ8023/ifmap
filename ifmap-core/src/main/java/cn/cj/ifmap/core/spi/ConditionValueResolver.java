package cn.cj.ifmap.core.spi;

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.model.IfmapRequest;

/**
 * 条件参数解析 SPI：把存量代码里"某银行某节点硬编码取某值"的逻辑外置（P1-11）。
 *
 * <p>默认实现 {@link AttributeConditionValueResolver} 支持
 * {@code payload:x} / {@code attr:x} / {@code header:x} 三种前缀与裸键。</p>
 *
 * @author caijun
 */
public interface ConditionValueResolver {

    /** 解析条件值；无法解析返回 null。 */
    Object resolve(String condition, IfmapRequest request, IfmapConfig config);
}
