package cn.cj.ifmap.core.spi;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认脱敏实现：纯正则、零依赖、不解析 JSON（避免为了脱敏再引入一个 JSON 库）。
 *
 * <p>处理三件事：</p>
 * <ol>
 *   <li><b>字段名命中</b>（{@code maskFields}）：值保留首字符，其余打星（姓名、地址…）；</li>
 *   <li><b>值形态命中</b>：手机号 {@code 1[3-9]\d{9}}、身份证 18 位（末位可为 X）、
 *       银行卡 13~19 位纯数字 → 按国家规范保留前 6 后 4（手机号保留前 3 后 4）；</li>
 *   <li><b>排除字段</b>（{@code excludeFields}）：值整体替换为 {@code ***}，<b>不落库</b>。</li>
 * </ol>
 *
 * <p>只处理"键值对"形态（含不加引号的数字），不对裸字符串做全局替换，
 * 避免把业务报文里的金额、流水号误伤成证件号。</p>
 *
 * @author caijun
 */
public class DefaultLogMasker implements LogMasker {

    /**
     * 默认需要按字段名脱敏的字段（姓名/称谓类，与审计文档的 NAME 语义一致）。
     *
     * <p>手机号、证件号、卡号走<b>值形态</b>识别（见 {@link #maskByPattern}），不在此列 ——
     * 否则 {@code mobile} 这类字段名会按姓名规则脱敏，得到 {@code 1**********} 这种无意义结果。</p>
     */
    public static final Set<String> DEFAULT_MASK_FIELDS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "acctName", "accountName", "certName", "userName", "realName",
                    "legalName", "legalPerson", "contactName")));

    private static final Pattern PAIR = Pattern.compile(
            "\"([A-Za-z0-9_\\-]+)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?|null|true|false)");

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[0-9Xx]");
    private static final Pattern BANK_CARD = Pattern.compile("\\d{13,19}");

    private final Set<String> maskFields;
    private final Set<String> excludeFields;

    public DefaultLogMasker() {
        this(DEFAULT_MASK_FIELDS, Collections.<String>emptySet());
    }

    /**
     * @param maskFields    需要按字段名脱敏的字段（<b>空集合 = 用内置默认</b>；希望完全关闭请自定义
     *                      {@link LogMasker} bean）
     * @param excludeFields 这些字段整体替换为 {@code ***}
     */
    public DefaultLogMasker(Set<String> maskFields, Set<String> excludeFields) {
        this.maskFields = toLowerSet(maskFields == null || maskFields.isEmpty() ? DEFAULT_MASK_FIELDS : maskFields);
        this.excludeFields = toLowerSet(excludeFields);
    }

    @Override
    public String mask(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        Matcher matcher = PAIR.matcher(json);
        StringBuffer out = new StringBuffer(json.length());
        while (matcher.find()) {
            String key = matcher.group(1);
            String rawValue = matcher.group(2);
            String masked = maskPair(key, rawValue);
            matcher.appendReplacement(out, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 处理单个键值对，返回替换后的整段文本（含键与引号）。 */
    private String maskPair(String key, String rawValue) {
        boolean quoted = rawValue.length() >= 2 && rawValue.charAt(0) == '"';
        String value = quoted ? rawValue.substring(1, rawValue.length() - 1) : rawValue;
        String lowerKey = key.toLowerCase();

        if (excludeFields.contains(lowerKey)) {
            return quote(key) + ":\"***\"";
        }
        String masked = null;
        if (maskFields.contains(lowerKey) && isPlain(value)) {
            masked = maskName(value);
        } else if (isPlain(value)) {
            masked = maskByPattern(value);
        }
        if (masked == null) {
            return quote(key) + ":" + rawValue;
        }
        return quote(key) + ":" + quote(masked);
    }

    /** 值无转义字符才做形态识别（带转义的字符串可能是嵌套 JSON，不猜）。 */
    private static boolean isPlain(String value) {
        return value.indexOf('\\') < 0 && value.indexOf('"') < 0;
    }

    private static String maskByPattern(String value) {
        if (ID_CARD.matcher(value).matches()) {
            return headTail(value, 6, 4);
        }
        if (PHONE.matcher(value).matches()) {
            return headTail(value, 3, 4);
        }
        if (BANK_CARD.matcher(value).matches()) {
            return headTail(value, 6, 4);
        }
        return null;
    }

    /** 姓名类：保留首字符，其余打星（单字符原样返回）。 */
    public static String maskName(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        sb.append(value.charAt(0));
        for (int i = 1; i < value.length(); i++) {
            sb.append('*');
        }
        return sb.toString();
    }

    /** 保留前 head 后 tail，中间打星；长度不足时整体打星。 */
    public static String headTail(String value, int head, int tail) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() <= head + tail) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                sb.append('*');
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder(value.length());
        sb.append(value, 0, head);
        for (int i = 0; i < value.length() - head - tail; i++) {
            sb.append('*');
        }
        sb.append(value, value.length() - tail, value.length());
        return sb.toString();
    }

    private static String quote(String value) {
        return '"' + value + '"';
    }

    private static Set<String> toLowerSet(Set<String> source) {
        Set<String> result = new HashSet<String>();
        if (source != null) {
            for (String item : source) {
                if (item != null && !item.trim().isEmpty()) {
                    result.add(item.trim().toLowerCase());
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
