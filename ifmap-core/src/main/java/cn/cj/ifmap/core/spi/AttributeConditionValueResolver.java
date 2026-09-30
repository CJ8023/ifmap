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

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.model.IfmapRequest;

/**
 * 默认条件解析：{@code payload:x} → 业务负载；{@code attr:x} → 扩展属性；{@code header:x} → 请求头；
 * 裸键按 负载 → 属性 → 请求头 顺序查找。
 *
 * @author caijun
 */
public class AttributeConditionValueResolver implements ConditionValueResolver {

    @Override
    public Object resolve(String condition, IfmapRequest request, IfmapConfig config) {
        if (condition == null || condition.isEmpty() || request == null) {
            return null;
        }
        String key = condition.trim();
        if (key.startsWith("payload:")) {
            return request.getPayload().get(key.substring("payload:".length()));
        }
        if (key.startsWith("attr:")) {
            return request.getAttributes().get(key.substring("attr:".length()));
        }
        if (key.startsWith("header:")) {
            return request.getHeader(key.substring("header:".length()));
        }
        if (request.getPayload().containsKey(key)) {
            return request.getPayload().get(key);
        }
        if (request.getAttributes().containsKey(key)) {
            return request.getAttributes().get(key);
        }
        return request.getHeader(key);
    }
}
