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

import cn.cj.ifmap.core.model.PartnerCall;
import cn.cj.ifmap.core.spi.PartnerServiceGateway;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 演示用"资方网关"：真实项目里这里换成 HTTP / SDK 调用。
 *
 * <p>编排器把渲染好的请求报文交给它，它返回原始响应报文（字符串），
 * 判定与出参映射由编排器按配置完成。</p>
 *
 * <p>顺手把最后一次收到的报文存起来（{@link #getLastRequest()}）：<b>前序策略改的是请求报文</b>，
 * 而报文出网之后引擎就不再暴露它了 —— 想看"策略到底把它改成了什么样"，只能在网关这一侧留证据。</p>
 *
 * @author caijun
 */
@Component
public class DemoPartnerGateway implements PartnerServiceGateway {

    private final AtomicReference<String> lastRequest = new AtomicReference<String>();

    @Override
    public String exchange(PartnerCall call) {
        lastRequest.set(call == null ? null : call.getRequestJson());
        // 演示：回一个"成功 + 业务流水号"的响应
        return "{\"resultCode\":\"0000\",\"resultMsg\":\"成功\",\"data\":{\"applyNo\":\"AP20250101001\"}}";
    }

    /** 最后一次收到的请求报文（前序策略执行完之后的那一份）；没调用过返回 {@code null}。 */
    public String getLastRequest() {
        return lastRequest.get();
    }
}
