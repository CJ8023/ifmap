package cn.cj.ifmap.core.exception;

/**
 * 策略/动作相关异常：策略未注册、动作缺失、注册歧义等。
 *
 * <p>对应设计 §7.6：<b>不再静默</b> —— 存量引擎里"策略找不到就跳过"的行为被取消。</p>
 *
 * @author caijun
 */
public class IfmapStrategyException extends IfmapException {

    private static final long serialVersionUID = 1L;

    public IfmapStrategyException(String message) {
        super(message);
    }

    public IfmapStrategyException(String message, Throwable cause) {
        super(message, cause);
    }
}
