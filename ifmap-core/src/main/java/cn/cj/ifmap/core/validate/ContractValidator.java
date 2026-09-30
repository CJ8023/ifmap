package cn.cj.ifmap.core.validate;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.exception.StartupValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 契约自检：把"DB 里的模板"与"已注册规则"对一遍，缺规则即报违规。
 *
 * <p>对应设计 §5.4 的验收口径 —— 存量引擎里 {@code @FUN(farmatDate,...)} 这种拼写错误
 * 会在生产静默输出字面量（已在生产实证 ×5），本类把问题提前到部署前的启动自检。</p>
 *
 * @author caijun
 */
public final class ContractValidator {

    private final IfmapEngine engine;

    public ContractValidator(IfmapEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine 不能为 null");
        }
        this.engine = engine;
    }

    /** 校验一批配置（遍历请求 + 响应模板）。 */
    public ContractReport validate(List<IfmapConfig> configs) {
        List<ContractReport.Violation> violations = new ArrayList<ContractReport.Violation>();
        if (configs == null) {
            return new ContractReport(violations);
        }
        for (IfmapConfig config : configs) {
            if (config == null) {
                continue;
            }
            check(config, config.getRequestParamTemplate(), ContractReport.Violation.KIND_REQUEST, violations);
            check(config, config.getResponseParamTemplate(), ContractReport.Violation.KIND_RESPONSE, violations);
        }
        return new ContractReport(violations);
    }

    private void check(IfmapConfig config, String template, String kind,
                       List<ContractReport.Violation> violations) {
        if (template == null || template.trim().isEmpty()) {
            return;
        }
        String templateId = config.getInterfaceNo() + ":" + kind;
        if (!engine.getJsonOps().isJson(template)) {
            violations.add(new ContractReport.Violation(config.getInterfaceNo(), config.getInterfaceOrder(),
                    kind, "模板非法：不是合法 JSON 文本"));
            return;
        }
        try {
            engine.validateTemplate(templateId, template);
        } catch (StartupValidationException e) {
            for (Map.Entry<String, Set<String>> entry : e.getMissingRules().entrySet()) {
                violations.add(new ContractReport.Violation(config.getInterfaceNo(), config.getInterfaceOrder(),
                        kind, "未注册规则 " + entry.getValue()));
            }
            if (e.getMissingRules().isEmpty()) {
                violations.add(new ContractReport.Violation(config.getInterfaceNo(), config.getInterfaceOrder(),
                        kind, e.getMessage()));
            }
        } catch (RuntimeException e) {
            violations.add(new ContractReport.Violation(config.getInterfaceNo(), config.getInterfaceOrder(),
                    kind, "模板非法：" + e.getMessage()));
        }
    }
}
