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
 * 特殊处理策略（对应存量 38 个 {@code *SpecialDealStrategyImpl}）。
 *
 * <p>按 <b>bean 名</b> 注册（与 {@code ifmap_config.strategy_name} 对齐），
 * 注册表同时接受"类简单名"与"首字母小写名"两个宽松 key（解决 P0-7），命中宽松 key 时记 WARN。</p>
 *
 * @author caijun
 */
public interface SpecialDealStrategy {

    /**
     * 执行特殊处理，可修改 {@code context} 里的参数。
     *
     * @return 需要替换的参数（null 表示不改动）；返回的键值会被合并进 {@code context}
     */
    Map<String, Object> apply(StrategyContext context);
}
