package cn.cj.ifmap.core.exception;

/**
 * 规则不存在。{@code @FUN(name,...)} 中的 name 未注册。
 *
 * <p>与存量引擎的关键差异：存量实现在规则不存在时静默返回 {@code null}，
 * 导致错误直到生产报文校验才暴露；ifmap 在<b>启动期</b>就抛出本异常。</p>
 *
 * @author caijun
 */
public class RuleNotFoundException extends IfmapConfigException {

    private static final long serialVersionUID = 1L;

    public RuleNotFoundException(String message) {
        super(message);
    }
}
