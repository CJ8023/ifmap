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

/**
 * 分支专用算子（{@link JudgeOp#STRATEGY} / {@link JudgeOp#JUDGED}）的求值入参。
 *
 * <p>规格树只有一份（{@link ResultJudgeSpec}），求值时的"输入"多样化：普通算子是报文原文，
 * 分支专用算子是策略注册表 / 结果判定值。用这个接口把差异挡在叶子节点外侧，
 * 避免为了两个算子复制一整棵求值树。</p>
 *
 * <p>{@code result_judge} 求值传 {@code null}（它的规格里不可能出现这两个算子）。</p>
 *
 * @author caijun
 */
public interface BranchInputs {

    /** {@code {"op":"strategy","value":"<key>"}} 是否命中（key 未注册时由调用方决定返回值）。 */
    boolean strategyMatched(String key);

    /** {@code result_judge} 判定到的值；{@code null} 表示没有判定值（该叶子不命中）。 */
    String judgedValue();
}
