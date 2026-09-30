package cn.cj.ifmap.core.exception;

/**
 * 规则参数非法：表达式格式错误、日期无法解析、数值不可转换等。
 *
 * @author caijun
 */
public class RuleArgumentException extends IfmapException {

    private static final long serialVersionUID = 1L;

    public RuleArgumentException(String message) {
        super(message);
    }

    public RuleArgumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
