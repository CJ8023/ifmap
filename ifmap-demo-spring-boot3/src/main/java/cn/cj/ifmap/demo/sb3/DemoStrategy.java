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

import cn.cj.ifmap.core.strategy.FullParam;
import cn.cj.ifmap.core.strategy.FullParamStrategy;
import cn.cj.ifmap.core.strategy.IfmapAction;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.strategy.SpecialDealStrategy;
import cn.cj.ifmap.core.strategy.StrategyContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 演示三类策略：声明成 Spring bean 即自动注册（不需要工厂、不需要反射方法名）。
 *
 * <ul>
 *   <li>{@link SpecialDealStrategy}：按 <b>bean 名</b> 注册 —— 类名 {@code DemoStrategy} 的
 *       bean 名是 {@code demoStrategy}，配置里 {@code strategy_name} 直接写它</li>
 *   <li>{@link FullParamStrategy}：按 {@link FullParam} 的 (partnerCode, busiNode) 注册</li>
 *   <li>{@link IfmapActionHandler}：见 {@code DemoSubmitAction}（按 {@code @IfmapAction} 的值注册，
 *       对应 {@code ifmap_logic_branch_config.method_flag}）</li>
 * </ul>
 *
 * @author caijun
 */
@Component
@FullParam(partnerCode = "CMB", busiNode = "apply")
public class DemoStrategy implements SpecialDealStrategy, FullParamStrategy {

    /** 特殊处理：给请求报文补一段资方要求的公共字段。 */
    @Override
    public Map<String, Object> apply(StrategyContext context) {
        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("channelCode", "ECC");
        return extra;
    }

    /** 整包组包：按 (partnerCode, busiNode) 组装公共参数。 */
    @Override
    public Map<String, Object> assemble(StrategyContext context) {
        Map<String, Object> full = new LinkedHashMap<String, Object>();
        full.put("partnerCode", "CMB");
        return full;
    }
}
