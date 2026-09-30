package cn.cj.ifmap.core.exception;

/**
 * 出网调用失败异常（可重试）。
 *
 * <p>宿主机的 {@code BankServiceGateway} 实现应把 IO/超时/非 2xx 包装成本异常，
 * 引擎据此区分"配置错"（不可重试）与"通道错"（可重试）。</p>
 *
 * @author caijun
 */
public class IfmapRemoteException extends IfmapException {

    private static final long serialVersionUID = 1L;

    public IfmapRemoteException(String message) {
        super(message);
    }

    public IfmapRemoteException(String message, Throwable cause) {
        super(message, cause);
    }
}
