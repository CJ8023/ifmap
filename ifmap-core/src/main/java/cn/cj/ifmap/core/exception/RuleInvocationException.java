package cn.cj.ifmap.core.exception;

/**
 * 规则调用失败：参数不匹配、重载歧义、规则内部抛异常。
 *
 * @author caijun
 */
public class RuleInvocationException extends IfmapException {

    private static final long serialVersionUID = 1L;

    public RuleInvocationException(String message) {
        super(message);
    }

    public RuleInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
