package cn.cj.ifmap.core.strategy;

import java.util.Map;

/**
 * 特殊处理策略（对应存量 38 个 {@code *SpecialDealStrategyImpl}）。
 *
 * <p>按 <b>bean 名</b> 注册（与 {@code ifmap_config.strategy_name} 对齐），
 * 注册表同时接受"类简单名"与"首字母小写名"两个宽松 key（解决 P0-7），命中宽松 key 时记 WARN。</p>
 *
 * @author caijun
 */
public interface SpecialDealStrategy {

    /**
     * 执行特殊处理，可修改 {@code context} 里的参数。
     *
     * @return 需要替换的参数（null 表示不改动）；返回的键值会被合并进 {@code context}
     */
    Map<String, Object> apply(StrategyContext context);
}
