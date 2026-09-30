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
package cn.cj.ifmap.core.strategy;

/**
 * 逻辑分支判断策略（对应存量 12 个 {@code *LogicBranchStrategyImpl}）。
 *
 * <p>按 {@code @LogicBranch("值")} 注册，注解值对应 {@code ifmap_logic_branch_config.logic_branch_flag}。
 * 分支命中后<b>动作</b>由 {@link ActionRegistry} 按
 * {@code method_flag} → Action 映射执行（取代存量的"反射方法名"，见 TS-6）。</p>
 *
 * @author caijun
 */
public interface LogicBranchStrategy {

    /** 是否命中该分支。 */
    boolean match(StrategyContext context);
}
