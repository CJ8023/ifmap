package cn.cj.ifmap.core.strategy;

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.model.IfmapRequest;
import cn.cj.ifmap.core.rule.RuleContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次接口执行中传给策略/动作的上下文。
 *
 * <p>{@code params} 是<b>可变</b>的：主参数组包（{@code FullParamStrategy}）与特殊处理
 * （{@code SpecialDealStrategy}）在其中补充/覆盖字段，这就是存量引擎"补参数"的替代形态。</p>
 *
 * @author caijun
 */
public final class StrategyContext {

    private final IfmapRequest request;
    private final IfmapConfig config;
    private final Map<String, Object> params = new LinkedHashMap<String, Object>();
    private final RuleContext ruleContext;

    private String requestJson;
    private String responseJson;
    private Object responseData;
    private String matchedBranch;

    private StrategyContext(Builder builder) {
        this.request = builder.request;
        this.config = builder.config;
        this.ruleContext = builder.ruleContext;
        this.requestJson = builder.requestJson;
        this.responseJson = builder.responseJson;
        this.responseData = builder.responseData;
        if (builder.params != null) {
            this.params.putAll(builder.params);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public IfmapRequest getRequest() {
        return request;
    }

    public IfmapConfig getConfig() {
        return config;
    }

    public RuleContext getRuleContext() {
        return ruleContext;
    }

    /** 当前参数（只读视图；写入用 {@link #putParam}）。 */
    /** 当前接口号（来自配置）；无配置时为 null。 */
    public String getInterfaceNo() {
        return config == null ? null : config.getInterfaceNo();
    }

    /** 当前业务号（来自请求）；用于分支动作里写业务数据。 */
    public String getBizId() {
        return request == null ? null : request.getBizId();
    }

    public Map<String, Object> getParams() {
        return Collections.unmodifiableMap(params);
    }

    /** 写参数（补参数 / 覆盖）。 */
    public StrategyContext putParam(String key, Object value) {
        params.put(key, value);
        return this;
    }

    /** 批量写参数。 */
    public StrategyContext putParams(Map<String, Object> values) {
        if (values != null) {
            params.putAll(values);
        }
        return this;
    }

    /** 按 参数 → 业务负载 → 扩展属性 顺序取值。 */
    public Object get(String key) {
        if (params.containsKey(key)) {
            return params.get(key);
        }
        if (request != null && request.getPayload().containsKey(key)) {
            return request.getPayload().get(key);
        }
        return request == null ? null : request.getAttribute(key);
    }

    public String getRequestJson() {
        return requestJson;
    }

    public void setRequestJson(String requestJson) {
        this.requestJson = requestJson;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }

    public Object getResponseData() {
        return responseData;
    }

    public void setResponseData(Object responseData) {
        this.responseData = responseData;
    }

    public String getMatchedBranch() {
        return matchedBranch;
    }

    public void setMatchedBranch(String matchedBranch) {
        this.matchedBranch = matchedBranch;
    }

    @Override
    public String toString() {
        return "StrategyContext{interfaceNo=" + (config == null ? null : config.getInterfaceNo())
                + ", paramsKeys=" + params.keySet() + '}';
    }

    /** 构造器。 */
    public static final class Builder {

        private IfmapRequest request;
        private IfmapConfig config;
        private RuleContext ruleContext = RuleContext.empty();
        private Map<String, Object> params;
        private String requestJson;
        private String responseJson;
        private Object responseData;

        public Builder request(IfmapRequest request) {
            this.request = request;
            return this;
        }

        public Builder config(IfmapConfig config) {
            this.config = config;
            return this;
        }

        public Builder ruleContext(RuleContext ruleContext) {
            this.ruleContext = ruleContext == null ? RuleContext.empty() : ruleContext;
            return this;
        }

        public Builder params(Map<String, Object> params) {
            this.params = params;
            return this;
        }

        public Builder requestJson(String requestJson) {
            this.requestJson = requestJson;
            return this;
        }

        public Builder responseJson(String responseJson) {
            this.responseJson = responseJson;
            return this;
        }

        public Builder responseData(Object responseData) {
            this.responseData = responseData;
            return this;
        }

        public StrategyContext build() {
            return new StrategyContext(this);
        }
    }
}
