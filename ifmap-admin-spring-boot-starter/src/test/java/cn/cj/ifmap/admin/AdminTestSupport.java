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
package cn.cj.ifmap.admin;

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.spi.SnowflakeIdGenerator;
import cn.cj.ifmap.jdbc.JdbcConfigHistoryRepository;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import cn.cj.ifmap.jdbc.TableNameResolver;
import cn.cj.ifmap.json.jackson.JacksonJsonOps;
import cn.cj.ifmap.spring.IfmapSchemaInitializer;
import cn.cj.ifmap.testkit.TestDatabases;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 管理端测试脚手架：默认 H2，设了 {@code IFMAP_JDBC_URL} 后同一套用例跑真 MySQL。
 *
 * <p>两种档位的隔离方式不同（H2 每个用例一块内存库；真库同一库里每个用例一套 {@code itt_*} 前缀），
 * 所以测试里<b>不能</b>再写死 {@code ifmap_config} 这类表名 —— 统一用 {@link Db} 拿表名。</p>
 *
 * @author caijun
 */
final class AdminTestSupport {

    private AdminTestSupport() {
    }

    /** 一个用例的库句柄：数据源 + 当前档位可用的表前缀 + 三个仓储。 */
    static final class Db {

        private final String prefix;
        private final DataSource dataSource;
        private final JdbcTemplate jdbc;

        Db(String name) {
            this.prefix = TestDatabases.prefixFor(name);
            this.dataSource = TestDatabases.fresh(name).dataSource();
            this.jdbc = new JdbcTemplate(dataSource);
        }

        DataSource dataSource() {
            return dataSource;
        }

        JdbcTemplate jdbc() {
            return jdbc;
        }

        String configTable() {
            return prefix + "config";
        }

        String logicBranchTable() {
            return prefix + "logic_branch_config";
        }

        String historyTable() {
            return prefix + "config_history";
        }

        TableNameResolver tables() {
            return new TableNameResolver(prefix);
        }

        /** 用 starter 的建表脚本初始化（与生产同一份 DDL），并把三张表清空。 */
        void createSchema() {
            new IfmapSchemaInitializer(dataSource, tables(), new DefaultResourceLoader())
                    .afterPropertiesSet();
            jdbc.update("DELETE FROM `" + configTable() + "`");
            jdbc.update("DELETE FROM `" + logicBranchTable() + "`");
            jdbc.update("DELETE FROM `" + historyTable() + "`");
        }

        JdbcConfigRepository repository() {
            return new JdbcConfigRepository(jdbc, tables(), SnowflakeIdGenerator.shared());
        }

        JdbcConfigWriter writer() {
            return new JdbcConfigWriter(jdbc, tables(), SnowflakeIdGenerator.shared());
        }

        JdbcConfigHistoryRepository history() {
            return new JdbcConfigHistoryRepository(jdbc, tables(), SnowflakeIdGenerator.shared());
        }
    }

    static Db db(String name) {
        return new Db(name);
    }

    static ConfigSnapshotMapper snapshots() {
        return new ConfigSnapshotMapper(new JacksonJsonOps());
    }

    /** 一条可用配置：模板里带 @FUN 与 JsonPath，result_flag/success_value 齐全。 */
    static IfmapConfig config(String interfaceNo, String busiNode, int order) {
        IfmapConfig config = new IfmapConfig();
        config.setTenantId(1L);
        config.setInterfaceNo(interfaceNo);
        config.setInterfaceCode("CMB_" + interfaceNo);
        config.setInterfaceName("测试接口 " + interfaceNo);
        config.setBusiNode(busiNode);
        config.setPartnerCode("CMB");
        config.setPartnerName("招商银行");
        config.setInterfaceOrder(order);
        config.setRequestParamTemplate("{\"bizNo\":\"$.bizNo\",\"amount\":\"@FUN(numRound,$.amount,2)\"}");
        config.setResponseParamTemplate("{\"applyNo\":\"$.data.applyNo\"}");
        config.setResultFlag("$.resultCode");
        config.setSuccessValue("0000");
        config.setStatus(1);
        config.setRemark("单测");
        return config;
    }

    static LogicBranchConfig branch(String interfaceNo, String methodFlag, String name,
                                    String flag, String value, int order) {
        LogicBranchConfig branch = new LogicBranchConfig();
        branch.setTenantId(1L);
        branch.setInterfaceNo(interfaceNo);
        branch.setMethodFlag(methodFlag);
        branch.setLogicBranchName(name);
        branch.setLogicBranchFlag(flag);
        branch.setLogicBranchValue(value);
        branch.setLogicBranchOrder(order);
        branch.setRemark("单测");
        return branch;
    }
}
