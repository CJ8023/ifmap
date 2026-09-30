package cn.cj.ifmap.jdbc;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用建表：读**生产 DDL**（{@code db/changelog/v1.0.0/*.sql}）并做最小确定性变换后在 H2(MySQL 模式) 执行。
 *
 * <p>为什么要变换：H2 不支持表尾的 {@code ENGINE=InnoDB DEFAULT CHARSET=... COLLATE=... ROW_FORMAT=...}
 * 与表级 {@code COMMENT=...}（存储引擎选项）。<b>列定义、主键、唯一键、普通索引、列注释全部原样执行</b>，
 * 因此"用 H2 验证仓储 SQL 与约束"仍然有效；MySQL 专属选项本身由发布侧在真库验证。</p>
 *
 * <p>变换是确定性断言式的：若某个 DDL 文件不再匹配预期的表尾模式，测试会失败而不是"静默跳过校验"。</p>
 *
 * @author caijun
 */
final class TestSchema {

    private static final List<String> FILES = Arrays.asList(
            "db/changelog/v1.0.0/001-create-ifmap-config.sql",
            "db/changelog/v1.0.0/002-create-logic-branch.sql",
            "db/changelog/v1.0.0/003-create-execution-log.sql",
            "db/changelog/v1.0.0/004-create-config-history.sql");

    private static final AtomicInteger SEQ = new AtomicInteger();

    private TestSchema() {
    }

    /** 新建一个 H2 内存库并按指定前缀建表。 */
    static DataSource freshDataSource(String tablePrefix) {
        String url = "jdbc:h2:mem:ifmap_" + SEQ.incrementAndGet()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        new org.springframework.jdbc.core.JdbcTemplate(ds).execute("/* warm up */ SELECT 1");
        for (String file : FILES) {
            String ddl = toH2(read(file), tablePrefix);
            new org.springframework.jdbc.core.JdbcTemplate(ds).execute(ddl);
        }
        return ds;
    }

    /** 默认前缀（ifmap_）建表。 */
    static DataSource freshDataSource() {
        return freshDataSource(TableNameResolver.DEFAULT_PREFIX);
    }

    /** 读取生产 DDL 原始文本（测试可直接断言，保证脚本存在且非空）。 */
    static String read(String classpath) {
        ClassPathResource res = new ClassPathResource(classpath);
        if (!res.exists()) {
            throw new IllegalStateException("建表脚本缺失：" + classpath);
        }
        try (InputStream in = res.getInputStream()) {
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("读取建表脚本失败：" + classpath, e);
        }
    }

    /** 生产 DDL → H2 可执行 DDL（只剥离表尾存储引擎选项）。 */
    static String toH2(String ddl, String tablePrefix) {
        String s = ddl.replace("${tablePrefix}", tablePrefix);
        String tail = ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC\n  COMMENT='";
        int idx = s.indexOf(tail);
        if (idx < 0) {
            throw new IllegalStateException("DDL 未匹配预期的表尾存储引擎选项，请同步更新 TestSchema.toH2：" + tail);
        }
        int commentEnd = s.indexOf("';", idx);
        if (commentEnd < 0) {
            throw new IllegalStateException("DDL 表尾 COMMENT 未以 \"';\" 结束");
        }
        String stripped = s.substring(0, idx) + ")" + s.substring(commentEnd + 2);
        return stripped.replaceAll("(?m)^--.*$", "").trim().replaceAll(";$", "");
    }
}
