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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 逻辑分支策略注册表：按 {@code @LogicBranch("flag")} 注册。
 *
 * <p>同一 flag 允许注册多个策略（按注册顺序判断），命中即算该分支成立（存量是反射方法名，
 * 现在是显式实现 + ActionRegistry 执行动作）。</p>
 *
 * @author caijun
 */
public final class LogicBranchStrategyRegistry {

    private final Map<String, List<LogicBranchStrategy>> strategies =
            new LinkedHashMap<String, List<LogicBranchStrategy>>();

    /** 按类上的 {@link LogicBranch} 注解注册。 */
    public void register(LogicBranchStrategy strategy) {
        if (strategy == null) {
            return;
        }
        LogicBranch annotation = Annotations.find(strategy.getClass(), LogicBranch.class);
        if (annotation == null) {
            throw new IllegalArgumentException("逻辑分支策略 " + strategy.getClass().getName()
                    + " 缺少 @LogicBranch 注解");
        }
        register(annotation.value(), strategy);
    }

    public void register(String key, LogicBranchStrategy strategy) {
        if (key == null || key.isEmpty() || strategy == null) {
            return;
        }
        List<LogicBranchStrategy> list = strategies.get(key);
        if (list == null) {
            list = new ArrayList<LogicBranchStrategy>();
            strategies.put(key, list);
        }
        list.add(strategy);
    }

    /** 该 flag 下是否有任一策略命中。 */
    public boolean matches(String key, StrategyContext context) {
        return matches(key, context, null);
    }

    /**
     * 该 flag 下是否有任一策略命中。
     *
     * @param hitRecorder 命中回调（可为 null），用于记录命中策略类名，便于日志与排错
     */
    public boolean matches(String key, StrategyContext context, HitRecorder hitRecorder) {
        List<LogicBranchStrategy> list = key == null ? null : strategies.get(key);
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (LogicBranchStrategy strategy : list) {
            boolean hit;
            try {
                hit = strategy.match(context);
            } catch (RuntimeException e) {
                throw new cn.cj.ifmap.core.exception.IfmapStrategyException(
                        "逻辑分支策略 " + strategy.getClass().getName() + " 执行失败：" + e.getMessage(), e);
            }
            if (hit) {
                if (hitRecorder != null) {
                    hitRecorder.onHit(strategy);
                }
                return true;
            }
        }
        return false;
    }

    public List<LogicBranchStrategy> find(String key) {
        List<LogicBranchStrategy> list = key == null ? null : strategies.get(key);
        return list == null ? Collections.<LogicBranchStrategy>emptyList()
                : Collections.unmodifiableList(new ArrayList<LogicBranchStrategy>(list));
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(strategies.keySet());
    }

    public int size() {
        int total = 0;
        for (List<LogicBranchStrategy> list : strategies.values()) {
            total += list.size();
        }
        return total;
    }

    /** 命中回调。 */
    public interface HitRecorder {
        void onHit(LogicBranchStrategy strategy);
    }
}
