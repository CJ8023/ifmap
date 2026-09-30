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

import cn.cj.ifmap.core.exception.IfmapException;
import cn.cj.ifmap.core.exception.RuleInvocationException;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 一条规则 = 一个被 {@link IfmapRule} 标注的方法 + 承载它的实例。
 *
 * @author caijun
 */
final class RuleMethod {

    private final Object bean;
    private final Method method;
    private final boolean hasContext;
    private final boolean varargs;
    private final Class<?>[] params;
    private final int dataParamCount;
    private final int minArity;
    private final RuleDescriptor descriptor;

    RuleMethod(Object bean, Method method, String name) {
        this.bean = bean;
        this.method = method;
        this.method.setAccessible(true);
        this.params = method.getParameterTypes();
        this.hasContext = params.length > 0 && RuleContext.class.isAssignableFrom(params[0]);
        this.varargs = method.isVarArgs();
        this.dataParamCount = params.length - (hasContext ? 1 : 0);
        this.minArity = varargs ? dataParamCount - 1 : dataParamCount;
        IfmapRule ann = method.getAnnotation(IfmapRule.class);
        this.descriptor = new RuleDescriptor(name, method.getDeclaringClass().getName(), signature(),
                ann == null ? "" : ann.desc(), ann == null ? "" : ann.example(),
                ann != null && ann.override(), ann != null && ann.allowNullArgs());
    }

    RuleDescriptor descriptor() {
        return descriptor;
    }

    boolean acceptsArity(int argCount) {
        return varargs ? argCount >= minArity : argCount == dataParamCount;
    }

    /** 第 argIndex 个「数据参数」的形参类型（变长参数取组件类型）。 */
    Class<?> dataParamType(int argIndex) {
        int p = argIndex + (hasContext ? 1 : 0);
        if (varargs && p >= params.length - 1) {
            return params[params.length - 1].getComponentType();
        }
        return params[p];
    }

    boolean canInvoke(Object[] args) {
        if (!acceptsArity(args.length)) {
            return false;
        }
        for (int i = 0; i < args.length; i++) {
            if (!Coercions.canCoerce(args[i], dataParamType(i))) {
                return false;
            }
        }
        return true;
    }

    /** 匹配得分：精确匹配 2 分，强转匹配 1 分。分高者优先。 */
    int score(Object[] args) {
        int score = 0;
        for (int i = 0; i < args.length; i++) {
            score += Coercions.isExact(args[i], dataParamType(i)) ? 2 : 1;
        }
        return score;
    }

    /** 声明类全名。 */
    String owner() {
        return method.getDeclaringClass().getName();
    }

    /** 签名键：方法名 + 形参类型，用于检测重复注册。 */
    String signatureKey() {
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        for (Class<?> p : params) {
            sb.append(p.getName()).append(';');
        }
        return sb.append(')').toString();
    }

    String signature() {
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getSimpleName());
        }
        sb.append(')');
        return sb.toString();
    }

    Object invoke(RuleContext context, Object[] args) {
        Object[] actual = new Object[params.length];
        int offset = hasContext ? 1 : 0;
        if (hasContext) {
            actual[0] = context == null ? RuleContext.empty() : context;
        }
        if (varargs) {
            int fixedCount = dataParamCount - 1;
            for (int i = 0; i < fixedCount; i++) {
                actual[i + offset] = Coercions.coerce(args[i], params[i + offset]);
            }
            Class<?> componentType = params[params.length - 1].getComponentType();
            int tailCount = args.length - fixedCount;
            Object tail = Array.newInstance(componentType, tailCount);
            for (int i = 0; i < tailCount; i++) {
                Array.set(tail, i, Coercions.coerce(args[fixedCount + i], componentType));
            }
            actual[params.length - 1] = tail;
        } else {
            for (int i = 0; i < args.length; i++) {
                actual[i + offset] = Coercions.coerce(args[i], params[i + offset]);
            }
        }
        try {
            return method.invoke(bean, actual);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IfmapException) {
                throw (IfmapException) cause;
            }
            throw new RuleInvocationException("规则 [" + descriptor.getName() + "] 执行失败（" + signature()
                    + "，实参 " + Arrays.toString(args) + "）：" + cause, cause);
        } catch (IllegalAccessException e) {
            throw new RuleInvocationException("规则 [" + descriptor.getName() + "] 不可访问：" + signature(), e);
        } catch (IllegalArgumentException e) {
            throw new RuleInvocationException("规则 [" + descriptor.getName() + "] 参数不匹配（" + signature()
                    + "，实参 " + Arrays.toString(args) + "）：" + e.getMessage(), e);
        }
    }
}
