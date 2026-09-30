package cn.cj.ifmap.admin.dto;

import java.util.List;

/**
 * 管理端统一错误体（HTTP 状态码 + 业务信息 + 可选校验明细）。
 *
 * @author caijun
 */
public class ApiError {

    private final boolean ok = false;
    private final String error;
    private final String type;
    private List<String> errors;
    private List<String> warnings;

    public ApiError(String error, String type) {
        this.error = error;
        this.type = type;
    }

    public ApiError(String error, String type, List<String> errors, List<String> warnings) {
        this.error = error;
        this.type = type;
        this.errors = errors;
        this.warnings = warnings;
    }

    public boolean isOk() {
        return ok;
    }

    public String getError() {
        return error;
    }

    public String getType() {
        return type;
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
