package cn.cj.ifmap.core.json;

import cn.cj.ifmap.core.exception.IfmapConfigException;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * {@link JsonOps} 的持有者：默认走 {@link ServiceLoader} 自动发现，
 * 允许宿主显式注入或覆盖。
 *
 * @author caijun
 */
public final class JsonOpsHolder {

    private static final String HINT = "未发现 JsonOps 实现。请任选其一："
            + "① 在 classpath 引入 cn.cj:ifmap-json-jackson（自动发现）；"
            + "② 调用 JsonOpsHolder.set(new MyJsonOps())。";

    private static volatile JsonOps instance;

    private JsonOpsHolder() {
    }

    /** 获取实例；未配置时抛 {@link IfmapConfigException}。 */
    public static JsonOps get() {
        JsonOps local = instance;
        if (local == null) {
            synchronized (JsonOpsHolder.class) {
                local = instance;
                if (local == null) {
                    local = loadFromSpi();
                    if (local == null) {
                        throw new IfmapConfigException(HINT);
                    }
                    instance = local;
                }
            }
        }
        return local;
    }

    /** 显式注入（优先级高于 SPI）。 */
    public static void set(JsonOps jsonOps) {
        if (jsonOps == null) {
            throw new IllegalArgumentException("jsonOps must not be null");
        }
        instance = jsonOps;
    }

    /** 已配置返回 true（不触发 SPI 加载）。 */
    public static boolean isPresent() {
        return instance != null;
    }

    /** 清理，仅供测试。 */
    public static void reset() {
        instance = null;
    }

    private static JsonOps loadFromSpi() {
        ServiceLoader<JsonOps> loader = ServiceLoader.load(JsonOps.class, JsonOpsHolder.class.getClassLoader());
        List<JsonOps> found = new ArrayList<JsonOps>();
        for (JsonOps ops : loader) {
            found.add(ops);
        }
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() > 1) {
            throw new IfmapConfigException("发现多个 JsonOps 实现：" + found
                    + "，请调用 JsonOpsHolder.set(...) 显式指定。");
        }
        return found.get(0);
    }
}
