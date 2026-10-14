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
 * <p>按 <b>bean 名</b> 注册（与 {@code ifmap_config.pre_strategy_name} / {@code post_strategy_name} 对齐），
 * 注册表同时接受"类简单名"与"首字母小写名"两个宽松 key（解决 P0-7），命中宽松 key 时记 WARN。</p>
 *
 * @author caijun
 */
public interface SpecialDealStrategy {

    /**
     * 执行特殊处理；用 {@link StrategyContext#getPhase()} 区分前序 / 后序。
     *
     * <table border="1">
     *   <caption>前后序差异</caption>
     *   <tr><th></th><th>{@code PRE}</th><th>{@code POST}</th></tr>
     *   <tr><td>时机</td><td>渲染请求后、出网前</td><td>响应产物后、判定前</td></tr>
     *   <tr><td>可见</td><td>{@code params} / {@code requestJson}</td>
     *       <td>再加 {@code responseJson} / {@code responseData}</td></tr>
     *   <tr><td>返回值去向</td><td>合并进 {@code params}，请求模板<b>重渲染</b></td>
     *       <td>合并进本步产物 {@code data}（也会入池供后续步骤引用）</td></tr>
     * </table>
     *
     * <p>后序额外可用 {@link StrategyContext#setResponseData(Object)} 整体替换产物（传 Map）。
     * 前后序共用同一注册表，成败判定始终读<b>原始回参</b>，后序策略影响不了判定。</p>
     *
     * @return 要合并的键值（null / 空表示不改动）
     */
    Map<String, Object> apply(StrategyContext context);
}
