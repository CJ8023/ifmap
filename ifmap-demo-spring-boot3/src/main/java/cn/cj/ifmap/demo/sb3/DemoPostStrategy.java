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
package cn.cj.ifmap.demo.sb3;

import cn.cj.ifmap.core.strategy.IfmapStrategyHook;
import cn.cj.ifmap.core.strategy.StrategyContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 出网方向的<b>后序</b>策略演示（配置写 {@code post_strategy_name=demoPostStrategy}）。
 *
 * <p>与 {@link DemoStrategy}（前序，改请求报文）正好是一对：后序跑在
 * 「响应模板渲染之后、结果判定之前」，<b>只能改产物，改不动成败</b> —— 判定读的是原始回参。</p>
 *
 * <p>三件事看清就够用了：</p>
 * <ul>
 *   <li>能读当前产物：{@link StrategyContext#getResponseData()}（这里拿 {@code applyNo} 拼结算引用号）；</li>
 *   <li>返回的 Map 会<b>合并</b>进产物（同时入池，后续步骤可用 {@code $.pool.BIZ_APPLY} 引用）；</li>
 *   <li>要整体换掉产物就 {@link StrategyContext#setResponseData(Object)} 传一个 Map ——
 *       引擎之后<b>只做合并、不保留原键</b>，所以换的时候记得自己把要留的键拷过去。</li>
 * </ul>
 *
 * @author caijun
 */
@Component
public class DemoPostStrategy implements IfmapStrategyHook {

    /** 后序：加工本步产物（不改报文、不改判定）。 */
    @Override
    public Map<String, Object> apply(StrategyContext context) {
        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        Object applyNo = currentApplyNo(context);
        extra.put("settleRef", applyNo == null ? "STL-UNKNOWN" : "STL-" + applyNo);
        extra.put("settleChannel", "CMB-SETTLE");
        return extra;
    }

    /** 产物是响应模板渲染出来的 Map（{@code {"applyNo":"AP..."}}），取不到就返回 null。 */
    private static Object currentApplyNo(StrategyContext context) {
        Object data = context.getResponseData();
        return data instanceof Map ? ((Map<?, ?>) data).get("applyNo") : null;
    }
}
