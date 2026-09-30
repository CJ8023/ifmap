package cn.cj.ifmap.core.config;

import java.util.List;
import java.util.Optional;

/**
 * 配置仓储 SPI：引擎从宿主机取配置与写执行日志的**唯一**入口。
 *
 * <p>实现方约定（违反会导致"看似随机"的行为）：</p>
 * <ol>
 *   <li>{@link #queryConfigs} <b>必须</b>按 {@code interfaceOrder} 升序返回，同值按 {@code keyId} 兜底
 *       —— 引擎不再自行排序（对比现状：原实现依赖 DB 返回顺序，且顺序字段从未被使用）。</li>
 *   <li>{@link #queryLogicBranches} <b>必须</b>按 {@code logicBranchOrder} 升序返回，同值按 {@code keyId} 兜底。</li>
 *   <li>只返回未删除（{@code del_status = 0}）且未停用的配置；停用配置由管理端维护，不应进入执行链路。</li>
 * </ol>
 *
 * <p>{@code tenantId} 用 {@code String} 承载以兼容"租户 ID 非数字"的宿主机；单租户场景传
 * {@code "-1"}。实现方负责解析为库内类型，解析失败应抛
 * {@link cn.cj.ifmap.core.exception.IfmapConfigException}。</p>
 *
 * <p>实现：{@code JdbcConfigRepository}（provider-jdbc）/ {@code FeignConfigRepository}（provider-feign）
 * / {@code InMemoryConfigRepository}（provider-memory、单测）。</p>
 *
 * @author caijun
 */
public interface ConfigRepository {

    /** 按业务键查询接口配置（按 interfaceOrder 升序）。 */
    List<IfmapConfig> queryConfigs(String tenantId, String interfaceNo, String busiNode);

    /** 查询单条配置。 */
    Optional<IfmapConfig> findConfig(String tenantId, String interfaceNo, String busiNode, int order);

    /** 查询逻辑分支（按 logicBranchOrder 升序）。 */
    List<LogicBranchConfig> queryLogicBranches(String tenantId, String interfaceNo);

    /** 写执行日志。 */
    void saveExecutionLog(ExecutionLog log);
}
