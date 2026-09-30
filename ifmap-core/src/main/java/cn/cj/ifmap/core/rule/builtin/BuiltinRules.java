package cn.cj.ifmap.core.rule.builtin;

import cn.cj.ifmap.core.rule.RuleRegistry;

/**
 * 内置规则集：18 个规则名 / 27 个方法（含重载与上下文注入版本）。
 *
 * <p>清单：</p>
 * <ul>
 *   <li>字符串：{@code concat} / {@code strDefault} / {@code strTruncate} / {@code strMask}</li>
 *   <li>码值：{@code dictVal} / {@code dictValArray}</li>
 *   <li>日期：{@code dateFormat} / {@code dateConvert} / {@code dateAdd} / {@code dateDiff}
 *       / {@code dateEndOfMonth} / {@code workdayAdd}</li>
 *   <li>数值：{@code numRound} / {@code numSum} / {@code numOffset} / {@code numFormat}</li>
 *   <li>集合：{@code listJoin} / {@code listOp}</li>
 * </ul>
 *
 * @author caijun
 */
public final class BuiltinRules {

    private BuiltinRules() {
    }

    /** 把内置规则注册进给定注册表。 */
    public static RuleRegistry registerTo(RuleRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        registry.registerAll(new StringRules(), new DictRules(), new DateRules(), new NumberRules(), new ListRules());
        return registry;
    }

    /** 新建一个只含内置规则的注册表。 */
    public static RuleRegistry newRegistry() {
        return registerTo(new RuleRegistry());
    }
}
