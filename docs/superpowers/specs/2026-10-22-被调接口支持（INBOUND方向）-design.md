# 被调接口支持（INBOUND 方向）—— 设计

- 状态：**待批准**（用户已定：方案 3「方向列 + 单点分叉」＋命名选项 A：`call_direction` / `CallDirection` / `OUTBOUND|INBOUND`；§9 四条检查点待评审）
- 日期：2026-10-22
- 作者：caijun

## 1. 背景与问题

### 1.1 引擎只有「出网」一条链路

`IfmapOrchestrator.executeOne`（`:235`）是唯一执行单元，六个槽位里第 4 个**硬编码**为出网：

| # | 槽位 | 现状代码 |
| --- | --- | --- |
| 1 | 参数合成 | `fullParams.assemble(...)` → `flattenPool(pool)` → `put(POOL_KEY, pool)`（`:247-252`） |
| 2 | 请求模板 | `render(config.getRequestParamTemplate(), jsonOps.toJson(params), ...)`（`:254`） |
| 3 | 前序策略 | `applyPreStrategy(...)`（`:257`，`:289`） |
| 4 | **出网** | `mockResponse != null ? PartnerOutcome.ofBody(mockResponse) : exchange(context, requestJson)`（`:260-261`，`:399`） |
| 5 | 响应模板 + 后序 | `renderResponse(...)` → `applyPostStrategy(...)`（`:266`） |
| 6 | 判定 / 入池 / 分支 / 日志 | `pipeline.judge(...)`（`:268`）/ `pool.put`（`:271`）/ `applyBranch`（`:447`）/ `writeLog`（`:543`） |

第 4 槽只有两个"不出网"的旁门：`mockResponse != null`（**dry-run / 单测语义**，`:139` 注释）和 `gateway == null`（**未装配的降级**，`:400-404`）。**配置层面没有任何地方能表达"这个接口本来就不出网"。**

### 1.2 「外部调平台」目前无处落地

三个具体表现：

1. 没有方向字段 → 一条配置无法声明自己是我方被调；管理端列表、审计、克隆配置全都看不见这个维度。
2. `IfmapRequest` 只有 `payload: Map`（`IfmapRequest:39`），**没有"原文"载体** → 即便强行不出网，落库的 `request_param` 也只能是 `jsonOps.toJson(params)`（`render:522` 模板为空时原样返回 source），拿不到对方推过来的报文原文。合规留痕就是二次序列化的近似值。
3. `response_param_template` 的渲染源是"回参"。出网时它是对方响应；被调时应当是**对方请求报文** —— 语义正好对得上，但没有任何配置能让它走到这一步。

### 1.3 `CallbackRegistry` 不是方案，而且它现在是坏的

- **能力上**：只有 `handle(request, rawBody)`（`IfmapCallbackHandler:31`），不读配置、不判定、不落库、不渲染应答 —— 把"报文 → 判定 → 动作 → 应答"整段又推回宿主写 Java。
- **实现上**：`callback():511` 传 `config = null` → `writeLog` 里 `interfaceNo` 为 null → `JdbcConfigRepository.saveExecutionLog` 的 `JdbcValues.require(getInterfaceNo())` 抛异常 → 被 `writeLog:591-593` 吞成一行 WARN。**真库上回调一条日志都写不进去**（内存仓库测试看不出来）。
- **契约上**：`CallbackRegistry:61` 的 javadoc 写「编排器据此决定是否走回调通道」，但全仓 `callbacks.contains(...)` **零调用点**；`callback(...)` 在生产代码里**零调用**（仅 `IfmapOrchestratorTest:696` 一个用例）。

结论：它既不是配置化的，也没真正解决被调场景 → 本设计一并删除（§4.10）。

### 1.4 两个可复用的既有不对称（不是缺陷）

- `render:522`：模板为空 → **原样返回 source**。被调接口不配请求模板时，"入参原文"直接进日志。
- `renderResponse:422`：模板为空 **或** 回参为 null → **空产物**（= 不提取）。被调接口不配应答模板时，`data` 为空 Map，宿主可自行回 `200 OK`。

## 2. 目标与非目标

目标：

1. **一列表达方向**：`call_direction`（`OUTBOUND` 默认 / `INBOUND`），存量零影响。
2. **被调复用整条链路**：判定、逻辑分支 + 动作、应答渲染、执行日志、共享池、起点截断、`stopOnFailure` 全部沿用现有实现。
3. **日志语义变正确**：`request_param` = 对方原文，`response_param` = 我方 ACK。
4. **允许混合计划**：同一 `busi_node` 内"先被调、后回查"（INBOUND 步骤 + 后续 OUTBOUND 步骤），靠既有池传参。
5. **静态护栏**：被调必须是节点第一步、节点内最多一个 —— 保存时即可拦截。

非目标（明确不做，附理由）：

| 不做 | 理由 |
| --- | --- |
| 验签 / 限流 / 鉴权 / 幂等 | 属于宿主 Web 层：验签要原始字节与 header，不在配置引擎职责内；幂等由宿主按 `biz_id` 或动作内判重（引擎做就多一个配置项） |
| 独立 inbound 配置表 / 独立编排器 | 与 `ifmap_config` 的 90% 列（模板、判定、分支、状态、历史）完全重复 → 两份真相 |
| 方向进 `execution_log` | 日志已经用 `interface_no` 区分，加列只增加宽表成本；排障时 join 配置表即可 |
| endpoint 抽象（`source_template`/`sink_template`、`endpoint_type`） | 为一个方向诉求付"未来多通道（MQ/文件/轮询）"的抽象成本 = 过度设计；真接 MQ 时抽象大概率还要返工 |
| 被调接口的"我方再回推"链路 | 用混合计划（INBOUND → OUTBOUND）表达，不需要第三种方向 |
| 存量数据回填 / 迁移脚本数据搬运 | 列有默认值，存量天然是 `OUTBOUND`；用户口径「当作新项目」 |

## 3. 决策记录

| # | 决策 | 理由 |
| --- | --- | --- |
| D1 | 列名 `call_direction`，枚举 `CallDirection`，取值 `OUTBOUND` / `INBOUND`（用户选 A） | 语义唯一无歧义、与"出网 / `PartnerServiceGateway`"话术衔接；中文标签放管理端字典，DB 值稳定 |
| D2 | 方向存在**配置列**，不放在代码里（否决 `CallbackRegistry` 升级） | 配置化 → 管理端可见/可校验/可克隆/可审计；避免"表里一行 + 代码里一个 handler"的双份真相 |
| D3 | 引擎**只改 `executeOne` 一处**：INBOUND 时 `requestJson = 入参原文`、`outcome = PartnerOutcome.ofBody(原文)`，其余 5 个槽位一行不改 | 最小分叉面；判定/分支/池/日志全复用，不会出现两套等价逻辑漂移 |
| D4 | 入参载体 = `IfmapRequest.rawBody`（新增字段）+ 便捷入口 `executeInbound(request, interfaceNo, busiNode, rawBody)`；`execute(...)` 4 参的 `mockResponse` **保持 dry-run 语义不变** | 原文属于请求上下文，策略（`StrategyContext.getRequest()`）与动作都能读到；不新增 SPI、不改既有签名 |
| D5 | INBOUND 时 **`mockResponse` 优先于 `rawBody`**（dry-run 用 mock 当"伪造的对方报文"） | 管理端 dry-run 否则会失效；语义与出网侧一致（mock 就是"对方给的东西"） |
| D6 | `payload` 非空 → 以 `payload` 作为 `params`；`payload` 为空且 `rawBody` 非空 → 用 `jsonOps.parse(rawBody)` 填充 `params`。`rawBody` 恒只作原文（日志用） | 让分支模板与动作里的 `$.xxx` 照常可用，同时原文不被改写 |
| D7 | 应答 = `applyPostStrategy(renderResponse(...))` 的产物（即 `IfmapResult.data`），渲染源 = 对方报文；**不引入第二套渲染源** | 沿用"响应模板根 = 回参"的既有契约；ACK 要掺我方字段（traceId/时间戳）用 `post_strategy_name` 加工产物 |
| D8 | 判定不变：同一份 `result_judge` / `judge_name`，读"回参"（INBOUND 下即对方报文） | 复用 `JudgementPipeline`；INBOUND 语义 = "这份通知是否可处理" |
| D9 | `writeLog` **只改调用处的一个实参**：INBOUND 时第二个报文参数传 ACK 的序列化结果。**不能**直接沿用 `response` 变量 —— INBOUND 下 `response = outcome.getBody() = 入参原文`，会让 `response_param` 与 `request_param` 重复，而 ACK 根本不进日志 | 日志语义变成「`request_param`=对方原文 / `response_param`=我方 ACK」，正是合规要的样子；同时顺带修掉回调通道"日志必丢"的根因（那时 `config=null`），不新增日志列 |
| D10 | 允许混合计划（INBOUND 起点 + 后续 OUTBOUND），池/起点截断/`stopOnFailure` 沿用 | 被调后回查是最常见形态，现有机制零改造即可支持 |
| D11 | 硬校验：同一 `tenant + busiNode` 内 `INBOUND` **≤ 1**，且必须 `interface_order` **最小且不并列** | "被调步骤配在第二步"是永远执行不到、但表格上看着正常的配置 → 必须 ERROR 拦在保存期 |
| D12 | 未知取值 **fail fast**（`IfmapConfigException`）；空值按 `OUTBOUND` 兜底 | 写错成 `INBOUD` 若被静默当成出网，就会给合作机构发一个莫名其妙的请求 —— 最危险的一类静默错误 |
| D13 | 同批**删除回调通道**（`CallbackRegistry` / `IfmapCallback` / `IfmapCallbackHandler` / `callback()` / `/strategies.callbacks`） | D2 落地后它没有不可替代的能力；留着会出现"半残 callback + 半残 INBOUND"共存期 |
| D14 | 静态校验只在 admin（`ConfigValidator`），core 启动自检不做 | 沿用既有 D14 决策 |
| D15 | 管理端字典走内置 `callDirection` key（`IfmapBuiltinEnums`），宿主 `IfmapEnumProvider` 同名 key 覆盖 | 沿用 A+C 那次定下的口径：框架码值内置，业务码值宿主提供 |
| D16 | 远端配置源 key = `callDirection`（缺省 → `OUTBOUND`；非法值 → 抛） | 与 Jackson 默认 camelCase 一致，也和 `JsonConfigMapper` 其它字段同风格 |

## 4. 设计

### 4.1 数据模型

新增 `db/changelog/v1.0.0/007-config-call-direction.sql`（**不改 001**：Liquibase 按 changeset checksum 判定重跑，改 001 会让已部署库重跑建表）：

```sql
--changeset caijun:007-config-call-direction
ALTER TABLE `${tablePrefix}config` ADD COLUMN `call_direction` varchar(16) NOT NULL DEFAULT 'OUTBOUND'
  COMMENT '调用方向：OUTBOUND 我方调用外部 / INBOUND 外部调用我方';
```

`db.changelog-master.yaml` 追加一行 `include`；`IfmapSchemaInitializer.ALTER_FILES`（`:83`）追加同一份文件 —— `columnExists` 判定（`:187-188` `if (add == exists) { continue; }`，定义在 `:204`）保证重复执行只 SKIP，幂等。

```java
/** 调用方向：报文流向。 */
public enum CallDirection {
    /** 我方调用外部接口（默认，存量行为）。 */
    OUTBOUND,
    /** 外部调用我方接口（被调）。 */
    INBOUND;

    /** 空 → OUTBOUND；未知 → 抛（写错方向绝不能被静默当成出网）。 */
    public static CallDirection of(String text) { ... }
}
```

`IfmapConfig` 增加 `private String callDirection;`（存英文码，与 `status` / `del_status` 同风格）+ `isInbound()` 便捷判定。

### 4.2 执行分叉（唯一改动点）

```java
// IfmapOrchestrator.executeOne:235
boolean inbound = config.isInbound();
String requestJson;
PartnerOutcome outcome;
if (inbound) {
    // D5：dry-run 的 mock 就是"伪造的对方报文"，优先于真实入参
    requestJson = mockResponse != null ? mockResponse : inboundBody(request);
    // D6：payload 优先；空则把原文宽松解析进 params（让 $.xxx / 分支模板仍可用）
    context.putParams(request.getPayload().isEmpty() ? parseLenient(requestJson) : request.getPayload());
    // D3：回参 = 入参，不出网（不碰 fullParams / 请求模板 / 前序策略）
    outcome = PartnerOutcome.ofBody(requestJson);
} else {
    // ── 现有逻辑原封不动：fullParams(:248) → render(:254) → applyPreStrategy(:257) → exchange(:261)
}

context.setRequestJson(requestJson);   // 出网分支已在 :255 设过（重复赋值无害）；被调分支靠这一行
String response = outcome.getBody();                       // 以下全部复用
context.setResponseJson(response);
Map<String, Object> data = applyPostStrategy(context, renderResponse(config, response, ruleContext)); // ACK
Judgement judgement = pipeline.judge(config, response, outcome);      // D8
if (judgement.isSuccess()) pool.put(config.getInterfaceNo(), data);   // D10
String matchedBranch = judgement.isSuccess() ? applyBranch(...) : null;
// D9：INBOUND 下 response 变量 == 入参原文，直接传它会让日志两条报文重复 ⇒ 第二报文参数换成 ACK
writeLog(tenantId, request, config, requestJson, inbound ? jsonOps.toJson(data) : response, judgement, elapsed);
```

六个槽位在两种方向下的对照：

| 槽位 | OUTBOUND | INBOUND |
| --- | --- | --- |
| 参数合成 | 组包策略 + 池展平 + `pool` | `payload` 或 `parse(rawBody)`（D6） |
| 请求模板 | 渲染出网报文 | **跳过** |
| 前序策略 | 出网前加工 | **跳过** |
| 出网 | `gateway.exchange` | **`ofBody(入参原文)`** |
| 响应模板 + 后序 | 从对方响应渲染产物 | 从对方报文渲染 **ACK** |
| 判定 / 入池 / 分支 / 日志 | 同 | 同（`writeLog` 的「回参」实参换成 ACK，见 D9） |

`inboundBody(request)` 规则：`rawBody` 非空 → 用它；否则 `jsonOps.toJson(payload)`；两者都空 → 抛 `IfmapConfigException("被调接口缺少入参报文：interfaceNo=...")`（空报文调被调接口一定是接线错误）。

### 4.3 入参载体

```java
public final class IfmapRequest {
    ...
    private final String rawBody;      // 新增：入参原文（被调接口的对方报文；出网接口通常为 null）
}

// IfmapRequest 新增派生方法（与既有 withAttribute:120-128 同一写法：不改动原对象）
public IfmapRequest withRawBody(String rawBody) { ... }

// IfmapOrchestrator 新增便捷入口
public IfmapResult executeInbound(IfmapRequest request, String interfaceNo, String busiNode, String rawBody) {
    return execute(request.withRawBody(rawBody), interfaceNo, busiNode, null);
}
```

不做的事：不给 `IfmapRequest` 加 `callDirection`（方向属于配置，不属于单次请求）；不改 `execute(...)` 既有 4 参语义（`mockResponse` 仍是 dry-run）。

### 4.4 应答与判定

- **ACK** = `IfmapResult.data`（Map）。宿主用自己的框架序列化回给对方；要带信封（`code`/`msg`）就写进 `response_param_template` 的常量。
- **要掺我方动态字段**（traceId、受理时间、受理流水号）→ `post_strategy_name`（`applyPostStrategy:326` 支持返回 Map 覆盖/替换产物）+ `@IfmapAction` 生成流水号写入 `StrategyContext`。**不新增机制。**
- **判定**：`{"op":"always"}` = "收到即可处理"；`{"all":[{"path":"$.status","op":"equal","value":"SUCCESS"}]}` = "只处理成功通知"。判定失败 → 不执行动作、不入池、日志 `FAIL` + `error_msg`（现有行为）。

#### 4.4.1 应答（ACK）怎么配

**出口只有一个**：`IfmapResult.data`（Map）。引擎不碰 HTTP 状态码、Content-Type、响应头、签名 —— 那些由宿主 Controller 决定；引擎只回答"回什么内容"。

渲染源是**对方报文原文**（`renderResponse:422`，模板为空 → 空 Map），模板取值语法见 `docs/02`（`$.path` 整值替换 / `@append@` `@concat@` 拼接 / `@FUN(...)` 函数 / `$.seqNo` 内置 20 位流水号 / 其余一律按字面量原样输出）。

| 场景 | `response_param_template` |
| --- | --- |
| 只回固定应答（最常见） | `{"code":"0000","msg":"success"}` |
| 回显对方流水号 + 我给一个回执号 | `{"code":"0000","msg":"success","applyNo":"$.applyNo","ackNo":"$.seqNo"}` |
| 字符串拼接（**必须用 `@append@`**） | `{"orderNo":"$.applyNo@append@-ACK"}` |
| 取嵌套 / 数组 | `{"firstSku":"$.items[0].sku","allSku":"$.items[*].sku"}` |
| 不回应答体（宿主只回 200） | **不配模板** → `data` 为空 Map |

三个必须知道的坑：

1. **不能写 `"prefix-$.applyNo"`**。总表（`docs/02` §1）里 `$.` 只认"整值以 `$.` 开头"，混在文本里会走第 9 条**字面量原样输出** —— 对方会收到字符串 `prefix-$.applyNo`。
2. **判定失败时 `data` 不是空的**：`renderResponse` 与 `applyPostStrategy` 与成败无关，都会执行。宿主拿到 `success=false` + 一份"本来要回的 ACK"，自行决定回什么（见下）。
3. **`$.seqNo` 每次渲染都不同**，不要用它当幂等键。

成功率不同回不同应答体（DSL 没有 if，两条路都行）：

- **A（推荐，零代码）**：宿主按 `result.isSuccess()` 分叉 —— 成功回 `result.getData()`，失败回自己的错误信封（`{"code":"9999","msg":err}`）。HTTP 状态也归宿主定。
- **B（要"失败也带业务字段"）**：`post_strategy_name` 里读 `context.getResponseJson()`（对方报文）+ `context.getRequest()`，用 `context.setResponseData(map)` **整体替换**产物：

```java
@Component("ackWithServerTime")   // 与 ifmap_config.post_strategy_name 对齐
public class AckWithServerTime implements IfmapStrategyHook {
    @Override
    public Map<String, Object> apply(StrategyContext context) {
        if (context.getPhase() != StrategyContext.Phase.POST) {
            return null;                       // 只挂后序；前序对被调接口不执行（D3）
        }
        Map<String, Object> ack = new LinkedHashMap<String, Object>(context.getResponseData());
        ack.put("serverTime", String.valueOf(System.currentTimeMillis()));
        context.setResponseData(ack);           // 整体替换产物：必须给 Map 才生效（:345-349）
        return null;                            // 返回 Map 则是"合并"，两者按需选一个
    }
}
```

**怎么预览 ACK 而不真被调用**：管理端 dry-run（`dryRun:132`）传 `mockResponse` = 一份"伪造的对方报文"，返回的 `IfmapResult.data` 就是将要回给对方的 ACK（D5）。这也是验收 ACK 配置最省事的手段。

### 4.5 混合计划示例

```
busi_node = LOAN_NOTIFY
  order=1  INBOUND   CB_LOAN_NOTIFY     判定通知合法 → 分支动作落库/更新状态 → 渲染 ACK
  order=2  OUTBOUND  QUERY_LOAN_STATUS  回查对方：组包用 $.pool.CB_LOAN_NOTIFY.applyNo
```

- `execute(request, "CB_LOAN_NOTIFY", "LOAN_NOTIFY")`：`CB_LOAN_NOTIFY` 是**起点**（`loadPlan:167` 起点截断），两步都跑。
- 断点续跑：`CB_LOAN_NOTIFY` 成功即入池，从第二步起跑时 `payload.pool` 回放即可（既有能力）。
- 反向（先 OUTBOUND 再 INBOUND）无意义且被 D11 校验拦住。

### 4.6 静态校验（`ConfigValidator`，admin 模块）

`validate(IfmapConfig, boolean)`（`:89`）追加第 6 步 `checkCallDirection`；跨行规则用 `liveRows(new ConfigQuery().setTenantId(..).setBusiNode(..).setSize(MAX_SIZE))`（与 `checkUniqueKey:480-493` 同一取数口径）。

| 规则 | 级别 | 文案示例 |
| --- | --- | --- |
| 同一 `tenant + busiNode` 内 `INBOUND` 行数 ≤ 1 | ERROR | `同一业务节点内最多一个被调接口：LOAN_NOTIFY 出现 2 个（CB_A、CB_B）` |
| 存在 INBOUND 时其 `interface_order` 必须严格最小（不可并列） | ERROR | `被调接口必须是节点内第一步：CB_LOAN_NOTIFY 的 interface_order=3，节点内已有 order=1 的 QUERY_LOAN_STATUS` |
| 取值不在 `OUTBOUND/INBOUND` 内 | ERROR | `call_direction 取值非法：INBOUD（允许：OUTBOUND/INBOUND）` |
| INBOUND 配了 `request_param_template` | WARN | `被调接口不出网，请求模板不会被执行：CB_LOAN_NOTIFY` |
| INBOUND 配了 `pre_strategy_name` | WARN | `被调接口没有出网前阶段，前序策略不会被执行：CB_LOAN_NOTIFY` |
| INBOUND 未配 `response_param_template` | WARN | `被调接口没有应答模板，宿主拿不到 ACK：CB_LOAN_NOTIFY` |

### 4.7 管理端

| 位置 | 改动 |
| --- | --- |
| 列表 | 新列「方向」（中文标签） |
| 编辑器 | `CONFIG_FIELDS`（`app.js:461`）插入 `{ name: 'callDirection', label: '调用方向', dict: 'callDirection' }` + 默认值 `OUTBOUND` |
| 字典 | `IfmapBuiltinEnums.CALL_DIRECTION_KEY = "callDirection"` → `OUTBOUND=出网调用` / `INBOUND=被调`（宿主同名 key 覆盖，走 `IfmapEnumCatalog.of`） |
| 历史 / 回滚 | `ConfigSnapshotMapper` 快照 map 双向补 `callDirection`（参照 `judgeName`：`:89-90` 读、`:200-201` 写） |
| 审计 | `ConfigAuditor` 无需改（差异来自快照 map，自动覆盖） |

`ConfigSaveRequest` 直接包 `IfmapConfig`，`ConfigAdminService.create/update` 零改动。

### 4.8 日志语义对照

| 列 | OUTBOUND | INBOUND |
| --- | --- | --- |
| `interface_no` | 配置的接口号 | 同 |
| `request_param` | 我方渲染出的出网报文 | **对方推来的报文原文**（脱敏 → 截断） |
| `response_param` | 对方响应原文 | **我方 ACK**（由 INBOUND 分支显式传入，见 D9） |
| `execution_result` / `fail_type` / `error_code` / `track_id` | 判定结论 | 同（判定的是对方报文） |
| `http_status` | 对方 HTTP 状态 | 空（`PartnerOutcome.ofBody` 不带状态码） |

### 4.9 远端配置源（provider-remote）

`JsonConfigMapper.toConfig` 增加 `callDirection`：key 缺失 → `OUTBOUND`；非 `OUTBOUND/INBOUND` → 抛（D16，与本地侧同口径）。`docs/11-远端配置源.md` 的字段表同步补一行。

### 4.10 删除回调通道（同批，BREAKING）

| 层 | 删除项 |
| --- | --- |
| core | `spi/IfmapCallback.java`(41)、`spi/IfmapCallbackHandler.java`(32)、`strategy/CallbackRegistry.java`(84)；`IfmapOrchestrator` 的 `import`/字段 `:95`/构造 `:114`/`callback():511-519`/builder 字段 `:695`/setter `:745-747`/默认 `:812-814` + 只服务于 callback 的 `writeLog` 8 参重载 `:543-546` |
| spring | `IfmapAutoConfiguration` 的 `import` / bean / `ifmapOrchestrator` 参数 / `.callbacks(...)`；`IfmapStrategyRegistrar` 的 `import`、`:94-97`、`isInfrastructure:112` |
| admin | `IfmapMetaAdminController` 的 `import`/字段/构造/`result.put("callbacks", ...)`；`IfmapAdminAutoConfiguration` 的 `import`/参数/实参 |
| 测试 | `IfmapOrchestratorTest:695-707`（整条用例）；`IfmapOrchestratorAutoConfigurationTest` 的 2 个静态类 + 2 处断言 + 2 个 import；`ConfigAdminServiceTest:32/360` |
| 文档 | `06:279/468/498`、`05:134/312`、`07:95`、`index:98`、`README:255`、`ifmap-整体设计_v1:352/401/1214/1217`、精读 `00(4)/03(1)/04(5)/07(2)` |

BREAKING 口径（写进 CHANGELOG）：宿主若实现过 `IfmapCallbackHandler` → 改为「`call_direction=INBOUND` 配置 + `result_judge` + `@IfmapAction`」；`/strategies` 响应少一个 `callbacks` key。

## 5. 改动面

| 模块 | 文件数 | 具体点 |
| --- | --- | --- |
| provider-jdbc | 6 | `007-*.sql`（新）+ `db.changelog-master.yaml`（+1 include）+ `IfmapRowMappers.CONFIG_COLUMNS`（28→29 列，`toConfig` 读 `call_direction`）+ `JdbcConfigWriter.insert:91-97`（24→25 列、**21→22 个 `?`**，当前实测 24 列 / 21 占位符 / 3 个字面量 `version=0`,`del_status=0`,`deleted_seq=0`）+ `update:178-188`（SET 18→19 个 `?`）+ 真库档测试断言 |
| core | 5 | `config/IfmapConfig`（字段 + `isInbound`）+ `config/CallDirection`（新）+ `model/IfmapRequest`（`rawBody`）+ `orchestrator/IfmapOrchestrator`（分叉 + `executeInbound` + `writeLog` 实参一处 + 删 callback 相关）+ 删 3 个文件 |
| spring-boot-starter | 1 | `IfmapSchemaInitializer.ALTER_FILES:83`（+007；`columnExists` 已幂等） |
| provider-remote | 1 | `JsonConfigMapper.toConfig`（+`callDirection`） |
| admin | 5 | `ConfigValidator`（+`checkCallDirection`）+ `IfmapBuiltinEnums`（+1 key）+ `ConfigSnapshotMapper`（双向）+ `app.js`（字段 + 列表列）+ `IfmapMetaAdminController`（删 callbacks） |
| 文档 | 9 | `04`（列说明）、`06`（+「被调接口」小节）、`07`（配置字段）、`11`（远端 key）、`index`、`README`、`ifmap-整体设计_v1`、`CHANGELOG`（BREAKING）、`docs/12-被调接口配置指南.md`（新） |
| 精读 | 5 | `00`（文件数/行数计数）、`03`（`executeOne` 分叉）、`04`（删 callback 节）、`06`（仓储列）、`07`（schema 初始化）——**行号需整体校正**（见 R1） |

净变化估计：约 **+350 / −250**（删回调净减 ~200 行，新增方向支持 ~350 行含测试）。

## 6. 测试计划

| 层 | 用例 | 断言要点 |
| --- | --- | --- |
| core（新文件 `IfmapOrchestratorInboundTest`） | `inboundStepDoesNotCallGateway` | gateway stub 抛 `IllegalStateException`，INBOUND 步骤仍 `success=true` |
| | `inboundRendersAckFromInboundBody` | `response_param_template` 取 `$.applyNo` → `data` = ACK |
| | `inboundLogsInboundBodyAsRequestParam` | `ExecutionLog.requestParam` == 原文；`interfaceNo` 非空（覆盖旧回调"日志必丢"） |
| | `inboundJudgementFailureSkipsActionAndPool` | 动作未执行、`pool` 无该键、日志 `FAIL` |
| | `inboundThenOutboundMixedPlan` | 第二步组包引用 `$.pool.CB_A.applyNo` 拿到值 |
| | `inboundDryRunUsesMockAsInboundBody` | `mockResponse` 优先于 `rawBody`（D5） |
| admin | `ConfigValidatorTest` +3 | 两条 ERROR（多个 INBOUND / 非首步）+ 三条 WARN 各一 |
| jdbc | 真库档 +1 | `insert` → `findByKeyId` 往返 `call_direction='INBOUND'`；`update` 改方向生效 |
| 回归 | 全量 `mvn clean verify`（JDK17） | 期望：**588 − 1（删 callback 用例）+ 10 = 597**，SpotBugs 0 × 各自模块 |

## 7. 风险

| # | 风险 | 处置 |
| --- | --- | --- |
| R1 | `docs/代码精读/` 是**行号锚定式**，本次改动让 `IfmapOrchestrator` / 仓储 / 校验器的行号整体漂移 | 收尾阶段逐篇校正（`03/04/06/07/08` + `00` 的计数），并把「本次改动导致的行号偏移」写进提交信息 |
| R2 | `JdbcConfigWriter` 列数/占位符不同步（当前 24 列 / 21 `?`） | 改完用脚本核对 `?` 个数与参数个数一致；真库档必须有 insert/update 往返用例 |
| R3 | 快照 map 多一个 key → `ConfigSnapshotMapperTest` / `ConfigAdminServiceTest` / 历史回滚用例可能因"字段数固定"断言失败 | P3 一并跑这三个测试；`ConfigSnapshotMapper` 双向补齐后再跑 |
| R4 | 删除回调 SPI 是破坏性变更 | 只影响已实现 `IfmapCallbackHandler` 的宿主（当前仓库内 0 处）；CHANGELOG 明确写 BREAKING 与替代路径 |
| R5 | 被调配到第二步 → 永远执行不到 | D11 硬校验拦住；同时 `docs/12` 写明「被调必须是节点第一步」 |
| R6 | 混合计划里 INBOUND 判定失败 → `stopOnFailure` 中断后续回查 | 这是正确语义（通知不合法就不该回查），文档写明；需要"失败也继续"的宿主把 `stopOnFailure=false` |
| R7 | 迁移 kit `00-precheck.sql` 的列齐备性清单不含 `call_direction` | 列有默认值，存量升级不依赖它；如需 kit 一致性，追加一行到清单（成本 1 行，P4 顺带） |

## 8. 与存量 ECC 的对照

| 存量 | 本设计 |
| --- | --- |
| `IfmapConfigApplication` 里 17 个回调方法（每类通知一个方法，改一行代码就要发版） | `call_direction=INBOUND` 一行配置 + `result_judge` + `@IfmapAction`（动作是宿主 bean，但**新增通知类型不需要改代码**） |
| 回调里手写 `if ("0000".equals(code))` 判定 | 判定规格配置化（`result_judge`），管理端可校验 |
| 回调里手写落库 | 逻辑分支 + `@IfmapAction`，分支条件配置化 |
| 回调里手写应答 JSON | `response_param_template` |
| 回调不留痕（或另写日志） | 复用 `execution_log`（`request_param` = 对方原文） |

## 9. 评审检查点

1. **D5 优先级**：`mockResponse` 在 INBOUND 下优先于 `rawBody`（dry-run 才能伪造对方报文）——确认无异议。
2. **D6 数据来源**：`payload` 优先、`rawBody` 只作原文（日志），确认不会出现"模板取不到字段"的预期外情况。
3. **D10 混合计划**：INBOUND 判定失败时是否应该中断后续 OUTBOUND（当前按 `stopOnFailure` 语义中断）——确认。
4. **D13 同批删除**：删回调通道与 INBOUND 支持同一个迭代落地（避免"半残 callback + 半残 INBOUND"共存）——确认。

## 10. 分阶段实施

| 相位 | 内容 | 验收 |
| --- | --- | --- |
| P1 | DDL 007 + master/ALTER_FILES + `CallDirection` + `IfmapConfig` + `IfmapRequest.rawBody` + 仓储读写（列/占位符）+ remote | JDK17 `clean verify` 通过；真库档 insert/update 往返断言 |
| P2 | 引擎分叉 + `executeInbound` + core 6 个用例 | 新用例全绿；既有 588 用例不回归 |
| P3 | admin：`checkCallDirection` + 内置字典 + 快照双向 + UI 字段/列 + 3 个校验用例 | 管理端保存被调配置 → 列表显示方向；非法配置被拦 |
| P4 | 删回调通道（代码 + 测试 + 8 处文档）+ `docs/12` + 精读校正 + CHANGELOG | 全量 `clean verify` 597 用例全绿、SpotBugs 0、`grep -rni callback` 残留仅为"业务回调不执行"表述 |
