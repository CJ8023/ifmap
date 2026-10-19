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
package cn.cj.ifmap.core.spi;

import cn.cj.ifmap.core.orchestrator.Judgement;

/**
 * 结果判定器 SPI：一个接口「怎么算成功」的全部逻辑都在这里。
 *
 * <p>默认实现 {@code ruleSpec}（注册名）读配置列 {@code result_judge}；
 * 宿主可注册自己的判定器覆盖（配置列 {@code judge_name} 指定）。</p>
 *
 * <p>判定器<b>不允许抛异常</b>：规格非法、响应非 JSON 都折算成
 * {@link Judgement} 的失败类型（fail-closed）。</p>
 *
 * @author caijun
 */
public interface ResultJudge {

    /** 注册名（配置列 {@code judge_name} 里写的值）。 */
    String name();

    /** 判定一次调用结果。 */
    Judgement judge(ResultJudgeContext context);
}
