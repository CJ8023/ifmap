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
package cn.cj.ifmap.testkit;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测试库入口：同一套测试代码，既可以跑 <b>H2 内存库</b>（默认），也可以跑 <b>真 MySQL</b>。
 *
 * <h2>怎么切到真库</h2>
 *
 * <pre>
 *   set IFMAP_JDBC_URL=jdbc:mysql://host:3306/ifmap
 *   set IFMAP_JDBC_USER=root
 *   set IFMAP_JDBC_PASSWORD=xxx
 *   mvn test
 * </pre>
 *
 * 未设置 {@code IFMAP_JDBC_URL} 时所有测试走 H2 内存库（开发机与公共 CI runner 零依赖）。
 *
 * <h2>隔离：唯一表前缀（不建库）</h2>
 *
 * 真库档下**不创建数据库**，而是在同一个库里给每处测试一个<b>唯一表前缀</b>：
 * {@code itt_<hint≤18>_<hex8>_}。这样：
 * <ul>
 *   <li>不需要 {@code CREATE DATABASE} 权限，也不会碰库里已有的 {@code ifmap_*} / {@code bankint_*} 表；</li>
 *   <li>多个模块、多次运行、并行 fork 之间天然不冲突（hex8 随机）；</li>
 *   <li>JVM 退出时由 shutdown hook 按前缀反查 {@code information_schema} 后 {@code DROP TABLE}。</li>
 * </ul>
 *
 * <b>安全栅栏</b>：前缀必须匹配 {@link #TEST_PREFIX_PATTERN}，待删表名必须 {@code startsWith(prefix)}，
 * 且只查当前库（{@code TABLE_SCHEMA = DATABASE()}）—— 构造上不可能命中 {@code ifmap_*}。
 *
 * <h2>生产 DDL 原样执行</h2>
 *
 * 建表语句直接读 {@code db/changelog/v1.0.0/*.sql}（**生产 DDL，不是测试专用 DDL**）：
 * MySQL 档原样执行（保留 {@code json} 列与 {@code ENGINE/COLLATE/ROW_FORMAT} 表尾），
 * H2 档做断言式降级（剥表尾 + {@code json → text}，与生产 {@code IfmapSchemaInitializer} 同口径）。
 *
 * @author caijun
 */
public final class TestDatabases {

    /** 真库档开关：设置成 JDBC URL 即启用（例如 {@code jdbc:mysql://127.0.0.1:3306/ifmap}）。 */
    public static final String URL_ENV = "IFMAP_JDBC_URL";

    /** 真库档用户名。 */
    public static final String USER_ENV = "IFMAP_JDBC_USER";

    /** 真库档密码（可空）。 */
    public static final String PASSWORD_ENV = "IFMAP_JDBC_PASSWORD";

    /** 生产建表脚本（Liquibase changelog 的 4 个 DDL），顺序即建表顺序。 */
    public static final List<String> PRODUCTION_DDL = Collections.unmodifiableList(Arrays.asList(
            "db/changelog/v1.0.0/001-create-ifmap-config.sql",
            "db/changelog/v1.0.0/002-create-logic-branch.sql",
            "db/changelog/v1.0.0/003-create-execution-log.sql",
            "db/changelog/v1.0.0/004-create-config-history.sql"));

    /** 执行日志归档冷表（可选运维脚本，字面量前缀需自行替换）。 */
    public static final String ARCHIVE_DDL = "db/optional/execution-log-partition/04-create-archive-table.sql";

    /** 测试表前缀的固定开头（与生产默认前缀 {@code ifmap_} 天然不冲突）。 */
    public static final String TEST_PREFIX = "itt_";

    /** 测试表前缀必须匹配的形状：{@code itt_<hint≤18>_<hex8>_}（hint 里保留下划线）。 */
    public static final Pattern TEST_PREFIX_PATTERN = Pattern.compile("^itt_[a-z0-9_]{0,18}_[0-9a-f]{8}_$");

    /** 表名开头必须匹配的测试前缀（表名 = 前缀 + 业务后缀，后缀里也有下划线）。 */
    private static final Pattern TEST_PREFIX_HEAD = Pattern.compile("^(itt_[a-z0-9_]{0,18}_[0-9a-f]{8}_)");

    private static final String H2_DRIVER = "org.h2.Driver";
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final int MAX_HINT_LENGTH = 18;

    /** 残留测试表的“陈旧”阈值（分钟）：见 {@link #sweepStaleTestTables(int)}。 */
    public static final int STALE_MINUTES = 30;
    private static final String H2_TAIL =
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC\n  COMMENT='";

    /** hint → 已解析前缀：同一 hint 在同一 JVM 里固定（与原来"同一 H2 库名"的口径一致）。 */
    private static final Map<String, String> PREFIX_CACHE = new ConcurrentHashMap<String, String>();

    /** hint → H2 内存库 URL：同一 hint 复用同一个库（与真库档"同一 hint 同一组表"口径一致）。 */
    private static final Map<String, String> H2_URL_CACHE = new ConcurrentHashMap<String, String>();

    /** 本次 JVM 创建过的（MySQL）测试前缀，退出时清理。 */
    private static final Set<String> MANAGED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean SWEPT = new AtomicBoolean();

    private static final Logger LOGGER = Logger.getLogger("cn.cj.ifmap.testkit");

    private TestDatabases() {
    }

    // ------------------------------------------------------------------ 开关

    /** 是否启用了真库档。 */
    public static boolean mysqlEnabled() {
        return jdbcUrl() != null;
    }

    /** 真库档 JDBC URL（未启用返回 {@code null}）。 */
    public static String jdbcUrl() {
        return env(URL_ENV);
    }

    /** 真库档用户名；H2 档固定 {@code sa}。 */
    public static String username() {
        String v = env(USER_ENV);
        if (v == null) {
            if (mysqlEnabled()) {
                throw new IllegalStateException("已设置 " + URL_ENV + " 但未设置 " + USER_ENV + "（真库档必须给用户名）");
            }
            return "sa";
        }
        return v;
    }

    /** 真库档密码；未设置按空串。 */
    public static String password() {
        String v = env(PASSWORD_ENV);
        return v == null ? "" : v;
    }

    // ------------------------------------------------------------------ 前缀

    /**
     * 把一个"逻辑前缀"（H2 档就是真实前缀，如 {@code ifmap_}）解析成当前档位可用的测试前缀。
     *
     * <p>H2 档原样返回；MySQL 档返回 {@code itt_<hint≤18>_<hex8>_} 并登记待清理。同一 hint 在同一
     * JVM 里固定（这样"同一个 H2 库名"的多处调用，在真库档下也落到同一组表上）。</p>
     */
    public static String prefixFor(String hint) {
        if (!mysqlEnabled()) {
            return hint;
        }
        sweepStaleOnce();
        String cached = PREFIX_CACHE.get(hint);
        if (cached != null) {
            return cached;
        }
        String prefix = TEST_PREFIX + sanitizeHint(hint) + "_" + hex8() + "_";
        if (!TEST_PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalStateException("测试前缀不合规（hint=" + hint + "）：" + prefix);
        }
        installShutdownHook();
        PREFIX_CACHE.put(hint, prefix);
        MANAGED.add(prefix);
        return prefix;
    }

    // ------------------------------------------------------------------ Schema

    /** 当前档位的裸数据源（不做隔离登记，适合“查库里有什么表”这类诊断）。 */
    public static DataSource dataSource() {
        return mysqlEnabled() ? mysqlDataSource() : h2DataSource();
    }

    /** 只给数据源（建表由 Spring 侧的 {@code IfmapSchemaInitializer} 负责）。 */
    public static Schema fresh() {
        return fresh("ifmap_");
    }

    /** 只给数据源。 */
    public static Schema fresh(String hint) {
        return build(hint, 0);
    }

    /** 数据源 + 4 个生产 DDL 建表（每次调用都是一份干净的初始状态）。 */
    public static Schema freshWithTables() {
        return freshWithTables("ifmap_");
    }

    /** 数据源 + 4 个生产 DDL 建表。 */
    public static Schema freshWithTables(String hint) {
        return build(hint, 1);
    }

    /** 数据源 + 4 个生产 DDL + 执行日志归档冷表。 */
    public static Schema freshWithArchive() {
        return freshWithArchive("ifmap_");
    }

    /** 数据源 + 4 个生产 DDL + 执行日志归档冷表。 */
    public static Schema freshWithArchive(String hint) {
        return build(hint, 2);
    }

    private static Schema build(String hint, int ddl) {
        String prefix = prefixFor(hint);
        DataSource ds = mysqlEnabled() ? new SimpleDataSource(mysqlUrl(), username(), password()) : h2DataSource();
        if (ddl >= 1) {
            applyProductionDdl(ds, prefix);
        }
        if (ddl >= 2) {
            applyOne(ds, prefix, read(ARCHIVE_DDL).replace("ifmap_", prefix), expectedJsonColumns(ARCHIVE_DDL));
        }
        return new Schema(ds, prefix);
    }

    /** 按当前档位建全部生产表（幂等：真库档先按前缀删干净再建）。 */
    public static void applyProductionDdl(DataSource ds, String prefix) {
        if (mysqlEnabled()) {
            // 注意用 dropTables 而不是 drop：建表前的“先删干净再建”不能注销登记，
            // 否则会话结束的 cleanupAll() 就找不到这组表了（真库积残表的根因）
            dropTables(prefix);
        }
        for (String file : PRODUCTION_DDL) {
            applyOne(ds, prefix, read(file), expectedJsonColumns(file));
        }
    }

    private static void applyOne(DataSource ds, String prefix, String rawDdl, int expectedJson) {
        String ddl = mysqlEnabled()
                ? toMysql(rawDdl, prefix)
                : toH2(rawDdl, prefix, expectedJson);
        execute(ds, ddl);
    }

    private static void execute(DataSource ds, String ddl) {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("执行建表语句失败：" + e.getMessage() + "\n" + ddl, e);
        }
    }

    // ------------------------------------------------------------------ Spring 属性

    /**
     * 生成 Spring 侧数据源属性（供 {@code ApplicationContextRunner#withPropertyValues} 与
     * {@code @SpringBootTest} 的 {@code @DynamicPropertySource} 使用），同时把前缀登记进待清理集合。
     */
    public static Map<String, String> springProperties(String hint) {
        String prefix = prefixFor(hint);
        Map<String, String> props = new LinkedHashMap<String, String>();
        if (mysqlEnabled()) {
            props.put("spring.datasource.url", mysqlUrl());
            props.put("spring.datasource.username", username());
            props.put("spring.datasource.password", password());
            props.put("spring.datasource.driver-class-name", MYSQL_DRIVER);
        } else {
            String url = H2_URL_CACHE.get(hint);
            if (url == null) {
                url = h2Url();
                H2_URL_CACHE.put(hint, url);
            }
            props.put("spring.datasource.url", url);
            props.put("spring.datasource.username", "sa");
            props.put("spring.datasource.password", "");
            props.put("spring.datasource.driver-class-name", H2_DRIVER);
        }
        props.put("ifmap.table-prefix", prefix);
        return props;
    }

    /** {@link #springProperties(String)} 的 {@code key=value} 数组形式。 */
    public static String[] springPropertyArray(String hint) {
        Map<String, String> props = springProperties(hint);
        String[] arr = new String[props.size()];
        int i = 0;
        for (Map.Entry<String, String> e : props.entrySet()) {
            arr[i++] = e.getKey() + "=" + e.getValue();
        }
        return arr;
    }

    // ------------------------------------------------------------------ DDL 适配

    /** 读 classpath 上的 SQL 文本（断言式：文件必须存在）。 */
    public static String read(String classpath) {
        ClassLoader cl = TestDatabases.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IllegalStateException("建表脚本缺失：" + classpath);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取建表脚本失败：" + classpath, e);
        }
    }

    /** 生产 DDL → MySQL 可执行 DDL：只做前缀替换与注释/分号规整，**列类型原样保留**。 */
    public static String toMysql(String ddl, String prefix) {
        return normalize(ddl.replace("${tablePrefix}", prefix));
    }

    /**
     * 生产 DDL → H2 可执行 DDL：剥掉表尾存储引擎选项 + 把 {@code json} 列降级成 {@code text}。
     *
     * <p>变换是确定性断言式的：表尾模式或 json 列个数与预期不符就抛异常，而不是静默跳过。</p>
     */
    public static String toH2(String ddl, String prefix, int expectedJsonColumns) {
        String s = ddl.replace("${tablePrefix}", prefix);
        int idx = s.indexOf(H2_TAIL);
        if (idx < 0) {
            throw new IllegalStateException("DDL 未匹配预期的表尾存储引擎选项，请同步更新 TestDatabases.toH2：" + H2_TAIL);
        }
        int commentEnd = s.indexOf("';", idx);
        if (commentEnd < 0) {
            throw new IllegalStateException("DDL 表尾 COMMENT 未以 \"';\" 结束");
        }
        String stripped = s.substring(0, idx) + ")" + s.substring(commentEnd + 2);
        return normalize(downgradeJsonColumns(stripped, expectedJsonColumns));
    }

    /**
     * 把列定义里的 {@code json} 改成 {@code text}（与生产 {@code IfmapSchemaInitializer} 同口径）。
     *
     * <p>只匹配"反引号列名 + json"这种列定义位置，注释里的 json 不受影响；匹配数必须等于预期。</p>
     */
    public static String downgradeJsonColumns(String ddl, int expected) {
        Matcher matcher = Pattern.compile("(?i)(`[A-Za-z0-9_]+`[ \t]+)json(?=[ \t,])").matcher(ddl);
        int count = 0;
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            count++;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(1) + "text"));
        }
        matcher.appendTail(sb);
        if (count != expected) {
            throw new IllegalStateException("json 列降级数量与预期不符：实际 " + count + " / 预期 " + expected);
        }
        return sb.toString();
    }

    /**
     * 各 DDL 文件里 {@code json} 列的预期个数。
     *
     * <p>004 配置历史表的 {@code snapshot}/{@code diff} 是 JSON 文档（写进去前由引擎生成，一定是合法 JSON）；
     * 003 执行日志表的 {@code request_param} 已改成 {@code mediumtext}（见设计 §5：审计日志要"一定写得进去 +
     * 内容忠实"，截断/规范化都会让 {@code json} 列丢行）。</p>
     */
    public static int expectedJsonColumns(String classpath) {
        return classpath.endsWith("004-create-config-history.sql") ? 2 : 0;
    }

    private static String normalize(String ddl) {
        return ddl.replaceAll("(?m)^--.*$", "").trim().replaceAll(";$", "");
    }

    // ------------------------------------------------------------------ 清理

    /** 删除某个测试前缀下的所有表（幂等；H2 档是空操作）。 */
    public static void drop(String prefix) {
        if (!mysqlEnabled()) {
            MANAGED.remove(prefix);
            return;
        }
        dropTables(prefix);
        MANAGED.remove(prefix);
    }

    /**
     * 只删表、<b>不改登记</b>。
     *
     * <p>建表前的“先删干净再建”（{@link #applyProductionDdl}）必须走这里：若顺手把前缀从 {@link #MANAGED}
     * 里注销，测试结束时 JUnit 会话监听器 / 退出钩子就找不到它了 —— 实测后果是每次 {@code fresh()} 都造一组
     * 永远没人清的 {@code itt_*} 表留在真库里（2026-10-08 真库联调时发现并修复）。</p>
     */
    private static void dropTables(String prefix) {
        requireManagedPrefix(prefix);
        for (String table : tablesWithPrefix(prefix)) {
            if (!table.startsWith(prefix)) {
                throw new IllegalStateException("拒绝删除非本测试创建的表：" + table);
            }
            execute(mysqlDataSource(), "DROP TABLE IF EXISTS `" + table + "`");
        }
    }

    /**
     * 扫掉<b>很早以前</b>残留的测试表（上一次被强杀的 JVM 留下的）。
     *
     * <p>surefire 的 fork 进程是 {@code Runtime.halt()} 结束的，shutdown hook 很可能不执行 ——
     * 单靠 hook 会慢慢在真库里积残表。所以这里再加一道按<b>创建时间</b>判定的安全清扫：
     * 只看 {@code itt_} 前缀、且创建时间早于 {@code NOW() - staleMinutes}。
     * 阈值默认 {@value #STALE_MINUTES} 分钟，远大于单个测试类的前缀存活时间，
     * 所以正在跑的另一个构建不会被误伤。</p>
     *
     * @return 实际删掉的表名（已排序）
     */
    public static List<String> sweepStaleTestTables(int staleMinutes) {
        if (!mysqlEnabled()) {
            return Collections.emptyList();
        }
        if (staleMinutes < 0) {
            throw new IllegalArgumentException("staleMinutes 不能为负：" + staleMinutes);
        }
        List<String> dropped = new ArrayList<String>();
        DataSource ds = mysqlDataSource();
        List<String> stale = new ArrayList<String>();
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             // LIKE 里的 _ 是通配符，必须转义；CREATE_TIME 为 NULL 的引擎保守放过
             ResultSet rs = st.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES"
                             + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'itt\\_%'"
                             + " AND CREATE_TIME IS NOT NULL"
                             + " AND CREATE_TIME <= DATE_SUB(NOW(), INTERVAL " + staleMinutes + " MINUTE)")) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (TEST_PREFIX_HEAD.matcher(table).find()) {
                    stale.add(table);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("扫描残留测试表失败", e);
        }
        for (String table : stale) {
            execute(ds, "DROP TABLE IF EXISTS `" + table + "`");
            dropped.add(table);
        }
        Collections.sort(dropped);
        return dropped;
    }

    private static void sweepStaleOnce() {
        if (SWEPT.compareAndSet(false, true)) {
            try {
                sweepStaleTestTables(STALE_MINUTES);
            } catch (RuntimeException e) {
                // 清扫失败不该让测试挂掉（可能是权限/并发），留个痕即可
                LOGGER.log(Level.WARNING, "残留测试表清扫失败（忽略）：{0}", e.getMessage());
            }
        }
    }

    /** 清理本次 JVM 创建过的全部测试表（幂等）。 */
    public static void cleanupAll() {
        if (!mysqlEnabled()) {
            return;
        }
        for (String prefix : new ArrayList<String>(MANAGED)) {
            drop(prefix);
        }
    }

    /**
     * 手工清理真库里所有测试表（含上次崩溃残留的）。
     *
     * <p>只删匹配 {@link #TEST_PREFIX_PATTERN} 前缀的表；<b>仅供人工执行</b>，正常测试不需要调用
     * （避免与另一台并行构建互相踩）。用法见 {@code docs/05-SpringBoot集成.md}。</p>
     */
    public static List<String> dropAllTestTables() {
        if (!mysqlEnabled()) {
            return Collections.emptyList();
        }
        List<String> dropped = new ArrayList<String>();
        for (String table : tablesWithPrefix(TEST_PREFIX)) {
            if (!TEST_PREFIX_HEAD.matcher(table).find()) {
                continue;
            }
            execute(mysqlDataSource(), "DROP TABLE IF EXISTS `" + table + "`");
            dropped.add(table);
        }
        return dropped;
    }

    /** 列出当前库里以 {@code prefix} 开头的表名。 */
    public static List<String> tablesWithPrefix(String prefix) {
        List<String> tables = new ArrayList<String>();
        DataSource ds = mysqlDataSource();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT TABLE_NAME FROM information_schema.TABLES"
                             + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE ?")) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询测试表失败：" + prefix, e);
        }
        Collections.sort(tables);
        return tables;
    }

    private static void requireManagedPrefix(String prefix) {
        if (!TEST_PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalStateException("拒绝清理不合规的测试前缀（只允许 " + TEST_PREFIX + " 开头）：" + prefix);
        }
    }

    private static void installShutdownHook() {
        if (HOOK_INSTALLED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        cleanupAll();
                    } catch (RuntimeException ignored) {
                        // 退出阶段的清理失败不影响测试结论（残留表前缀可手工清理）
                    }
                }
            }, "ifmap-testkit-cleanup"));
        }
    }

    // ------------------------------------------------------------------ 连接

    /** H2 内存库（每次调用一个新库）。 */
    private static DataSource h2DataSource() {
        return new SimpleDataSource(h2Url(), "sa", "");
    }

    private static String h2Url() {
        return "jdbc:h2:mem:ifmap_testkit_" + SEQ.incrementAndGet()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    /** 真库连接（URL 缺省参数按需补齐）。 */
    private static DataSource mysqlDataSource() {
        return new SimpleDataSource(mysqlUrl(), username(), password());
    }

    /**
     * 真库 URL + 缺失参数补默认值。
     *
     * <p>只补 URL 里没有的参数：{@code characterEncoding=UTF-8}（真库表是 utf8mb4）、{@code useSSL=false}、
     * {@code allowPublicKeyRetrieval=true}，以及<b>成对</b>的时区设定：
     * {@code serverTimezone=<JVM 偏移>} + {@code sessionVariables=time_zone='<同一偏移>'}。</p>
     *
     * <p>为什么要成对：驱动按 {@code serverTimezone} 解释从库里取回的 {@code datetime} 墙钟，而库里的
     * {@code CURRENT_TIMESTAMP(3)} 默认值按<b>会话</b>时区生成；只设一半就会出现几小时的系统性偏移
     * （实测：服务器 OS 是 UTC、JVM 是 +08:00，只设 {@code serverTimezone=+08:00} → 归档时间比当前时间早 8 小时）。
     * 会话时区对齐后，服务端默认值与 JVM 墙钟一致，测试断言才能写"接近当前时间"。
     * 顺带也避开了 MySQL 5.7 服务器时区缩写 {@code CST} 的歧义报错（服务器上 {@code system_time_zone} 可能是 CST）。</p>
     */
    /** 真库连接 URL（缺省参数按需补齐）。 */
    public static String mysqlUrl() {
        String url = jdbcUrl();
        Map<String, String> defaults = new LinkedHashMap<String, String>();
        defaults.put("useUnicode", "true");
        defaults.put("characterEncoding", "UTF-8");
        defaults.put("useSSL", "false");
        defaults.put("allowPublicKeyRetrieval", "true");
        defaults.put("serverTimezone", timeZoneParam());
        defaults.put("sessionVariables", "time_zone='" + timeZoneParam() + "'");
        StringBuilder sb = new StringBuilder(url);
        String sep = url.indexOf('?') < 0 ? "?" : "&";
        for (Map.Entry<String, String> e : defaults.entrySet()) {
            if (!url.contains(e.getKey() + "=")) {
                sb.append(sep).append(e.getKey()).append('=').append(e.getValue());
                sep = "&";
            }
        }
        return sb.toString();
    }

    /**
     * {@code serverTimezone} / {@code sessionVariables} 取值：与 JVM 默认时区一致的**数字偏移**（如 {@code +08:00}）。
     *
     * <p>用偏移而不是区域 ID（Windows 上 JVM 默认值是 {@code GMT+08:00}）：偏移形式库和驱动都认，且不依赖服务器是否
     * 装了时区表（{@code time_zone='Asia/Shanghai'} 需要 mysql 时区表）。</p>
     *
     * <p>另：URL 查询串里的 {@code +} 会被 JDBC 解码成空格（实测报
     * {@code Invalid ID for region-based ZoneId, invalid format: GMT 08:00}），因此必须先编码成 {@code %2B}。</p>
     */
    private static String timeZoneParam() {
        String id = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getId();
        if ("Z".equals(id)) {
            id = "+00:00";
        }
        return id.replace("+", "%2B");
    }

    private static String sanitizeHint(String hint) {
        StringBuilder sb = new StringBuilder();
        String lower = hint == null ? "" : hint.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length() && sb.length() < MAX_HINT_LENGTH; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            }
        }
        while (sb.length() > 0 && sb.charAt(0) == '_') {
            sb.deleteCharAt(0);
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    private static String hex8() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /** 手工清理入口：{@code IFMAP_JDBC_URL=... java -cp <testkit+driver> cn.cj.ifmap.testkit.TestDatabases}。 */
    public static void main(String[] args) {
        if (!mysqlEnabled()) {
            System.out.println("未设置 " + URL_ENV + "，无需清理（H2 档没有落库的表）");
            return;
        }
        List<String> dropped = dropAllTestTables();
        System.out.println("已清理测试表 " + dropped.size() + " 张：" + dropped);
    }

    // ------------------------------------------------------------------ 类型

    /** 一次测试用的"库 + 表前缀"：数据源与前缀必须配套使用（前缀用于 TableNameResolver）。 */
    public static final class Schema {

        private final DataSource dataSource;
        private final String prefix;

        Schema(DataSource dataSource, String prefix) {
            this.dataSource = dataSource;
            this.prefix = prefix;
        }

        public DataSource dataSource() {
            return dataSource;
        }

        /** 当前档位下真实生效的表前缀（H2 档 = 逻辑前缀；真库档 = {@code itt_..._}）。 */
        public String prefix() {
            return prefix;
        }

        @Override
        public String toString() {
            return "Schema{prefix=" + prefix + ", dialect=" + (mysqlEnabled() ? "mysql" : "h2") + "}";
        }
    }

    /** 只有一个 JDBC 连接的极简数据源（刻意不依赖 spring-jdbc：避免把 5.3.x 传进 SB3 模块）。 */
    private static final class SimpleDataSource implements DataSource {

        private final String url;
        private final String user;
        private final String pass;

        SimpleDataSource(String url, String user, String pass) {
            this.url = url;
            this.user = user;
            this.pass = pass;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, pass);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, user, pass);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // 测试数据源不需要日志输出
        }

        @Override
        public void setLoginTimeout(int seconds) {
            DriverManager.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() {
            return DriverManager.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return Logger.getLogger("cn.cj.ifmap.testkit");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("不支持 unwrap：" + iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
