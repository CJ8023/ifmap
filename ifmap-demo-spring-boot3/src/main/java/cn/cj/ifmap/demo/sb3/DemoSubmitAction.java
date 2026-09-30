package cn.cj.ifmap.demo.sb3;

import cn.cj.ifmap.core.strategy.IfmapAction;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.strategy.StrategyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 演示逻辑分支动作：注解值 = {@code ifmap_logic_branch_config.method_flag}。
 *
 * <p>取代存量的"按方法名反射调用"：动作 key 是显式注解值，重命名方法不会静默失效；
 * 缺失动作默认快速失败（{@code ifmap.orchestrator.fail-on-missing-action=true}）。</p>
 *
 * @author caijun
 */
@Component
@IfmapAction("submit")
public class DemoSubmitAction implements IfmapActionHandler {

    private static final Logger log = LoggerFactory.getLogger(DemoSubmitAction.class);

    @Override
    public void execute(StrategyContext context) {
        log.info("分支动作已执行：interfaceNo={}, bizId={}, 命中分支={}",
                context.getInterfaceNo(), context.getBizId(), context.getMatchedBranch());
    }
}
