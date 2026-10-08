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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 枚举目录：provider 有没有、返回值脏不脏，都不能把页面带崩。
 *
 * @author caijun
 */
class IfmapEnumCatalogTest {

    @Test
    @DisplayName("没有 provider：不抛异常，返回空字典（端点照样 200）")
    void withoutProvider() {
        IfmapEnumCatalog catalog = IfmapEnumCatalog.empty();

        assertFalse(catalog.isPresent());
        assertTrue(catalog.options().isEmpty());
        assertTrue(IfmapEnumCatalog.of(null).options().isEmpty());
        // of(null) 与 empty() 是同一个实例：调用方不必区分两个"没有字典"的入口
        assertSame(catalog, IfmapEnumCatalog.of(null));
    }

    @Test
    @DisplayName("provider 返回 null / 空 Map：都按「没有字典」处理")
    void providerReturnsNothing() {
        assertTrue(IfmapEnumCatalog.of(() -> null).options().isEmpty());
        assertTrue(IfmapEnumCatalog.of(Collections::emptyMap).options().isEmpty());
    }

    @Test
    @DisplayName("正常返回：保留插入顺序，value/label 原样带出")
    void keepsOrderAndContent() {
        Map<String, List<EnumOption>> source = new LinkedHashMap<String, List<EnumOption>>();
        source.put("partnerCode", Arrays.asList(EnumOption.of("CMB", "招商银行"), EnumOption.of("ICBC", "工商银行")));
        source.put("status", Collections.singletonList(EnumOption.of("1", "启用")));

        IfmapEnumCatalog catalog = IfmapEnumCatalog.of(() -> source);

        assertTrue(catalog.isPresent());
        assertEquals(Arrays.asList("partnerCode", "status"), new ArrayList<String>(catalog.options().keySet()));
        assertEquals(EnumOption.of("ICBC", "工商银行"), catalog.options().get("partnerCode").get(1));
        assertEquals("启用", catalog.options().get("status").get(0).getLabel());
    }

    @Test
    @DisplayName("label 缺省时用 value 兜底；value 为空直接拒绝（脏数据在注册期就炸，别等到页面上）")
    void labelFallsBackToValue() {
        assertEquals("CMB", EnumOption.of("CMB", null).getLabel());
        assertEquals("CMB", EnumOption.of("CMB", "").getLabel());
        assertThrows(IllegalArgumentException.class, () -> EnumOption.of("  ", "空白值"));
        assertThrows(IllegalArgumentException.class, () -> EnumOption.of(null, "空值"));
    }

    @Test
    @DisplayName("脏数据被安全跳过：空白 key、null list、null 项都不会进结果")
    void skipsDirtyEntries() {
        Map<String, List<EnumOption>> source = new LinkedHashMap<String, List<EnumOption>>();
        source.put("  ", Collections.singletonList(EnumOption.of("X", "空白 key")));
        source.put("empty", null);
        source.put("dirty", Arrays.asList(EnumOption.of("A", "有效"), null));

        Map<String, List<EnumOption>> options = IfmapEnumCatalog.of(() -> source).options();

        assertEquals(2, options.size(), String.valueOf(options));
        assertTrue(options.get("empty").isEmpty());
        assertEquals(1, options.get("dirty").size());
    }

    @Test
    @DisplayName("返回的是防御性拷贝：provider 侧后续改动、或调用方想改，都影响不到对方")
    void defensiveCopy() {
        List<EnumOption> mutable = new ArrayList<EnumOption>();
        mutable.add(EnumOption.of("CMB", "招商银行"));
        Map<String, List<EnumOption>> source = new LinkedHashMap<String, List<EnumOption>>();
        source.put("partnerCode", mutable);

        IfmapEnumCatalog catalog = IfmapEnumCatalog.of(() -> source);
        Map<String, List<EnumOption>> first = catalog.options();
        mutable.add(EnumOption.of("ICBC", "工商银行"));

        assertEquals(1, first.get("partnerCode").size(), "取出的结果不该被 provider 的后续改动影响");
        assertThrows(UnsupportedOperationException.class, () -> first.put("x", Collections.emptyList()));
        assertThrows(UnsupportedOperationException.class,
                () -> first.get("partnerCode").add(EnumOption.of("ABC", "农业银行")));
    }
}
