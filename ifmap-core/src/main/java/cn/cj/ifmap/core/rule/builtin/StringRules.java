package cn.cj.ifmap.core.rule.builtin;

import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.util.Text;

/**
 * 内置字符串规则。
 *
 * @author caijun
 */
public class StringRules {

    /** 多值拼接：null 按空串处理。 */
    @IfmapRule(value = "concat", allowNullArgs = true,
            desc = "多值拼接（null 按空串）",
            example = "@FUN(concat,$.province,$.city,$.district)")
    public String concat(Object... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            if (part != null) {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /** 空值兜底：null 或空串时返回默认值。 */
    @IfmapRule(value = "strDefault", allowNullArgs = true,
            desc = "空值兜底（null 或空串时返回默认值）",
            example = "@FUN(strDefault,$.remark,无)")
    public String strDefault(String value, String defaultValue) {
        return Text.isEmpty(value) ? defaultValue : value;
    }

    /** 截断：超长直接截断。 */
    @IfmapRule(value = "strTruncate",
            desc = "按最大长度截断",
            example = "@FUN(strTruncate,$.remark,50)")
    public String strTruncate(String value, Integer maxLength) {
        return strTruncate(value, maxLength, "");
    }

    /** 截断：超长截断并追加后缀（如省略号）。 */
    @IfmapRule(value = "strTruncate",
            desc = "按最大长度截断并追加后缀",
            example = "@FUN(strTruncate,$.remark,50,...)")
    public String strTruncate(String value, Integer maxLength, String suffix) {
        if (value == null) {
            return null;
        }
        int max = maxLength == null || maxLength <= 0 ? value.length() : maxLength;
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + (suffix == null ? "" : suffix);
    }

    /** 脱敏：按类型选择内置规则。 */
    @IfmapRule(value = "strMask",
            desc = "脱敏，type 取 ID_CARD / MOBILE / BANK_CARD / NAME / EMAIL",
            example = "@FUN(strMask,$.idCard,ID_CARD)")
    public String strMask(String value, String type) {
        if (Text.isEmpty(value) || Text.isEmpty(type)) {
            return value;
        }
        String t = type.trim().toUpperCase();
        if ("ID_CARD".equals(t)) {
            return strMask(value, t, 4, 2);
        }
        if ("MOBILE".equals(t)) {
            return strMask(value, t, 3, 2);
        }
        if ("BANK_CARD".equals(t)) {
            return strMask(value, t, 4, 3);
        }
        if ("NAME".equals(t)) {
            return strMask(value, t, 1, 0);
        }
        if ("EMAIL".equals(t)) {
            int at = value.indexOf('@');
            if (at <= 1) {
                return value;
            }
            return value.substring(0, 1) + repeat('*', at - 1) + value.substring(at);
        }
        return value;
    }

    /** 脱敏：保留头尾、中间打星。 */
    @IfmapRule(value = "strMask",
            desc = "脱敏，保留头 keepPrefix 位与尾 keepSuffix 位",
            example = "@FUN(strMask,$.bankCard,BANK_CARD,4,3)")
    public String strMask(String value, String type, Integer keepPrefix, Integer keepSuffix) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 1) {
            return value;
        }
        int prefix = keepPrefix == null || keepPrefix < 0 ? 0 : keepPrefix;
        int suffix = keepSuffix == null || keepSuffix < 0 ? 0 : keepSuffix;
        if (prefix + suffix >= value.length()) {
            return value;
        }
        return value.substring(0, prefix)
                + repeat('*', value.length() - prefix - suffix)
                + value.substring(value.length() - suffix);
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
