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
package cn.cj.ifmap.admin.spi;

import java.util.List;
import java.util.Map;

/**
 * 可选 SPI：把宿主机自己的字典（银行、融资模式、状态…）喂给管理端页面做下拉。
 *
 * <p><b>不强制实现</b>（设计 Q8 的结论）：ifmap 不建字典表、也不解释业务枚举的语义，
 * 枚举一律由宿主机提供。宿主机只要注册一个本接口的 bean，{@code GET /enums} 就会返回内容；
 * 没有这个 bean 时该端点返回 {@code {}}，页面退化成"纯文本输入"，功能不受影响。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>key 用<b>字段名</b>（{@code bankCode} / {@code financingMode} / {@code status} …），
 *       页面按同名字段把它渲染成 {@code <select>}；</li>
 *   <li>value 是下拉项，顺序即展示顺序（内部用 {@code LinkedHashMap} 保持插序）；</li>
 *   <li>实现必须是<b>只读且无副作用</b>的：页面每次加载都会调用一次，不要在这里写库或外呼。</li>
 * </ul>
 *
 * <pre>{@code
 * @Bean
 * public IfmapEnumProvider enumProvider() {
 *     return () -> Map.of("bankCode", List.of(EnumOption.of("CMB", "招商银行")),
 *                         "status", List.of(EnumOption.of("1", "启用"),
 *                                           EnumOption.of("0", "停用")));
 * }
 * }</pre>
 *
 * @author caijun
 */
@FunctionalInterface
public interface IfmapEnumProvider {

    /**
     * 字段名 → 可选项。返回 {@code null} 或空 Map 都视为"没有枚举"（不报错）。
     *
     * @return 下拉选项（按插入顺序展示）
     */
    Map<String, List<EnumOption>> options();
}
