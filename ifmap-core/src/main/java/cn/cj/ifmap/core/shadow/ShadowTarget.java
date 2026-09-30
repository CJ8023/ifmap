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

/**
 * 影子运行的**存量链路**（主链路）适配器：把现有实现包一层，产出可比的 {@link ShadowOutcome}。
 *
 * <p>注意异常语义：本接口<b>不允许声明受检异常</b>，且 <b>{@link ShadowRunner} 不会捕获这里的异常</b>。
 * 也就是说存量链路怎么抛，调用方就怎么收到 —— 影子运行不能改变主链路的任何行为，
 * 包括失败行为。存量侧的受检异常请在适配器里自行包装成 {@code RuntimeException}。</p>
 *
 * <p>示例（迁移期的适配器，切换完成后整个类直接删掉）：</p>
 *
 * <pre>{@code
 * ShadowTarget legacy = request -> {
 *     ApiResp resp = oldBankintService.execute(legacyPayload);
 *     Map<String, Object> fields = new LinkedHashMap<String, Object>();
 *     fields.put("applyNo", resp.getApplyNo());
 *     fields.put("applyStatus", resp.getStatus());
 *     return ShadowOutcome.of(resp.isSuccess(), resp.getCode(), fields);
 * };
 * }</pre>
 *
 * @author caijun
 */
@FunctionalInterface
public interface ShadowTarget {

    /** 执行存量链路并归一化成可比结果。 */
    ShadowOutcome execute(ShadowRequest request);
}
