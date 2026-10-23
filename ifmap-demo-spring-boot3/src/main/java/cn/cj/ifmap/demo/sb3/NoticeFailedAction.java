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
 * 被调方向的「处理失败」分支动作（{@code logic_branch_flag=FAILED}）。
 *
 * <p>对方推的是「放款失败」的通知：报文本身可受理（判定通过、要回 ACK），
 * 但我方处理结论是失败。做法是<b>把应答模板里的成功码覆盖成失败码</b>，
 * 顺带给出 {@code retryable}，让对方知道「换一次可能就好了」。</p>
 *
 * <p><b>为什么不是抛异常</b>：动作在 {@code writeLog} 之前执行，抛出去会跳过执行日志，
 * 而且宿主也拿不到应答 Map —— 表现为「对方超时、我方日志里查不到这笔」。业务失败要回填，不要抛。</p>
 *
 * @author caijun
 */
@Component
@IfmapAction("noticeFailed")
public class NoticeFailedAction implements IfmapActionHandler {

    private static final Logger log = LoggerFactory.getLogger(NoticeFailedAction.class);

    @Override
    @SuppressWarnings("unchecked")
    public void execute(StrategyContext context) {
        Object data = context.getResponseData();
        if (!(data instanceof Map)) {
            log.warn("失败分支没有可回填的应答 Map（response_param_template 为空？）");
            return;
        }
        Map<String, Object> ack = (Map<String, Object>) data;
        // 2xxx = 我方可受理但处理失败（1xxx 留给「根本不可受理」，那时连分支都不会进）
        ack.put("code", "2001");
        ack.put("message", "处理失败：放款未成功，请稍后重试");
        ack.put("retryable", Boolean.TRUE);
        log.info("失败分支已执行：原应答 code=0000 已被覆盖为 {}, 对方原因={}",
                ack.get("code"), context.getParams().get("failReason"));
    }
}
