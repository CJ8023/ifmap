/*
 * Copyright 2026 caijun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 * 主参数组包注册表：按 {@code @FullParam(partnerCode, busiNode)} 注册，支持通配。
 *
 * <p>匹配优先级：精确 &gt; (partner,*) &gt; (*,busiNode) &gt; (*,*)。命中多个同优先级不同 key 时
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
        String partnerCode = annotation == null ? WILDCARD : annotation.partnerCode();
        String busiNode = annotation == null ? WILDCARD : annotation.busiNode();
        register(partnerCode, busiNode, strategy);
    }

    public void register(String partnerCode, String busiNode, FullParamStrategy strategy) {
        if (strategy == null) {
            return;
        }
        String key = key(partnerCode, busiNode);
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
    public FullParamStrategy lookup(String partnerCode, String busiNode) {
        String[] order = new String[] {
                key(partnerCode, busiNode),
                key(partnerCode, WILDCARD),
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
    public Map<String, Object> assemble(String partnerCode, String busiNode, StrategyContext context) {
        FullParamStrategy strategy = lookup(partnerCode, busiNode);
        if (strategy == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = strategy.assemble(context);
        return result == null ? Collections.<String, Object>emptyMap() : result;
    }

    public boolean contains(String partnerCode, String busiNode) {
        return lookup(partnerCode, busiNode) != null;
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

    /** 组合键：{@code partnerCode|busiNode}（空值视作通配）。 */
    public static String key(String partnerCode, String busiNode) {
        return normalize(partnerCode) + '|' + normalize(busiNode);
    }

    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return WILDCARD;
        }
        return value.trim();
    }
}
