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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 默认租户解析：上下文 &gt; 请求头 &gt; 默认值，且请求头大小写不敏感。 */
class TenantResolverTest {

    private final HeaderTenantResolver resolver = new HeaderTenantResolver();

    @Test
    void contextTenantWins() {
        IfmapRequest request = IfmapRequest.builder().tenantId("100").header("X-Tenant-Id", "200").build();
        assertEquals("100", resolver.resolve(request));
    }

    @Test
    void headerIsUsedWhenContextMissing() {
        IfmapRequest request = IfmapRequest.builder().header("x-tenant-id", "200").build();
        assertEquals("200", resolver.resolve(request));
    }

    @Test
    void fallsBackToDefault() {
        assertEquals("-1", resolver.resolve(IfmapRequest.empty()));
        assertEquals("-1", resolver.resolve(null));
    }

    @Test
    void customHeaderAndDefault() {
        HeaderTenantResolver custom = new HeaderTenantResolver("X-Org", "0");
        assertEquals("0", custom.resolve(IfmapRequest.empty()));
        assertEquals("9", custom.resolve(IfmapRequest.builder().header("X-Org", "9").build()));
        assertEquals("X-Org", custom.getHeaderName());
        assertEquals("0", custom.getDefaultTenantId());
    }
}
