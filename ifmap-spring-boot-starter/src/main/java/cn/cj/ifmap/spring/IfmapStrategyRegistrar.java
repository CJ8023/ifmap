package cn.cj.ifmap.spring;

import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.CallbackRegistry;
import cn.cj.ifmap.core.strategy.FullParamStrategy;
import cn.cj.ifmap.core.strategy.FullParamStrategyRegistry;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.spi.IfmapCallbackHandler;
import cn.cj.ifmap.core.strategy.LogicBranchStrategy;
import cn.cj.ifmap.core.strategy.LogicBranchStrategyRegistry;
import cn.cj.ifmap.core.strategy.SpecialDealStrategy;
import cn.cj.ifmap.core.strategy.SpecialDealStrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 把宿主机里的策略 bean 自动注册进对应的策略注册表 —— 替代 ECC 里
 * <b>按 bean 名反射调用方法</b> 的老做法（设计文档 P0-7 / TS-6）。
 *
 * <p>识别规则（一个 bean 可实现多个接口）：</p>
 * <ul>
 *   <li>{@link SpecialDealStrategy} → 按 <b>bean 名</b>注册（对齐 {@code strategy_name} 列）</li>
 *   <li>{@link FullParamStrategy} → 按 {@code @FullParam(bankCode, busiNode)} 注册</li>
 *   <li>{@link LogicBranchStrategy} → 按 {@code @LogicBranch} 注册</li>
 *   <li>{@link IfmapActionHandler} → 按 {@code @IfmapAction} 注册</li>
 *   <li>{@link IfmapCallbackHandler} → 按接口号注册</li>
 * </ul>
 *
 * <p>宿主机 bean 缺失注解时注册会失败并<b>快速失败</b>（启动期就报，不留到线上）。</p>
 *
 * @author caijun
 */
public class IfmapStrategyRegistrar implements BeanPostProcessor, BeanFactoryAware {

    private static final Logger log = LoggerFactory.getLogger(IfmapStrategyRegistrar.class);

    private final List<String> registeredBeanNames = new CopyOnWriteArrayList<String>();

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (isInfrastructure(bean)) {
            return bean;
        }
        boolean registered = false;
        if (bean instanceof SpecialDealStrategy) {
            beanFactory.getBean(SpecialDealStrategyRegistry.class).register(beanName, (SpecialDealStrategy) bean);
            registered = true;
        }
        if (bean instanceof FullParamStrategy) {
            beanFactory.getBean(FullParamStrategyRegistry.class).register((FullParamStrategy) bean);
            registered = true;
        }
        if (bean instanceof LogicBranchStrategy) {
            beanFactory.getBean(LogicBranchStrategyRegistry.class).register((LogicBranchStrategy) bean);
            registered = true;
        }
        if (bean instanceof IfmapActionHandler) {
            beanFactory.getBean(ActionRegistry.class).register((IfmapActionHandler) bean);
            registered = true;
        }
        if (bean instanceof IfmapCallbackHandler) {
            beanFactory.getBean(CallbackRegistry.class).register((IfmapCallbackHandler) bean);
            registered = true;
        }
        if (registered) {
            registeredBeanNames.add(beanName);
            log.info("ifmap 已注册宿主机策略 bean：[{}]（{}）", beanName, ClassUtils.getUserClass(bean).getName());
        }
        return bean;
    }

    /** 注册表自身与编排器不是策略 bean，跳过以免自注册。 */
    private static boolean isInfrastructure(Object bean) {
        return bean instanceof IfmapOrchestrator
                || bean instanceof SpecialDealStrategyRegistry
                || bean instanceof FullParamStrategyRegistry
                || bean instanceof LogicBranchStrategyRegistry
                || bean instanceof ActionRegistry
                || bean instanceof CallbackRegistry;
    }

    /** 已注册的策略 bean 名（供排障与测试断言）。 */
    public List<String> registeredBeanNames() {
        return Collections.unmodifiableList(new ArrayList<String>(registeredBeanNames));
    }
}
