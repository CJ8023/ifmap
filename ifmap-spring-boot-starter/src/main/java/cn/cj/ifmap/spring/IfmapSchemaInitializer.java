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
package cn.cj.ifmap.spring;

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.jdbc.TableNameResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动期建表（{@code ifmap.ddl.auto=true}，默认开启）：让"引入 starter 就能用"闭环（P0-4）。
 *
 * <p>执行的是 <b>ifmap-provider-jdbc 里那份生产建表脚本</b>（不是另写一份测试 DDL）：
 * 逐个文件判断"表是否已存在"，不存在才执行，因此多次启动、多实例并发启动都安全。</p>
 *
 * <p><b>非 MySQL 数据库</b>（例如开发/测试用的 H2）：脚本里的表尾 MySQL 表选项
 * （{@code ENGINE= / DEFAULT CHARSET= / COLLATE= / ROW_FORMAT= / COMMENT=}）会被剥掉后再执行
 * —— 这些选项在 H2 上即使是 {@code MODE=MySQL} 也不被接受（W2 实测），
 * 而列定义、主键、唯一键、索引、列注释全部原样保留。剥表尾是<b>断言式</b>的：
 * 脚本结构与预期不符即失败，不会"静默建半张表"。</p>
 *
 * <p>H2 需要 {@code MODE=MySQL}（脚本用了 {@code KEY idx_...} / {@code tinyint(1)} /
 * {@code datetime(3)} 等 MySQL 写法），例：
 * {@code jdbc:h2:mem:demo;MODE=MySQL;DB_CLOSE_DELAY=-1}。</p>
 *
 * <p><b>生产建议</b>：由 DBA 执行 SQL 后把 {@code ifmap.ddl.auto} 置为 {@code false}，
 * 或把 {@code ifmap.table-prefix} 指向已有表。</p>
 *
 * @author caijun
 */
public class IfmapSchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(IfmapSchemaInitializer.class);

    /** 建表脚本（与 Liquibase changelog 完全同一份文件）。 */
    private static final String[] DDL_FILES = {
            "db/changelog/v1.0.0/001-create-ifmap-config.sql",
            "db/changelog/v1.0.0/002-create-logic-branch.sql",
            "db/changelog/v1.0.0/003-create-execution-log.sql",
            "db/changelog/v1.0.0/004-create-config-history.sql"
    };

    /** 脚本里表名前缀的占位符（与 Liquibase changelog 的 property 同名）。 */
    static final String PREFIX_PLACEHOLDER = "${tablePrefix}";

    /** MySQL 表尾选项的起始标记。 */
    private static final String MYSQL_TAIL_PATTERN = "(?is)\\)\\s*ENGINE=.*";

    /**
     * 列类型 {@code json}（MySQL 专用）。
     *
     * <p>为什么要改写：H2 1.4.200 的 {@code JSON} 类型与 MySQL 的 {@code json} <b>语义不等价</b> ——
     * 用 JDBC 写字符串（{@code PreparedStatement.setString("{\"a\":1}")}）时 H2 会把它当成
     * "JSON 字符串值"存成 {@code "{\"a\":1}"}（多一层引号 + 反转义），读回来就不再是对象，
     * 历史快照 / 差异回滚会直接解析失败。MySQL 侧不存在这个问题，所以只在非 MySQL 分支把
     * {@code json} 列降级成 {@code text}，让 H2 上的读写口径与 MySQL 一致（存 / 取都是 JSON 文本）。</p>
     */
    private static final Pattern JSON_COLUMN_PATTERN =
            Pattern.compile("(?i)(`[A-Za-z0-9_]+`[ \\t]+)json(?=[ \\t,])");

    private final JdbcTemplate jdbc;
    private final TableNameResolver tables;
    private final ResourceLoader resourceLoader;
    private final boolean mysqlFamily;

    public IfmapSchemaInitializer(DataSource dataSource, TableNameResolver tables, ResourceLoader resourceLoader) {
        if (dataSource == null || tables == null || resourceLoader == null) {
            throw new IfmapConfigException("IfmapSchemaInitializer 构造参数不能为空");
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.tables = tables;
        this.resourceLoader = resourceLoader;
        this.mysqlFamily = detectMysqlFamily(dataSource);
    }

    @Override
    public void afterPropertiesSet() {
        createTablesIfAbsent();
    }

    /**
     * 建表（幂等）。
     *
     * @return 本次实际新建的表名
     */
    public List<String> createTablesIfAbsent() {
        String[] tableNames = {
                tables.configTable(),
                tables.logicBranchTable(),
                tables.executionLogTable(),
                tables.configHistoryTable()
        };
        List<String> created = new ArrayList<String>(tableNames.length);
        for (int i = 0; i < DDL_FILES.length; i++) {
            String table = tableNames[i];
            if (tableExists(table)) {
                log.debug("ifmap 表 [{}] 已存在，跳过建表", table);
                continue;
            }
            executeDdl(table, readDdl(DDL_FILES[i]));
            created.add(table);
            log.info("ifmap 已建表：[{}]（脚本 {}）", table, DDL_FILES[i]);
        }
        if (created.isEmpty()) {
            log.info("ifmap 建表检查完成：4 张表均已存在，未做变更（前缀 {}）", tables.prefix());
        } else {
            log.info("ifmap 建表完成：新建 {} 张表 {}", created.size(), created);
        }
        return created;
    }

    /** 单条 CREATE TABLE 执行；并发建表时（另一个实例抢先建好）不报错。 */
    private void executeDdl(String table, String ddl) {
        try {
            jdbc.execute(ddl);
        } catch (DataAccessException e) {
            if (tableExists(table)) {
                log.debug("ifmap 表 [{}] 已被其他实例建好，忽略本次失败", table);
                return;
            }
            throw new IfmapConfigException("ifmap 建表失败：" + table + "；SQL 片段："
                    + ddl.substring(0, Math.min(160, ddl.length())), e);
        }
    }

    /** 表是否存在（不依赖 information_schema，MySQL / H2 都适用）。 */
    public boolean tableExists(String table) {
        try {
            jdbc.execute("SELECT 1 FROM " + table + " WHERE 1 = 0");
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    /** 目标库是否 MySQL 系（决定是否剥表尾选项）。 */
    public boolean isMysqlFamily() {
        return mysqlFamily;
    }

    /** 读脚本 + 前缀替换 + 表尾适配 + 去注释（断言式：必须是单条语句）。 */
    private String readDdl(String location) {
        Resource resource = resourceLoader.getResource("classpath:" + location);
        if (!resource.exists()) {
            throw new IfmapConfigException("ifmap 建表脚本不存在：" + location
                    + "（应随 ifmap-provider-jdbc 一起在 classpath 上）");
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            throw new IfmapConfigException("读取 ifmap 建表脚本失败：" + location, e);
        }
        String sql = adaptDdl(sb.toString().replace(PREFIX_PLACEHOLDER, tables.prefix()).trim(), mysqlFamily);
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (hasStatementSeparator(sql)) {
            throw new IfmapConfigException("建表脚本 " + location
                    + " 含多条语句，IfmapSchemaInitializer 只执行单条 CREATE TABLE；请改用 Liquibase。");
        }
        return sql;
    }

    /**
     * 是否含语句分隔符（分号）。
     *
     * <p>必须跳过单引号内的内容：本项目的列注释里就有分号，例如
     * {@code COMMENT '删除标识;0:未删除1:已删除'}，按字符串扫会误判成两条语句。</p>
     */
    static boolean hasStatementSeparator(String sql) {
        boolean inQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                boolean escaped = i + 1 < sql.length() && sql.charAt(i + 1) == '\'';
                if (escaped) {
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if (c == ';' && !inQuote) {
                return true;
            }
        }
        return false;
    }

    /**
     * 表尾适配：MySQL 原样；非 MySQL 剥掉表尾表选项（断言式）+ 把 {@code json} 列降级成 {@code text}。
     *
     * @param ddl        已替换前缀的建表语句
     * @param mysqlFamily 目标库是否 MySQL 系
     * @return 可执行语句
     */
    static String adaptDdl(String ddl, boolean mysqlFamily) {
        if (mysqlFamily) {
            return ddl;
        }
        String adapted = ddl.replaceAll(MYSQL_TAIL_PATTERN, ")");
        if (adapted.equals(ddl)) {
            throw new IfmapConfigException("非 MySQL 数据库的表尾适配失败：未匹配到 \") ENGINE=...\" 段，"
                    + "建表脚本结构可能已被改坏（拒绝在拿不准的 SQL 上建表）");
        }
        return downgradeJsonColumns(adapted);
    }

    /** 非 MySQL：{@code json} 列 → {@code text}（H2 的 JSON 类型存不了 JDBC 字符串，见 {@link #JSON_COLUMN_PATTERN}）。 */
    private static String downgradeJsonColumns(String ddl) {
        Matcher matcher = JSON_COLUMN_PATTERN.matcher(ddl);
        StringBuilder sb = new StringBuilder(ddl.length());
        int count = 0;
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(1)) + "text");
            count++;
        }
        matcher.appendTail(sb);
        if (count > 0) {
            log.info("ifmap 非 MySQL 建表适配：{} 个 json 列降级为 text", count);
        }
        return sb.toString();
    }

    private static boolean detectMysqlFamily(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String name = product == null ? "" : product.toLowerCase();
            boolean mysql = name.contains("mysql") || name.contains("mariadb");
            if (!mysql) {
                log.info("ifmap 检测到非 MySQL 数据库（{}），建表时将剥掉表尾 MySQL 表选项", product);
            }
            return mysql;
        } catch (SQLException e) {
            // 判定不了就按 MySQL 处理：宁可让脚本原样执行并暴露问题，也不擅自改写 DDL
            log.warn("ifmap 无法识别数据库类型，按 MySQL 处理（脚本原样执行）：{}", e.getMessage());
            return true;
        }
    }
}
