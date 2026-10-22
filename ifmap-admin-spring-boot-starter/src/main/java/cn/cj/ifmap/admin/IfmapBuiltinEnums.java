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
package cn.cj.ifmap.admin;

import cn.cj.ifmap.admin.spi.EnumOption;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 框架自带的字典：引擎自己就能算出可选项的那些字段，不该让宿主机再抄一遍。
 *
 * <p>起因：页面把「前序策略 / 后序策略」渲染成下拉，而这两列的可选值本来就是
 * {@code StrategyHookRegistry} 里注册了什么 —— 之前这份清单只喂给了「元数据」页的标签，
 * 没接到表单，于是宿主机不额外注册 {@code IfmapEnumProvider} 时，前序策略的下拉只有一个「（空）」，
 * <b>值根本填不进去</b>。</p>
 *
 * <p>所以这里给出「框架知道答案」的两组：</p>
 * <ul>
 *   <li>{@code strategyName} ← 已注册的前/后序策略名（{@link #of(Collection)} 的入参）；</li>
 *   <li>{@code status} ← 启用 / 停用（{@code ifmap_config.status} 只有这两个值）；</li>
 *   <li>{@code callDirection} ← 我方调对方 / 对方调我方（{@code ifmap_config.call_direction} 的两值）。</li>
 * </ul>
 *
 * <p>业务码值（合作机构、融资模式……）仍然只能由宿主机通过 {@code IfmapEnumProvider} 给，
 * 而且宿主机同名 key <b>覆盖</b>这里的默认值（见 {@link IfmapEnumCatalog}）。</p>
 *
 * @author caijun
 */
public final class IfmapBuiltinEnums {

    /** 前序 / 后序策略共用的字典 key（页面字段 {@code preStrategyName} / {@code postStrategyName} 都指它）。 */
    public static final String STRATEGY_NAME_KEY = "strategyName";

    /** 配置状态字典 key。 */
    public static final String STATUS_KEY = "status";

    /** 调用方向字典 key（页面字段 {@code callDirection}）。 */
    public static final String CALL_DIRECTION_KEY = "callDirection";

    private IfmapBuiltinEnums() {
    }

    /**
     * 组装框架字典。
     *
     * @param strategyNames 已注册的前/后序策略名，可为 {@code null}（则不提供 {@code strategyName} 这一组）
     * @return 不可变 Map；{@code status} 恒定存在
     */
    public static Map<String, List<EnumOption>> of(Collection<String> strategyNames) {
        Map<String, List<EnumOption>> options = new LinkedHashMap<String, List<EnumOption>>();
        // TreeSet 去重 + 排序：下拉顺序不该随 bean 注册顺序抖动（同一份配置两次打开页面对不上）
        TreeSet<String> sorted = new TreeSet<String>();
        if (strategyNames != null) {
            for (String name : strategyNames) {
                if (name != null && !name.trim().isEmpty()) {
                    sorted.add(name.trim());
                }
            }
        }
        if (!sorted.isEmpty()) {
            List<EnumOption> strategies = new ArrayList<EnumOption>(sorted.size());
            for (String name : sorted) {
                strategies.add(EnumOption.of(name, "已注册策略"));
            }
            options.put(STRATEGY_NAME_KEY, Collections.unmodifiableList(strategies));
        }
        options.put(STATUS_KEY, Collections.unmodifiableList(Arrays.asList(
                EnumOption.of("1", "启用"), EnumOption.of("0", "停用"))));
        // 值必须与 CallDirection 的枚举名一致（列里存的就是枚举名），标签解释「谁调谁」，别让人靠猜
        options.put(CALL_DIRECTION_KEY, Collections.unmodifiableList(Arrays.asList(
                EnumOption.of("OUTBOUND", "我方调对方（出网）"),
                EnumOption.of("INBOUND", "对方调我方（被调）"))));
        return Collections.unmodifiableMap(options);
    }
}
