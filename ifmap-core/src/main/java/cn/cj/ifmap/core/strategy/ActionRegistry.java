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

import cn.cj.ifmap.core.util.Annotations;
import cn.cj.ifmap.core.exception.IfmapStrategyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动作注册表：{@code ifmap_logic_branch_config.method_flag} → 动作处理器（TS-6）。
 *
 * <p>相对存量的行为修正：<b>动作缺失不再静默</b>。默认 {@code failOnMissingAction = true}
 * 时抛 {@link IfmapStrategyException}（把"线上分支没生效"提前暴露）；置 false 则记 WARN 继续。</p>
 *
 * @author caijun
 */
public final class ActionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ActionRegistry.class);

    private final Map<String, IfmapActionHandler> actions = new LinkedHashMap<String, IfmapActionHandler>();
    private final Map<String, List<String>> conflicts = new LinkedHashMap<String, List<String>>();
    private boolean failOnMissingAction = true;

    /** 按类上的 {@link IfmapAction} 注解注册。 */
    public void register(IfmapActionHandler handler) {
        if (handler == null) {
            return;
        }
        IfmapAction annotation = Annotations.find(handler.getClass(), IfmapAction.class);
        if (annotation == null) {
            throw new IllegalArgumentException("动作处理器 " + handler.getClass().getName()
                    + " 缺少 @IfmapAction 注解");
        }
        register(annotation.value(), handler);
    }

    public void register(String key, IfmapActionHandler handler) {
        if (key == null || key.isEmpty() || handler == null) {
            return;
        }
        IfmapActionHandler exist = actions.get(key);
        if (exist == null) {
            actions.put(key, handler);
            return;
        }
        if (exist != handler) {
            List<String> owners = conflicts.get(key);
            if (owners == null) {
                owners = new ArrayList<String>();
                conflicts.put(key, owners);
            }
            if (!owners.contains(handler.getClass().getName())) {
                owners.add(handler.getClass().getName());
            }
            LOG.warn("ifmap 动作注册歧义：key={} 保留先注册者 {}", key, owners);
        }
    }

    public boolean contains(String key) {
        return key != null && actions.containsKey(key);
    }

    /**
     * 执行动作。
     *
     * @throws IfmapStrategyException key 为空或未注册且 {@code failOnMissingAction = true}
     */
    public void execute(String key, StrategyContext context) {
        if (key == null || key.isEmpty()) {
            throw new IfmapStrategyException("逻辑分支缺少 method_flag（动作 key），无法执行");
        }
        IfmapActionHandler handler = actions.get(key);
        if (handler == null) {
            if (failOnMissingAction) {
                throw new IfmapStrategyException("逻辑分支动作未注册：method_flag=" + key);
            }
            LOG.warn("ifmap 逻辑分支动作未注册，已跳过：method_flag={}", key);
            return;
        }
        handler.execute(context);
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(actions.keySet());
    }

    public int size() {
        return actions.size();
    }

    public boolean isFailOnMissingAction() {
        return failOnMissingAction;
    }

    public void setFailOnMissingAction(boolean failOnMissingAction) {
        this.failOnMissingAction = failOnMissingAction;
    }

    public Map<String, List<String>> getConflicts() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, List<String>>(conflicts));
    }
}
