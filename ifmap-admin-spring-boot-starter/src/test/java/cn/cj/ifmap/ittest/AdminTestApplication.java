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

import cn.cj.ifmap.admin.spi.EnumOption;
import cn.cj.ifmap.admin.spi.IfmapEnumProvider;
import cn.cj.ifmap.core.model.PartnerCall;
import cn.cj.ifmap.core.spi.PartnerServiceGateway;
import cn.cj.ifmap.core.strategy.IfmapAction;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.strategy.StrategyContext;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;

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

    /**
     * 宿主机字典（可选 SPI 的"有"这一支）：页面拿它把 partnerCode 之类的字段渲染成下拉。
     *
     * <p>注意字典的语义完全由宿主机决定：ifmap 不建字典表、也不解释枚举含义（设计 Q8）。</p>
     */
    @Bean
    public IfmapEnumProvider adminTestEnumProvider() {
        return () -> Map.of(
                "partnerCode", List.of(EnumOption.of("CMB", "招商银行"), EnumOption.of("ICBC", "工商银行")),
                "status", List.of(EnumOption.of("1", "启用"), EnumOption.of("0", "停用")));
    }

    /** 试跑必须走 mock 应答；此网关一旦被调用就说明"不出网"保障失效了。 */
    @Bean
    public PartnerServiceGateway failFastGateway() {
        return new PartnerServiceGateway() {
            @Override
            public String exchange(PartnerCall call) {
                throw new IllegalStateException("测试用例不允许真实外呼：" + call);
            }
        };
    }
}
