package cn.cj.ifmap.core.rule;

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.exception.RuleInvocationException;
import cn.cj.ifmap.core.exception.RuleNotFoundException;
import cn.cj.ifmap.core.exception.StartupValidationException;
import cn.cj.ifmap.core.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则注册表：注册、解析、调用规则。
 *
 * <p>关键行为：</p>
 * <ul>
 *   <li>同名规则默认视为<b>重载</b>（只拒绝重复签名）；要整体替换同名规则须显式
 *       {@code @IfmapRule(override = true)}，且覆盖者只能有一个；</li>
 *   <li>实参为 {@code List} 而没有任何候选可匹配时，自动把规则<b>逐元素映射</b>
 *       （解决存量引擎「有数组版、没有数组版就报错」的不对称）；</li>
 *   <li>{@link #assertRulesExist(Collection)} 供启动期校验，规则不存在直接失败。</li>
 * </ul>
 *
 * @author caijun
 */
public final class RuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuleRegistry.class);

    /** 自动逐元素映射的最大嵌套深度，防止异常数据导致无限递归。 */
    private static final int MAX_MAP_DEPTH = 3;

    private final Map<String, List<RuleMethod>> index = new LinkedHashMap<String, List<RuleMethod>>();
    private final Set<String> overridden = new LinkedHashSet<String>();
    private final Map<String, RuleMethod> resolutionCache = new ConcurrentHashMap<String, RuleMethod>();

    /** 注册一个承载规则方法的实例。 */
    public RuleRegistry register(Object bean) {
        if (bean == null) {
            throw new IfmapConfigException("规则实例不能为 null");
        }
        List<Method> annotated = new ArrayList<Method>();
        for (Method m : bean.getClass().getMethods()) {
            if (m.getAnnotation(IfmapRule.class) != null) {
                annotated.add(m);
            }
        }
        if (annotated.isEmpty()) {
            log.debug("类 {} 上没有任何 @IfmapRule 方法，跳过注册", bean.getClass().getName());
            return this;
        }
        // 排序保证注册顺序与解析结果稳定
        Collections.sort(annotated, new Comparator<Method>() {
            @Override
            public int compare(Method a, Method b) {
                int c = a.getName().compareTo(b.getName());
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(a.getParameterCount(), b.getParameterCount());
                if (c != 0) {
                    return c;
                }
                return a.toGenericString().compareTo(b.toGenericString());
            }
        });
        for (Method m : annotated) {
            register(bean, m);
        }
        return this;
    }

    /** 批量注册。 */
    public RuleRegistry registerAll(Object... beans) {
        if (beans != null) {
            for (Object bean : beans) {
                register(bean);
            }
        }
        return this;
    }

    private void register(Object bean, Method method) {
        IfmapRule ann = method.getAnnotation(IfmapRule.class);
        String name = ann.value() == null ? null : ann.value().trim();
        if (Text.isEmpty(name)) {
            throw new IfmapConfigException("规则名不能为空：" + method);
        }
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IfmapConfigException("规则名 [" + name + "] 非法：只允许字母、数字、下划线，且不能以数字开头（" + method + "）");
        }
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IfmapConfigException("规则方法不能是 static：" + method);
        }
        RuleMethod rule = new RuleMethod(bean, method, name);
        List<RuleMethod> exists = index.get(name);
        if (exists != null && !exists.isEmpty()) {
            if (ann.override()) {
                if (overridden.contains(name)) {
                    throw new IfmapConfigException("规则 [" + name + "] 存在多个 override=true 的实现，"
                            + "覆盖者只能有一个：" + exists.get(0).descriptor() + " 与 " + rule.descriptor());
                }
                log.info("规则 [{}] 被覆盖：{} -> {}", name, exists.get(0).descriptor(), rule.descriptor());
                index.put(name, newList(rule));
                overridden.add(name);
            } else if (overridden.contains(name)) {
                throw new IfmapConfigException("规则 [" + name + "] 已被 override 实现接管，"
                        + "普通实现不得再注册：" + rule.descriptor());
            } else {
                // 同名不同签名 = 合法重载
                for (RuleMethod exist : exists) {
                    if (exist.signatureKey().equals(rule.signatureKey())) {
                        throw new IfmapConfigException("规则签名重复 [" + name + "]：" + exist.descriptor()
                                + " 与 " + rule.descriptor() + "。如需覆盖，请设置 @IfmapRule(override = true)。");
                    }
                }
                exists.add(rule);
                log.debug("规则 [{}] 新增重载：[{}]", name, rule.signature());
            }
        } else {
            List<RuleMethod> list = new ArrayList<RuleMethod>();
            list.add(rule);
            index.put(name, list);
        }
        resolutionCache.clear();
    }

    private static List<RuleMethod> newList(RuleMethod rule) {
        List<RuleMethod> list = new ArrayList<RuleMethod>();
        list.add(rule);
        return list;
    }

    public boolean contains(String name) {
        return name != null && index.containsKey(name);
    }

    /** 已注册规则名（有序）。 */
    public Set<String> getRuleNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(index.keySet()));
    }

    /** 全部规则元信息（有序）。 */
    public List<RuleDescriptor> getDescriptors() {
        List<RuleDescriptor> all = new ArrayList<RuleDescriptor>();
        for (List<RuleMethod> list : index.values()) {
            for (RuleMethod m : list) {
                all.add(m.descriptor());
            }
        }
        return all;
    }

    /** 取规则元信息；不存在抛 {@link RuleNotFoundException}。 */
    public RuleDescriptor descriptor(String name) {
        List<RuleMethod> list = index.get(name);
        if (list == null || list.isEmpty()) {
            throw notFound(name);
        }
        RuleMethod first = list.get(0);
        boolean allowNull = false;
        for (RuleMethod m : list) {
            allowNull = allowNull || m.descriptor().allowsNullArgs();
        }
        RuleDescriptor d = first.descriptor();
        if (allowNull == d.allowsNullArgs()) {
            return d;
        }
        return new RuleDescriptor(d.getName(), d.getOwner(), d.getSignature(), d.getDesc(), d.getExample(),
                d.isOverride(), allowNull);
    }

    /** 调用规则。 */
    public Object invoke(String name, RuleContext context, Object... args) {
        return invoke(name, context, args == null ? new Object[0] : args, 0);
    }

    private Object invoke(String name, RuleContext context, Object[] args, int depth) {
        List<RuleMethod> candidates = index.get(name);
        if (candidates == null || candidates.isEmpty()) {
            throw notFound(name);
        }
        List<RuleMethod> matched = new ArrayList<RuleMethod>();
        for (RuleMethod m : candidates) {
            if (m.canInvoke(args)) {
                matched.add(m);
            }
        }
        if (matched.isEmpty()) {
            Object mapped = tryMapOverList(name, context, args, depth);
            if (mapped != NOT_MAPPED) {
                return mapped;
            }
            throw new RuleInvocationException("规则 [" + name + "] 无匹配方法：实参 " + describe(args)
                    + "；已注册签名 " + signatures(candidates));
        }
        if (matched.size() == 1) {
            return matched.get(0).invoke(context, args);
        }
        int best = Integer.MIN_VALUE;
        List<RuleMethod> bests = new ArrayList<RuleMethod>();
        for (RuleMethod m : matched) {
            int s = m.score(args);
            if (s > best) {
                best = s;
                bests.clear();
                bests.add(m);
            } else if (s == best) {
                bests.add(m);
            }
        }
        if (bests.size() == 1) {
            return bests.get(0).invoke(context, args);
        }
        throw new RuleInvocationException("规则 [" + name + "] 调用歧义：实参 " + describe(args)
                + " 同时匹配 " + signatures(bests) + "，请显式区分参数类型或重命名规则。");
    }

    private static final Object NOT_MAPPED = new Object();

    /** 实参里有 List 且无候选可匹配时，逐元素调用。 */
    private Object tryMapOverList(String name, RuleContext context, Object[] args, int depth) {
        if (depth >= MAX_MAP_DEPTH) {
            return NOT_MAPPED;
        }
        int listIndex = -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof List) {
                if (listIndex >= 0) {
                    return NOT_MAPPED;
                }
                listIndex = i;
            }
        }
        if (listIndex < 0) {
            return NOT_MAPPED;
        }
        List<?> items = (List<?>) args[listIndex];
        List<Object> out = new ArrayList<Object>(items.size());
        for (Object item : items) {
            Object[] copy = args.clone();
            copy[listIndex] = item;
            out.add(invoke(name, context, copy, depth + 1));
        }
        return out;
    }

    private static String describe(Object[] args) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object a = args[i];
            sb.append(a == null ? "null" : a.getClass().getSimpleName() + "=" + a);
        }
        return sb.append(')').toString();
    }

    private static String signatures(List<RuleMethod> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(list.get(i).signature());
        }
        return sb.append(']').toString();
    }

    private static RuleNotFoundException notFound(String name) {
        return new RuleNotFoundException("规则 [" + name + "] 不存在。请确认已注册该规则，"
                + "或检查模板中的 @FUN(" + name + ",...) 是否拼写错误。");
    }

    /**
     * 启动期校验：给定「模板标识 -> 模板原文」，检查模板里引用到的规则是否都已注册。
     *
     * @throws StartupValidationException 存在未注册规则
     */
    public void assertRulesExist(Map<String, String> templates) {
        Map<String, Set<String>> missing = new LinkedHashMap<String, Set<String>>();
        if (templates != null) {
            for (Map.Entry<String, String> e : templates.entrySet()) {
                Set<String> used = cn.cj.ifmap.core.template.TemplateScanner.ruleNames(e.getValue());
                Set<String> bad = new LinkedHashSet<String>();
                for (String rule : used) {
                    if (!contains(rule)) {
                        bad.add(rule);
                    }
                }
                if (!bad.isEmpty()) {
                    missing.put(e.getKey(), bad);
                }
            }
        }
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("模板中存在未注册的规则，已阻断启动：");
            for (Map.Entry<String, Set<String>> e : missing.entrySet()) {
                sb.append("\n  - ").append(e.getKey()).append(" -> ").append(e.getValue());
            }
            sb.append("\n已注册规则：").append(getRuleNames());
            throw new StartupValidationException(sb.toString(), missing);
        }
    }

    /** 启动期校验：直接给定规则名集合。 */
    public void assertRulesExist(Collection<String> ruleNames) {
        Set<String> bad = new LinkedHashSet<String>();
        if (ruleNames != null) {
            for (String rule : ruleNames) {
                if (!contains(rule)) {
                    bad.add(rule);
                }
            }
        }
        if (!bad.isEmpty()) {
            throw new StartupValidationException("存在未注册的规则：" + bad + "；已注册规则：" + getRuleNames(),
                    Collections.singletonMap("<inline>", bad));
        }
    }
}
