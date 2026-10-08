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

/**
 * 本模块的测试库入口（名字保留历史叫法，实现全部委托给 {@link TestDatabases}）。
 *
 * <p>建表语句读**生产 DDL**（{@code db/changelog/v1.0.0/*.sql}）：
 * 默认跑 H2 内存库（剥表尾存储引擎选项 + {@code json → text}），
 * 设置 {@code IFMAP_JDBC_URL} 后跑**真 MySQL 并原样执行生产 DDL**（保留 {@code json} 列与表尾选项），
 * 此时返回的 {@link TestDatabases.Schema#prefix()} 是带随机后缀的唯一前缀，避免碰到库里已有的表。</p>
 *
 * <p>用法：<pre>
 *   TestDatabases.Schema schema = TestSchema.fresh();          // 建好 4 张生产表
 *   JdbcTemplate jdbc = new JdbcTemplate(schema.dataSource());
 *   JdbcConfigRepository repo = new JdbcConfigRepository(schema.dataSource(), schema.prefix());
 * </pre></p>
 *
 * @author caijun
 */
final class TestSchema {

    /** 执行日志归档冷表（可选运维脚本，字面量前缀由实现按当前档位替换）。 */
    static final String ARCHIVE_DDL = TestDatabases.ARCHIVE_DDL;

    private TestSchema() {
    }

    /** 建 4 张生产表（默认逻辑前缀 {@code ifmap_}）。 */
    static TestDatabases.Schema fresh() {
        return TestDatabases.freshWithTables();
    }

    /** 按逻辑前缀建 4 张生产表（真库档下会加唯一后缀）。 */
    static TestDatabases.Schema fresh(String hint) {
        return TestDatabases.freshWithTables(hint);
    }

    /** 建 4 张生产表 + 归档冷表。 */
    static TestDatabases.Schema freshWithArchive() {
        return TestDatabases.freshWithArchive();
    }

    /** 按逻辑前缀建 4 张生产表 + 归档冷表。 */
    static TestDatabases.Schema freshWithArchive(String hint) {
        return TestDatabases.freshWithArchive(hint);
    }
}
