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
package cn.cj.ifmap.core.orchestrator;

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接口执行顺序（TS-1 的落地）：<b>引擎侧兜底排序</b>，即使 provider 没排好也不会乱序。
 *
 * <ol>
 *   <li>{@link #enabled} —— 只保留 {@code status = 1}（存量引擎从不看这个字段）；</li>
 *   <li>{@link #sort} —— 按 {@code interface_order} 升序、同值按 {@code key_id} 兜底（稳定排序）；</li>
 *   <li>{@link #topological} —— 按 {@code front_interface_no} 拓扑排序，前置接口先执行；
 *       <b>检出环即抛</b>（存量是无限递归 StackOverflow）。</li>
 * </ol>
 *
 * @author caijun
 */
public final class InterfaceOrdering {

    private InterfaceOrdering() {
    }

    /** 只保留启用（{@code status} 为 null 视为启用）且非删除的配置。 */
    public static List<IfmapConfig> enabled(List<IfmapConfig> configs) {
        List<IfmapConfig> result = new ArrayList<IfmapConfig>();
        if (configs == null) {
            return result;
        }
        for (IfmapConfig config : configs) {
            if (config == null) {
                continue;
            }
            Integer status = config.getStatus();
            if (status != null && status.intValue() != 1) {
                continue;
            }
            Integer delStatus = config.getDelStatus();
            if (delStatus != null && delStatus.intValue() != 0) {
                continue;
            }
            result.add(config);
        }
        return result;
    }

    /** 按 {@code interface_order} 升序（null 排最后）、{@code key_id} 兜底；稳定排序。 */
    public static List<IfmapConfig> sort(List<IfmapConfig> configs) {
        List<IfmapConfig> result = new ArrayList<IfmapConfig>(configs == null
                ? Collections.<IfmapConfig>emptyList() : configs);
        Collections.sort(result, new Comparator<IfmapConfig>() {
            @Override
            public int compare(IfmapConfig a, IfmapConfig b) {
                int byOrder = compareOrder(a.getInterfaceOrder(), b.getInterfaceOrder());
                if (byOrder != 0) {
                    return byOrder;
                }
                long ak = a.getKeyId() == null ? 0L : a.getKeyId().longValue();
                long bk = b.getKeyId() == null ? 0L : b.getKeyId().longValue();
                return ak < bk ? -1 : (ak > bk ? 1 : 0);
            }
        });
        return result;
    }

    private static int compareOrder(Integer a, Integer b) {
        int av = a == null ? Integer.MAX_VALUE : a.intValue();
        int bv = b == null ? Integer.MAX_VALUE : b.intValue();
        return av < bv ? -1 : (av > bv ? 1 : 0);
    }

    /** 过滤 + 排序 + 拓扑排序（引擎执行的最终顺序）。 */
    public static List<IfmapConfig> plan(List<IfmapConfig> configs) {
        return topological(sort(enabled(configs)));
    }

    /**
     * 按 {@code front_interface_no} 拓扑排序：前置接口先执行，其余保持传入顺序。
     *
     * @throws IfmapConfigException 存在循环依赖
     */
    public static List<IfmapConfig> topological(List<IfmapConfig> sorted) {
        List<IfmapConfig> result = new ArrayList<IfmapConfig>();
        if (sorted == null || sorted.isEmpty()) {
            return result;
        }
        Map<String, List<IfmapConfig>> byInterface = new LinkedHashMap<String, List<IfmapConfig>>();
        for (IfmapConfig config : sorted) {
            String interfaceNo = config.getInterfaceNo();
            if (interfaceNo == null || interfaceNo.isEmpty()) {
                continue;
            }
            List<IfmapConfig> list = byInterface.get(interfaceNo);
            if (list == null) {
                list = new ArrayList<IfmapConfig>();
                byInterface.put(interfaceNo, list);
            }
            list.add(config);
        }
        Set<IfmapConfig> visited = Collections.newSetFromMap(new IdentityHashMap<IfmapConfig, Boolean>());
        Set<String> visiting = new LinkedHashSet<String>();
        Deque<String> path = new ArrayDeque<String>();
        for (IfmapConfig config : sorted) {
            visit(config, byInterface, visited, visiting, path, result);
        }
        return result;
    }

    private static void visit(IfmapConfig config, Map<String, List<IfmapConfig>> byInterface,
                              Set<IfmapConfig> visited, Set<String> visiting, Deque<String> path,
                              List<IfmapConfig> result) {
        if (visited.contains(config)) {
            return;
        }
        String interfaceNo = config.getInterfaceNo();
        if (interfaceNo != null && visiting.contains(interfaceNo)) {
            throw new IfmapConfigException("接口前置关系存在循环依赖：" + cycle(path, interfaceNo));
        }
        if (interfaceNo != null) {
            visiting.add(interfaceNo);
            path.addLast(interfaceNo);
        }
        String front = config.getFrontInterfaceNo();
        if (front != null && !front.trim().isEmpty()) {
            List<IfmapConfig> prerequisites = byInterface.get(front.trim());
            if (prerequisites != null) {
                for (IfmapConfig prerequisite : prerequisites) {
                    if (prerequisite != config) {
                        visit(prerequisite, byInterface, visited, visiting, path, result);
                    }
                }
            }
        }
        if (interfaceNo != null) {
            path.removeLast();
            visiting.remove(interfaceNo);
        }
        visited.add(config);
        result.add(config);
    }

    private static String cycle(Deque<String> path, String repeated) {
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (String item : path) {
            if (item.equals(repeated)) {
                started = true;
            }
            if (started) {
                sb.append(item).append(" -> ");
            }
        }
        return sb.append(repeated).toString();
    }
}
