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

import java.util.Map;

/**
 * 主参数组包策略（对应存量 20 个 {@code *FullParamStrategyImpl}，按 {@code @FullParam(bankCode, busiNode)} 注册）。
 *
 * @author caijun
 */
public interface FullParamStrategy {

    /**
     * 组装主参数。
     *
     * @return 组包结果（null 视为空）；会被合并进 {@code context} 的参数表
     */
    Map<String, Object> assemble(StrategyContext context);
}
