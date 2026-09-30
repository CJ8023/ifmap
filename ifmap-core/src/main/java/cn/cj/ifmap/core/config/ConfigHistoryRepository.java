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
package cn.cj.ifmap.core.config;

import cn.cj.ifmap.core.model.IfmapConfigHistory;

import java.util.List;
import java.util.Optional;

/**
 * 配置变更历史仓储 SPI（设计 §6.5 / TS-13）：管理端"版本对比 / 一键回滚 / 审计"的数据来源。
 *
 * <p>由管理端在写操作时落库；引擎运行期<b>只读或不读</b>，不参与执行链路。</p>
 *
 * @author caijun
 */
public interface ConfigHistoryRepository {

    /**
     * 按配置主键查历史，<b>按时间倒序</b>（最新在前）。
     *
     * @param configKeyId 配置主键
     * @param limit       最多返回条数（&lt;= 0 视为默认 50）
     */
    List<IfmapConfigHistory> list(long configKeyId, int limit);

    /** 按历史主键查单条（回滚用）。 */
    Optional<IfmapConfigHistory> findByKeyId(long keyId);
}
