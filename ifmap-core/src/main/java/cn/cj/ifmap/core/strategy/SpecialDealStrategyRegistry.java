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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 特殊处理策略注册表：<b>三 key</b> 注册，直接解决存量 P0-7（配置里写的是 bean 名、
 * 代码里改过类名/包名 → 线上"策略找不到"静默跳过）。
 *
 * <pre>
 * register("cmbSpecialDealStrategy", strategy)      // 权威 key：Spring bean 名
 *   → "cmbSpecialDealStrategy"                      // ① bean 名，精确命中，无告警
 *   → "CmbSpecialDealStrategy"                      // ② 类简单名，命中记 WARN（宽松兜底）
 *   → "cmbSpecialDealStrategy"                      // ③ 首字母小写名，命中记 WARN（人工配置）
 * </pre>
 *
 * <p>不同策略注册到同一 key 时<b>保留先注册者</b>并记录歧义（启动期可打印/断言）。</p>
 *
 * @author caijun
 */
public final class SpecialDealStrategyRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(SpecialDealStrategyRegistry.class);

    private final Map<String, SpecialDealStrategy> authoritative = new LinkedHashMap<String, SpecialDealStrategy>();
    private final Map<String, SpecialDealStrategy> aliases = new LinkedHashMap<String, SpecialDealStrategy>();
    private final Map<String, List<String>> conflicts = new LinkedHashMap<String, List<String>>();
    private final List<String> aliasHits = new ArrayList<String>();

    /**
     * 注册一个策略。
     *
     * @param beanName Spring bean 名（权威 key，可为 null —— 此时只用类名兜底）
     * @param strategy 策略实例
     */
    public void register(String beanName, SpecialDealStrategy strategy) {
        if (strategy == null) {
            return;
        }
        if (beanName != null && !beanName.isEmpty()) {
            put(authoritative, beanName, strategy, beanName);
        }
        String simpleName = strategy.getClass().getSimpleName();
        if (!simpleName.isEmpty()) {
            put(aliases, simpleName, strategy, simpleName);
            put(aliases, decapitalizeLoose(simpleName), strategy, simpleName);
        }
    }

    private void put(Map<String, SpecialDealStrategy> target, String key, SpecialDealStrategy strategy, String owner) {
        SpecialDealStrategy exist = target.get(key);
        if (exist == null) {
            target.put(key, strategy);
            return;
        }
        if (exist != strategy) {
            conflict(key, owner);
        }
    }

    private void conflict(String key, String owner) {
        List<String> owners = conflicts.get(key);
        if (owners == null) {
            owners = new ArrayList<String>();
            conflicts.put(key, owners);
        }
        if (!owners.contains(owner)) {
            owners.add(owner);
        }
        LOG.warn("ifmap 特殊处理策略注册歧义：key={} 已由 {} 占用，保留先注册者", key, owners);
    }

    /** 按名称查找；先精确（bean 名），再宽松（类简单名 / 首字母小写名）。找不到返回 null。 */
    public SpecialDealStrategy lookup(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        SpecialDealStrategy exact = authoritative.get(name);
        if (exact != null) {
            return exact;
        }
        SpecialDealStrategy loose = aliases.get(name);
        if (loose != null) {
            aliasHits.add(name);
            LOG.warn("ifmap 特殊处理策略 [{}] 命中宽松 key（建议把配置改为 Spring bean 名）", name);
            return loose;
        }
        return null;
    }

    public boolean contains(String name) {
        return name != null && (authoritative.containsKey(name) || aliases.containsKey(name));
    }

    /** 全部可查 key（含宽松 key），用于启动期审计与管理端展示。 */
    public Set<String> keys() {
        Set<String> all = new LinkedHashSet<String>();
        all.addAll(authoritative.keySet());
        all.addAll(aliases.keySet());
        return Collections.unmodifiableSet(all);
    }

    public int size() {
        return authoritative.size();
    }

    /** 命中过宽松 key 的记录（启动期审计报表用）。 */
    public List<String> getAliasHits() {
        return Collections.unmodifiableList(new ArrayList<String>(aliasHits));
    }

    /** 注册歧义：key → 涉及方。 */
    public Map<String, List<String>> getConflicts() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, List<String>>(conflicts));
    }

    /** 首字母小写（只动第一个字符，与 Spring 的 decapitalize 宽松版一致）。 */
    public static String decapitalizeLoose(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
