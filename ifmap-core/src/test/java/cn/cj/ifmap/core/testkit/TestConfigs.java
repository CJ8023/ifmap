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
package cn.cj.ifmap.core.testkit;

import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 测试用配置构造器与内存仓储（不依赖 H2 / mock 框架）。
 *
 * @author caijun
 */
public final class TestConfigs {

    private TestConfigs() {
    }

    /** 造一条接口配置（只设置与本类断言相关的字段）。 */
    public static IfmapConfig config(String interfaceNo, String busiNode, int order, long keyId) {
        IfmapConfig config = new IfmapConfig();
        config.setKeyId(Long.valueOf(keyId));
        config.setTenantId(Long.valueOf(-1L));
        config.setInterfaceNo(interfaceNo);
        config.setBusiNode(busiNode);
        config.setInterfaceOrder(Integer.valueOf(order));
        config.setStatus(Integer.valueOf(1));
        config.setDelStatus(Integer.valueOf(0));
        return config;
    }

    /** 造一条逻辑分支配置。 */
    public static LogicBranchConfig branch(long keyId, String interfaceNo, int order, String flag, String value,
                                           String methodFlag) {
        LogicBranchConfig branch = new LogicBranchConfig();
        branch.setKeyId(Long.valueOf(keyId));
        branch.setTenantId(Long.valueOf(-1L));
        branch.setInterfaceNo(interfaceNo);
        branch.setLogicBranchOrder(Integer.valueOf(order));
        branch.setLogicBranchFlag(flag);
        branch.setLogicBranchValue(value);
        branch.setMethodFlag(methodFlag);
        return branch;
    }

    /** 记录调用顺序的内存仓储。 */
    public static final class InMemoryRepository implements ConfigRepository {

        private final List<IfmapConfig> configs = new ArrayList<IfmapConfig>();
        private final List<LogicBranchConfig> branches = new ArrayList<LogicBranchConfig>();
        private final List<ExecutionLog> logs = new ArrayList<ExecutionLog>();
        private final List<String> queried = new ArrayList<String>();

        public InMemoryRepository add(IfmapConfig config) {
            configs.add(config);
            return this;
        }

        public InMemoryRepository add(LogicBranchConfig branch) {
            branches.add(branch);
            return this;
        }

        public List<ExecutionLog> getLogs() {
            return logs;
        }

        public List<String> getQueried() {
            return queried;
        }

        @Override
        public List<IfmapConfig> queryConfigs(String tenantId, String interfaceNo, String busiNode) {
            queried.add(interfaceNo);
            List<IfmapConfig> result = new ArrayList<IfmapConfig>();
            for (IfmapConfig config : configs) {
                if (interfaceNo.equals(config.getInterfaceNo())
                        && (busiNode == null || busiNode.equals(config.getBusiNode()))) {
                    result.add(config);
                }
            }
            return result;
        }

        @Override
        public Optional<IfmapConfig> findConfig(String tenantId, String interfaceNo, String busiNode, int order) {
            for (IfmapConfig config : configs) {
                if (interfaceNo.equals(config.getInterfaceNo()) && config.getInterfaceOrder() != null
                        && config.getInterfaceOrder().intValue() == order) {
                    return Optional.of(config);
                }
            }
            return Optional.empty();
        }

        @Override
        public List<LogicBranchConfig> queryLogicBranches(String tenantId, String interfaceNo) {
            List<LogicBranchConfig> result = new ArrayList<LogicBranchConfig>();
            for (LogicBranchConfig branch : branches) {
                if (interfaceNo.equals(branch.getInterfaceNo())) {
                    result.add(branch);
                }
            }
            Collections.sort(result, new java.util.Comparator<LogicBranchConfig>() {
                @Override
                public int compare(LogicBranchConfig a, LogicBranchConfig b) {
                    return a.getLogicBranchOrder().intValue() - b.getLogicBranchOrder().intValue();
                }
            });
            return result;
        }

        @Override
        public void saveExecutionLog(ExecutionLog log) {
            logs.add(log);
        }
    }
}
