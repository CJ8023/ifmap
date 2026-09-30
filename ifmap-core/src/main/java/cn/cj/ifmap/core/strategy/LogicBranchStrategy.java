package cn.cj.ifmap.core.strategy;

/**
 * 逻辑分支判断策略（对应存量 12 个 {@code *LogicBranchStrategyImpl}）。
 *
 * <p>按 {@code @LogicBranch("值")} 注册，注解值对应 {@code ifmap_logic_branch_config.logic_branch_flag}。
 * 分支命中后<b>动作</b>由 {@link ActionRegistry} 按
 * {@code method_flag} → Action 映射执行（取代存量的"反射方法名"，见 TS-6）。</p>
 *
 * @author caijun
 */
public interface LogicBranchStrategy {

    /** 是否命中该分支。 */
    boolean match(StrategyContext context);
}
