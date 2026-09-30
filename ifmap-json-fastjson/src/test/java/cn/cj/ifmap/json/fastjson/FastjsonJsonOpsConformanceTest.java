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
package cn.cj.ifmap.json.fastjson;

import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.json.tck.JsonOpsConformanceTestBase;

/**
 * fastjson 实现跑一遍 {@code JsonOps} 一致性测试套件（TCK）。
 *
 * <p>这个类本身没有测试代码：所有断言都在 {@link JsonOpsConformanceTestBase} 里。
 * 它与 {@code JacksonJsonOpsConformanceTest} 跑的是同一套 24 条契约 ——
 * 换 JSON 库不能悄悄改变取值语义（路径不存在返回什么、通配为空返回什么、数字是什么类型）。</p>
 *
 * @author caijun
 */
class FastjsonJsonOpsConformanceTest extends JsonOpsConformanceTestBase {

    @Override
    protected JsonOps jsonOps() {
        return new FastjsonJsonOps();
    }
}
