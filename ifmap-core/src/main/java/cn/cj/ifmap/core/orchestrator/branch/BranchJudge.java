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
package cn.cj.ifmap.core.orchestrator.branch;

import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonReadContext;
import cn.cj.ifmap.core.orchestrator.Judgement;
import cn.cj.ifmap.core.orchestrator.judgement.BranchInputs;
import cn.cj.ifmap.core.orchestrator.judgement.BranchJudgeSpec;
import cn.cj.ifmap.core.orchestrator.judgement.ResultJudgeSpec;
import cn.cj.ifmap.core.strategy.LogicBranchStrategyRegistry;
import cn.cj.ifmap.core.strategy.StrategyContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分支判定：把 {@code branch_judge} 文本编译成规格并对原文求值。
 *
 * <p>与结果判定共用同一棵求值树（{@link ResultJudgeSpec}），差异只有两个分支专用算子：
 * {@code strategy} 交 {@link LogicBranchStrategyRegistry}、{@code judged} 比
 * {@link Judgement#getActualValue()}。</p>
 *
 * <p><b>缓存</b>：规格文本 → 编译结果，进程内有界（{@value #CACHE_LIMIT} 条，LRU 淘汰）。
 * 分支是每次调用的热路径，而分支配置是低频变更的；缓存键用原文而不是 {@code key_id}，
 * 这样配置一改文本就自然失效，不需要版本号或主动清理。</p>
 *
 * <p><b>不抛异常</b>：非法规格返回 {@link Result#isInvalid()} 而不是抛 —— 由调用方决定
 * fail-closed 的落库口径（{@code SPEC_INVALID}），避免一个配置错误把整条调用链炸掉。</p>
 *
 * @author caijun
 */
public final class BranchJudge {

    /** 缓存上限：超过就淘汰最久未用的（配置条目数级别，不需要更大）。 */
    static final int CACHE_LIMIT = 1024;

    private final JsonOps jsonOps;
    private final LogicBranchStrategyRegistry strategies;
    private final Map<String, Compiled> cache = new LinkedHashMap<String, Compiled>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Compiled> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    public BranchJudge(JsonOps jsonOps, LogicBranchStrategyRegistry strategies) {
        this.jsonOps = jsonOps;
        this.strategies = strategies;
    }

    /** 是否兜底分支（{@code {"op":"always"}}）；非法规格返回 {@code false}，由 {@link #errorOf} 报。 */
    public boolean isAlways(String raw) {
        Compiled compiled = compile(raw);
        return compiled.spec != null && compiled.spec.isAlways();
    }

    /** 规格的非法原因；合法返回 {@code null}。 */
    public String errorOf(String raw) {
        return compile(raw).error;
    }

    /**
     * 求值。
     *
     * @param raw             规格原文（{@code branch_judge} 列）
     * @param ctx             原文读取上下文；{@code null} = 对方回参不是 JSON，按"取不到值"求值
     * @param judgement       结果判定结论（{@code judged} 算子读它的 {@code actualValue}）
     * @param strategyContext 策略上下文（{@code strategy} 算子传给 {@code @LogicBranch} 实现）
     */
    public Result evaluate(String raw, JsonReadContext ctx, Judgement judgement, StrategyContext strategyContext) {
        Compiled compiled = compile(raw);
        if (compiled.error != null) {
            return Result.invalid(compiled.error);
        }
        ResultJudgeSpec.Evaluation evaluation = compiled.spec.evaluate(ctx,
                new Inputs(judgement, strategyContext));
        return evaluation.isMatched() ? Result.MATCHED : Result.MISSED;
    }

    /** 编译一次并缓存；同一份文本只会解析一遍。 */
    private synchronized Compiled compile(String raw) {
        Compiled cached = cache.get(raw);
        if (cached != null) {
            return cached;
        }
        Compiled compiled;
        try {
            compiled = new Compiled(BranchJudgeSpec.parse(raw, jsonOps), null);
        } catch (RuntimeException e) {
            // 解析失败的文本同样缓存：坏配置在热路径上重试解析没有意义
            compiled = new Compiled(null, e.getMessage() == null ? "非法规格" : e.getMessage());
        }
        cache.put(raw, compiled);
        return compiled;
    }

    /** 编译产物：要么有规格，要么有错误（两者互斥）。 */
    private static final class Compiled {
        private final ResultJudgeSpec spec;
        private final String error;

        private Compiled(ResultJudgeSpec spec, String error) {
            this.spec = spec;
            this.error = error;
        }
    }

    /** 分支专用算子的输入：策略注册表 + 结果判定值。 */
    private final class Inputs implements BranchInputs {

        private final Judgement judgement;
        private final StrategyContext strategyContext;

        private Inputs(Judgement judgement, StrategyContext strategyContext) {
            this.judgement = judgement;
            this.strategyContext = strategyContext;
        }

        @Override
        public boolean strategyMatched(String key) {
            // 未注册与"注册了但没命中"在运行期是同一件事（都不成立）；保存期已用 warning 提示过
            return strategies != null && strategies.matches(key, strategyContext);
        }

        @Override
        public String judgedValue() {
            return judgement == null ? null : judgement.getActualValue();
        }
    }

    /** 求值结论：命中 / 未命中 / 规格非法（三分，不把"非法"混进"未命中"）。 */
    public static final class Result {

        /** 命中。 */
        public static final Result MATCHED = new Result(true, false, null);
        /** 未命中。 */
        public static final Result MISSED = new Result(false, false, null);

        private final boolean matched;
        private final boolean invalid;
        private final String error;

        private Result(boolean matched, boolean invalid, String error) {
            this.matched = matched;
            this.invalid = invalid;
            this.error = error;
        }

        /** 规格非法（fail-closed：调用方必须把这一步判失败，不得落到兜底）。 */
        public static Result invalid(String error) {
            return new Result(false, true, error);
        }

        public boolean isMatched() {
            return matched;
        }

        public boolean isInvalid() {
            return invalid;
        }

        /** 非法原因；合法规格返回 {@code null}。 */
        public String getError() {
            return error;
        }
    }
}
