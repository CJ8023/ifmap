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
package cn.cj.ifmap.core.orchestrator.judgement;

import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.spi.ResultJudge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 判定器注册表：按名解析，未配置时用默认（{@code ruleSpec}）。
 *
 * <p>只保留「注册名 + 大小写不敏感兜底」两档（判定器数量少，别名体系是 YAGNI）；
 * 兜底命中记 WARN，便于抓出人工写错大小写的配置。</p>
 *
 * @author caijun
 */
public final class ResultJudgeRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ResultJudgeRegistry.class);

    private final Map<String, ResultJudge> judges = new LinkedHashMap<String, ResultJudge>();
    private String defaultName = RuleSpecJudge.NAME;

    /** 装上默认判定器 {@code ruleSpec}。 */
    public static ResultJudgeRegistry withDefaults(JsonOps jsonOps) {
        ResultJudgeRegistry registry = new ResultJudgeRegistry();
        registry.register(RuleSpecJudge.NAME, new RuleSpecJudge(jsonOps));
        return registry;
    }

    /** 注册（同名覆盖，启动期是单线程，不做并发控制）。 */
    public ResultJudgeRegistry register(String name, ResultJudge judge) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("判定器注册名不能为空");
        }
        if (judge == null) {
            throw new IllegalArgumentException("判定器不能为空：" + name);
        }
        judges.put(name.trim(), judge);
        return this;
    }

    /** 配置没写 {@code judge_name} 时使用的判定器名（默认 {@code ruleSpec}）。 */
    public String defaultName() {
        return defaultName;
    }

    public void setDefaultName(String defaultName) {
        this.defaultName = defaultName == null || defaultName.trim().isEmpty()
                ? RuleSpecJudge.NAME : defaultName.trim();
    }

    /**
     * 按名取判定器；{@code name} 为空 → 默认判定器。取不到返回 {@code null}
     * （调用方按 {@code SPEC_INVALID} fail-closed，不静默换默认）。
     */
    public ResultJudge lookup(String name) {
        String key = name == null || name.trim().isEmpty() ? defaultName : name.trim();
        ResultJudge direct = judges.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, ResultJudge> entry : judges.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                LOG.warn("判定器名大小写不匹配：配置为 {}，实际注册为 {}（按后者执行）", key, entry.getKey());
                return entry.getValue();
            }
        }
        return null;
    }
}
