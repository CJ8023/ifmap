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
package cn.cj.ifmap.core.strategy;

import cn.cj.ifmap.core.exception.IfmapStrategyException;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.spi.IfmapCallback;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.spi.IfmapCallbackHandler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 银行回调注册表：{@code interfaceNo} → 回调处理器（替换存量 17 个回调方法）。
 *
 * @author caijun
 */
public final class CallbackRegistry {

    private final Map<String, IfmapCallbackHandler> handlers = new LinkedHashMap<String, IfmapCallbackHandler>();

    /** 按类上的 {@link IfmapCallback} 注解注册（Spring 自动收集入口）。 */
    public void register(IfmapCallbackHandler handler) {
        if (handler == null) {
            return;
        }
        IfmapCallback annotation = cn.cj.ifmap.core.util.Annotations.find(handler.getClass(), IfmapCallback.class);
        if (annotation == null) {
            throw new IllegalArgumentException("回调处理器 " + handler.getClass().getName()
                    + " 缺少 @IfmapCallback 注解");
        }
        register(annotation.value(), handler);
    }

    public void register(String interfaceNo, IfmapCallbackHandler handler) {
        if (interfaceNo == null || interfaceNo.isEmpty() || handler == null) {
            return;
        }
        if (!handlers.containsKey(interfaceNo)) {
            handlers.put(interfaceNo, handler);
        }
    }

    public boolean contains(String interfaceNo) {
        return interfaceNo != null && handlers.containsKey(interfaceNo);
    }

    /** 分发回调；未注册抛 {@link IfmapStrategyException}（不静默）。 */
    public IfmapResult dispatch(String interfaceNo, IfmapRequest request, String rawBody) {
        IfmapCallbackHandler handler = interfaceNo == null ? null : handlers.get(interfaceNo);
        if (handler == null) {
            throw new IfmapStrategyException("回调未注册：interfaceNo=" + interfaceNo);
        }
        return handler.handle(request, rawBody);
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(handlers.keySet());
    }

    public int size() {
        return handlers.size();
    }
}
