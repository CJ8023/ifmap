package cn.cj.ifmap.admin.web;

import cn.cj.ifmap.admin.ConfigAdminService;
import cn.cj.ifmap.admin.IfmapAdminHeaders;
import cn.cj.ifmap.admin.IfmapValidationException;
import cn.cj.ifmap.admin.ValidationResult;
import cn.cj.ifmap.admin.dto.ConfigSaveRequest;
import cn.cj.ifmap.admin.dto.DryRunRequest;
import cn.cj.ifmap.admin.dto.HistoryView;
import cn.cj.ifmap.admin.dto.RollbackRequest;
import cn.cj.ifmap.admin.dto.StatusChangeRequest;
import cn.cj.ifmap.admin.dto.ValidateRequest;
import cn.cj.ifmap.core.config.ConfigQuery;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.PageResult;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.model.IfmapResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 配置管理端（设计 §8.2）：挂载在 {@code ifmap.admin.base-path} 之下，默认 {@code /ifmap/admin}。
 *
 * <p>状态码约定（详见 {@code docs/07-管理端REST.md}）：<b>200</b> 成功（含"业务上没做成" —— 乐观锁冲突、
 * 目标已删除时返回 {@code ok=false}，由前端按业务提示而不是当异常）；<b>400</b> 参数/目标问题（如"配置不存在"）；
 * <b>422</b> 保存前校验未通过（带 errors/warnings 明细）；<b>502</b> 外呼失败（试跑正常不出网）；
 * <b>500</b> 兜底。</p>
 *
 * @author caijun
 */
@RestController
@RequestMapping("/configs")
public class IfmapConfigAdminController {

    private final ConfigAdminService service;

    public IfmapConfigAdminController(ConfigAdminService service) {
        this.service = service;
    }

    /** 分页列表。 */
    @GetMapping
    public PageResult<IfmapConfig> list(@RequestParam(value = "tenantId", required = false) String tenantId,
                                        @RequestParam(value = "interfaceNo", required = false) String interfaceNo,
                                        @RequestParam(value = "busiNode", required = false) String busiNode,
                                        @RequestParam(value = "bankCode", required = false) String bankCode,
                                        @RequestParam(value = "status", required = false) Integer status,
                                        @RequestParam(value = "includeDeleted", required = false) Boolean includeDeleted,
                                        @RequestParam(value = "page", defaultValue = "1") int page,
                                        @RequestParam(value = "size", defaultValue = "20") int size) {
        ConfigQuery query = new ConfigQuery()
                .setTenantId(tenantId)
                .setInterfaceNo(interfaceNo)
                .setBusiNode(busiNode)
                .setBankCode(bankCode)
                .setStatus(status)
                .setIncludeDeleted(includeDeleted != null && includeDeleted)
                .setPage(page)
                .setSize(size);
        return service.list(query);
    }

    /** 详情。 */
    @GetMapping("/{keyId}")
    public IfmapConfig get(@PathVariable("keyId") long keyId) {
        return service.get(keyId);
    }

    /**
     * 保存前校验（不落库）。
     *
     * <p>有 error 时抛 {@link IfmapValidationException} → 由统一异常处理返回 <b>422 + errors/warnings</b>；
     * 只有 warning（或全过）时返回 200 + 明细。前端据此直接展示"哪几项不过、哪些是提醒"。</p>
     */
    @PostMapping("/validate")
    public ValidationResult validate(@RequestBody ValidateRequest request) {
        if (request == null || request.getConfig() == null) {
            throw new IfmapConfigException("config 不能为空");
        }
        ValidationResult result = service.validate(request.getConfig(),
                !Boolean.FALSE.equals(request.getIsCreate()));
        if (!result.isPassed()) {
            throw new IfmapValidationException(result);
        }
        return result;
    }

    /** 试跑（用假应答，不出网）。 */
    @PostMapping("/dry-run")
    public IfmapResult dryRun(@RequestBody DryRunRequest request) {
        return service.dryRun(request);
    }

    /** 新增。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody ConfigSaveRequest request,
                                      @RequestHeader(value = IfmapAdminHeaders.OPERATOR_ID, required = false)
                                      String operatorId,
                                      @RequestHeader(value = IfmapAdminHeaders.REQUEST_ID, required = false)
                                      String requestId) {
        if (request == null || request.getConfig() == null) {
            throw new IfmapConfigException("config 不能为空");
        }
        long keyId = service.create(request.getConfig(), request.getReason(), operatorId, requestId);
        return Map.of("ok", true, "keyId", keyId);
    }

    /** 修改（乐观锁）。 */
    @PutMapping("/{keyId}")
    public Map<String, Object> update(@PathVariable("keyId") long keyId, @RequestBody ConfigSaveRequest request,
                                      @RequestHeader(value = IfmapAdminHeaders.OPERATOR_ID, required = false)
                                      String operatorId,
                                      @RequestHeader(value = IfmapAdminHeaders.REQUEST_ID, required = false)
                                      String requestId) {
        if (request == null || request.getConfig() == null) {
            throw new IfmapConfigException("config 不能为空");
        }
        request.getConfig().setKeyId(keyId);
        boolean ok = service.update(request.getConfig(), request.getExpectedVersion(), request.getReason(),
                operatorId, requestId);
        return Map.of("ok", ok, "conflict", !ok);
    }

    /** 逻辑删除。 */
    @DeleteMapping("/{keyId}")
    public Map<String, Object> delete(@PathVariable("keyId") long keyId,
                                      @RequestParam(value = "reason", required = false) String reason,
                                      @RequestHeader(value = IfmapAdminHeaders.OPERATOR_ID, required = false)
                                      String operatorId,
                                      @RequestHeader(value = IfmapAdminHeaders.REQUEST_ID, required = false)
                                      String requestId) {
        return Map.of("ok", service.delete(keyId, reason, operatorId, requestId));
    }

    /** 启用 / 停用。 */
    @PostMapping("/{keyId}/status")
    public Map<String, Object> changeStatus(@PathVariable("keyId") long keyId,
                                            @RequestBody StatusChangeRequest request,
                                            @RequestHeader(value = IfmapAdminHeaders.OPERATOR_ID, required = false)
                                            String operatorId,
                                            @RequestHeader(value = IfmapAdminHeaders.REQUEST_ID, required = false)
                                            String requestId) {
        if (request == null) {
            throw new IfmapConfigException("请求体不能为空");
        }
        boolean ok = service.changeStatus(keyId, request.getStatus(), request.getExpectedVersion(),
                request.getReason(), operatorId, requestId);
        return Map.of("ok", ok, "conflict", !ok);
    }

    /** 变更历史（最新在前）。 */
    @GetMapping("/{keyId}/history")
    public List<HistoryView> history(@PathVariable("keyId") long keyId,
                                     @RequestParam(value = "limit", required = false) Integer limit) {
        return service.history(keyId, limit);
    }

    /** 回滚到指定历史版本。 */
    @PostMapping("/{keyId}/rollback")
    public Map<String, Object> rollback(@PathVariable("keyId") long keyId, @RequestBody RollbackRequest request,
                                        @RequestHeader(value = IfmapAdminHeaders.OPERATOR_ID, required = false)
                                        String operatorId,
                                        @RequestHeader(value = IfmapAdminHeaders.REQUEST_ID, required = false)
                                        String requestId) {
        if (request == null || request.getHistoryId() == null) {
            throw new IfmapConfigException("historyId 不能为空");
        }
        boolean ok = service.rollback(keyId, request.getHistoryId(), request.getExpectedVersion(),
                request.getReason(), operatorId, requestId);
        return Map.of("ok", ok, "conflict", !ok);
    }
}
