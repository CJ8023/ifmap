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
package cn.cj.ifmap.spring;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.rule.RuleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 把宿主机里「带 {@code @IfmapRule} 方法的 Spring bean」自动注册进 {@link RuleRegistry}。
 *
 * <p>宿主机只需把规则类声明为 bean（{@code @Component} / {@code @Bean}），
 * <b>不需要</b>手动调用 {@code registry.register(...)}：</p>
 *
 * <pre>{@code
 * @Component
 * public class MyBankRules {
 *     @IfmapRule(value = "bankAcctNo", desc = "银行账号", example = "...")
 *     public String bankAcctNo(String v) { ... }
 * }
 * }</pre>
 *
 * <p>规则注册发生在 bean 初始化之后、业务首次调用之前；注册顺序不影响解析结果
 * （{@link RuleRegistry} 内部对方法做了确定性排序）。</p>
 *
 * @author caijun
 */
public class IfmapRuleRegistrar implements BeanPostProcessor, BeanFactoryAware {

    private static final Logger log = LoggerFactory.getLogger(IfmapRuleRegistrar.class);

    private final Map<Class<?>, Boolean> ruleTypeCache = new ConcurrentHashMap<Class<?>, Boolean>();
    private final List<String> registeredBeanNames = new CopyOnWriteArrayList<String>();

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof RuleRegistry || bean instanceof IfmapEngine) {
            return bean;
        }
        Class<?> userClass = ClassUtils.getUserClass(bean.getClass());
        if (!hasRuleMethod(userClass)) {
            return bean;
        }
        RuleRegistry registry = beanFactory.getBean(RuleRegistry.class);
        registry.register(bean);
        registeredBeanNames.add(beanName);
        log.info("ifmap 已注册宿主机规则 bean：[{}]（{}）", beanName, userClass.getName());
        return bean;
    }

    private boolean hasRuleMethod(Class<?> type) {
        Boolean cached = ruleTypeCache.get(type);
        if (cached != null) {
            return cached;
        }
        boolean found = false;
        for (Method method : type.getMethods()) {
            if (method.getAnnotation(IfmapRule.class) != null) {
                found = true;
                break;
            }
        }
        ruleTypeCache.put(type, found);
        return found;
    }

    /** 已注册的规则 bean 名（供排障与测试断言）。 */
    public List<String> registeredBeanNames() {
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(registeredBeanNames));
    }
}
