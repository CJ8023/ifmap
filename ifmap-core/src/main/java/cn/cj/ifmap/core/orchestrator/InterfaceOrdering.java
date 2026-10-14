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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 接口执行顺序：<b>引擎侧兜底排序</b>，即使 provider 没排好也不会乱序。
 *
 * <ol>
 *   <li>{@link #enabled} —— 只保留启用（{@code status} 为 null 视为启用）且未删除的配置；</li>
 *   <li>{@link #sort} —— 按 {@code interface_order} 升序、同值按 {@code key_id} 兜底（稳定排序）。</li>
 * </ol>
 *
 * <p><b>顺序只有一个来源</b>：{@code interface_order}。v1.0 这里还叠过一层按
 * {@code front_interface_no} 的拓扑排序（含环检测 / 深度护栏 / 4 类校验）—— 两者表达的是同一件事，
 * 机制数 2 → 1 后那一层连同校验一起删掉。副作用要记住：漏排序不再会被拓扑"纠偏"，
 * 只表现为顺序不对，所以 {@link cn.cj.ifmap.core.config.ConfigRepository#queryConfigsByBusiNode}
 * 的"必须升序返回"是硬契约。</p>
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
}
