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
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * 管理端测试脚手架：H2 真库 + 直接用自动装配同一份建表脚本建表。
 *
 * @author caijun
 */
final class AdminTestSupport {

    private AdminTestSupport() {
    }

    static DataSource dataSource(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        return ds;
    }

    static JdbcTemplate jdbc(DataSource ds) {
        return new JdbcTemplate(ds);
    }

    static TableNameResolver tables() {
        return TableNameResolver.defaults();
    }

    /** 用 starter 的建表脚本初始化（与生产同一份 DDL）。 */
    static void createSchema(DataSource ds) {
        IfmapSchemaInitializer initializer =
                new IfmapSchemaInitializer(ds, tables(), new DefaultResourceLoader());
        initializer.afterPropertiesSet();
    }

    static JdbcConfigRepository repository(JdbcTemplate jdbc) {
        return new JdbcConfigRepository(jdbc, tables(), SnowflakeIdGenerator.shared());
    }

    static JdbcConfigWriter writer(JdbcTemplate jdbc) {
        return new JdbcConfigWriter(jdbc, tables(), SnowflakeIdGenerator.shared());
    }

    static JdbcConfigHistoryRepository history(JdbcTemplate jdbc) {
        return new JdbcConfigHistoryRepository(jdbc, tables(), SnowflakeIdGenerator.shared());
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
