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
import cn.cj.ifmap.admin.spi.IfmapEnumProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 枚举目录：把"宿主机有没有提供 {@link IfmapEnumProvider}"这件事收进一个 bean，
 * 让 Controller 不必关心 {@code ObjectProvider} 的空值分支（分支写多了总有一条是错的）。
 *
 * <p>内容 = 框架自带字典（{@link IfmapBuiltinEnums}：已注册的前/后序策略名、启用/停用）+ 宿主机字典，
 * 同名 key 以宿主为准。两边都没有时 {@link #options()} 返回空 Map：{@code GET /enums} 依旧是 200，
 * 页面照常工作（那个字段退化成输入框）。返回前做一次防御性拷贝，调用方改不动 provider 内部的状态。</p>
 *
 * @author caijun
 */
public final class IfmapEnumCatalog {

    private static final IfmapEnumCatalog EMPTY = new IfmapEnumCatalog(null, Collections.<String, List<EnumOption>>emptyMap());

    private final IfmapEnumProvider provider;

    /** 框架自带字典（{@link IfmapBuiltinEnums}）；宿主机同名 key 覆盖它。 */
    private final Map<String, List<EnumOption>> builtin;

    /**
     * 构造器私有 + 静态工厂：本类持有 {@link #EMPTY} 单例，公开构造器会让 SpotBugs 报
     * {@code SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR}；工厂方法同时把"可空 provider"这件事
     * 收敛到一个入口（{@code of(null)} 等价于 {@link #empty()}）。
     */
    private IfmapEnumCatalog(IfmapEnumProvider provider, Map<String, List<EnumOption>> builtin) {
        this.provider = provider;
        this.builtin = builtin;
    }

    /** 包装宿主机注册的枚举来源；{@code provider} 为 {@code null} 时等价于 {@link #empty()}。 */
    public static IfmapEnumCatalog of(IfmapEnumProvider provider) {
        return of(provider, Collections.<String, List<EnumOption>>emptyMap());
    }

    /**
     * 框架字典 + 宿主机字典。
     *
     * <p>两边都没有（provider 为空且 builtin 为空）时退化成 {@link #empty()} 单例。</p>
     */
    public static IfmapEnumCatalog of(IfmapEnumProvider provider, Map<String, List<EnumOption>> builtin) {
        Map<String, List<EnumOption>> base = sanitize(builtin);
        if (provider == null && base.isEmpty()) {
            return EMPTY;
        }
        return new IfmapEnumCatalog(provider, base);
    }

    /** 没有任何枚举来源（宿主机未注册 provider）。 */
    public static IfmapEnumCatalog empty() {
        return EMPTY;
    }

    /** 是否由宿主机提供了枚举（框架自带字典不算）。 */
    public boolean isPresent() {
        return provider != null;
    }

    /**
     * 字段名 → 选项（不可变）。
     *
     * <p>先放框架字典（{@link IfmapBuiltinEnums}），再用宿主机字典覆盖同名 key：
     * 「启用」的文案、合作机构的码值，宿主机最清楚，不该被框架的默认值挡住。</p>
     */
    public Map<String, List<EnumOption>> options() {
        Map<String, List<EnumOption>> merged = new LinkedHashMap<String, List<EnumOption>>(builtin);
        merged.putAll(sanitize(provider == null ? null : provider.options()));
        return Collections.unmodifiableMap(merged);
    }

    /**
     * 防御性拷贝：{@code null} key / 空白 key / {@code null} 列表 / {@code null} 项全部跳过，
     * 坏一项不影响其它字段（外部 provider 是宿主写的，不能假定它干净）。
     */
    private static Map<String, List<EnumOption>> sanitize(Map<String, List<EnumOption>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<EnumOption>> copy = new LinkedHashMap<String, List<EnumOption>>();
        for (Map.Entry<String, List<EnumOption>> entry : raw.entrySet()) {
            String field = entry.getKey();
            if (field == null || field.trim().isEmpty()) {
                continue;
            }
            List<EnumOption> items = new ArrayList<EnumOption>();
            if (entry.getValue() != null) {
                for (EnumOption option : entry.getValue()) {
                    if (option != null) {
                        items.add(option);
                    }
                }
            }
            copy.put(field, Collections.unmodifiableList(items));
        }
        return Collections.unmodifiableMap(copy);
    }
}
