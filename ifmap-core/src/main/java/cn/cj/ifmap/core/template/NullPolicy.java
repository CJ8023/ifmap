package cn.cj.ifmap.core.template;

/**
 * 取值为空时的处理策略。
 *
 * <p>存量引擎在取不到值时会<b>把模板原文写进报文</b>（例如报文字段出现
 * {@code "@FUN(farmatDate,$.dueDate,yyyy-MM-dd)"} 字面量），本枚举用于显式定义正确行为。</p>
 *
 * @author caijun
 */
public enum NullPolicy {

    /** 省略该字段（默认）。 */
    SKIP_FIELD,

    /** 写入空字符串。 */
    EMPTY_STRING,

    /** 直接失败，便于提前暴露配置错误。 */
    FAIL
}
