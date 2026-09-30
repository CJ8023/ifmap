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
package cn.cj.ifmap.admin.web;

import cn.cj.ifmap.admin.AuditReport;
import cn.cj.ifmap.admin.ConfigAdminService;
import cn.cj.ifmap.admin.IfmapEnumCatalog;
import cn.cj.ifmap.admin.spi.EnumOption;
import cn.cj.ifmap.core.rule.RuleDescriptor;
import cn.cj.ifmap.core.rule.RuleRegistry;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.CallbackRegistry;
import cn.cj.ifmap.core.strategy.FullParamStrategyRegistry;
import cn.cj.ifmap.core.strategy.LogicBranchStrategyRegistry;
import cn.cj.ifmap.core.strategy.SpecialDealStrategyRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 元数据与巡检端：规则清单 / 动作清单 / 策略清单 / 全量审计。
 *
 * <p>配置页要用"已注册的规则与策略"做下拉与即时校验，所以这几个端点必须由服务端提供
 * （而不是让前端硬编码）。</p>
 *
 * @author caijun
 */
@RestController
@RequestMapping
public class IfmapMetaAdminController {

    private final ConfigAdminService service;
    private final RuleRegistry rules;
    private final SpecialDealStrategyRegistry specialDeals;
    private final FullParamStrategyRegistry fullParams;
    private final LogicBranchStrategyRegistry logicBranches;
    private final ActionRegistry actions;
    private final CallbackRegistry callbacks;
    private final IfmapEnumCatalog enums;

    public IfmapMetaAdminController(ConfigAdminService service, RuleRegistry rules,
                                    SpecialDealStrategyRegistry specialDeals,
                                    FullParamStrategyRegistry fullParams,
                                    LogicBranchStrategyRegistry logicBranches,
                                    ActionRegistry actions, CallbackRegistry callbacks,
                                    IfmapEnumCatalog enums) {
        this.enums = enums == null ? IfmapEnumCatalog.empty() : enums;
        this.service = service;
        this.rules = rules;
        this.specialDeals = specialDeals;
        this.fullParams = fullParams;
        this.logicBranches = logicBranches;
        this.actions = actions;
        this.callbacks = callbacks;
    }

    /** 已注册规则（模板 DSL 里 {@code @FUN} 可用清单）。 */
    @GetMapping("/rules")
    public List<RuleDescriptor> rules() {
        return rules.getDescriptors();
    }

    /** 已注册动作（{@code method_flag} 取值）。 */
    @GetMapping("/actions")
    public Set<String> actions() {
        return actions.keys();
    }

    /** 已注册策略与回调（{@code strategy_name} / 分支判定 key / 全量参数 key）。 */
    @GetMapping("/strategies")
    public Map<String, Object> strategies() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("specialDeals", specialDeals.keys());
        result.put("fullParams", fullParams.keys());
        result.put("logicBranches", logicBranches.keys());
        result.put("actions", actions.keys());
        result.put("callbacks", callbacks.keys());
        return result;
    }

    /**
     * 宿主机字典（可选 SPI，设计 Q8）：页面用它把 {@code bankCode} 之类的字段渲染成下拉。
     *
     * <p>没注册 {@code IfmapEnumProvider} 时返回 {@code {}}（而不是 404）：页面只有一条
     * "没有枚举"的普通分支，不用区分两种失败。</p>
     */
    @GetMapping("/enums")
    public Map<String, List<EnumOption>> enums() {
        return enums.options();
    }

    /** 全量巡检（可带 markdown=true 取 Markdown 报告）。 */
    @GetMapping("/audit")
    public Object audit(@RequestParam(value = "tenantId", required = false) String tenantId,
                        @RequestParam(value = "busiNode", required = false) String busiNode,
                        @RequestParam(value = "markdown", required = false) Boolean markdown) {
        AuditReport report = service.audit(tenantId, busiNode);
        return markdown != null && markdown ? Map.of("markdown", report.toMarkdown()) : report;
    }
}
