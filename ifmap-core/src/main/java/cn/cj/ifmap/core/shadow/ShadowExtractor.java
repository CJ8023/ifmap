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

/**
 * 把新引擎结果投影成可比结果 {@link ShadowOutcome}。
 *
 * <p>默认实现直接使用 {@code IfmapResult} 的四个字段：{@code success} → 成败判定、
 * {@code matchedBranch} → 判定标识、{@code data} → 可比字段集。宿主若只关心其中一部分
 * 字段（典型场景：只想比对资方返回的关键业务字段），自己实现本接口做投影即可。</p>
 *
 * @author caijun
 */
@FunctionalInterface
public interface ShadowExtractor {

    /** 投影。 */
    ShadowOutcome extract(IfmapResult result);

    /** 默认投影：{@code success} / {@code matchedBranch} / {@code data}。 */
    static ShadowExtractor defaults() {
        return new ShadowExtractor() {
            @Override
            public ShadowOutcome extract(IfmapResult result) {
                if (result == null) {
                    return ShadowOutcome.failure("新引擎未返回结果");
                }
                return ShadowOutcome.of(result.isSuccess(), result.getMatchedBranch(),
                        result.getData(), result.getErrorMessage());
            }
        };
    }
}
