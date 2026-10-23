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
 * 被调方向的<b>后序</b>策略演示（配置写 {@code post_strategy_name=noticeAckStamp}）。
 *
 * <p>被调方向没有请求报文，{@code pre_strategy_name} 必须留空；后序是这里<b>唯一</b>能挂的策略位。
 * 而且它比出网方向更好用：产物就是<b>回给三方的应答报文</b>，所以补 ACK 字段可以不写
 * {@code @IfmapAction}，直接在这里加工。</p>
 *
 * <p>两个必须知道的边界：</p>
 * <ul>
 *   <li><b>后序在结果判定之前</b>：报文不可受理（例如状态不认识）时这段也会执行，
 *       但判定失败 ⇒ 应答不会返回给三方（<b>加工了也白加工</b>），别指望靠它兜"拒收应答"；</li>
 *   <li><b>它改不动判定</b>：判定读的是对方原文，这里动的是产物。
 *       想让报文算"不可受理"，只能改 {@code result_judge} 或那个 {@code @IfmapAction}。</li>
 * </ul>
 *
 * @author caijun
 */
@Component
public class NoticeAckStamp implements IfmapStrategyHook {

    /** 后序：给应答产物盖两个"我方处理信息"字段。 */
    @Override
    public Map<String, Object> apply(StrategyContext context) {
        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("ackChannel", "NOTICE");
        // 业务号来自请求（IfmapRequest.bizId），与应答模板无关 —— 演示"产物之外的信息也能补进来"
        extra.put("ackBizId", context.getBizId());
        return extra;
    }
}
