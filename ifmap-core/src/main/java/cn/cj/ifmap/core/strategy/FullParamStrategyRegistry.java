package cn.cj.ifmap.core.strategy;

import cn.cj.ifmap.core.util.Annotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主参数组包注册表：按 {@code @FullParam(bankCode, busiNode)} 注册，支持通配。
 *
 * <p>匹配优先级：精确 &gt; (bank,*) &gt; (*,busiNode) &gt; (*,*)。命中多个同优先级不同 key 时
 * 保留先注册者并记录歧义。</p>
 *
 * @author caijun
 */
public final class FullParamStrategyRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(FullParamStrategyRegistry.class);

    /** 通配符。 */
    public static final String WILDCARD = "*";

    private final Map<String, FullParamStrategy> strategies = new LinkedHashMap<String, FullParamStrategy>();
    private final Map<String, List<String>> conflicts = new LinkedHashMap<String, List<String>>();

    /** 按类上的 {@link FullParam} 注解注册。 */
    public void register(FullParamStrategy strategy) {
        if (strategy == null) {
            return;
        }
        FullParam annotation = Annotations.find(strategy.getClass(), FullParam.class);
        String bankCode = annotation == null ? WILDCARD : annotation.bankCode();
        String busiNode = annotation == null ? WILDCARD : annotation.busiNode();
        register(bankCode, busiNode, strategy);
    }

    public void register(String bankCode, String busiNode, FullParamStrategy strategy) {
        if (strategy == null) {
            return;
        }
        String key = key(bankCode, busiNode);
        FullParamStrategy exist = strategies.get(key);
        if (exist == null) {
            strategies.put(key, strategy);
            return;
        }
        if (exist != strategy) {
            List<String> owners = conflicts.get(key);
            if (owners == null) {
                owners = new ArrayList<String>();
                conflicts.put(key, owners);
            }
            if (!owners.contains(strategy.getClass().getName())) {
                owners.add(strategy.getClass().getName());
            }
            LOG.warn("ifmap 主参数组包注册歧义：key={} 保留先注册者 {}", key, owners);
        }
    }

    /** 按优先级查找；找不到返回 null（引擎按"无组包"继续，不报错）。 */
    public FullParamStrategy lookup(String bankCode, String busiNode) {
        String[] order = new String[] {
                key(bankCode, busiNode),
                key(bankCode, WILDCARD),
                key(WILDCARD, busiNode),
                key(WILDCARD, WILDCARD)
        };
        for (String candidate : order) {
            FullParamStrategy strategy = strategies.get(candidate);
            if (strategy != null) {
                return strategy;
            }
        }
        return null;
    }

    /** 执行组包，返回结果（无策略或返回 null 时为空 Map）。 */
    public Map<String, Object> assemble(String bankCode, String busiNode, StrategyContext context) {
        FullParamStrategy strategy = lookup(bankCode, busiNode);
        if (strategy == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = strategy.assemble(context);
        return result == null ? Collections.<String, Object>emptyMap() : result;
    }

    public boolean contains(String bankCode, String busiNode) {
        return lookup(bankCode, busiNode) != null;
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(strategies.keySet());
    }

    public int size() {
        return strategies.size();
    }

    public Map<String, List<String>> getConflicts() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, List<String>>(conflicts));
    }

    /** 组合键：{@code bankCode|busiNode}（空值视作通配）。 */
    public static String key(String bankCode, String busiNode) {
        return normalize(bankCode) + '|' + normalize(busiNode);
    }

    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return WILDCARD;
        }
        return value.trim();
    }
}
