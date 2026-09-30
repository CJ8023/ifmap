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
package cn.cj.ifmap.demo.sb3;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 演示「引入 starter 之后的完整用法」：
 * 写配置 → 从库里查配置 → 引擎按配置模板渲染报文 → 编排器出网 + 判定 + 分支动作 + 落日志。
 *
 * @author caijun
 */
@Component
public class DemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private static final String TENANT = "1001";
    private static final String INTERFACE_NO = "BIZ_APPLY";
    private static final String BUSI_NODE = "apply";

    private static final String REQUEST_TEMPLATE = "{"
            + "\"orgNo\":\"@FUN(bankOrgNo,$.orgCode)\","
            + "\"applyNo\":\"$.applyNo\","
            + "\"applyDate\":\"@FUN(dateFormat,$.applyTime,yyyyMMdd)\","
            + "\"acctName\":\"@FUN(strMask,$.acctName,NAME)\","
            + "\"channelCode\":\"$.channelCode\""
            + "}";

    private static final String RESPONSE_TEMPLATE = "{\"applyNo\":\"$.data.applyNo\"}";

    private final ConfigRepository repository;
    private final JdbcConfigWriter writer;
    private final IfmapEngine engine;
    private final IfmapOrchestrator orchestrator;

    public DemoRunner(ConfigRepository repository, JdbcConfigWriter writer, IfmapEngine engine,
                      IfmapOrchestrator orchestrator) {
        this.repository = repository;
        this.writer = writer;
        this.engine = engine;
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfAbsent();

        List<IfmapConfig> configs = repository.queryConfigs(TENANT, INTERFACE_NO, BUSI_NODE);
        log.info("从库里查到 {} 条配置（表已由 starter 自动建好）", configs.size());

        List<LogicBranchConfig> branches = repository.queryLogicBranches(TENANT, INTERFACE_NO);
        log.info("逻辑分支 {} 条（该查询带缓存装饰器）", branches.size());

        String source = "{"
                + "\"orgCode\":\"12\","
                + "\"applyNo\":\"AP20250101001\","
                + "\"applyTime\":\"2025-03-08 10:20:30\","
                + "\"acctName\":\"张三\""
                + "}";
        String rendered = engine.render(configs.get(0).getRequestParamTemplate(), source);
        log.info("仅渲染（不经编排）结果：{}", rendered);

        // W4：走完整编排 —— 特殊处理策略 → 组包策略 → 渲染 → 出网 → 判定 → 分支动作 → 落执行日志
        IfmapRequest request = IfmapRequest.builder()
                .tenantId(TENANT)
                .bizId("BIZ-20250101-0001")
                .operatorId("demo")
                .requestId("REQ-1")
                .put("orgCode", "12")
                .put("applyNo", "AP20250101001")
                .put("applyTime", "2025-03-08 10:20:30")
                .put("acctName", "张三")
                .build();
        IfmapResult result = orchestrator.execute(request, INTERFACE_NO, BUSI_NODE);
        log.info("编排结果：success={}, data={}, 命中分支={}, 耗时={}ms",
                result.isSuccess(), result.getData(), result.getMatchedBranch(), result.getElapsedMs());
    }

    /** 幂等写入演示配置（重复启动不会插重）。 */
    private void seedIfAbsent() {
        if (!repository.queryConfigs(TENANT, INTERFACE_NO, BUSI_NODE).isEmpty()) {
            return;
        }
        IfmapConfig config = new IfmapConfig();
        config.setTenantId(Long.valueOf(TENANT));
        config.setInterfaceNo(INTERFACE_NO);
        config.setInterfaceCode("BIZ_APPLY_001");
        config.setInterfaceName("授信申请-报文组装");
        config.setBusiNode(BUSI_NODE);
        config.setBankCode("CMB");
        config.setBankName("招商银行");
        config.setInterfaceOrder(Integer.valueOf(1));
        config.setRequestParamTemplate(REQUEST_TEMPLATE);
        config.setResponseParamTemplate(RESPONSE_TEMPLATE);
        config.setResultFlag("$.resultCode");
        config.setSuccessValue("0000");
        // 按 bean 名指定特殊处理策略（DemoStrategy 的 bean 名 = demoStrategy）
        config.setStrategyName("demoStrategy");
        config.setStatus(Integer.valueOf(1));
        long keyId = writer.insert(config);
        log.info("已写入演示配置 keyId={}", keyId);

        LogicBranchConfig branch = new LogicBranchConfig();
        branch.setTenantId(Long.valueOf(TENANT));
        branch.setInterfaceNo(INTERFACE_NO);
        branch.setMethodFlag("submit");
        branch.setLogicBranchName("成功分支");
        branch.setLogicBranchFlag("submit");
        branch.setLogicBranchValue("0000");
        branch.setLogicBranchOrder(Integer.valueOf(1));
        long branchId = writer.insert(branch);
        log.info("已写入演示逻辑分支 keyId={}（flag=submit / value=0000 / 动作=submit）", branchId);
    }
}
