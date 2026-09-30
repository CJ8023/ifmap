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
package cn.cj.ifmap.core.shadow;

import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;

/**
 * 影子运行的**新引擎**侧执行入口。
 *
 * <p>抽成接口而不是直接依赖 {@code IfmapOrchestrator}（它是 {@code final} 类）有两个好处：
 * 便于单测替换，也便于宿主在切换期把"新链路"换成任意实现（例如先用 dry-run 接口、
 * 或先用另一个编排器实例）。</p>
 *
 * @author caijun
 */
@FunctionalInterface
public interface ShadowEngine {

    /** 执行新链路；异常会被 {@link ShadowRunner} 捕获并记录，不影响主链路。 */
    IfmapResult execute(ShadowRequest request);

    /**
     * 用 {@link IfmapOrchestrator} 作为新链路：{@code legacyRawResponse} 非空时直接当
     * {@code mockResponse} 用（不出网）。
     */
    static ShadowEngine of(final IfmapOrchestrator orchestrator) {
        if (orchestrator == null) {
            throw new IllegalArgumentException("orchestrator 不能为空");
        }
        return new ShadowEngine() {
            @Override
            public IfmapResult execute(ShadowRequest request) {
                return orchestrator.execute(request.toIfmapRequest(), request.getInterfaceNo(),
                        request.getBusiNode(), request.getLegacyRawResponse());
            }
        };
    }
}
