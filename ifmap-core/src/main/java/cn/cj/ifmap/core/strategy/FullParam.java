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
package cn.cj.ifmap.core.strategy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明主参数组包策略的适用维度（与存量 {@code @FullParam} 语义一致）。
 *
 * <p>{@code partnerCode} / {@code busiNode} 任一项写 {@code *} 或留空表示通配；
 * 匹配优先级：精确 &gt; (partner,*) &gt; (*,busiNode) &gt; (*,*)。</p>
 *
 * @author caijun
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FullParam {

    /** 银行编码，{@code *} 表示不限。 */
    String partnerCode() default "*";

    /** 业务节点，{@code *} 表示不限。 */
    String busiNode() default "*";
}
