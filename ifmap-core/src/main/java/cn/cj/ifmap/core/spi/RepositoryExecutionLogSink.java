package cn.cj.ifmap.core.spi;

import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.ExecutionLog;

/**
 * 默认日志出口：委托 {@link ConfigRepository#saveExecutionLog}。
 *
 * @author caijun
 */
public class RepositoryExecutionLogSink implements ExecutionLogSink {

    private final ConfigRepository repository;

    public RepositoryExecutionLogSink(ConfigRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("repository 不能为 null");
        }
        this.repository = repository;
    }

    @Override
    public void write(ExecutionLog log) {
        repository.saveExecutionLog(log);
    }
}
