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

import cn.cj.ifmap.core.strategy.IfmapAction;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.strategy.StrategyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 被调方向的「受理」分支动作（{@code logic_branch_flag=ACCEPTED}）。
 *
 * <p>应答报文此时<b>已经渲染好</b>（{@code response_param_template}）并回填到
 * {@link StrategyContext#getResponseData()}，动作要做的是<b>原地改这个 Map</b> 补上模板里
 * 表达不了的东西（这里演示补一个我方受理号）。</p>
 *
 * <p>注意：改完之后对外生效的只有这个 Map 里的键 —— 池、{@code IfmapResult} 与执行日志用的是同一个对象。</p>
 *
 * @author caijun
 */
@Component
@IfmapAction("noticeAccepted")
public class NoticeAcceptedAction implements IfmapActionHandler {

    private static final Logger log = LoggerFactory.getLogger(NoticeAcceptedAction.class);

    @Override
    @SuppressWarnings("unchecked")
    public void execute(StrategyContext context) {
        Object data = context.getResponseData();
        if (!(data instanceof Map)) {
            // 应答模板为空时产物是 null / 非 Map：这时没有可改的对象，只能记一笔
            log.warn("受理分支没有可回填的应答 Map（response_param_template 为空？）");
            return;
        }
        Map<String, Object> ack = (Map<String, Object>) data;
        ack.put("approveNo", "ACC-" + context.getBizId());
        log.info("受理分支已执行：对方流水号={}, 我方受理号={}",
                context.getParams().get("seqNo"), ack.get("approveNo"));
    }
}
