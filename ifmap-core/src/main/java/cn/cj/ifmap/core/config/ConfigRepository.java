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
package cn.cj.ifmap.core.config;

import java.util.List;
import java.util.Optional;

/**
 * 配置仓储 SPI：引擎从宿主机取配置与写执行日志的**唯一**入口。
 *
 * <p>实现方约定（违反会导致"看似随机"的行为）：</p>
 * <ol>
 *   <li>{@link #queryConfigsByBusiNode} <b>必须</b>按 {@code interfaceOrder} 升序返回，同值按 {@code keyId} 兜底
 *       —— 它是引擎取执行计划的唯一入口（对比现状：原实现按前置链 BFS 拼计划，现已删除）。</li>
 *   <li>{@link #queryConfigs} 同样按 {@code interfaceOrder} 升序返回（管理端单条查询与 demo 用）。</li>
 *   <li>{@link #queryLogicBranches} <b>必须</b>按 {@code logicBranchOrder} 升序返回，同值按 {@code keyId} 兜底。</li>
 *   <li>只返回未删除（{@code del_status = 0}）且未停用的配置；停用配置由管理端维护，不应进入执行链路。</li>
 * </ol>
 *
 * <p>{@code tenantId} 用 {@code String} 承载以兼容"租户 ID 非数字"的宿主机；单租户场景传
 * {@code "-1"}。实现方负责解析为库内类型，解析失败应抛
 * {@link cn.cj.ifmap.core.exception.IfmapConfigException}。</p>
 *
 * <p>实现：{@code JdbcConfigRepository}（provider-jdbc）/ {@code RemoteConfigRepository}（provider-remote，
 * 配置在远端 HTTP 接口时用）；单测里用匿名实现或桩类。</p>
 *
 * @author caijun
 */
public interface ConfigRepository {

    /**
     * 查询某业务节点下<b>全部</b>启用配置（按 {@code interfaceOrder} 升序，同值 {@code keyId} 兜底）。
     *
     * <p>引擎用它取执行计划：一个 {@code busi_node} 就是一条完整流水线，
     * {@code interfaceNo} 只是这条流水线上的<b>起点</b>（见 {@code IfmapOrchestrator.loadPlan}）。</p>
     *
     * <p><b>必须只返回未删除（{@code del_status = 0}）且未停用（{@code status = 1}）的行</b>，
     * 且<b>必须已排好序</b> —— 引擎只在本地再兜底排一次，顺序错了不会被拓扑纠正。</p>
     *
     * <p>{@code busiNode} 为空时返回空列表（而不是全表）。</p>
     */
    List<IfmapConfig> queryConfigsByBusiNode(String tenantId, String busiNode);

    /** 按业务键查询接口配置（按 interfaceOrder 升序）。 */
    List<IfmapConfig> queryConfigs(String tenantId, String interfaceNo, String busiNode);

    /** 查询单条配置。 */
    Optional<IfmapConfig> findConfig(String tenantId, String interfaceNo, String busiNode, int order);

    /** 查询逻辑分支（按 logicBranchOrder 升序）。 */
    List<LogicBranchConfig> queryLogicBranches(String tenantId, String interfaceNo);

    /** 写执行日志。 */
    void saveExecutionLog(ExecutionLog log);
}
