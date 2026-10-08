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

import cn.cj.ifmap.testkit.TestDatabases;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真库档下"建了表就必须清得掉"的守卫。
 *
 * <p>背景（2026-10-08 真库联调发现的缺陷）：{@code TestSchema.fresh()} 内部要"先按前缀删干净再建"，
 * 若那次清理顺手把前缀从待清理登记里注销，测试结束时的清理就找不到这组表了 —— 每跑一次就永久留一组
 * {@code itt_*} 表在真库里。本用例断言 {@code fresh()} 之后前缀仍在册、{@code cleanupAll()} 能清干净。</p>
 *
 * @author caijun
 */
class TestSchemaCleanupTest {

    @Test
    @DisplayName("真库档：fresh() 建的表仍在册，cleanupAll() 必须清干净（回归：登记被提前注销 → 真库积残表）")
    void freshTablesStayManagedUntilCleanup() {
        Assumptions.assumeTrue(TestDatabases.mysqlEnabled(), "只在真库档验证（H2 档没有落库的表）");

        TestDatabases.Schema schema = TestSchema.fresh("ifmap_managed_case");
        assertFalse(TestDatabases.tablesWithPrefix(schema.prefix()).isEmpty(), "前置条件：fresh() 后表已建");

        TestDatabases.cleanupAll();

        assertTrue(TestDatabases.tablesWithPrefix(schema.prefix()).isEmpty(),
                "cleanupAll() 之后不应残留 fresh() 建的表（建表前那次清理若注销了登记，会话监听器/退出钩子都清不掉）");
    }
}
