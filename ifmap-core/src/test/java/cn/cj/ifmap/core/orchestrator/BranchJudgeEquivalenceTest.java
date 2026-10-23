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
package cn.cj.ifmap.core.orchestrator;

import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.orchestrator.branch.BranchJudge;
import cn.cj.ifmap.core.strategy.LogicBranchStrategy;
import cn.cj.ifmap.core.strategy.LogicBranchStrategyRegistry;
import cn.cj.ifmap.core.strategy.StrategyContext;
import cn.cj.ifmap.core.testkit.TestJsonOps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移等价性证明：旧 {@code logic_branch_flag} + {@code logic_branch_value} 的分支选择语义，
 * 与新 {@code branch_judge} 规格（按 {@code 009} 回填规则生成）<b>逐格相同</b>。
 *
 * <p>做法是"双跑对照"，不是"读一遍 SQL 觉得对"：把旧语义<b>原样复制</b>成测试内的
 * {@link #legacyMatches}（禁止调用生产代码，否则就是自己跟自己对），再用一张参数表跑两个实现。</p>
 *
 * <p>唯一允许不同的格子是"判定值带首尾空白"：旧实现两边都 {@code trim()}，新实现不 trim。
 * 该偏差在设计中已声明（{@code RuleSpecJudge} 产出的 {@code actualValue} 通常已 trim）。</p>
 *
 * @author caijun
 */
class BranchJudgeEquivalenceTest {

    private final JsonOps jsonOps = new TestJsonOps();

    /** 旧实现的语义（抄自 {@code IfmapOrchestrator.matches}，不引用生产代码）。 */
    private static boolean legacyMatches(String flag, String value, String actualValue, boolean strategyHit) {
        if (trimToNull(flag) == null) {
            // 兜底分支：不参与常规匹配（走两趟里的第二趟）
            return false;
        }
        if (strategyHit) {
            return true;
        }
        String values = trimToNull(value);
        if (values == null || actualValue == null) {
            return false;
        }
        for (String candidate : values.split("[;,]")) {
            if (candidate.trim().equalsIgnoreCase(actualValue.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 与 {@code 009-backfill-branch-judge.sql} 等价的映射（机械 1:1，不含任何判断逻辑）。
     *
     * <p>三行对照设计 §4.5.1：flag 空 → always；只有 flag → strategy；两者都有 → strategy OR judged。</p>
     */
    private static String migrationSpec(String flag, String value) {
        String strategyKey = trimToNull(flag);
        if (strategyKey == null) {
            return "{\"op\":\"always\"}";
        }
        String rawValues = trimToNull(value);
        if (rawValues == null) {
            return "{\"any\":[{\"op\":\"strategy\",\"value\":\"" + strategyKey + "\"}]}";
        }
        List<String> parts = new ArrayList<String>();
        for (String part : rawValues.split("[;,]")) {
            if (!part.trim().isEmpty()) {
                parts.add("\"" + part.trim() + "\"");
            }
        }
        return "{\"any\":[{\"op\":\"strategy\",\"value\":\"" + strategyKey + "\"},"
                + "{\"op\":\"judged\",\"value\":[" + join(parts) + "],\"ignoreCase\":true}]}";
    }

    private BranchJudge judge(boolean strategyHit) {
        LogicBranchStrategyRegistry registry = new LogicBranchStrategyRegistry();
        for (String key : Arrays.asList("HIT", "MISS")) {
            registry.register(key, new FixedStrategy(strategyHit));
        }
        return new BranchJudge(jsonOps, registry);
    }

    @Test
    void everyCellOfTheGridAgreesWithLegacySemantics() {
        String[] flags = {"", "HIT", "MISS"};
        String[] values = {"", "SUCCESS", "SUCCESS;FAILED", " success , FAILED "};
        String[] actuals = {null, "SUCCESS", "success", " SUCCESS ", "FAILED", "a|b"};

        int compared = 0;
        int deviations = 0;
        for (String flag : flags) {
            for (String value : values) {
                for (String actual : actuals) {
                    for (boolean strategyHit : new boolean[]{true, false}) {
                        String spec = migrationSpec(flag, value);
                        boolean isFallback = trimToNull(flag) == null;
                        assertEquals(isFallback, judge(strategyHit).isAlways(spec),
                                "flag 空 ⟺ 兜底（always）：flag=" + flag);
                        if (isFallback) {
                            // 兜底走两趟里的第二趟，与 legacyMatches 的 false 不是同一件事，单独断言 isAlways
                            continue;
                        }
                        boolean expected = legacyMatches(flag, value, actual, strategyHit);
                        BranchJudge.Result result = judge(strategyHit)
                                .evaluate(spec, null, Judgement.success(actual, null, null), context());
                        assertTrue(!result.isInvalid(), result.getError());
                        compared++;
                        if (driftsOn(actual)) {
                            // 已声明偏差：旧实现 trim 判定值，新实现严格。trim 只会让旧实现更宽松，
                            // 所以允许不一致、但不允许方向反过来
                            assertTrue(!(result.isMatched() && !expected),
                                    "偏差方向反了：新实现命中而旧实现不命中，actual=[" + actual + "]");
                            deviations++;
                            continue;
                        }
                        assertEquals(expected, result.isMatched(),
                                "格子不一致：flag=" + flag + " value=[" + value + "] actual=[" + actual
                                        + "] strategy=" + strategyHit + " spec=" + spec);
                    }
                }
            }
        }
        assertTrue(compared > 40, "对照格子太少（" + compared + "），参数表可能写漏了");
        // 偏差只允许出现在「判定值带首尾空白」那一列（actuals 里只有 " SUCCESS " 一处）：
        // 非兜底 flag 数 × value 个数 × 2 种策略命中
        assertEquals((flags.length - 1) * values.length * 2, deviations,
                "偏差格子数应恰好等于「判定值带首尾空白」的格子数");
    }

    @Test
    void fallbackIsTheOnlyDifferenceAndItIsExplicit() {
        // flag 空 + value 非空：旧数据是脏的（配了 value 没配 flag），旧行为本来就走兜底 ⇒ 迁成 always 不改行为
        assertEquals("{\"op\":\"always\"}", migrationSpec("", "SUCCESS,FAILED"));
        assertTrue(judge(false).isAlways(migrationSpec(null, null)));
        // 只有 flag：纯策略通道
        assertEquals("{\"any\":[{\"op\":\"strategy\",\"value\":\"HIT\"}]}", migrationSpec("HIT", ""));
        // 两者都有：OR 语义
        assertEquals("{\"any\":[{\"op\":\"strategy\",\"value\":\"HIT\"},"
                + "{\"op\":\"judged\",\"value\":[\"SUCCESS\",\"FAILED\"],\"ignoreCase\":true}]}",
                migrationSpec("HIT", "SUCCESS;FAILED"));
    }

    /** 判定值带首尾空白 = 已声明偏差（旧实现 trim，新实现严格）。 */
    private static boolean driftsOn(String actual) {
        return actual != null && !actual.equals(actual.trim());
    }

    private static StrategyContext context() {
        return StrategyContext.builder().build();
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(sb.length() == 0 ? "" : ",").append(part);
        }
        return sb.toString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 固定命中的策略实现：{@code strategy} 算子按 key 找到它。 */
    private static final class FixedStrategy implements LogicBranchStrategy {

        private final boolean hit;

        private FixedStrategy(boolean hit) {
            this.hit = hit;
        }

        @Override
        public boolean match(StrategyContext context) {
            return hit;
        }
    }
}
