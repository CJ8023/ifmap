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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    @DisplayName("真库档：cleanupAll() 清空登记后，同一 hint 再建的表仍须在册（回归：缓存命中路径漏登记 -> CI 残留 itt_* 表）")
    void reuseAfterCleanupStaysManaged() {
        Assumptions.assumeTrue(TestDatabases.mysqlEnabled(), "只在真库档验证（H2 档没有落库的表）");

        TestDatabases.Schema first = TestSchema.fresh("ifmap_reuse_case");
        assertFalse(TestDatabases.tablesWithPrefix(first.prefix()).isEmpty(), "前置条件：fresh() 后表已建");

        // 模拟“同一 JVM 里别处调过 cleanupAll()”：登记被清空，但 hint -> 前缀 的映射还在缓存里。
        // 这正是 CI（Linux runner，文件系统序与本地不同）暴露出来的场景：
        // 某个测试类调了 cleanupAll()，之后另一个测试类用同一个 hint 再建表。
        TestDatabases.cleanupAll();

        TestDatabases.Schema second = TestSchema.fresh("ifmap_reuse_case");
        assertEquals(first.prefix(), second.prefix(), "同一 hint 在同一 JVM 里必须复用同一前缀（走缓存命中路径）");

        // 会话结束时的清理 == 这一步
        TestDatabases.cleanupAll();

        assertTrue(TestDatabases.tablesWithPrefix(second.prefix()).isEmpty(),
                "缓存命中路径若不重新登记，之后建的表永远不会被清理（CI 门禁实测残留 itt_bankint_*_config）");
    }
}
