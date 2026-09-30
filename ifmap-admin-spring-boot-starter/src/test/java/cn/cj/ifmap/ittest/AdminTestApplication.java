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
package cn.cj.ifmap.ittest;

import cn.cj.ifmap.core.model.BankCall;
import cn.cj.ifmap.core.spi.BankServiceGateway;
import cn.cj.ifmap.core.strategy.IfmapAction;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.strategy.StrategyContext;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Web 端到端测试用的宿主应用（真实启动 Tomcat + 真实 HTTP 前缀）。
 *
 * @author caijun
 */
@SpringBootApplication
public class AdminTestApplication {

    /**
     * 逻辑分支动作：{@code ifmap_logic_branch_config.method_flag = submit} 的落点。
     *
     * <p>{@code ActionRegistry.failOnMissingAction} 默认开启，宿主没这个 bean 时分支命中会直接抛
     * {@code IfmapStrategyException}（这正是 {@code POST /branches} 校验要拦截的场景）。</p>
     */
    @IfmapAction("submit")
    public static class SubmitAction implements IfmapActionHandler {

        @Override
        public void execute(StrategyContext context) {
            context.putParam("submitted", Boolean.TRUE);
        }
    }

    @Bean
    public SubmitAction adminTestSubmitAction() {
        return new SubmitAction();
    }

    /** 试跑必须走 mock 应答；此网关一旦被调用就说明"不出网"保障失效了。 */
    @Bean
    public BankServiceGateway failFastGateway() {
        return new BankServiceGateway() {
            @Override
            public String exchange(BankCall call) {
                throw new IllegalStateException("测试用例不允许真实外呼：" + call);
            }
        };
    }
}
