package cn.cj.ifmap.core.cache;

import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 仓储缓存装饰器：只给「逻辑分支列表」加缓存。
 *
 * <p>为什么只缓存逻辑分支？</p>
 * <ul>
 *   <li>分支列表按 {@code (tenantId, interfaceNo)} 查询、<b>与业务参数无关</b>，命中率天然最高；</li>
 *   <li>接口配置主表按 {@code (tenantId, interfaceNo, busiNode)} 查询，业务差异大、失效面宽，
 *       缓存收益小、风险高（原方案 v1 的结论），故<b>不</b>缓存。</li>
 * </ul>
 *
 * <p><b>缓存 key 必含租户</b>（跨租户串配置是本模块的最高风险项）。
 * 管理端保存/删除分支配置后请调用 {@link #invalidate(String, String)}。</p>
 *
 * @author caijun
 */
public final class CachingConfigRepository implements ConfigRepository {

    private static final String KEY_PREFIX = "ifmap:logicBranch:";

    private final ConfigRepository delegate;
    private final ConfigCache cache;

    public CachingConfigRepository(ConfigRepository delegate, ConfigCache cache) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (cache == null) {
            throw new IllegalArgumentException("cache must not be null");
        }
        this.delegate = delegate;
        this.cache = cache;
    }

    /** 缓存 key：{@code ifmap:logicBranch:<tenantId>:<interfaceNo>}。 */
    public static String cacheKey(String tenantId, String interfaceNo) {
        return KEY_PREFIX + (tenantId == null ? "" : tenantId.trim()) + ":"
                + (interfaceNo == null ? "" : interfaceNo.trim());
    }

    @Override
    public List<LogicBranchConfig> queryLogicBranches(final String tenantId, final String interfaceNo) {
        return cache.get(cacheKey(tenantId, interfaceNo), new java.util.function.Function<String, List<LogicBranchConfig>>() {
            @Override
            public List<LogicBranchConfig> apply(String key) {
                List<LogicBranchConfig> loaded = delegate.queryLogicBranches(tenantId, interfaceNo);
                return Collections.unmodifiableList(new ArrayList<LogicBranchConfig>(loaded));
            }
        });
    }

    /** 失效某个接口的分支缓存。 */
    public void invalidate(String tenantId, String interfaceNo) {
        cache.invalidate(cacheKey(tenantId, interfaceNo));
    }

    @Override
    public List<IfmapConfig> queryConfigs(String tenantId, String interfaceNo, String busiNode) {
        return delegate.queryConfigs(tenantId, interfaceNo, busiNode);
    }

    @Override
    public Optional<IfmapConfig> findConfig(String tenantId, String interfaceNo, String busiNode, int order) {
        return delegate.findConfig(tenantId, interfaceNo, busiNode, order);
    }

    @Override
    public void saveExecutionLog(ExecutionLog log) {
        delegate.saveExecutionLog(log);
    }

    /** 被装饰的仓储。 */
    public ConfigRepository delegate() {
        return delegate;
    }

    /** 缓存实现。 */
    public ConfigCache cache() {
        return cache;
    }
}
