package cn.cj.ifmap.core.util;

import cn.cj.ifmap.core.exception.RuleArgumentException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 内部文本/数值小工具（不对外承诺兼容性）。
 *
 * @author caijun
 */
public final class Text {

    private Text() {
    }

    public static boolean isEmpty(String s) {
        return s == null || s.length() == 0;
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().length() == 0;
    }

    /** null 安全取值：null 或空串时返回 def。 */
    public static String nvl(Object v, String def) {
        if (v == null) {
            return def;
        }
        String s = v instanceof String ? (String) v : String.valueOf(v);
        return s.length() == 0 ? def : s;
    }

    /** 去掉首尾空白；null/空白返回 null。 */
    public static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() == 0 ? null : t;
    }

    public static boolean isNumeric(String s) {
        if (isBlank(s)) {
            return false;
        }
        try {
            new BigDecimal(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 转 BigDecimal，非数值抛 {@link RuleArgumentException}。 */
    public static BigDecimal toDecimal(Object v, String what) {
        if (v == null) {
            throw new RuleArgumentException(what + " 为空，无法转换为数值");
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Number) {
            return new BigDecimal(v.toString());
        }
        String s = String.valueOf(v).trim();
        if (s.length() == 0) {
            throw new RuleArgumentException(what + " 为空字符串，无法转换为数值");
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new RuleArgumentException(what + " 不是合法数值：" + v);
        }
    }

    /** 把未知对象规整为 List（null -> 空 List，List -> 展平一层，其他 -> 单元素 List）。 */
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object v) {
        List<Object> list = new ArrayList<Object>();
        if (v == null) {
            return list;
        }
        if (v instanceof List) {
            for (Object e : (List<Object>) v) {
                if (e instanceof Collection && !(e instanceof List)) {
                    list.addAll((Collection<Object>) e);
                } else {
                    list.add(e);
                }
            }
            return list;
        }
        if (v instanceof Object[]) {
            for (Object e : (Object[]) v) {
                list.add(e);
            }
            return list;
        }
        list.add(v);
        return list;
    }

    /** 是否为结构化值（Map/List）。 */
    public static boolean isStructured(Object v) {
        return v instanceof Map || v instanceof List;
    }

    public static String join(Collection<?> items, String separator) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                sb.append(separator);
            }
            sb.append(item == null ? "" : String.valueOf(item));
            first = false;
        }
        return sb.toString();
    }
}
