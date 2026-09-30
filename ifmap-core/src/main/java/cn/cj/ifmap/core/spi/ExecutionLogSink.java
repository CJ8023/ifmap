package cn.cj.ifmap.core.spi;

import cn.cj.ifmap.core.config.ExecutionLog;

/**
 * 执行日志落库 SPI。
 *
 * <p>默认实现 {@link RepositoryExecutionLogSink} 直接委托 {@code ConfigRepository#saveExecutionLog}；
 * 宿主机若要写异步队列 / 审计库，替换本接口即可。</p>
 *
 * <p><b>注意</b>：写日志失败<b>不允许吞掉业务异常</b>（对应设计 §7.6 的行为修正），
 * 实现方应自行 WARN 记录。</p>
 *
 * @author caijun
 */
public interface ExecutionLogSink {

    /** 写一条执行日志（入参已脱敏、已截断）。 */
    void write(ExecutionLog log);
}
