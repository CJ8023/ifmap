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
package cn.cj.ifmap.admin;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * 把「大到 JavaScript 存不下」的 {@code long} 写成 JSON 字符串，其余仍写数字。
 *
 * <p>为什么需要：雪花主键约 9e17，而 JS 的 {@code Number.MAX_SAFE_INTEGER} 只有 2^53−1
 * （9007199254740991）。以 JSON number 下发时浏览器用 double 解析，
 * {@code 899855950645559296} 会变成 {@code 899855950645559300}——页面拿这串"就近取整"的数字
 * 回传，服务端就找不到那条记录，报「配置不存在：#899855950645559300」。</p>
 *
 * <p>阈值做判断而不是一刀切：{@code tenantId}、{@code version}、总数这类小整数仍是数字，
 * 不会把现有契约改成"全是字符串"。</p>
 *
 * <p>只作用于管理端的响应序列化（{@link IfmapAdminAutoConfiguration} 里注册），
 * 引擎链路的 {@code JsonOps} 与入库快照都不受影响。</p>
 *
 * @author caijun
 */
public final class JsSafeLongSerializer extends JsonSerializer<Long> {

    /** {@code Number.MAX_SAFE_INTEGER} = 2^53 − 1。 */
    static final long MAX_SAFE = 9007199254740991L;

    @Override
    public void serialize(Long value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
        // 超阈值写字符串、其余写数字：整型契约只在「JS 存不下」这一段改变
        if (value > MAX_SAFE || value < -MAX_SAFE) {
            generator.writeString(value.toString());
        } else {
            generator.writeNumber(value.longValue());
        }
    }
}
