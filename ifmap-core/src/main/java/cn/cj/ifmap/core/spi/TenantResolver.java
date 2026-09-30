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
package cn.cj.ifmap.core.spi;

import cn.cj.ifmap.core.model.IfmapRequest;

/**
 * 租户解析 SPI：把请求上下文解析为租户 ID（P1-9）。
 *
 * <p><b>缓存 key 必须含 tenantId</b> —— 这是方案 v1 风险表里"跨租户串配置"的根治点。
 * 单租户场景返回 {@code "-1"} 即可。</p>
 *
 * @author caijun
 */
public interface TenantResolver {

    /** 解析租户 ID；返回 null/空表示无法解析（引擎回落到默认租户）。 */
    String resolve(IfmapRequest request);
}
