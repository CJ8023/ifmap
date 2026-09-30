package cn.cj.ifmap.core.spi;

import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;

/**
 * 银行回调 SPI：替换存量 {@code IfmapConfigApplication} 里的 17 个回调方法。
 *
 * <p>按 {@code interfaceNo} 注册（见 {@code CallbackRegistry}），引擎值此一个入口。</p>
 *
 * @author caijun
 */
public interface IfmapCallbackHandler {

    /** 处理回调；返回值表示处理结果（宿主机自定义语义）。 */
    IfmapResult handle(IfmapRequest request, String rawBody);
}
