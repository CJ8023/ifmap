package cn.cj.ifmap.core.exception;

/**
 * 配置/模板错误：模板语法错误、规则名非法、空值策略为 FAIL 时取值为空等。
 *
 * @author caijun
 */
public class IfmapConfigException extends IfmapException {

    private static final long serialVersionUID = 1L;

    public IfmapConfigException(String message) {
        super(message);
    }

    public IfmapConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
