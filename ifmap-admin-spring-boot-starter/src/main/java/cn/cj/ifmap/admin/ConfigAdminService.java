package cn.cj.ifmap.admin;

import cn.cj.ifmap.admin.dto.DryRunRequest;
import cn.cj.ifmap.admin.dto.HistoryView;
import cn.cj.ifmap.core.cache.CachingConfigRepository;
import cn.cj.ifmap.core.config.ConfigQuery;
import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.config.PageResult;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.model.IfmapConfigHistory;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.orchestrator.IfmapOrchestrator;
import cn.cj.ifmap.jdbc.JdbcConfigHistoryRepository;
import cn.cj.ifmap.jdbc.JdbcConfigRepository;
import cn.cj.ifmap.jdbc.JdbcConfigWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理端业务编排（设计 §8）：配置 CRUD + 校验 + 试跑 + 历史回滚 + 审计。
 *
 * <p>四类写操作（新增/修改/删除/启停/回滚）都在一个事务里完成"改配置 + 落历史"，
 * 保证审计链不会出现"改了但没记"的缺口；写完立刻失效引擎缓存，
 * 避免管理端改完、引擎还按老配置跑（TTL 内的旧数据）。</p>
 *
 * @author caijun
 */
public class ConfigAdminService {

    private static final Logger log = LoggerFactory.getLogger(ConfigAdminService.class);

    private final JdbcConfigWriter writer;
    private final JdbcConfigRepository repository;
    private final JdbcConfigHistoryRepository historyRepository;
    private final ConfigSnapshotMapper snapshots;
    private final ConfigValidator validator;
    private final ConfigAuditor auditor;
    private final ObjectProvider<IfmapOrchestrator> orchestratorProvider;
    private final ObjectProvider<ConfigRepository> engineRepositoryProvider;
    private final IfmapAdminProperties properties;

    public ConfigAdminService(JdbcConfigWriter writer, JdbcConfigRepository repository,
                              JdbcConfigHistoryRepository historyRepository,
                              ConfigSnapshotMapper snapshots, ConfigValidator validator,
                              ConfigAuditor auditor,
                              ObjectProvider<IfmapOrchestrator> orchestratorProvider,
                              ObjectProvider<ConfigRepository> engineRepositoryProvider,
                              IfmapAdminProperties properties) {
        this.writer = writer;
        this.repository = repository;
        this.historyRepository = historyRepository;
        this.snapshots = snapshots;
        this.validator = validator;
        this.auditor = auditor;
        this.orchestratorProvider = orchestratorProvider;
        this.engineRepositoryProvider = engineRepositoryProvider;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- 查询

    /** 分页查询配置（默认不含已删除行）。 */
    public PageResult<IfmapConfig> list(ConfigQuery query) {
        ConfigQuery effective = query == null ? new ConfigQuery() : query;
        List<IfmapConfig> rows = repository.queryConfigs(effective);
        long total = repository.countConfigs(effective);
        return new PageResult<IfmapConfig>(total, effective.getPage(), effective.getSize(), rows);
    }

    /** 查询单条配置（含停用/已删除，管理端编辑页需要）。 */
    public IfmapConfig get(long keyId) {
        return writer.findByKeyId(keyId).orElseThrow(
                () -> new IfmapConfigException("配置不存在：#" + keyId));
    }

    /** 查询逻辑分支。 */
    public List<LogicBranchConfig> branches(String tenantId, String interfaceNo, boolean includeDeleted) {
        if (interfaceNo == null || interfaceNo.trim().isEmpty()) {
            throw new IfmapConfigException("interfaceNo 不能为空");
        }
        return repository.queryLogicBranches(tenantId, interfaceNo, includeDeleted);
    }

    /** 查询变更历史（最新在前），快照与差异已解析成结构。 */
    public List<HistoryView> history(long keyId, Integer limit) {
        int rows = limit == null || limit <= 0 ? properties.getHistoryLimit() : limit;
        List<IfmapConfigHistory> list = historyRepository.list(keyId, rows);
        List<HistoryView> views = new ArrayList<HistoryView>(list.size());
        for (IfmapConfigHistory row : list) {
            views.add(new HistoryView(row, snapshots.parseJsonOrNull(row.getSnapshot()),
                    snapshots.parseJsonOrNull(row.getDiff())));
        }
        return views;
    }

    // ---------------------------------------------------------------- 校验 / 试跑 / 审计

    /** 保存前校验（不落库）：配置本身 + 该接口的分支集合。 */
    public ValidationResult validate(IfmapConfig config, boolean creating) {
        ValidationResult result = validator.validate(config, creating);
        if (config != null && config.getInterfaceNo() != null && !config.getInterfaceNo().trim().isEmpty()) {
            String tenantId = config.getTenantId() == null ? "-1" : String.valueOf(config.getTenantId());
            result.merge(validator.validateBranches(tenantId, config.getInterfaceNo()));
        }
        return result;
    }

    /** 试跑：用库里现有配置渲染并判定，{@code mockResponse} 造假应答，绝不外呼。 */
    public IfmapResult dryRun(DryRunRequest request) {
        if (request == null || request.getInterfaceNo() == null || request.getInterfaceNo().trim().isEmpty()) {
            throw new IfmapConfigException("dry-run 必须提供 interfaceNo");
        }
        requireBlank(request.getBusiNode(), "dry-run 必须提供 busiNode");
        IfmapOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            throw new IfmapConfigException("dry-run 需要 IfmapOrchestrator：请确认已装配 ifmap 编排器（需要 DataSource）");
        }
        IfmapRequest engineRequest = IfmapRequest.builder()
                .tenantId(request.getTenantId())
                .bizId(request.getBizId())
                .payload(request.getParams())
                .build();
        String mock = request.getMockResponse() == null || request.getMockResponse().trim().isEmpty()
                ? "{}" : request.getMockResponse();
        return orchestrator.execute(engineRequest, request.getInterfaceNo(), request.getBusiNode(), mock);
    }

    /** 全量巡检。 */
    public AuditReport audit(String tenantId, String busiNode) {
        return auditor.audit(tenantId, busiNode);
    }

    // ---------------------------------------------------------------- 配置写操作

    /** 新增配置，返回主键。 */
    @Transactional(rollbackFor = Exception.class)
    public long create(IfmapConfig config, String reason, String operatorId, String requestId) {
        ValidationResult result = validate(config, true);
        if (!result.isPassed()) {
            throw new IfmapValidationException(result);
        }
        long keyId = writer.insert(config);
        IfmapConfig saved = get(keyId);
        record(saved, IfmapConfigHistory.CREATE, reason, operatorId, requestId, null);
        evict(saved);
        log.info("ifmap 管理端新增配置 #{}（{}#{}）by {}", keyId, saved.getInterfaceNo(),
                saved.getInterfaceOrder(), operatorId);
        return keyId;
    }

    /**
     * 修改配置。
     *
     * @return {@code true} 成功；{@code false} 版本不匹配（已被他人修改）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean update(IfmapConfig config, Integer expectedVersion, String reason,
                          String operatorId, String requestId) {
        if (config == null || config.getKeyId() == null) {
            throw new IfmapConfigException("修改配置必须提供 keyId");
        }
        if (expectedVersion == null) {
            throw new IfmapConfigException("修改配置必须提供 expectedVersion（乐观锁）");
        }
        IfmapConfig before = get(config.getKeyId());
        if (config.getTenantId() == null) {
            config.setTenantId(before.getTenantId());
        }
        ValidationResult result = validate(config, false);
        if (!result.isPassed()) {
            throw new IfmapValidationException(result);
        }
        if (!writer.update(config, expectedVersion.intValue(), operatorId, requestId)) {
            return false;
        }
        IfmapConfig after = get(config.getKeyId());
        record(after, IfmapConfigHistory.UPDATE, reason, operatorId, requestId, snapshots.diff(before, after));
        evict(after);
        log.info("ifmap 管理端修改配置 #{}（{}#{}）by {}", after.getKeyId(), after.getInterfaceNo(),
                after.getInterfaceOrder(), operatorId);
        return true;
    }

    /** 逻辑删除配置（历史里保留删除前快照）。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(long keyId, String reason, String operatorId, String requestId) {
        IfmapConfig before = get(keyId);
        if (!writer.softDelete(keyId, operatorId, requestId)) {
            return false;
        }
        record(before, IfmapConfigHistory.DELETE, reason, operatorId, requestId, null);
        evict(before);
        log.info("ifmap 管理端删除配置 #{}（{}）by {}", keyId, before.getInterfaceNo(), operatorId);
        return true;
    }

    /**
     * 启用 / 停用。
     *
     * @return {@code true} 成功；{@code false} 版本不匹配
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean changeStatus(long keyId, Integer status, Integer expectedVersion, String reason,
                                String operatorId, String requestId) {
        if (status == null) {
            throw new IfmapConfigException("必须提供 status（1 启用 / 0 停用）");
        }
        if (expectedVersion == null) {
            throw new IfmapConfigException("必须提供 expectedVersion（乐观锁）");
        }
        get(keyId);
        if (!writer.updateStatus(keyId, status.intValue(), expectedVersion.intValue(), operatorId, requestId)) {
            return false;
        }
        IfmapConfig after = get(keyId);
        String changeType = status.intValue() == 1 ? IfmapConfigHistory.ENABLE : IfmapConfigHistory.DISABLE;
        record(after, changeType, reason, operatorId, requestId, null);
        evict(after);
        log.info("ifmap 管理端{}配置 #{} by {}", status.intValue() == 1 ? "启用" : "停用", keyId, operatorId);
        return true;
    }

    /**
     * 回滚到指定历史版本：把快照内容写回配置（同样走乐观锁），并再记一条 UPDATE 历史。
     *
     * @return {@code true} 成功；{@code false} 版本不匹配
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean rollback(long keyId, long historyId, Integer expectedVersion, String reason,
                            String operatorId, String requestId) {
        if (expectedVersion == null) {
            throw new IfmapConfigException("回滚必须提供 expectedVersion（乐观锁）");
        }
        IfmapConfigHistory target = historyRepository.findByKeyId(historyId).orElseThrow(
                () -> new IfmapConfigException("历史记录不存在：#" + historyId));
        if (target.getConfigKeyId() == null || target.getConfigKeyId().longValue() != keyId) {
            throw new IfmapConfigException("历史记录 #" + historyId + " 不属于配置 #" + keyId);
        }
        IfmapConfig before = get(keyId);
        IfmapConfig restore = snapshots.fromSnapshot(target.getSnapshot());
        restore.setKeyId(keyId);
        if (restore.getTenantId() == null) {
            restore.setTenantId(before.getTenantId());
        }
        ValidationResult check = validate(restore, false);
        if (!check.isPassed()) {
            throw new IfmapValidationException(check);
        }
        if (!writer.update(restore, expectedVersion.intValue(), operatorId, requestId)) {
            return false;
        }
        IfmapConfig after = get(keyId);
        String rollbackReason = "回滚到历史 #" + historyId
                + (reason == null || reason.trim().isEmpty() ? "" : "：" + reason.trim());
        record(after, IfmapConfigHistory.UPDATE, rollbackReason, operatorId, requestId,
                snapshots.diff(before, after));
        evict(after);
        log.info("ifmap 管理端回滚配置 #{} 到历史 #{} by {}", keyId, historyId, operatorId);
        return true;
    }

    // ---------------------------------------------------------------- 分支写操作

    /** 新增逻辑分支，返回主键。 */
    @Transactional(rollbackFor = Exception.class)
    public long createBranch(LogicBranchConfig branch, String operatorId) {
        ValidationResult result = validator.validateBranch(branch, true);
        if (!result.isPassed()) {
            throw new IfmapValidationException(result);
        }
        long keyId = writer.insert(branch);
        evictByInterface(branch.getTenantId(), branch.getInterfaceNo());
        log.info("ifmap 管理端新增逻辑分支 #{}（{}）by {}", keyId, branch.getLogicBranchName(), operatorId);
        return keyId;
    }

    /**
     * 修改逻辑分支。
     *
     * @return {@code true} 成功；{@code false} 目标不存在或已删除
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean updateBranch(LogicBranchConfig branch, String operatorId) {
        if (branch == null || branch.getKeyId() == null) {
            throw new IfmapConfigException("修改逻辑分支必须提供 keyId");
        }
        ValidationResult result = validator.validateBranch(branch, false);
        if (!result.isPassed()) {
            throw new IfmapValidationException(result);
        }
        java.util.Optional<LogicBranchConfig> before = repository.findLogicBranch(branch.getKeyId());
        boolean ok = writer.update(branch);
        if (before.isPresent()) {
            // 分支可能被改到别的接口上，两个接口的缓存都要失效
            evictByInterface(before.get().getTenantId(), before.get().getInterfaceNo());
        }
        evictByInterface(branch.getTenantId(), branch.getInterfaceNo());
        if (ok) {
            log.info("ifmap 管理端修改逻辑分支 #{} by {}", branch.getKeyId(), operatorId);
        }
        return ok;
    }

    /**
     * 逻辑删除逻辑分支。
     *
     * @return {@code true} 成功；{@code false} 目标不存在或已删除
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteBranch(long keyId, String operatorId) {
        java.util.Optional<LogicBranchConfig> before = repository.findLogicBranch(keyId);
        boolean ok = writer.softDeleteBranch(keyId);
        if (before.isPresent()) {
            evictByInterface(before.get().getTenantId(), before.get().getInterfaceNo());
        }
        log.info("ifmap 管理端删除逻辑分支 #{} by {}（ok={}）", keyId, operatorId, ok);
        return ok;
    }

    // ---------------------------------------------------------------- 内部

    private void record(IfmapConfig config, String changeType, String reason, String operatorId,
                        String requestId, String diff) {
        IfmapConfigHistory row = new IfmapConfigHistory();
        row.setConfigKeyId(config.getKeyId());
        row.setTenantId(config.getTenantId());
        row.setInterfaceNo(config.getInterfaceNo());
        row.setChangeType(changeType);
        row.setChangeReason(reason);
        row.setSnapshot(snapshots.toSnapshot(config));
        row.setDiff(diff);
        row.setAddUserId(operatorId);
        row.setAddRequestId(requestId);
        historyRepository.insert(row);
    }

    private void evict(IfmapConfig config) {
        evictByInterface(config.getTenantId(), config.getInterfaceNo());
    }

    /** 改完立刻失效引擎缓存：否则 TTL 内引擎仍按旧配置跑。 */
    private void evictByInterface(Long tenantId, String interfaceNo) {
        ConfigRepository engineRepository = engineRepositoryProvider.getIfAvailable();
        if (!(engineRepository instanceof CachingConfigRepository)) {
            return;
        }
        ((CachingConfigRepository) engineRepository).invalidate(
                tenantId == null ? "-1" : String.valueOf(tenantId), interfaceNo);
    }

    private static void requireBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IfmapConfigException(message);
        }
    }
}
