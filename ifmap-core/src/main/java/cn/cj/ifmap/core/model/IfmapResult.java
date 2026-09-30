package cn.cj.ifmap.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎输出：替换存量引擎里 `ResponseParamEntity` + `ResponseParamVO` 的组合。
 *
 * <p>只含 JDK 类型，宿主机据此组装自己的响应对象。</p>
 *
 * @author caijun
 */
public final class IfmapResult {

    private final boolean success;
    private final Map<String, Object> data;
    private final String rawResponse;
    private final List<String> executedInterfaces;
    private final String matchedBranch;
    private final long elapsedMs;
    private final String errorMessage;

    private IfmapResult(Builder builder) {
        this.success = builder.success;
        this.data = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.data));
        this.rawResponse = builder.rawResponse;
        this.executedInterfaces = Collections.unmodifiableList(new ArrayList<String>(builder.executedInterfaces));
        this.matchedBranch = builder.matchedBranch;
        this.elapsedMs = builder.elapsedMs;
        this.errorMessage = builder.errorMessage;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 成功结果。 */
    public static IfmapResult success(Map<String, Object> data) {
        return builder().success(true).data(data).build();
    }

    /** 失败结果。 */
    public static IfmapResult failure(String errorMessage) {
        return builder().success(false).errorMessage(errorMessage).build();
    }

    public boolean isSuccess() {
        return success;
    }

    /** 按 {@code response_param_template} 渲染出的响应数据（多个接口按顺序合并）。 */
    public Map<String, Object> getData() {
        return data;
    }

    /** 出网响应原文（未脱敏，落库前由 {@code LogMasker} 处理）。 */
    public String getRawResponse() {
        return rawResponse;
    }

    /** 实际执行过的接口号（前置接口链 + 主接口，按执行顺序）。 */
    public List<String> getExecutedInterfaces() {
        return executedInterfaces;
    }

    /** 命中的逻辑分支（{@code logic_branch_flag}），未命中为 null。 */
    public String getMatchedBranch() {
        return matchedBranch;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    /** 失败原因，成功时为 null。 */
    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return "IfmapResult{success=" + success + ", executed=" + executedInterfaces
                + ", branch=" + matchedBranch + ", elapsedMs=" + elapsedMs
                + (success ? "" : ", error=" + errorMessage) + '}';
    }

    /** 构造器。 */
    public static final class Builder {

        private boolean success;
        private final Map<String, Object> data = new LinkedHashMap<String, Object>();
        private String rawResponse;
        private final List<String> executedInterfaces = new ArrayList<String>();
        private String matchedBranch;
        private long elapsedMs;
        private String errorMessage;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder data(Map<String, Object> data) {
            if (data != null) {
                this.data.putAll(data);
            }
            return this;
        }

        public Builder put(String key, Object value) {
            this.data.put(key, value);
            return this;
        }

        public Builder rawResponse(String rawResponse) {
            this.rawResponse = rawResponse;
            return this;
        }

        public Builder executedInterface(String interfaceNo) {
            this.executedInterfaces.add(interfaceNo);
            return this;
        }

        public Builder matchedBranch(String matchedBranch) {
            this.matchedBranch = matchedBranch;
            return this;
        }

        public Builder elapsedMs(long elapsedMs) {
            this.elapsedMs = elapsedMs;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public IfmapResult build() {
            return new IfmapResult(this);
        }
    }
}
