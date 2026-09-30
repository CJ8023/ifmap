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
 * <p>没有 provider 时 {@link #options()} 返回空 Map：{@code GET /enums} 依旧是 200，
 * 页面照常工作（只是没有下拉）。返回前做一次防御性拷贝，调用方改不动 provider 内部的状态。</p>
 *
 * @author caijun
 */
public final class IfmapEnumCatalog {

    private static final IfmapEnumCatalog EMPTY = new IfmapEnumCatalog(null);

    private final IfmapEnumProvider provider;

    public IfmapEnumCatalog(IfmapEnumProvider provider) {
        this.provider = provider;
    }

    /** 没有任何枚举来源（宿主机未注册 provider）。 */
    public static IfmapEnumCatalog empty() {
        return EMPTY;
    }

    /** 是否由宿主机提供了枚举。 */
    public boolean isPresent() {
        return provider != null;
    }

    /** 字段名 → 选项（不可变；provider 返回 null / 空白 key / null 项都会被安全跳过）。 */
    public Map<String, List<EnumOption>> options() {
        if (provider == null) {
            return Collections.emptyMap();
        }
        Map<String, List<EnumOption>> raw = provider.options();
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
