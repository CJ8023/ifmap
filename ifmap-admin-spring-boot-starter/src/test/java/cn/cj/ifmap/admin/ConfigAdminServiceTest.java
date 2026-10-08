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
package cn.cj.ifmap.admin;

import cn.cj.ifmap.core.cache.CachingConfigRepository;
import cn.cj.ifmap.core.cache.InMemoryConfigCache;
import cn.cj.ifmap.core.config.ConfigQuery;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.config.PageResult;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.model.PartnerCall;
import cn.cj.ifmap.core.model.IfmapConfigHistory;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;
import cn.cj.ifmap.core.spi.PartnerServiceGateway;
import cn.cj.ifmap.core.strategy.ActionRegistry;
import cn.cj.ifmap.core.strategy.CallbackRegistry;
import cn.cj.ifmap.core.strategy.FullParamStrategyRegistry;
import cn.cj.ifmap.core.strategy.IfmapAction;
import cn.cj.ifmap.core.strategy.IfmapActionHandler;
import cn.cj.ifmap.core.strategy.LogicBranchStrategyRegistry;
import cn.cj.ifmap.core.strategy.SpecialDealStrategyRegistry;
import cn.cj.ifmap.core.strategy.StrategyContext;
import cn.cj.ifmap.core.validate.ContractValidator;
import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.admin.dto.DryRunRequest;
import cn.cj.ifmap.admin.dto.HistoryView;
import cn.cj.ifmap.jdbc.JdbcConfigHistoryRepository;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import cn.cj.ifmap.json.jackson.JacksonJsonOps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端业务编排：写操作 + 审计链 + 乐观锁 + 缓存失效 + 试跑。
 *
 * @author caijun
 */
class ConfigAdminServiceTest {

    private static final String REQ = "req-1";
    private static final String OPERATOR = "tester";

    private JdbcTemplate jdbc;
    private JdbcConfigWriter writer;
    private JdbcConfigRepository repository;
    private JdbcConfigHistoryRepository historyRepository;
    private ConfigAdminService service;
    private CachingConfigRepository engineRepository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = AdminTestSupport.dataSource("ifmap_admin_service");
        AdminTestSupport.createSchema(dataSource);
        jdbc = AdminTestSupport.jdbc(dataSource);
        jdbc.update("DELETE FROM `ifmap_config`");
        jdbc.update("DELETE FROM `ifmap_logic_branch_config`");
        jdbc.update("DELETE FROM `ifmap_config_history`");
        writer = AdminTestSupport.writer(jdbc);
        repository = AdminTestSupport.repository(jdbc);
        historyRepository = AdminTestSupport.history(jdbc);
        engineRepository = new CachingConfigRepository(repository, new InMemoryConfigCache(1000, 60000L));

        JacksonJsonOps jsonOps = new JacksonJsonOps();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new SubmitAction());
        ConfigValidator validator = new ConfigValidator(
                new ContractValidator(IfmapEngine.builder().jsonOps(jsonOps).build()),
                jsonOps, new SpecialDealStrategyRegistry(), actions, repository);

        IfmapAdminProperties properties = new IfmapAdminProperties();
        service = new ConfigAdminService(writer, repository, historyRepository, AdminTestSupport.snapshots(),
                validator, new ConfigAuditor(repository, validator, properties.getAuditMaxConfigs()),
                provider(orchestrator(engineRepository)), provider(engineRepository), properties);
    }

    @Test
    @DisplayName("新增配置：返回主键 + 落一条 CREATE 历史（快照含内容）")
    void createWritesHistory() {
        long keyId = service.create(AdminTestSupport.config("IF_A", "apply", 1), "初始化", OPERATOR, REQ);

        IfmapConfig saved = service.get(keyId);
        assertEquals("IF_A", saved.getInterfaceNo());
        // version 基线是 0（DDL: `version` int NOT NULL DEFAULT 0），第一次修改的 expectedVersion 就是 0
        assertEquals(0, ((Number) saved.getVersion()).intValue());

        List<HistoryView> views = service.history(keyId, null);
        assertEquals(1, views.size());
        assertEquals(IfmapConfigHistory.CREATE, views.get(0).getChangeType());
        assertEquals("初始化", views.get(0).getChangeReason());
        assertEquals(OPERATOR, views.get(0).getOperatorId());
        assertEquals(REQ, views.get(0).getRequestId());
        assertNotNull(views.get(0).getSnapshot());
        assertTrue(((Map<?, ?>) views.get(0).getSnapshot()).containsKey("interfaceNo"));
    }

    @Test
    @DisplayName("分页查询：条件过滤 + total 与 rows 口径一致")
    void listPaging() {
        service.create(AdminTestSupport.config("IF_A", "apply", 1), null, OPERATOR, REQ);
        service.create(AdminTestSupport.config("IF_B", "apply", 1), null, OPERATOR, REQ);

        PageResult<IfmapConfig> all = service.list(new ConfigQuery().setTenantId("1").setSize(10));
        assertEquals(2, ((Number) all.getTotal()).longValue());
        assertEquals(2, all.getRows().size());

        PageResult<IfmapConfig> one = service.list(new ConfigQuery().setTenantId("1").setInterfaceNo("IF_B"));
        assertEquals(1, ((Number) one.getTotal()).longValue());
        assertEquals("IF_B", one.getRows().get(0).getInterfaceNo());
    }

    @Test
    @DisplayName("校验不过 → 422 语义异常，且一行都没写（事务前的失败要早）")
    void invalidConfigIsRejectedBeforeInsert() {
        IfmapConfig bad = AdminTestSupport.config("IF_A", "apply", 1);
        bad.setPartnerCode(null);

        IfmapValidationException e = assertThrows(IfmapValidationException.class,
                () -> service.create(bad, null, OPERATOR, REQ));
        assertFalse(e.getResult().isPassed());
        assertEquals(0, ((Number) service.list(new ConfigQuery()).getTotal()).longValue());
        assertEquals(0, ((Number) jdbc.queryForObject("SELECT COUNT(*) FROM `ifmap_config_history`", Long.class))
                .longValue());
    }

    @Test
    @DisplayName("修改：版本不匹配返回 false 且不落历史；命中则落 UPDATE 历史 + diff")
    void updateRespectsOptimisticLock() {
        long keyId = service.create(AdminTestSupport.config("IF_A", "apply", 1), null, OPERATOR, REQ);

        IfmapConfig change = service.get(keyId);
        change.setSuccessValue("0001");
        assertFalse(service.update(change, 999, "过期版本", OPERATOR, REQ), "过期版本必须被拒");
        assertEquals(1, service.history(keyId, null).size(), "被拒的修改不能产生历史");

        assertTrue(service.update(change, 0, "改成功值", OPERATOR, REQ));
        IfmapConfig after = service.get(keyId);
        assertEquals("0001", after.getSuccessValue());
        assertEquals(1, ((Number) after.getVersion()).intValue(), "成功修改 version 自增");

        List<HistoryView> views = service.history(keyId, null);
        assertEquals(2, views.size());
        assertEquals(IfmapConfigHistory.UPDATE, views.get(0).getChangeType());
        Map<?, ?> diff = (Map<?, ?>) views.get(0).getDiff();
        assertNotNull(diff);
        assertTrue(diff.containsKey("successValue"), String.valueOf(diff));
    }

    @Test
    @DisplayName("修改必须带 expectedVersion（不允许省略后静默覆盖）")
    void updateRequiresExpectedVersion() {
        long keyId = service.create(AdminTestSupport.config("IF_A", "apply", 1), null, OPERATOR, REQ);
        IfmapConfig change = service.get(keyId);
        assertThrows(IfmapConfigException.class,
                () -> service.update(change, null, "没带版本", OPERATOR, REQ));
    }

    @Test
    @DisplayName("停用：引擎侧查询立刻查不到（缓存被失效），历史记 DISABLE")
    void changeStatusEvictsCacheAndWritesHistory() {
        long keyId = service.create(AdminTestSupport.config("IF_A", "apply", 1), null, OPERATOR, REQ);
        assertFalse(engineRepository.queryConfigs("1", "IF_A", "apply").isEmpty(), "停用前引擎可见");

        assertTrue(service.changeStatus(keyId, 0, 0, "人工停用", OPERATOR, REQ));
        assertTrue(engineRepository.queryConfigs("1", "IF_A", "apply").isEmpty(), "停用后引擎必须立刻看不到");

        assertTrue(service.changeStatus(keyId, 1, 1, "重新启用", OPERATOR, REQ));
        assertFalse(engineRepository.queryConfigs("1", "IF_A", "apply").isEmpty(), "启用后引擎可见");

        List<HistoryView> views = service.history(keyId, null);
        assertEquals(IfmapConfigHistory.ENABLE, views.get(0).getChangeType());
        assertEquals(IfmapConfigHistory.DISABLE, views.get(1).getChangeType());
    }

    @Test
    @DisplayName("删除：软删后管理端仍能查到（includeDeleted），引擎查不到")
    void deleteIsLogical() {
        long keyId = service.create(AdminTestSupport.config("IF_A", "apply", 1), null, OPERATOR, REQ);
        assertTrue(service.delete(keyId, "误配", OPERATOR, REQ));

        assertEquals(1, ((Number) service.list(new ConfigQuery().setTenantId("1").setIncludeDeleted(true))
                .getTotal()).longValue());
        assertEquals(0, ((Number) service.list(new ConfigQuery().setTenantId("1")).getTotal()).longValue());
        assertTrue(engineRepository.queryConfigs("1", "IF_A", "apply").isEmpty());
        assertEquals(IfmapConfigHistory.DELETE, service.history(keyId, null).get(0).getChangeType());

        // 删除后可重建同维度配置（deleted_seq 让唯一键不冲突）；管理端 get 仍能取到已删行
        assertEquals("IF_A", service.get(keyId).getInterfaceNo());
    }

    @Test
    @DisplayName("回滚：把旧快照写回配置，并再记一条 UPDATE 历史")
    void rollbackRestoresSnapshot() {
        long keyId = service.create(AdminTestSupport.config("IF_A", "apply", 1), null, OPERATOR, REQ);
        long createdHistoryId = service.history(keyId, null).get(0).getKeyId();

        IfmapConfig change = service.get(keyId);
        change.setSuccessValue("9999");
        assertTrue(service.update(change, 0, "改坏", OPERATOR, REQ));
        assertEquals("9999", service.get(keyId).getSuccessValue());

        assertTrue(service.rollback(keyId, createdHistoryId, 1, "回滚", OPERATOR, REQ));
        assertEquals("0000", service.get(keyId).getSuccessValue(), "回滚后回到快照值");

        List<HistoryView> views = service.history(keyId, null);
        assertEquals(3, views.size());
        assertTrue(views.get(0).getChangeReason().contains("回滚到历史 #" + createdHistoryId),
                views.get(0).getChangeReason());
    }

    @Test
    @DisplayName("回滚：历史不属于该配置 / 版本不匹配 → 拒绝")
    void rollbackGuards() {
        long a = service.create(AdminTestSupport.config("IF_A", "apply", 1), null, OPERATOR, REQ);
        long b = service.create(AdminTestSupport.config("IF_B", "apply", 1), null, OPERATOR, REQ);
        long bHistory = service.history(b, null).get(0).getKeyId();

        IfmapConfigException e = assertThrows(IfmapConfigException.class,
                () -> service.rollback(a, bHistory, 0, null, OPERATOR, REQ));
        assertTrue(e.getMessage().contains("不属于配置"), e.getMessage());

        long aHistory = service.history(a, null).get(0).getKeyId();
        assertFalse(service.rollback(a, aHistory, 99, null, OPERATOR, REQ));
    }

    @Test
    @DisplayName("分支：新增/修改/删除 + 唯一键冲突翻译成业务异常")
    void branchLifecycle() {
        service.create(AdminTestSupport.config("IF_A", "apply", 1), null, OPERATOR, REQ);
        long branchId = service.createBranch(AdminTestSupport.branch("IF_A", "submit", "命中", "loanResult", "0000", 1),
                OPERATOR);
        assertEquals(1, service.branches("1", "IF_A", false).size());

        LogicBranchConfig branch = service.branches("1", "IF_A", false).get(0);
        branch.setLogicBranchValue("0001");
        assertTrue(service.updateBranch(branch, OPERATOR));
        assertEquals("0001", service.branches("1", "IF_A", false).get(0).getLogicBranchValue());

        IfmapConfigException duplicate = assertThrows(IfmapConfigException.class,
                () -> service.createBranch(AdminTestSupport.branch("IF_A", "submit", "命中", "f", "v", 1), OPERATOR));
        assertTrue(duplicate.getMessage().contains("已存在"), duplicate.getMessage());

        assertTrue(service.deleteBranch(branchId, OPERATOR));
        assertEquals(0, service.branches("1", "IF_A", false).size());
        assertEquals(1, service.branches("1", "IF_A", true).size());
        assertFalse(service.deleteBranch(branchId, OPERATOR), "重复删除返回 false");
    }

    @Test
    @DisplayName("分支集合问题：新增第二条默认兜底分支 → 校验拦住")
    void branchSetValidation() {
        service.create(AdminTestSupport.config("IF_A", "apply", 1), null, OPERATOR, REQ);
        long first = service.createBranch(AdminTestSupport.branch("IF_A", "submit", "默认", "", "", 1), OPERATOR);
        assertTrue(first > 0, "新增分支应返回主键");
        assertThrows(IfmapValidationException.class,
                () -> service.createBranch(AdminTestSupport.branch("IF_A", "submit", "默认2", "", "", 2), OPERATOR));
    }

    @Test
    @DisplayName("试跑：mockResponse 直接判定，不外呼；缺 interfaceNo/busiNode → 400 语义异常")
    void dryRunDoesNotCallPartner() {
        service.create(AdminTestSupport.config("IF_A", "apply", 1), null, OPERATOR, REQ);

        DryRunRequest request = new DryRunRequest();
        request.setTenantId("1");
        request.setInterfaceNo("IF_A");
        request.setBusiNode("apply");
        request.setParams(params());
        request.setMockResponse("{\"resultCode\":\"0000\",\"data\":{\"applyNo\":\"A1\"}}");

        IfmapResult result = service.dryRun(request);
        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertTrue(result.getRawResponse().contains("A1"), result.getRawResponse());
        assertEquals("A1", result.getData().get("applyNo"), String.valueOf(result.getData()));
        assertEquals(1, result.getExecutedInterfaces().size());

        DryRunRequest noMock = new DryRunRequest();
        noMock.setTenantId("1");
        noMock.setInterfaceNo("IF_A");
        noMock.setBusiNode("apply");
        noMock.setParams(params());
        IfmapResult failed = service.dryRun(noMock);
        assertFalse(failed.isSuccess(), "空应答判定为失败，但不应抛异常");

        assertThrows(IfmapConfigException.class, () -> service.dryRun(new DryRunRequest()));
    }

    @Test
    @DisplayName("试跑：编排器未装配 → 明确报错（不是 NPE）")
    void dryRunWithoutOrchestrator() {
        IfmapAdminProperties properties = new IfmapAdminProperties();
        ConfigValidator validator = new ConfigValidator(null, new JacksonJsonOps(),
                new SpecialDealStrategyRegistry(), new ActionRegistry(), repository);
        ConfigAdminService noOrchestrator = new ConfigAdminService(writer, repository, historyRepository,
                AdminTestSupport.snapshots(), validator, new ConfigAuditor(repository, validator, 100),
                provider(null), provider(null), properties);
        DryRunRequest request = new DryRunRequest();
        request.setInterfaceNo("IF_A");
        request.setBusiNode("apply");
        IfmapConfigException e = assertThrows(IfmapConfigException.class, () -> noOrchestrator.dryRun(request));
        assertTrue(e.getMessage().contains("IfmapOrchestrator"), e.getMessage());
    }

    @Test
    @DisplayName("审计：通过服务入口也能跑出报告")
    void auditThroughService() {
        service.create(AdminTestSupport.config("IF_A", "apply", 1), null, OPERATOR, REQ);
        AuditReport report = service.audit("1", null);
        assertEquals(1, report.getConfigCount());
    }

    // ---------------------------------------------------------------- 内部

    private static IfmapOrchestrator orchestrator(ConfigRepository repository) {
        JacksonJsonOps jsonOps = new JacksonJsonOps();
        ActionRegistry actions = new ActionRegistry();
        actions.register(new SubmitAction());
        return new IfmapOrchestrator.Builder()
                .engine(IfmapEngine.builder().jsonOps(jsonOps).build())
                .repository(repository)
                .tenantResolver(request -> request.getTenantId())
                .jsonOps(jsonOps)
                .actions(actions)
                .logicBranches(new LogicBranchStrategyRegistry())
                .specialDeals(new SpecialDealStrategyRegistry())
                .fullParams(new FullParamStrategyRegistry())
                .callbacks(new CallbackRegistry())
                .gateway(new FailingGateway())
                .build();
    }

    private static Map<String, Object> params() {
        Map<String, Object> params = new java.util.LinkedHashMap<String, Object>();
        params.put("bizNo", "B1");
        params.put("amount", 100);
        return params;
    }

    private static <T> ObjectProvider<T> provider(final T instance) {
        return new ObjectProvider<T>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }
        };
    }

    @IfmapAction("submit")
    static class SubmitAction implements IfmapActionHandler {
        @Override
        public void execute(StrategyContext context) {
            context.putParam("submitted", Boolean.TRUE);
        }
    }

    /** 试跑必须走 mock，真外呼直接失败（一旦被调用测试就红）。 */
    static class FailingGateway implements PartnerServiceGateway {
        @Override
        public String exchange(PartnerCall call) {
            throw new IllegalStateException("dry-run 不允许外呼真银行：" + call);
        }
    }
}
