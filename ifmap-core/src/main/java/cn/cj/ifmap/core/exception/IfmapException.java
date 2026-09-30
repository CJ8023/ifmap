package cn.cj.ifmap.core.exception;

/**
 * ifmap 基础异常（unchecked）。
 *
 * <p>所有 ifmap 抛出的异常都继承本类，便于宿主统一兜底。</p>
 *
 * @author caijun
 */
public class IfmapException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IfmapException(String message) {
        super(message);
    }

    public IfmapException(String message, Throwable cause) {
        super(message, cause);
    }
}
