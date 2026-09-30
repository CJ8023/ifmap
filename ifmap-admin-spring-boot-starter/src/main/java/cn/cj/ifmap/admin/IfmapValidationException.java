package cn.cj.ifmap.admin;

import cn.cj.ifmap.core.exception.IfmapConfigException;

/**
 * 保存前校验未通过：携带完整违规清单，管理端直接回给前端做定位（不落库）。
 *
 * @author caijun
 */
public class IfmapValidationException extends IfmapConfigException {

    private static final long serialVersionUID = 1L;

    private final transient ValidationResult result;

    public IfmapValidationException(ValidationResult result) {
        super(buildMessage(result));
        this.result = result;
    }

    public ValidationResult getResult() {
        return result;
    }

    private static String buildMessage(ValidationResult result) {
        if (result == null || result.getErrors().isEmpty()) {
            return "配置校验未通过";
        }
        return "配置校验未通过：" + String.join("；", result.getErrors());
    }
}
