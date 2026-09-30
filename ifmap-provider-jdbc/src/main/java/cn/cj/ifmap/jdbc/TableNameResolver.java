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
package cn.cj.ifmap.jdbc;

import cn.cj.ifmap.core.exception.IfmapConfigException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表名解析：按前缀拼出四张表的物理名，并**白名单校验**前缀以防 SQL 注入。
 *
 * <p>前缀来自配置（{@code ifmap.table-prefix}），会被直接拼进 SQL，因此必须校验。
 * 允许字符：字母、数字、下划线；长度 0~32；必须以字母或下划线开头。默认前缀 {@code ifmap_}。</p>
 *
 * @author caijun
 */
public final class TableNameResolver {

    /** 默认前缀。 */
    public static final String DEFAULT_PREFIX = "ifmap_";

    private static final String CONFIG_SUFFIX = "config";
    private static final String LOGIC_BRANCH_SUFFIX = "logic_branch_config";
    private static final String EXECUTION_LOG_SUFFIX = "execution_log";
    private static final String CONFIG_HISTORY_SUFFIX = "config_history";
    private static final int MAX_PREFIX_LENGTH = 32;

    private final String prefix;
    private final Map<String, String> tables;

    public TableNameResolver(String prefix) {
        String normalized = prefix == null ? DEFAULT_PREFIX : prefix.trim();
        validate(normalized);
        this.prefix = normalized;
        Map<String, String> map = new LinkedHashMap<String, String>(8);
        map.put("config", normalized + CONFIG_SUFFIX);
        map.put("logicBranchConfig", normalized + LOGIC_BRANCH_SUFFIX);
        map.put("executionLog", normalized + EXECUTION_LOG_SUFFIX);
        map.put("configHistory", normalized + CONFIG_HISTORY_SUFFIX);
        this.tables = Collections.unmodifiableMap(map);
    }

    /** 默认前缀构造。 */
    public static TableNameResolver defaults() {
        return new TableNameResolver(DEFAULT_PREFIX);
    }

    private static void validate(String prefix) {
        if (prefix.length() > MAX_PREFIX_LENGTH) {
            throw new IfmapConfigException("表名前缀过长（>" + MAX_PREFIX_LENGTH + "）：" + prefix);
        }
        if (!prefix.isEmpty()) {
            char first = prefix.charAt(0);
            boolean firstOk = Character.isLetter(first) || first == '_';
            if (!firstOk) {
                throw new IfmapConfigException("表名前缀必须以字母或下划线开头：" + prefix);
            }
        }
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                throw new IfmapConfigException("表名前缀只允许字母、数字与下划线：" + prefix);
            }
        }
    }

    /** 前缀。 */
    public String prefix() {
        return prefix;
    }

    /** 接口配置表。 */
    public String configTable() {
        return tables.get("config");
    }

    /** 逻辑分支配置表。 */
    public String logicBranchTable() {
        return tables.get("logicBranchConfig");
    }

    /** 执行日志表。 */
    public String executionLogTable() {
        return tables.get("executionLog");
    }

    /** 配置变更历史表。 */
    public String configHistoryTable() {
        return tables.get("configHistory");
    }

    /** 四张表的只读视图（表逻辑名 → 物理名）。 */
    public Map<String, String> tables() {
        return tables;
    }
}
