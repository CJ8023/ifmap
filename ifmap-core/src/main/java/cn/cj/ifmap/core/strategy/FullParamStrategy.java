package cn.cj.ifmap.core.strategy;

import java.util.Map;

/**
 * 主参数组包策略（对应存量 20 个 {@code *FullParamStrategyImpl}，按 {@code @FullParam(bankCode, busiNode)} 注册）。
 *
 * @author caijun
 */
public interface FullParamStrategy {

    /**
     * 组装主参数。
     *
     * @return 组包结果（null 视为空）；会被合并进 {@code context} 的参数表
     */
    Map<String, Object> assemble(StrategyContext context);
}
