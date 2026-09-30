package cn.cj.ifmap.jdbc;

import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.spi.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓储端到端测试：用**生产建表脚本**（H2 MySQL 模式）验证 §6 表结构 + 仓储 SQL + 两个关键约定
 * （查询有序、软删除可重建）。
 *
 * @author caijun
 */
class JdbcConfigRepositoryTest {

    private static final String TENANT = "1001";

    private DataSource ds;
    private JdbcTemplate jdbc;
    private JdbcConfigRepository repo;
    private JdbcConfigWriter writer;

    @BeforeEach
    void setUp() {
        ds = TestSchema.freshDataSource();
        jdbc = new JdbcTemplate(ds);
        repo = new JdbcConfigRepository(ds);
        writer = new JdbcConfigWriter(ds);
    }

    // ------------------------------------------------------------------ 查询

    @Test
    @DisplayName("queryConfigs 按 interfaceOrder 升序返回（不再依赖 DB 返回顺序）")
    void queryConfigsOrderedByInterfaceOrder() {
        insert("IF_A", "GP81", 30, 3001L);
        insert("IF_A", "GP81", 10, 1001L);
        insert("IF_A", "GP81", 20, 2001L);

        List<IfmapConfig> list = repo.queryConfigs(TENANT, "IF_A", "GP81");

        assertEquals(3, list.size());
        assertEquals(10, ((Number) list.get(0).getInterfaceOrder()).intValue());
        assertEquals(20, ((Number) list.get(1).getInterfaceOrder()).intValue());
        assertEquals(30, ((Number) list.get(2).getInterfaceOrder()).intValue());
    }

    @Test
    @DisplayName("唯一键语义：(租户+接口+业务节点+顺序) 唯一 —— 同节点同顺序无法重复，不同节点同顺序可共存")
    void uniqueKeyScopeIsPerBusiNode() {
        long gp81 = insert("IF_B", "GP81", 5, 9001L);
        // 同接口、同顺序但不同业务节点 → 允许
        long gp85 = insert("IF_B", "GP85", 5, 8001L);
        // 同节点同顺序再来一条 → 唯一键拦截
        assertThrows(IfmapConfigException.class, () -> insert("IF_B", "GP81", 5, 7001L));

        List<IfmapConfig> gp81List = repo.queryConfigs(TENANT, "IF_B", "GP81");
        assertEquals(1, gp81List.size());
        assertEquals(gp81, gp81List.get(0).getKeyId().longValue());
        assertEquals(gp85, repo.queryConfigs(TENANT, "IF_B", "GP85").get(0).getKeyId().longValue());
    }

    @Test
    @DisplayName("queryConfigs 过滤停用（status=0）与已删除（del_status=1）的行")
    void queryConfigsFiltersDisabledAndDeleted() {
        insert("IF_C", "GP81", 1, 101L);
        IfmapConfig disabled = config("IF_C", "GP81", 2, 102L);
        disabled.setStatus(0);
        writer.insert(disabled);
        insert("IF_C", "GP81", 3, 103L);
        assertTrue(writer.softDelete(103L, "tester", "req-del"));

        List<IfmapConfig> list = repo.queryConfigs(TENANT, "IF_C", "GP81");

        assertEquals(1, list.size());
        assertEquals(101L, list.get(0).getKeyId().longValue());
    }

    @Test
    @DisplayName("queryConfigs 按租户隔离")
    void queryConfigsIsolatesTenant() {
        insert("IF_D", "GP81", 1, 201L);
        IfmapConfig other = config("IF_D", "GP81", 1, 202L);
        other.setTenantId(2002L);
        writer.insert(other);

        assertEquals(1, repo.queryConfigs(TENANT, "IF_D", "GP81").size());
        assertEquals(1, repo.queryConfigs("2002", "IF_D", "GP81").size());
    }

    @Test
    @DisplayName("findConfig 精确命中指定 order；不存在时返回空 Optional")
    void findConfig() {
        insert("IF_E", "GP81", 7, 301L);

        Optional<IfmapConfig> hit = repo.findConfig(TENANT, "IF_E", "GP81", 7);
        assertTrue(hit.isPresent());
        assertEquals(301L, hit.get().getKeyId().longValue());

        assertFalse(repo.findConfig(TENANT, "IF_E", "GP81", 8).isPresent());
    }

    @Test
    @DisplayName("queryLogicBranches 按 logicBranchOrder 升序返回")
    void queryLogicBranchesOrdered() {
        branch("IF_F", "b2", 20, 2L);
        branch("IF_F", "b1", 10, 1L);
        branch("IF_F", null, 99, 3L);

        List<LogicBranchConfig> list = repo.queryLogicBranches(TENANT, "IF_F");

        assertEquals(3, list.size());
        assertEquals("b1", list.get(0).getLogicBranchName());
        assertEquals("b2", list.get(1).getLogicBranchName());
        assertTrue(list.get(2).isDefaultBranch());
    }

    // ------------------------------------------------------------------ 写入

    @Test
    @DisplayName("insert 补齐默认值：version=0 / del_status=0 / deleted_seq=0 / status=1 / tenant_id=-1")
    void insertAppliesDefaults() {
        IfmapConfig c = new IfmapConfig();
        c.setInterfaceNo("IF_G");
        c.setInterfaceCode("PSBC_0001");
        c.setInterfaceName("签约申请");
        c.setBusiNode("GP81");
        c.setBankCode("PSBC");

        long id = writer.insert(c);
        assertTrue(id > 0);

        IfmapConfig saved = writer.findByKeyId(id).orElseThrow(IllegalStateException::new);
        assertEquals(-1L, saved.getTenantId().longValue());
        assertEquals(0, ((Number) saved.getVersion()).intValue());
        assertEquals(0, ((Number) saved.getDelStatus()).intValue());
        assertEquals(0L, saved.getDeletedSeq().longValue());
        assertEquals(1, ((Number) saved.getStatus()).intValue());
        assertEquals(0, ((Number) saved.getInterfaceOrder()).intValue());
        assertTrue(saved.enabled());
        assertNotNull(saved.getAddTime());
    }

    @Test
    @DisplayName("insert 把 NOT NULL DEFAULT '' 列的 null 归一化为空串（否则 MySQL 严格模式直接报错）")
    void insertNormalisesNullsForNotNullColumns() {
        IfmapConfig c = config("IF_G2", "GP81", 1, 1501L);
        c.setResultFlag(null);
        c.setSuccessValue(null);
        c.setStrategyName(null);
        c.setRemark(null);
        c.setAddUserId(null);
        c.setAddRequestId(null);

        long id = writer.insert(c);
        IfmapConfig saved = writer.findByKeyId(id).orElseThrow(IllegalStateException::new);
        assertEquals("", saved.getResultFlag());
        assertEquals("", saved.getSuccessValue());
        assertEquals("", saved.getStrategyName());
        assertEquals("", saved.getRemark());
        assertEquals("", saved.getAddUserId());
        assertEquals("", saved.getAddRequestId());
    }

    @Test
    @DisplayName("insert 缺必填字段时抛 IfmapConfigException（先于 DB 约束）")
    void insertValidatesRequiredFields() {
        IfmapConfig c = config("IF_G3", "GP81", 1, 1601L);
        c.setInterfaceCode(null);
        IfmapConfigException e = assertThrows(IfmapConfigException.class, () -> writer.insert(c));
        assertTrue(e.getMessage().contains("interfaceCode"), e.getMessage());

        IfmapConfig blank = config("IF_G4", "GP81", 1, 1602L);
        blank.setBusiNode("   ");
        assertThrows(IfmapConfigException.class, () -> writer.insert(blank));
    }

    @Test
    @DisplayName("insert 唯一键冲突（同租户+接口+节点+顺序）抛出 IfmapConfigException")
    void insertDuplicateBusinessKeyFails() {
        insert("IF_H", "GP81", 1, 401L);
        IfmapConfig dup = config("IF_H", "GP81", 1, 402L);

        IfmapConfigException e = assertThrows(IfmapConfigException.class, () -> writer.insert(dup));
        assertTrue(e.getMessage().contains("唯一键冲突"), e.getMessage());
    }

    @Test
    @DisplayName("update 乐观锁：版本匹配成功且版本+1；版本不匹配返回 false 且不覆盖数据")
    void updateUsesOptimisticLock() {
        long id = insert("IF_I", "GP81", 1, 501L);
        IfmapConfig loaded = writer.findByKeyId(id).orElseThrow(IllegalStateException::new);
        assertEquals(0, ((Number) loaded.getVersion()).intValue());

        IfmapConfig edit = writer.findByKeyId(id).orElseThrow(IllegalStateException::new);
        edit.setInterfaceName("签约申请V2");
        edit.setRemark("改名");
        assertTrue(writer.update(edit, 0, "tester", "req-1"));

        IfmapConfig afterUpdate = writer.findByKeyId(id).orElseThrow(IllegalStateException::new);
        assertEquals("签约申请V2", afterUpdate.getInterfaceName());
        assertEquals("改名", afterUpdate.getRemark());
        assertEquals(1, ((Number) afterUpdate.getVersion()).intValue());
        assertEquals("tester", afterUpdate.getModifyUserId());
        assertEquals("req-1", afterUpdate.getModifyRequestId());

        // 用过期版本号再次提交 → 失败，且数据保持不变
        IfmapConfig stale = writer.findByKeyId(id).orElseThrow(IllegalStateException::new);
        stale.setInterfaceName("脏数据");
        assertFalse(writer.update(stale, 0, "tester", "req-2"));
        assertEquals("签约申请V2", writer.findByKeyId(id).orElseThrow(IllegalStateException::new).getInterfaceName());
    }

    @Test
    @DisplayName("softDelete 置 deleted_seq=key_id 并递增版本；重复删除幂等返回 false")
    void softDeleteIsAtomicAndIdempotent() {
        long id = insert("IF_J", "GP81", 1, 601L);

        assertTrue(writer.softDelete(id, "tester", "req-del"));
        IfmapConfig deleted = writer.findByKeyId(id).orElseThrow(IllegalStateException::new);
        assertEquals(1, ((Number) deleted.getDelStatus()).intValue());
        assertEquals(id, deleted.getDeletedSeq().longValue());
        assertEquals(1, ((Number) deleted.getVersion()).intValue());
        assertFalse(deleted.enabled() && deleted.getDelStatus() == 0);

        assertFalse(writer.softDelete(id, "tester", "req-del-again"));
    }

    @Test
    @DisplayName("软删除后可重建同名配置（deleted_seq 方案的核心收益，可反复多次）")
    void canRecreateAfterSoftDelete() {
        long first = insert("IF_K", "GP81", 1, 701L);
        assertTrue(writer.softDelete(first, "tester", "req-1"));

        long second = insert("IF_K", "GP81", 1, 702L);
        assertTrue(second != first);

        IfmapConfig current = repo.queryConfigs(TENANT, "IF_K", "GP81").get(0);
        assertEquals(702L, current.getKeyId().longValue());

        assertTrue(writer.softDelete(second, "tester", "req-2"));
        long third = insert("IF_K", "GP81", 1, 703L);
        assertEquals(703L, repo.queryConfigs(TENANT, "IF_K", "GP81").get(0).getKeyId().longValue());

        // 历史行仍在库中（软删除），共 3 行
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM `ifmap_config` WHERE `interface_no` = 'IF_K'", Integer.class);
        assertEquals(3, ((Number) rows).intValue());
        assertEquals(third, 703L);
    }

    // ------------------------------------------------------------------ 日志

    @Test
    @DisplayName("saveExecutionLog 写入执行日志，recentLogs 可按业务单查回")
    void saveAndQueryExecutionLog() {
        ExecutionLog log = ExecutionLog.success("IF_L", "BIZ-1", 128L);
        log.setTenantId(1001L);
        log.setResponseParam("{\"code\":\"0000\"}");
        repo.saveExecutionLog(log);

        assertNotNull(log.getKeyId());
        List<ExecutionLog> logs = repo.recentLogs(TENANT, "BIZ-1", 10);
        assertEquals(1, logs.size());
        assertEquals("IF_L", logs.get(0).getInterfaceNo());
        assertEquals("SUCCESS", logs.get(0).getExecutionResult());
        assertEquals(128L, logs.get(0).getExecutionTime().longValue());
        assertEquals("{\"code\":\"0000\"}", logs.get(0).getResponseParam());
    }

    @Test
    @DisplayName("saveExecutionLog 缺少 execution_result 时抛 IfmapConfigException")
    void saveExecutionLogRequiresResult() {
        ExecutionLog log = new ExecutionLog();
        log.setInterfaceNo("IF_L2");
        log.setBizId("BIZ-X");
        assertThrows(IfmapConfigException.class, () -> repo.saveExecutionLog(log));
    }

    @Test
    @DisplayName("saveExecutionLog 失败日志带 error_msg（截断由调用方负责）")
    void saveFailLog() {
        ExecutionLog log = ExecutionLog.fail("IF_M", "BIZ-2", 900L, "银行返回超时");
        log.setTenantId(1001L);
        repo.saveExecutionLog(log);

        ExecutionLog saved = repo.recentLogs(TENANT, "BIZ-2", 1).get(0);
        assertEquals("FAIL", saved.getExecutionResult());
        assertEquals("银行返回超时", saved.getErrorMsg());
    }

    @Test
    @DisplayName("saveExecutionLog 无 keyId 且生成器为 AUTO_INCREMENT 时抛错（避免写空主键）")
    void saveExecutionLogWithoutIdFails() {
        JdbcConfigRepository autoRepo = new JdbcConfigRepository(
                jdbc, TableNameResolver.defaults(), IdGenerator.AUTO_INCREMENT);
        ExecutionLog log = ExecutionLog.success("IF_N", "BIZ-3", 1L);

        IfmapConfigException e = assertThrows(IfmapConfigException.class, () -> autoRepo.saveExecutionLog(log));
        assertTrue(e.getMessage().contains("主键"), e.getMessage());
    }

    @Test
    @DisplayName("租户 ID 非数字时抛 IfmapConfigException（而不是静默查错租户）")
    void invalidTenantIdFails() {
        assertThrows(IfmapConfigException.class, () -> repo.queryConfigs("abc", "IF_X", "GP81"));
    }

    // ------------------------------------------------------------------ 前缀

    @Test
    @DisplayName("自定义表名前缀（bankint_）可建表可读写 —— 复用存量表路径")
    void customTablePrefixWorks() {
        DataSource legacy = TestSchema.freshDataSource("bankint_");
        JdbcConfigRepository legacyRepo = new JdbcConfigRepository(legacy, "bankint_");
        JdbcConfigWriter legacyWriter = new JdbcConfigWriter(legacy, "bankint_");

        long id = legacyWriter.insert(config("IF_P", "GP81", 1, 801L));
        assertEquals(1, legacyRepo.queryConfigs(TENANT, "IF_P", "GP81").size());
        assertEquals(id, legacyRepo.queryConfigs(TENANT, "IF_P", "GP81").get(0).getKeyId().longValue());
    }

    // ------------------------------------------------------------------ 工具

    private long insert(String interfaceNo, String busiNode, int order, long keyId) {
        return writer.insert(config(interfaceNo, busiNode, order, keyId));
    }

    private IfmapConfig config(String interfaceNo, String busiNode, int order, long keyId) {
        IfmapConfig c = new IfmapConfig();
        c.setKeyId(keyId);
        c.setTenantId(Long.parseLong(TENANT));
        c.setInterfaceNo(interfaceNo);
        c.setInterfaceCode(interfaceNo + "_CODE");
        c.setInterfaceName(interfaceNo + " 接口");
        c.setBusiNode(busiNode);
        c.setBankCode("PSBC");
        c.setInterfaceOrder(order);
        c.setRequestParamTemplate("{\"a\":\"$.a\"}");
        c.setResponseParamTemplate("{\"b\":\"$.b\"}");
        c.setResultFlag("$.code");
        c.setSuccessValue("0000|000000");
        return c;
    }

    private long branch(String interfaceNo, String methodFlag, int order, long keyId) {
        String sql = "INSERT INTO `ifmap_logic_branch_config`"
                + " (`key_id`,`tenant_id`,`interface_no`,`method_flag`,`logic_branch_name`,"
                + "`logic_branch_flag`,`logic_branch_value`,`logic_branch_order`,`remark`,`del_status`,`deleted_seq`)"
                + " VALUES (?,?,?,?,?,?,?,?,'',0,0)";
        jdbc.update(sql, keyId, Long.parseLong(TENANT), interfaceNo, methodFlag,
                methodFlag == null ? "默认分支" : methodFlag, "$.flag", "1", order);
        return keyId;
    }
}
