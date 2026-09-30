package cn.cj.ifmap.core.strategy;

/**
 * 逻辑分支动作处理器：由 {@code ifmap_logic_branch_config.method_flag} 作为 key 注册。
 *
 * @author caijun
 */
public interface IfmapActionHandler {

    /** 执行动作（可读写 {@code context} 里的参数）。 */
    void execute(StrategyContext context);
}
