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
 * 逻辑分支动作处理器：由 {@code ifmap_logic_branch_config.method_flag} 作为 key 注册。
 *
 * @author caijun
 */
public interface IfmapActionHandler {

    /** 执行动作（可读写 {@code context} 里的参数）。 */
    void execute(StrategyContext context);
}
