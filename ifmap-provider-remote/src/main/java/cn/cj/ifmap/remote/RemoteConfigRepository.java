/*
 * Copyright 2026 caijun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.cj.ifmap.remote;

import cn.cj.ifmap.core.config.ConfigRepository;
import cn.cj.ifmap.core.config.ExecutionLog;
import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.json.JsonOps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ConfigRepository} 的<b>远端实现</b>：配置与执行日志都在另一个服务里，本实现只做
 * 「拼请求 → 解析 → 映射 → 按契约排序/筛选」。
 *
 * <p><b>远端接口约定</b>（与 ECC 现行 {@code IBankintConfigApi} 的三个方法一一对应，
 * 默认 {@code baseUri} 为 {@code /api/bankint}）：</p>
 * <table border="1">
 *   <caption>与 {@link ConfigRepository} 方法的对应关系</caption>
 *   <tr><th>本接口方法</th><th>HTTP</th><th>响应</th></tr>
 *   <tr><td>{@link #queryConfigs}</td><td>GET {@code {baseUri}/query_bankint_config_list?interfaceNo=&busiNode=}</td>
 *       <td>配置数组</td></tr>
 *   <tr><td>{@link #findConfig}</td><td>同上（本地按 {@code interface_order} 筛一条）</td><td>—</td></tr>
 *   <tr><td>{@link #queryLogicBranches}</td><td>GET {@code {baseUri}/query_logic_branch_list?interfaceNo=}</td>
 *       <td>逻辑分支数组</td></tr>
 *   <tr><td>{@link #saveExecutionLog}</td><td>POST {@code {baseUri}/insert_log}</td><td>布尔</td></tr>
 * </table>
 *
 * <p><b>为什么排序/筛选放在本地做</b>：{@code ConfigRepository} 的契约要求「按 order 升序、只给未删除
 * 且启用的行」，而远端 SQL 的 {@code ORDER BY} / {@code WHERE} 随时可能被人改动（存量服务就是这样
 * 演进的）。靠调用方兜底，配置顺序不会因为远端一次「无害重构」而变；代价只是本地排一下已有数据。</p>
 *
 * <p><b>已知取舍</b>：① 一次调用返回整个节点下的配置列表（远端接口就是这么设计的），所以
 * {@link #findConfig} 会多取几条 —— 引擎一次执行本来就会拿到整组配置，不额外增加调用；
 * ② 不做缓存与重试：缓存见 {@code CachingConfigRepository}（可用它包一层），重试由
 * {@link ConfigFetcher} 实现决定。</p>
 *
 * @author caijun
 */
public final class RemoteConfigRepository implements ConfigRepository {

    /** 与 ECC 现行 {@code IBankintConfigApi} 一致的默认路径前缀。 */
    public static final String DEFAULT_BASE_URI = "/api/bankint";

    /** 查询接口配置列表。 */
    static final String PATH_CONFIGS = "/query_bankint_config_list";
    /** 查询逻辑分支列表。 */
    static final String PATH_BRANCHES = "/query_logic_branch_list";
    /** 写执行日志。 */
    static final String PATH_LOG = "/insert_log";

    /** 顺序契约：{@code interfaceOrder} 升序，同值按 {@code keyId} 兜底（与 JDBC 实现的 ORDER BY 一致）。 */
    private static final Comparator<IfmapConfig> CONFIG_ORDER = new Comparator<IfmapConfig>() {
        @Override
        public int compare(IfmapConfig left, IfmapConfig right) {
            int byOrder = compareInteger(left.getInterfaceOrder(), right.getInterfaceOrder());
            return byOrder != 0 ? byOrder : compareLong(left.getKeyId(), right.getKeyId());
        }
    };

    /** 顺序契约：{@code logicBranchOrder} 升序，同值按 {@code keyId} 兜底。 */
    private static final Comparator<LogicBranchConfig> BRANCH_ORDER = new Comparator<LogicBranchConfig>() {
        @Override
        public int compare(LogicBranchConfig left, LogicBranchConfig right) {
            int byOrder = compareInteger(left.getLogicBranchOrder(), right.getLogicBranchOrder());
            return byOrder != 0 ? byOrder : compareLong(left.getKeyId(), right.getKeyId());
        }
    };

    private final ConfigFetcher fetcher;
    private final JsonOps jsonOps;
    private final String baseUri;

    /** 用默认路径前缀 {@link #DEFAULT_BASE_URI} 构造。 */
    public RemoteConfigRepository(ConfigFetcher fetcher, JsonOps jsonOps) {
        this(fetcher, jsonOps, DEFAULT_BASE_URI);
    }

    /**
     * @param fetcher 远端调用出口（Feign / RestTemplate / 测试桩）
     * @param jsonOps 解析响应体的 JSON 实现（一般复用引擎的那个 bean）
     * @param baseUri 路径前缀，如 {@code /api/bankint} 或 {@code http://financing-scheme/api/bankint}；
     *                末尾多余的 {@code /} 会被去掉
     */
    public RemoteConfigRepository(ConfigFetcher fetcher, JsonOps jsonOps, String baseUri) {
        if (fetcher == null) {
            throw new IfmapConfigException("ConfigFetcher 不能为空");
        }
        if (jsonOps == null) {
            throw new IfmapConfigException("JsonOps 不能为空");
        }
        if (baseUri == null || baseUri.trim().isEmpty()) {
            throw new IfmapConfigException("远端路径前缀不能为空");
        }
        this.fetcher = fetcher;
        this.jsonOps = jsonOps;
        String trimmed = baseUri.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUri = trimmed;
    }

    @Override
    public List<IfmapConfig> queryConfigs(String tenantId, String interfaceNo, String busiNode) {
        Map<String, String> query = new LinkedHashMap<String, String>();
        putIfPresent(query, "interfaceNo", interfaceNo);
        putIfPresent(query, "busiNode", busiNode);
        String body = fetcher.get(baseUri + PATH_CONFIGS, query);
        List<IfmapConfig> configs = JsonConfigMapper.toConfigs(jsonOps, body, parseTenantId(tenantId));
        List<IfmapConfig> enabled = new ArrayList<IfmapConfig>(configs.size());
        for (IfmapConfig config : configs) {
            // 契约：只返回未删除且未停用的配置（远端可能没筛，这里兜底）
            if (isActive(config.getDelStatus()) && config.enabled()) {
                enabled.add(config);
            }
        }
        Collections.sort(enabled, CONFIG_ORDER);
        return enabled;
    }

    @Override
    public Optional<IfmapConfig> findConfig(String tenantId, String interfaceNo, String busiNode, int order) {
        for (IfmapConfig config : queryConfigs(tenantId, interfaceNo, busiNode)) {
            // 刻意用 equals 而不是 ==：包装类型比较用 == 只在 -128~127 缓存范围内碰巧正确
            if (Integer.valueOf(order).equals(config.getInterfaceOrder())) {
                return Optional.of(config);
            }
        }
        return Optional.<IfmapConfig>empty();
    }

    @Override
    public List<LogicBranchConfig> queryLogicBranches(String tenantId, String interfaceNo) {
        Map<String, String> query = new LinkedHashMap<String, String>();
        putIfPresent(query, "interfaceNo", interfaceNo);
        String body = fetcher.get(baseUri + PATH_BRANCHES, query);
        List<LogicBranchConfig> branches = JsonConfigMapper.toBranches(jsonOps, body, parseTenantId(tenantId));
        List<LogicBranchConfig> active = new ArrayList<LogicBranchConfig>(branches.size());
        for (LogicBranchConfig branch : branches) {
            if (isActive(branch.getDelStatus())) {
                active.add(branch);
            }
        }
        Collections.sort(active, BRANCH_ORDER);
        return active;
    }

    @Override
    public void saveExecutionLog(ExecutionLog log) {
        if (log == null) {
            throw new IfmapConfigException("执行日志不能为空");
        }
        String body = jsonOps.toJson(log);
        String response = fetcher.postJson(baseUri + PATH_LOG, body);
        if (isFalse(response)) {
            // 远端明确说「没写成」时不要静默放过：执行日志是合规留痕，悄悄丢掉最难查。
            // 引擎侧写日志失败会降级为 WARN（不影响业务），所以这里抛异常是安全的。
            throw new IfmapConfigException("远端返回失败，执行日志未写入：" + response.trim());
        }
    }

    /** 远端路径前缀（便于日志/诊断）。 */
    public String baseUri() {
        return baseUri;
    }

    /**
     * 租户 ID：空白 → 单租户 {@code -1}；非数字 → 失败（与 {@code JdbcConfigRepository} 同一口径，
     * {@link ConfigRepository} 的契约明确要求解析失败要抛 {@link IfmapConfigException}）。
     */
    private static Long parseTenantId(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return Long.valueOf(-1L);
        }
        try {
            return Long.valueOf(tenantId.trim());
        } catch (NumberFormatException e) {
            throw new IfmapConfigException("租户 ID 不是数字：" + tenantId, e);
        }
    }

    /** 空白条件不拼进查询串：远端若按 {@code busi_node = ''} 过滤，传空串会查不到数据。 */
    private static void putIfPresent(Map<String, String> query, String name, String value) {
        if (value != null && !value.trim().isEmpty()) {
            query.put(name, value);
        }
    }

    private static boolean isActive(Integer delStatus) {
        return delStatus == null || delStatus.intValue() == 0;
    }

    /** 远端返回 {@code false}（JSON 布尔、字符串 {@code "false"}）视为失败；空体/其它内容不解读。 */
    private static boolean isFalse(String response) {
        return response != null && "false".equalsIgnoreCase(response.trim());
    }

    private static int compareInteger(Integer left, Integer right) {
        if (left == null) {
            return right == null ? 0 : 1;
        }
        if (right == null) {
            return -1;
        }
        return left.compareTo(right);
    }

    private static int compareLong(Long left, Long right) {
        if (left == null) {
            return right == null ? 0 : 1;
        }
        if (right == null) {
            return -1;
        }
        return left.compareTo(right);
    }
}
