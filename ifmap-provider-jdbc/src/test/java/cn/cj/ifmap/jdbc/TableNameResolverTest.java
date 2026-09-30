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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表名前缀校验（前缀会直接拼进 SQL，必须挡住注入与拼写事故）。
 *
 * @author caijun
 */
class TableNameResolverTest {

    @Test
    @DisplayName("默认前缀拼出四张表名")
    void defaults() {
        TableNameResolver r = TableNameResolver.defaults();
        assertEquals("ifmap_", r.prefix());
        assertEquals("ifmap_config", r.configTable());
        assertEquals("ifmap_logic_branch_config", r.logicBranchTable());
        assertEquals("ifmap_execution_log", r.executionLogTable());
        assertEquals("ifmap_config_history", r.configHistoryTable());
    }

    @Test
    @DisplayName("复用存量表：前缀 bankint_")
    void reuseLegacyPrefix() {
        TableNameResolver r = new TableNameResolver("bankint_");
        assertEquals("bankint_config", r.configTable());
        assertEquals("bankint_execution_log", r.executionLogTable());
    }

    @Test
    @DisplayName("空前缀合法（表名不带前缀）")
    void emptyPrefixAllowed() {
        TableNameResolver r = new TableNameResolver("");
        assertEquals("config", r.configTable());
    }

    @Test
    @DisplayName("null 前缀回退为默认值")
    void nullPrefixFallsBack() {
        assertEquals("ifmap_config", new TableNameResolver(null).configTable());
    }

    @Test
    @DisplayName("非法前缀（注入 / 非法字符 / 超长 / 数字开头）一律拒绝")
    void rejectsIllegalPrefix() {
        String[] illegal = {"a-b", "ifmap_config; DROP TABLE x;--", "ifmap config", "1ifmap_",
                "这是一个很长的前缀这是一个很长的前缀这是一个很长的前缀这是一个很长的前缀"};
        for (String p : illegal) {
            IfmapConfigException e = assertThrows(IfmapConfigException.class, () -> new TableNameResolver(p), p);
            assertTrue(e.getMessage().contains("前缀"), p);
        }
    }

    @Test
    @DisplayName("表名视图包含四张表")
    void tableView() {
        assertEquals(4, TableNameResolver.defaults().tables().size());
    }
}
