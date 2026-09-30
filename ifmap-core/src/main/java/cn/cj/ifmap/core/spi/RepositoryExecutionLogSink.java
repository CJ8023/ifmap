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
