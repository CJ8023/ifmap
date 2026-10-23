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
        // result_judge 是 NOT NULL 的必填列：夹具默认「不判定」，需要判定的用例自己覆盖
        config.setResultJudge("{\"op\":\"always\"}");
        config.setJudgeName("");
        return config;
    }

    /** 造一条逻辑分支配置。branchJudge 必填，兜底分支写 always 规格。 */
    public static LogicBranchConfig branch(long keyId, String interfaceNo, int order, String name,
                                           String branchJudge, String methodFlag) {
        LogicBranchConfig branch = new LogicBranchConfig();
        branch.setKeyId(Long.valueOf(keyId));
        branch.setTenantId(Long.valueOf(-1L));
        branch.setInterfaceNo(interfaceNo);
        branch.setLogicBranchOrder(Integer.valueOf(order));
        branch.setLogicBranchName(name);
        branch.setBranchJudge(branchJudge);
        branch.setMethodFlag(methodFlag);
        return branch;
    }

    /** 兜底分支（规格恒真）。 */
    public static LogicBranchConfig fallbackBranch(long keyId, String interfaceNo, int order, String name,
                                                   String methodFlag) {
        return branch(keyId, interfaceNo, order, name, "{\"op\":\"always\"}", methodFlag);
    }

    /** 按结果判定值分流（旧 {@code logic_branch_value} 的等价形态）。 */
    public static LogicBranchConfig judgedBranch(long keyId, String interfaceNo, int order, String name,
                                                String methodFlag, String... values) {
        StringBuilder sb = new StringBuilder("{\"any\":[{\"op\":\"judged\",\"ignoreCase\":true,\"value\":[");
        for (int i = 0; i < values.length; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(values[i]).append('"');
        }
        return branch(keyId, interfaceNo, order, name, sb.append("]}]}").toString(), methodFlag);
    }

    /** 按原文 JsonPath 分流。 */
    public static LogicBranchConfig pathBranch(long keyId, String interfaceNo, int order, String name,
                                              String methodFlag, String path, String op, String... values) {
        StringBuilder sb = new StringBuilder("{\"any\":[{\"path\":\"").append(path)
                .append("\",\"op\":\"").append(op).append('"');
        // 单值算子（eq/ne/regex）写标量；in/notIn 写数组 —— 写错了就是非法规格，夹具不该替使用者兜底
        boolean list = "in".equalsIgnoreCase(op) || "notIn".equalsIgnoreCase(op);
        if (values.length > 0) {
            sb.append(",\"value\":").append(list ? "[" : "");
            for (int i = 0; i < values.length; i++) {
                sb.append(i == 0 ? "" : ",").append('"').append(values[i]).append('"');
            }
            sb.append(list ? "]" : "");
        }
        return branch(keyId, interfaceNo, order, name, sb.append("}]}").toString(), methodFlag);
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
        public List<IfmapConfig> queryConfigsByBusiNode(String tenantId, String busiNode) {
            queried.add(busiNode);
            List<IfmapConfig> result = new ArrayList<IfmapConfig>();
            for (IfmapConfig config : configs) {
                if (busiNode == null || busiNode.equals(config.getBusiNode())) {
                    result.add(config);
                }
            }
            return result;
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
