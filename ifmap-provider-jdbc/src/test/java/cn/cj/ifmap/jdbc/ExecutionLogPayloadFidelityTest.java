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

import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.util.Logs;
import cn.cj.ifmap.testkit.TestDatabases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行日志报文列的契约测试（对应真库实测到的缺陷，见设计文档 §5）。
 *
 * <p>缺陷链路：写日志前会用 {@link Logs#truncate(String, int)} 截断超长报文 →
 * 截断结果必然不是合法 JSON → 列类型若是 MySQL {@code json} → 写入报 <b>error 3140</b> →
 * 调用方吞掉异常只打 WARN → <b>该条执行日志静默丢失</b>（业务无感，审计缺失）。</p>
 *
 * <p>H2 档测不出来的原因：H2 跑的是"json 降级成 text"后的 DDL。所以这里用两道互补的断言把契约钉死：</p>
 * <ol>
 *   <li><b>DDL 栅栏</b>：生产 DDL 与迁移脚本里日志报文列必须是<b>文本类型</b>（改回 {@code json} 会红）；</li>
 *   <li><b>行为断言</b>：走真实仓储写入"截断后的报文"必须能落库并<b>原样</b>读回（含小数形态）；
 *       真库档下若列还是 {@code json}，这条会因 3140 变红。</li>
 * </ol>
 *
 * @author caijun
 */
class ExecutionLogPayloadFidelityTest {

    /** 与 {@code IfmapProperties.log.truncateThreshold} 的默认值一致。 */
    private static final int TRUNCATE_THRESHOLD = 65536;

    private static final String TENANT = "1001";

    @Test
    @DisplayName("生产 DDL：日志报文列必须是文本类型（json 列写不进截断后的报文）")
    void ddlKeepsPayloadColumnsTextual() {
        assertColumnType("db/changelog/v1.0.0/003-create-execution-log.sql", "mediumtext");
        assertColumnType(TestDatabases.ARCHIVE_DDL, "mediumtext");
        assertColumnType("db/migration/ecc-to-ifmap/03-modify-and-index.sql", "mediumtext");
    }

    @Test
    @DisplayName("迁移脚本：存量 text 列不能被改成 json（否则缺陷会带给存量系统）")
    void migrationScriptNeverTurnsPayloadIntoJson() {
        String migration = TestDatabases.read("db/migration/ecc-to-ifmap/03-modify-and-index.sql");
        Matcher matcher = Pattern.compile("(?i)`request_param`[ \t]+(json|mediumtext|longtext|text)")
                .matcher(migration);
        assertTrue(matcher.find(), "迁移脚本里必须有 request_param 的类型变更");
        assertEquals("mediumtext", matcher.group(1),
                "日志报文列必须是文本类型：超长截断后的报文不是合法 JSON，json 列会 3140 丢行");
    }

    @Test
    @DisplayName("截断后的超长报文能落库并原样读回（含 10.00 / 1.0E10 形态，json 列会丢行或规范化）")
    void truncatedPayloadRoundTripsFaithfully() {
        TestDatabases.Schema schema = TestSchema.fresh();
        JdbcTemplate jdbc = new JdbcTemplate(schema.dataSource());
        JdbcConfigRepository repo = new JdbcConfigRepository(schema.dataSource(), schema.prefix());

        String payload = "{\"amount\":10.00,\"exp\":1.0E10,\"pad\":\"" + repeat("x", TRUNCATE_THRESHOLD + 1000) + "\"}";
        String truncated = Logs.truncate(payload, TRUNCATE_THRESHOLD);
        assertNotNull(truncated);
        assertTrue(truncated.endsWith(Logs.TRUNCATED), "超长报文必须被截断（截断后不是合法 JSON）");

        ExecutionLog log = ExecutionLog.success("IF_FID", "BIZ-FID", 12L);
        log.setTenantId(Long.parseLong(TENANT));
        log.setRequestParam(truncated);
        log.setResponseParam("{\"code\":\"0000\",\"fee\":3.10}");
        repo.saveExecutionLog(log);

        List<ExecutionLog> saved = repo.recentLogs(TENANT, "BIZ-FID", 1);
        assertEquals(1, saved.size(), "截断后的报文必须能落库（json 列会 3140 → 整条日志丢失）");
        assertEquals(truncated, saved.get(0).getRequestParam(), "报文必须原样保存（json 列会把 10.00 规范化成 10）");
        assertEquals("{\"code\":\"0000\",\"fee\":3.10}", saved.get(0).getResponseParam());
        assertEquals(1, count(jdbc, schema.prefix() + "execution_log"));
    }

    private static void assertColumnType(String classpath, String expectedType) {
        String ddl = TestDatabases.read(classpath);
        Matcher matcher = Pattern.compile("(?i)`request_param`[ \t]+([a-z]+)").matcher(ddl);
        assertTrue(matcher.find(), classpath + " 里必须有 request_param 列定义");
        assertEquals(expectedType, matcher.group(1), classpath + " 的 request_param 列类型不对");
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
        return n == null ? 0 : n;
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
