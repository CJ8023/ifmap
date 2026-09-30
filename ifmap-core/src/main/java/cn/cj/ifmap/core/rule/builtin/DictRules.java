package cn.cj.ifmap.core.rule.builtin;

import cn.cj.ifmap.core.exception.RuleArgumentException;
import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.util.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置码值翻译规则。
 *
 * <p>表达式的设计意图是<b>让码值映射全部配置化</b>，从而不需要为每家机构各写一个 Java 方法：</p>
 * <pre>
 *   @FUN(dictVal,$.invoiceType,01:增值税专用发票;02:增值税普通发票)
 * </pre>
 *
 * <p>格式约定：</p>
 * <ul>
 *   <li>映射对之间用半角分号 {@code ;} 分隔；</li>
 *   <li>键与值之间优先用半角冒号 {@code :} 分隔（兼容历史写法 {@code -}，按<b>首个</b>分隔符切分，
 *       因此值里含 {@code -} 不会被截断）；</li>
 *   <li>因为 {@code @FUN} 的参数按半角逗号切分，表达式里<b>不能出现逗号</b>；</li>
 *   <li>键是字符串精确匹配（不做 trim 之外的归一化）。</li>
 * </ul>
 *
 * @author caijun
 */
public class DictRules {

    /** 单值码值翻译。 */
    @IfmapRule(value = "dictVal", allowNullArgs = true,
            desc = "单值码值翻译，表达式形如 01:待处理;02:已处理",
            example = "@FUN(dictVal,$.status,01:待处理;02:已处理)")
    public String dictVal(String value, String expression) {
        return dictVal(value, expression, null);
    }

    /** 单值码值翻译（带兜底值）。 */
    @IfmapRule(value = "dictVal", allowNullArgs = true,
            desc = "单值码值翻译，未命中时返回默认值",
            example = "@FUN(dictVal,$.status,01:待处理,未知)")
    public String dictVal(String value, String expression, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        Map<String, String> dict = parse(expression);
        String key = String.valueOf(value);
        String mapped = dict.get(key.trim());
        return mapped == null ? defaultValue : mapped;
    }

    /** 数组码值翻译。 */
    @IfmapRule(value = "dictValArray", allowNullArgs = true,
            desc = "数组码值翻译，逐元素翻译并保持原顺序",
            example = "@FUN(dictValArray,$.invoiceTypes,01:专票;02:普票)")
    public List<Object> dictValArray(List<?> values, String expression) {
        return dictValArray(values, expression, null);
    }

    /** 数组码值翻译（带兜底值）。 */
    @IfmapRule(value = "dictValArray", allowNullArgs = true,
            desc = "数组码值翻译，未命中时返回默认值",
            example = "@FUN(dictValArray,$.invoiceTypes,01:专票,其他)")
    public List<Object> dictValArray(List<?> values, String expression, String defaultValue) {
        List<Object> out = new ArrayList<Object>();
        if (values == null) {
            return out;
        }
        Map<String, String> dict = parse(expression);
        for (Object item : values) {
            if (item == null) {
                continue;
            }
            String mapped = dict.get(String.valueOf(item).trim());
            out.add(mapped == null ? defaultValue : mapped);
        }
        return out;
    }

    /** 解析表达式为映射表。 */
    static Map<String, String> parse(String expression) {
        if (Text.isBlank(expression)) {
            throw new RuleArgumentException("码值表达式为空，应形如 01:待处理;02:已处理");
        }
        Map<String, String> dict = new LinkedHashMap<String, String>();
        for (String pair : expression.split(";")) {
            String item = pair.trim();
            if (item.length() == 0) {
                continue;
            }
            int idx = item.indexOf(':');
            if (idx < 0) {
                idx = item.indexOf('-');
            }
            if (idx <= 0) {
                throw new RuleArgumentException("码值表达式片段 [" + item + "] 非法，应为 键:值 形式");
            }
            String key = item.substring(0, idx).trim();
            String value = item.substring(idx + 1).trim();
            if (key.length() == 0) {
                throw new RuleArgumentException("码值表达式片段 [" + item + "] 的键为空");
            }
            dict.put(key, value);
        }
        if (dict.isEmpty()) {
            throw new RuleArgumentException("码值表达式 [" + expression + "] 未解析出任何映射对");
        }
        return dict;
    }
}
