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
package cn.cj.ifmap.jdbc;

import cn.cj.ifmap.core.config.ConfigHistoryRepository;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.model.IfmapConfigHistory;
import cn.cj.ifmap.core.spi.IdGenerator;
import cn.cj.ifmap.core.spi.SnowflakeIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * 配置变更历史仓储（`ifmap_config_history`）的 JDBC 实现。
 *
 * <p>设计取舍：{@code snapshot} / {@code diff} 由调用方（管理端）序列化成 JSON 文本后传入，
 * provider 层<b>不引入任何 JSON 库</b>（core / provider 的依赖边界不变）。</p>
 *
 * @author caijun
 */
public class JdbcConfigHistoryRepository implements ConfigHistoryRepository {

    private static final int DEFAULT_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final TableNameResolver tables;
    private final IdGenerator idGenerator;

    public JdbcConfigHistoryRepository(DataSource dataSource) {
        this(dataSource, TableNameResolver.DEFAULT_PREFIX);
    }

    public JdbcConfigHistoryRepository(DataSource dataSource, String tablePrefix) {
        this(new JdbcTemplate(dataSource), new TableNameResolver(tablePrefix), SnowflakeIdGenerator.shared());
    }

    public JdbcConfigHistoryRepository(JdbcTemplate jdbcTemplate, TableNameResolver tables, IdGenerator idGenerator) {
        if (jdbcTemplate == null) {
            throw new IfmapConfigException("JdbcTemplate 不能为空");
        }
        this.jdbc = jdbcTemplate;
        this.tables = tables == null ? TableNameResolver.defaults() : tables;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.shared() : idGenerator;
    }

    /**
     * 追加一条历史记录，返回历史主键。
     *
     * <p>自动补齐：{@code keyId}（为空时生成）、{@code tenantId}（为空时 -1）。
     * {@code add_time} 交给数据库默认值（{@code CURRENT_TIMESTAMP(3)}），保证多实例时钟一致。</p>
     */
    public long insert(IfmapConfigHistory history) {
        if (history == null) {
            throw new IfmapConfigException("历史记录不能为空");
        }
        JdbcValues.require(history.getChangeType(), "changeType");
        JdbcValues.require(history.getSnapshot(), "snapshot");
        if (history.getConfigKeyId() == null) {
            throw new IfmapConfigException("历史记录缺少 configKeyId");
        }
        Long id = history.getKeyId() != null ? history.getKeyId() : idGenerator.nextId();
        if (id == null) {
            throw new IfmapConfigException("新增历史记录缺少主键：请提供 keyId，或注入会生成 ID 的 IdGenerator");
        }
        String sql = "INSERT INTO `" + tables.configHistoryTable() + "`"
                + " (`key_id`,`config_key_id`,`tenant_id`,`interface_no`,`change_type`,`change_reason`,"
                + "`snapshot`,`diff`,`add_user_id`,`add_request_id`)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)";
        jdbc.update(sql, id, history.getConfigKeyId(), JdbcValues.orDefault(history.getTenantId(), -1L),
                JdbcValues.orEmpty(history.getInterfaceNo()), history.getChangeType(),
                JdbcValues.orEmpty(history.getChangeReason()), history.getSnapshot(), history.getDiff(),
                JdbcValues.orEmpty(history.getAddUserId()), JdbcValues.orEmpty(history.getAddRequestId()));
        history.setKeyId(id);
        return id;
    }

    @Override
    public List<IfmapConfigHistory> list(long configKeyId, int limit) {
        int rows = limit <= 0 ? DEFAULT_LIMIT : limit;
        String sql = "SELECT " + IfmapRowMappers.HISTORY_COLUMNS + " FROM `" + tables.configHistoryTable() + "`"
                + " WHERE `config_key_id` = ? ORDER BY `add_time` DESC, `key_id` DESC LIMIT " + rows;
        return jdbc.query(sql, IfmapRowMappers.history(), configKeyId);
    }

    @Override
    public Optional<IfmapConfigHistory> findByKeyId(long keyId) {
        String sql = "SELECT " + IfmapRowMappers.HISTORY_COLUMNS + " FROM `" + tables.configHistoryTable() + "`"
                + " WHERE `key_id` = ?";
        List<IfmapConfigHistory> list = jdbc.query(sql, IfmapRowMappers.history(), keyId);
        return list.isEmpty() ? Optional.<IfmapConfigHistory>empty() : Optional.of(list.get(0));
    }

    /** 表名解析器（管理端拼报表/排错用）。 */
    public TableNameResolver tables() {
        return tables;
    }
}
