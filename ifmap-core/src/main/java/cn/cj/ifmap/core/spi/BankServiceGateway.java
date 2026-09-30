package cn.cj.ifmap.core.spi;

import cn.cj.ifmap.core.model.BankCall;

/**
 * 出网调用 SPI：由宿主机实现（各银行 SDK / HTTP 客户端 / 加解密都在这里）。
 *
 * <p>引擎<b>不做</b>任何网络与加密（设计 §2.3「明确不做的事」）。实现方应把
 * IO/超时/非 2xx 包装成 {@link cn.cj.ifmap.core.exception.IfmapRemoteException}（可重试）。</p>
 *
 * @author caijun
 */
public interface BankServiceGateway {

    /**
     * 发起调用并返回响应原文（JSON 文本）。
     *
     * @param call 本次调用入参（配置 + 上下文 + 渲染后的报文）
     * @return 响应原文，允许为 null（表示无响应体）
     */
    String exchange(BankCall call);
}
