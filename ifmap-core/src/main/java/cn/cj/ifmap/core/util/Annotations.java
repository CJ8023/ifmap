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
package cn.cj.ifmap.core.util;

import java.lang.annotation.Annotation;

/**
 * 注解查找：沿<b>父类链与接口链</b>回溯。
 *
 * <p>为什么需要：Spring AOP / CGLIB 代理类的 {@code getClass().getAnnotation(X)} 取不到
 * 标注在用户类上的注解（类注解不继承）。宿主机策略 bean 常被事务/日志切面代理，
 * 所以注册表一律走本工具查找。</p>
 *
 * @author caijun
 */
public final class Annotations {

    private Annotations() {
    }

    /** 在类自身、父类链、接口链上查找注解；找不到返回 null。 */
    public static <A extends Annotation> A find(Class<?> type, Class<A> annotationType) {
        if (type == null || annotationType == null) {
            return null;
        }
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            A found = current.getAnnotation(annotationType);
            if (found != null) {
                return found;
            }
            found = findOnInterfaces(current, annotationType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static <A extends Annotation> A findOnInterfaces(Class<?> type, Class<A> annotationType) {
        Class<?>[] interfaces = type.getInterfaces();
        for (Class<?> itf : interfaces) {
            A found = itf.getAnnotation(annotationType);
            if (found != null) {
                return found;
            }
            found = findOnInterfaces(itf, annotationType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
