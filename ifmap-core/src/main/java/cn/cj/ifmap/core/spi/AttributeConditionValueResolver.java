package cn.cj.ifmap.core.spi;

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.model.IfmapRequest;

/**
 * 默认条件解析：{@code payload:x} → 业务负载；{@code attr:x} → 扩展属性；{@code header:x} → 请求头；
 * 裸键按 负载 → 属性 → 请求头 顺序查找。
 *
 * @author caijun
 */
public class AttributeConditionValueResolver implements ConditionValueResolver {

    @Override
    public Object resolve(String condition, IfmapRequest request, IfmapConfig config) {
        if (condition == null || condition.isEmpty() || request == null) {
            return null;
        }
        String key = condition.trim();
        if (key.startsWith("payload:")) {
            return request.getPayload().get(key.substring("payload:".length()));
        }
        if (key.startsWith("attr:")) {
            return request.getAttributes().get(key.substring("attr:".length()));
        }
        if (key.startsWith("header:")) {
            return request.getHeader(key.substring("header:".length()));
        }
        if (request.getPayload().containsKey(key)) {
            return request.getPayload().get(key);
        }
        if (request.getAttributes().containsKey(key)) {
            return request.getAttributes().get(key);
        }
        return request.getHeader(key);
    }
}
