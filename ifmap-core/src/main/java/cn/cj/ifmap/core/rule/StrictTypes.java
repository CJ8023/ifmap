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
package cn.cj.ifmap.core.rule;

/**
 * 模板里「静默出错」的处置口径：{@link #WARN} 保持存量行为只告警，{@link #FAIL} 直接拒绝。
 *
 * <p>为什么要有这一档：存量引擎有一类错误既不改字段也不抛异常 —— 数组被 {@code StringBuilder.append}
 * 串成 {@code "[01, 02]"}、"abc" 当 0 相加，报文照发。这类错误一旦静默，只能靠对账发现。
 * 但一次全改成抛异常会让存量模板在升级当晚集体失败，所以先给一档可观测的 {@code WARN}
 * （默认），下一版把默认值切到 {@code FAIL}。</p>
 *
 * <p>注：表达式错语法（{@code @sum@$.a,$.b} / 未闭合 {@code @FUN(}）<b>不受本开关控制</b>，
 * 启动期与保存前一律拒绝 —— 错的东西不该能存进去。</p>
 *
 * @author caijun
 */
public enum StrictTypes {

    /** 默认：保持既有输出，打 ERROR 日志（可观测但不打断业务）。 */
    WARN,

    /** 直接抛异常（附字段路径与替代写法）。 */
    FAIL;

    /** 是否快速失败。 */
    public boolean failFast() {
        return this == FAIL;
    }
}
