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

    /**
     * 升级脚本（{@code ALTER TABLE}）。
     *
     * <p>001~004 是建表，005~007 是<b>加列/删列</b>：全新建库时两者顺序执行才落到目标结构，
     * 存量库升级时也走同一份脚本（Liquibase 与 {@code ifmap.ddl.auto=true} 共用，不分叉）。</p>
     *
     * <p>为什么 005~007 不并回 001：Liquibase 按 changeset 的 checksum 判定「是否需要重跑」，
     * 改 001 会让所有已部署库（包括检查过 DDL 的库）重跑建表而失败。便宜的做法是新增 changeset，
     * 把 {@code result_flag/success_value} 的删除留在 005。</p>
     */
    private static final String[] ALTER_FILES = {
            "db/changelog/v1.0.0/005-config-result-judge.sql",
            "db/changelog/v1.0.0/006-execution-log-judge-columns.sql",
            "db/changelog/v1.0.0/007-config-call-direction.sql"
    };

    /** {@code ALTER TABLE `t` ADD|DROP COLUMN `c` ...} 的识别式（只认本项目的两条形式）。 */
    private static final Pattern ALTER_COLUMN_PATTERN = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+`?([A-Za-z0-9_]+)`?\\s+(ADD|DROP)\\s+COLUMN\\s+`?([A-Za-z0-9_]+)`?.*");

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
        applyAlterScripts();
        if (created.isEmpty()) {
            log.info("ifmap 建表检查完成：4 张表均已存在，未做变更（前缀 {}）", tables.prefix());
        } else {
            log.info("ifmap 建表完成：新建 {} 张表 {}", created.size(), created);
        }
        return created;
    }

    /**
     * 应用升级脚本（幂等）：{@code ADD COLUMN} 只在列不存在时执行、{@code DROP COLUMN} 只在列存在时执行。
     *
     * <p><b>为什么靠「列是否存在」判断而不是靠版本表</b>：{@code ifmap.ddl.auto=true} 的用户可能是
     * 全新建库（001 建完表直接 ALTER）、也可能是手工跑过 Liquibase 的存量库（列已齐）。
     * 用列探针判断两种情况都得到正确结果，而且不需要额外的版本表（starter 不引入 Liquibase 依赖）。</p>
     *
     * <p>解析入口是 {@link #ALTER_COLUMN_PATTERN}：只认「一张表 + 一个列 + 一条加列语句」这种形态，
     * 复合 ALTER 语句会被忽略（列探针拿不到列名就没法判断该不该执行）。</p>
     *
     * @return 本次实际执行的动作数（0 = 已是目标结构）
     */
    public int applyAlterScripts() {
        int applied = 0;
        for (String file : ALTER_FILES) {
            for (String statement : splitStatements(readRaw(file).replace(PREFIX_PLACEHOLDER, tables.prefix()))) {
                Matcher matcher = ALTER_COLUMN_PATTERN.matcher(statement);
                if (!matcher.matches()) {
                    throw new IfmapConfigException("升级脚本 " + file + " 出现未识别的语句（只支持 "
                            + "ALTER TABLE ... ADD/DROP COLUMN）：" + statement.substring(0, Math.min(120, statement.length())));
                }
                // 列探针决定这条 ALTER 要不要执行：ADD 时列已存在就跳过，DROP 时列不存在也跳过（= 幂等）
                String table = matcher.group(1);
                boolean add = "ADD".equalsIgnoreCase(matcher.group(2));
                String column = matcher.group(3);
                boolean exists = columnExists(table, column);
                if (add == exists) {
                    continue;
                }
                // 非 MySQL 系要先把 json 列换成 text，否则 H2 建不出来；末尾分号必须去掉，否则 Statement.execute 报错
                jdbc.execute(dropTrailingSemicolon(mysqlFamily ? statement : downgradeJsonColumns(statement)));
                applied++;
                log.info("ifmap 升级表结构：[{}] {} 列 {}", table, add ? "新增" : "删除", column);
            }
        }
        if (applied == 0) {
            log.info("ifmap 结构升级检查完成：列均已是最新，未做变更（前缀 {}）", tables.prefix());
        }
        return applied;
    }

    /** 列是否存在（投一条零行查询，MySQL / H2 通用，不依赖 information_schema）。 */
    private boolean columnExists(String table, String column) {
        try {
            jdbc.execute("SELECT " + column + " FROM " + table + " WHERE 1 = 0");
            return true;
        } catch (DataAccessException e) {
            return false;
        }
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

    /** 读脚本原文（去空行与 {@code --} 注释行，保留分号以便按语句切开）。 */
    private String readRaw(String location) {
        Resource resource = resourceLoader.getResource("classpath:" + location);
        if (!resource.exists()) {
            throw new IfmapConfigException("ifmap 升级脚本不存在：" + location
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
            throw new IfmapConfigException("读取 ifmap 升级脚本失败：" + location, e);
        }
        return sb.toString();
    }

    /** 按分号切语句（跳过单引号内的分号：列注释里就有 {@code ;}）。 */
    static java.util.List<String> splitStatements(String sql) {
        java.util.List<String> statements = new java.util.ArrayList<String>();
        // 引号状态机：只有引号外的分号才切语句
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            // 单引号内的分号不算分隔符（列注释里就有 "a;b"）；'' 是 SQL 的转义写法，成对跳过
            if (c == '\'') {
                boolean escaped = i + 1 < sql.length() && sql.charAt(i + 1) == '\'';
                if (escaped) {
                    current.append(c).append(sql.charAt(++i));
                    continue;
                }
                inQuote = !inQuote;
            }
            if (c == ';' && !inQuote) {
                addIfNotBlank(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addIfNotBlank(statements, current);
        return statements;
    }

    /** 收一条语句（去空白、去尾部 {@code ;}），空语句直接丢。 */
    private static void addIfNotBlank(java.util.List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }

    /** 去掉结尾分号：JDBC 的 {@code Statement.execute} 不接受空语句，多一个 {@code ;} 就会报错。 */
    private static String dropTrailingSemicolon(String statement) {
        String trimmed = statement.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
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

    /**
     * 把 DDL 里的 {@code json} 列降级成 {@code text}：H2 等库没有 JSON 类型（{@code JSON} 列存不了 JDBC 字符串），
     * 只有 MySQL 系才保留原样。识别规则见 {@link #JSON_COLUMN_PATTERN}。
     */
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

    /** 探测数据库产品名（拿不到连接时按非 MySQL 处理，走更保守的 H2 兼容分支）。 */
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
