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
import cn.cj.ifmap.json.tck.JudgeConsistencyTestBase;

/**
 * Jackson 实现跑一遍判定结论一致性套件。
 *
 * @author caijun
 */
class JacksonJudgeConsistencyTest extends JudgeConsistencyTestBase {

    @Override
    protected JsonOps jsonOps() {
        return new JacksonJsonOps();
    }
}
