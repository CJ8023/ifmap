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

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 分支判定规格（{@code branch_judge} 列）的门面。
 *
 * <p><b>与 {@code result_judge} 同源</b>：同一个解析器（{@link ResultJudgeSpec}）、同一个深度上限、
 * 同一套 {@code all/any} 与 {@code ignoreCase} 规则；唯一差别是额外允许两个分支专用无 path 算子
 * {@code strategy} / {@code judged}（见 {@link ResultJudgeSpec#BRANCH_OPS}）。这里<b>不</b>实现第二套语法。</p>
 *
 * <p>顶层三形态：① {@code {"op":"always"}} 恒真（= 兜底分支）；② 空组合（{@code ""} / {@code {}}
 * / {@code {"all":[]}}）<b>非法</b>；③ 条件组合。</p>
 *
 * @author caijun
 */
public final class BranchJudgeSpec {

    private BranchJudgeSpec() {
    }

    /** 校验规格，返回违规清单（空 = 合法）。管理端 422 与巡检共用本方法。 */
    public static List<String> errorsOf(String raw, JsonOps jsonOps) {
        return ResultJudgeSpec.validate(raw, jsonOps, ResultJudgeSpec.BRANCH_OPS);
    }

    /**
     * 解析规格；非法抛 {@link IllegalArgumentException}。
     *
     * <p>运行期不要用它做"先校验再解析"的两遍调用（那就是解析两遍）—— 交给
     * {@code BranchJudge} 的进程内缓存，热路径只解析一次。</p>
     */
    public static ResultJudgeSpec parse(String raw, JsonOps jsonOps) {
        return ResultJudgeSpec.parse(raw, jsonOps, ResultJudgeSpec.BRANCH_OPS);
    }

    /**
     * 是否兜底分支（{@code {"op":"always"}}）。
     *
     * <p>必须<b>编译后判根节点</b>，不能对原文做字符串比较：{@code { "op" : "ALWAYS" }} 也是兜底。
     * 非法规格返回 {@code false}，让"非法"由规格校验去报（这里不再重复报"兜底"）。</p>
     */
    public static boolean isAlways(String raw, JsonOps jsonOps) {
        try {
            return parse(raw, jsonOps).isAlways();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 规格里用到的策略键（{@code strategy} 算子）；非法规格返回空集（先看 {@link #errorsOf}）。
     *
     * <p>保存期用它报"策略未注册"的 warning：写错策略键在运行期<b>不会报错</b>，
     * 只会表现为"这条分支永远不命中"，最后悄悄落到兜底分支 —— 典型的静默陷阱。</p>
     */
    public static Set<String> strategyKeys(String raw, JsonOps jsonOps) {
        try {
            return parse(raw, jsonOps).strategyKeys();
        } catch (RuntimeException e) {
            return Collections.emptySet();
        }
    }

    /** 规格里是否用到某个算子（非法规格返回 {@code false}，先看 {@link #errorsOf}）。 */
    public static boolean usesOp(String raw, JsonOps jsonOps, JudgeOp op) {
        try {
            return parse(raw, jsonOps).ops().contains(op);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
