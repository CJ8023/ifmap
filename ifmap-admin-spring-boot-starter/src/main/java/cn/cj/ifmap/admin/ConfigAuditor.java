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

import cn.cj.ifmap.core.config.ConfigQuery;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 配置巡检（设计 §8.2 {@code GET /audit}）：把"部署前契约自检"变成"随时可跑的全量体检"。
 *
 * <p>逐个接口跑 {@link ConfigValidator}（模板契约 + JsonPath + strategy_name + 前置链），
 * 再对每个接口跑一次分支校验（默认兜底 ≤ 1 / 顺序唯一 / method_flag 已注册）。</p>
 *
 * @author caijun
 */
public class ConfigAuditor {

    private final JdbcConfigRepository repository;
    private final ConfigValidator validator;
    private final int maxConfigs;

    public ConfigAuditor(JdbcConfigRepository repository, ConfigValidator validator, int maxConfigs) {
        this.repository = repository;
        this.validator = validator;
        this.maxConfigs = maxConfigs <= 0 ? 5000 : maxConfigs;
    }

    /** 全量（或按 tenant/busiNode 过滤）巡检；{@code tenantId} 为空 = 跨租户全量。 */
    public AuditReport audit(String tenantId, String busiNode) {
        AuditReport report = new AuditReport();
        List<IfmapConfig> configs = loadAll(tenantId, busiNode, report);
        report.setConfigCount(configs.size());

        Set<String> interfaces = new LinkedHashSet<String>();
        for (IfmapConfig config : configs) {
            ValidationResult result = validator.validate(config, false);
            for (String error : result.getErrors()) {
                report.error(location(config) + " " + error);
            }
            for (String warning : result.getWarnings()) {
                report.warning(location(config) + " " + warning);
            }
            if (config.getInterfaceNo() != null) {
                // 分支校验按行自己的租户（跨租户巡检时每行租户可能不同）
                interfaces.add(config.getTenantId() + "\u0000" + config.getInterfaceNo());
            }
        }

        int branchCount = 0;
        for (String pair : interfaces) {
            int split = pair.indexOf('\u0000');
            String tenant = pair.substring(0, split);
            String interfaceNo = pair.substring(split + 1);
            List<cn.cj.ifmap.core.config.LogicBranchConfig> rows =
                    repository.queryLogicBranches(tenant, interfaceNo, false);
            branchCount += rows.size();
            ValidationResult result = validator.validateBranches(tenant, interfaceNo);
            for (String error : result.getErrors()) {
                report.error("接口 " + interfaceNo + "：" + error);
            }
            for (String warning : result.getWarnings()) {
                report.warning("接口 " + interfaceNo + "：" + warning);
            }
        }
        report.setBranchCount(branchCount);
        return report;
    }

    private List<IfmapConfig> loadAll(String tenantId, String busiNode, AuditReport report) {
        List<IfmapConfig> all = new ArrayList<IfmapConfig>();
        ConfigQuery query = new ConfigQuery()
                .setTenantId(tenantId)
                .setAllTenants(isBlank(tenantId))
                .setBusiNode(busiNode)
                .setSize(ConfigQuery.MAX_SIZE);
        int page = 1;
        while (all.size() < maxConfigs) {
            query.setPage(page++);
            List<IfmapConfig> rows = repository.queryConfigs(query);
            if (rows.isEmpty()) {
                break;
            }
            all.addAll(rows);
            if (rows.size() < query.getSize()) {
                break;
            }
        }
        if (all.size() > maxConfigs) {
            report.setTruncated(true);
            return new ArrayList<IfmapConfig>(all.subList(0, maxConfigs));
        }
        // 恰好等于上限时可能还有更多：再探一页
        if (all.size() == maxConfigs) {
            query.setPage(page);
            if (!repository.queryConfigs(query).isEmpty()) {
                report.setTruncated(true);
            }
        }
        return all;
    }

    private static String location(IfmapConfig config) {
        return "[" + config.getInterfaceNo() + "#" + config.getInterfaceOrder()
                + " busi=" + config.getBusiNode() + " keyId=" + config.getKeyId() + "]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
