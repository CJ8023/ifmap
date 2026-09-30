package cn.cj.ifmap.core.rule.builtin;

import cn.cj.ifmap.core.exception.RuleArgumentException;
import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.util.Text;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内置集合规则。
 *
 * @author caijun
 */
public class ListRules {

    /** 列表拼接成字符串。 */
    @IfmapRule(value = "listJoin",
            desc = "列表拼接为字符串，null 元素按空串",
            example = "@FUN(listJoin,$.tags,/)")
    public String listJoin(List<?> values, String separator) {
        if (values == null) {
            return null;
        }
        return Text.join(values, separator == null ? "" : separator);
    }

    /** 列表加工（无参数版）。 */
    @IfmapRule(value = "listOp",
            desc = "列表加工，op 取 DISTINCT / SORT / SORT_DESC / REVERSE / MAX / MIN",
            example = "@FUN(listOp,$.tags,DISTINCT)")
    public Object listOp(List<?> values, String op) {
        return doOp(values, op, null);
    }

    /** 列表加工（带参数版，如 LIMIT:3）。 */
    @IfmapRule(value = "listOp",
            desc = "列表加工，op 取 DISTINCT / SORT / SORT_DESC / REVERSE / LIMIT / MAX / MIN",
            example = "@FUN(listOp,$.tags,LIMIT,3)")
    public Object listOp(List<?> values, String op, String arg) {
        return doOp(values, op, arg);
    }

    private Object doOp(List<?> values, String op, String arg) {
        if (values == null) {
            return null;
        }
        String o = Text.isBlank(op) ? "DISTINCT" : op.trim().toUpperCase();
        if ("DISTINCT".equals(o)) {
            Set<Object> set = new LinkedHashSet<Object>(values);
            return new ArrayList<Object>(set);
        }
        if ("REVERSE".equals(o)) {
            List<Object> copy = new ArrayList<Object>(values);
            Collections.reverse(copy);
            return copy;
        }
        if ("SORT".equals(o) || "SORT_DESC".equals(o)) {
            List<Object> copy = new ArrayList<Object>(values);
            Comparator<Object> comparator = new SmartComparator();
            Collections.sort(copy, "SORT_DESC".equals(o) ? Collections.reverseOrder(comparator) : comparator);
            return copy;
        }
        if ("LIMIT".equals(o)) {
            int limit = parseInt(arg, "listOp LIMIT 参数");
            if (limit < 0) {
                limit = 0;
            }
            return new ArrayList<Object>(values.subList(0, Math.min(limit, values.size())));
        }
        if ("MAX".equals(o) || "MIN".equals(o)) {
            Object best = null;
            Comparator<Object> comparator = new SmartComparator();
            for (Object item : values) {
                if (item == null) {
                    continue;
                }
                if (best == null) {
                    best = item;
                    continue;
                }
                int cmp = comparator.compare(item, best);
                if (("MAX".equals(o) && cmp > 0) || ("MIN".equals(o) && cmp < 0)) {
                    best = item;
                }
            }
            return best;
        }
        throw new RuleArgumentException("listOp 的 op [" + op + "] 不支持，"
                + "可用 DISTINCT / SORT / SORT_DESC / REVERSE / LIMIT / MAX / MIN");
    }

    private static int parseInt(String arg, String what) {
        if (Text.isBlank(arg)) {
            throw new RuleArgumentException(what + " 不能为空");
        }
        try {
            return Integer.parseInt(arg.trim());
        } catch (NumberFormatException e) {
            throw new RuleArgumentException(what + " 不是整数：" + arg);
        }
    }

    /** 数值优先、其次按字符串比较，避免不同类型直接比较抛异常。 */
    private static final class SmartComparator implements Comparator<Object> {

        @Override
        public int compare(Object a, Object b) {
            if (a == null && b == null) {
                return 0;
            }
            if (a == null) {
                return -1;
            }
            if (b == null) {
                return 1;
            }
            BigDecimal da = asDecimal(a);
            BigDecimal db = asDecimal(b);
            if (da != null && db != null) {
                return da.compareTo(db);
            }
            return String.valueOf(a).compareTo(String.valueOf(b));
        }

        private static BigDecimal asDecimal(Object value) {
            if (value instanceof Number) {
                return new BigDecimal(value.toString());
            }
            String s = String.valueOf(value).trim();
            return Text.isNumeric(s) ? new BigDecimal(s) : null;
        }
    }
}
