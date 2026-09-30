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
package cn.cj.ifmap.json.jackson;

import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.json.tck.JsonOpsConformanceTestBase;

/**
 * Jackson 实现跑一遍 {@code JsonOps} 一致性测试套件（TCK）。
 *
 * <p>这个类本身没有测试代码：所有断言都在 {@link JsonOpsConformanceTestBase} 里，
 * 换 JSON 库时照抄本类（把返回值换成新实现）即可复用同一套合规断言。</p>
 *
 * @author caijun
 */
class JacksonJsonOpsConformanceTest extends JsonOpsConformanceTestBase {

    @Override
    protected JsonOps jsonOps() {
        return new JacksonJsonOps();
    }
}
