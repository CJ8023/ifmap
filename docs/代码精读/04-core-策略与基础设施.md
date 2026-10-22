# 04 · core：策略与基础设施

覆盖 `strategy`（12 文件）与 `spi`（15 文件）。

两个包的分工很清楚：

- `strategy` = **宿主可以换掉的行为**（组包、分支判定、动作、前后序钩子），由 `@interface` + 注册表 + 上下文三件套构成；
- `spi` = **宿主必须提供或可以实现的能力**（网关、日志落地、租户解析、ID 生成、时钟、日志脱敏、判定器），由纯接口 + 两个自带实现构成。

> 历史包袱说明：存量类名 `*SpecialDealStrategy` 已整体改名为 `IfmapStrategyHook` / `StrategyHookRegistry`
> （提交 `d6334e7`）。`specialDeal`（做什么）与 `pre/post`（什么时候做）是两个维度，
> 原来的名字把二者混在一起，现在拆开了。

---

## 1. `strategy` 包的五个扩展点

| 扩展点 | 注解/接口 | 注册表 | 作用时机 |
|---|---|---|---|
| 组包 | `@FullParam` + `FullParamStrategy` | `FullParamStrategyRegistry` | 渲染请求模板**之前**（`IfmapOrchestrator:260`） |
| 前序策略钩子 | `IfmapStrategyHook`（`phase=PRE`） | `StrategyHookRegistry` | 渲染后、出网**之前**（`:276`，被调方向不执行） |
| 后序策略钩子 | `IfmapStrategyHook`（`phase=POST`） | 同上 | 产物之后、判定**之前**（`:287`） |
| 分支判定 | `@LogicBranch` + `LogicBranchStrategy` | `LogicBranchStrategyRegistry` | 判定成功后匹配逻辑分支（`:542`） |
| 分支动作 | `@IfmapAction` + `IfmapActionHandler` | `ActionRegistry` | 分支命中后执行（`:562`） |

---

## 2. `strategy/StrategyContext.java`（228 行）

贯穿上述所有扩展点的**唯一上下文**。

| 行 | 成员 | 说明 |
|---|---|---|
| 37 | `Phase` 枚举 | `PRE` / `POST` —— 前后序共用一个接口时的自辨依据 |
| 44–46 | 三个 final 字段 | `request` / `config` / `params`（内部 `LinkedHashMap`） |
| 47 | `ruleContext` | 模板里 `@FUN(...)` 用的上下文，与参数 Map 分开 |
| 49–53 | 五个可变状态 | `requestJson` / `responseJson` / `responseData` / `matchedBranch` / `phase`（默认 `PRE`） |
| 55–66 | 构造 | **`builder.params` 是 `putAll` 拷一份**（`63–65`）—— 策略改 `params` 不会动到宿主的 payload |
| 68 | `builder()` | 构造器入口 |
| 85 | `getInterfaceNo()` | 从 config 取（无配置 → null） |
| 90 | `getBizId()` | 从 request 取（无请求 → null） |
| 95 | `getParams()` | **`unmodifiableMap` 只读视图**；写入必须用 `putParam`/`putParams` |
| 100 / 106 | `putParam` / `putParams` | 返回 `this`（链式） |
| 114 | `get(key)` | 三级查找：`params` → `request.payload` → `request.attributes`（**参数优先**） |
| 124–138 | `requestJson` 读写 | 前序策略改完由编排器重渲染覆盖 |
| 140–146 | `responseData` 读写 | 后序策略可整体替换产物（`Map` 才生效） |
| 148–154 | `matchedBranch` 读写 | 分支执行时设置 |
| 157–164 | `phase` 读写 | `setPhase(null)` 归一成 `PRE` |
| 166 | `toString()` | 只打 interfaceNo + paramKeys（**不打值**，防敏感信息进日志） |
| 173–227 | `Builder` | 8 个字段；`ruleContext` 缺省 `RuleContext.empty()` |

---

## 3. `strategy/StrategyHookRegistry.java`（154 行）

前后序策略钩子的注册表，**双 key 表**设计。

| 行 | 成员 | 说明 |
|---|---|---|
| 49 | `authoritative` | 权威 key = **Spring bean 名** |
| 50 | `aliases` | 宽松 key = 类简单名 + 首字母小写名 |
| 51 | `conflicts` | key → 涉及方（歧义记录） |
| 58 | `aliasHits` | 命中过宽松 key 的名字（启动审计用） |
| 66 | `register(beanName, strategy)` | `beanName` 非空才进 `authoritative`；类简单名两种写法都进 `aliases` |
| 80 | `put(...)` | 已存在且**不是同一实例** → `conflict`（保留先注册者） |
| 91 | `conflict(...)` | WARN + 记录 owner |
| 104 | `lookup(name)` | **先精确、再宽松**；命中宽松时记入 `aliasHits` 并 WARN（提示改成 bean 名） |
| 121 | `contains(name)` | 两表都查 |
| 126 | `keys()` | 两表 key 的并集（去重保序，`unmodifiableSet`） |
| 133 | `size()` | **只数 `authoritative`**（宽松别名会让别名表数量虚高） |
| 138 / 143 | `getAliasHits` / `getConflicts` | 审计读取（不可变视图） |
| 148 | `decapitalizeLoose` | 只把首字符小写（与 Spring 的 `decapitalize` 宽松版一致） |

**为什么允许宽松 key**：存量配置里 `pre_strategy_name` 写的就是类名或首字母小写名，
迁移期不能要求一次性改完。代价是「同一策略可能有 3 个 key」，
所以 `size()` 只算权威名、`keys()` 才给全集。

---

## 4. `strategy/FullParamStrategyRegistry.java`（135 行）

组包策略：按 `(partnerCode, busiNode)` 找实现，**四段回退**。

| 行 | 成员 | 说明 |
|---|---|---|
| 42 | `WILDCARD = "*"` | 通配符 |
| 44–45 | `strategies` / `conflicts` | 组合键 → 策略；（歧义记录） |
| 48 / 58 | `register(...)` | 注解版 / 显式 `(partnerCode, busiNode)` 版 |
| 82 | `lookup(partnerCode, busiNode)` | 依次试 `(p,b)` → `(p,*)` → `(*,b)` → `(*,*)`（`83–88`）；**全无 → null** |
| 99 | `assemble(...)` | 无策略或返回值 null → `emptyMap()`（**永不给 null**，调用方不必判空） |
| 108 / 112 / 116 | `contains` / `keys` / `size` | 常规 |
| 125 | `key(partnerCode, busiNode)` | `partnerCode + '\|' + busiNode` |
| 129 | `normalize(value)` | null/空白 → `"*"`；否则 `trim()` |

> 组合键用 `|` 而不是拼接，是为了让 `(a, b|c)` 与 `(a|b, c)` 不撞车 —— 实际不会出现，
> 但显式分隔符让 `keys()` 的输出可读。

---

## 5. `strategy/LogicBranchStrategyRegistry.java`（119 行）

一个 `logic_branch_flag` 可以挂**多个**策略（任一命中即算命中）。

| 行 | 成员 | 说明 |
|---|---|---|
| 36 | `strategies` | `flag → List<LogicBranchStrategy>` |
| 40 / 52 | `register(...)` | 注解版（`@LogicBranch`）/ 显式 key 版；**同名追加到 List** |
| 65 | `matches(key, ctx)` | 转调 3 参版（`hitRecorder=null`） |
| 74 | `matches(key, ctx, hitRecorder)` | key 为 null 或无策略 → false；逐个 `strategy.match(ctx)`；**异常包成 `IfmapStrategyException`**（带策略类全名）；命中即返回 true |
| 97 | `find(key)` | 该 flag 下全部策略（不可变副本） |
| 103 / 107 | `keys` / `size` | **`size()` 数的是策略总数**（不是 flag 数） |
| 116 | `HitRecorder` | `onHit(strategy)` 回调，供日志记录"谁命中的" |

**为什么异常要包一层**：`match` 抛出的原生异常（NPE 等）里没有"哪个策略"的信息，
线上只看 `NullPointerException` 根本定位不到配置。

---

## 6. `strategy/ActionRegistry.java`（124 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 42–44 | 字段 | `actions` / `conflicts` / `failOnMissingAction = true` |
| 47 | `register(handler)` | 靠 `Annotations.find(cls, IfmapAction.class)` 取 key；**缺注解直接抛 `IllegalArgumentException`**（注册期就炸，不留到运行期） |
| 59 | `register(key, handler)` | 手动 key；同 key 不同实例 → 记 `conflicts` + WARN，保留先注册者 |
| 81 | `contains(key)` | — |
| 90 | `execute(key, ctx)` | **key 为空/空串 → 直接抛**（`91–93`，不受 `failOnMissingAction` 控制）；未注册时按开关抛或 WARN 跳过 |
| 105 / 109 | `keys` / `size` | — |
| 113 / 117 | `isFailOnMissingAction` / `setFailOnMissingAction` | 存量 ECC "静默跳过"的行为已取消，默认 `true` |
| 121 | `getConflicts()` | 审计读取 |

---

## 7. 其余 strategy 文件

| 文件 | 行 | 说明 |
|---|---|---|
| `strategy/IfmapStrategyHook.java` | 54 | `apply(StrategyContext)`（:53）—— 返回值语义：`null`/空 = 无额外参数；后序相位返回键**覆盖**产物 |
| `strategy/FullParamStrategy.java` | 33 | `assemble(StrategyContext)`（:32） |
| `strategy/LogicBranchStrategy.java` | 31 | `boolean match(StrategyContext)`（:30） |
| `strategy/IfmapActionHandler.java` | 27 | `void execute(StrategyContext)`（:26） |
| `strategy/FullParam.java` | 42 | `@FullParam(partnerCode="*", busiNode="*")`（两个属性默认 `"*"`） |
| `strategy/LogicBranch.java` | 36 | `@LogicBranch("flag")`（`value` 必填） |
| `strategy/IfmapAction.java` | 39 | `@IfmapAction("actionKey")`（`value` 必填） |

---

## 8. `spi` 包的 15 个文件

### 8.1 分类

| 类别 | 文件 | 行 | 宿主是否必须实现 |
|---|---|---|---|
| 出网 | `PartnerServiceGateway` | 47 | **否**（不配 = dry-run，只本地渲染） |
| 出网结果 | `PartnerOutcome` | 97 | 自带（`ofBody` / `failure` / `timeout`） |
| 日志落地 | `ExecutionLogSink` | 35 | 否（不配 = 不落库） |
| 日志落地实现 | `RepositoryExecutionLogSink` | 41 | 自带（转 `ConfigRepository.saveExecutionLog`） |
| 日志脱敏 | `LogMasker` | 30 | 否（不配 = 不脱敏） |
| 日志脱敏实现 | `DefaultLogMasker` | 184 | 自带（正则规则表） |
| 租户解析 | `TenantResolver` | 32 | 否（缺省 `-1` 单租户） |
| 租户解析实现 | `HeaderTenantResolver` | 82 | 自带（从 header 读） |
| ID 生成 | `IdGenerator` | 34 | 否（缺省 Snowflake） |
| ID 生成实现 | `SnowflakeIdGenerator` | 140 | 自带 |
| 时钟 | `ClockProvider` / `SystemClockProvider` | 27 / 32 | 自带（测试可注入） |
| 节假日 | `HolidayCalendar` | 31 | 否（缺省周一~周五） |
| 判定器 | `ResultJudge` + `ResultJudgeContext` | 38 / 61 | 自带（`ruleSpec`） |

### 8.2 `PartnerOutcome`（97 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 30–34 | 五个字段 | `body` / `statusCode` / `timedOut` / `failed` / `message` |
| 45 | `ofBody(body)` | 只有响应体（`dry-run` / `mockResponse` 用这个） |
| 50 / 55 | `of(message, statusCode[, body])` | 有 HTTP 状态码的正常返回 |
| 60 | `timeout(message)` | 超时（`timedOut=true`） |
| 65 | `failure(message)` | 传输失败（`failed=true`） |
| 69–88 | 三个查询 | `getBody` / `getStatusCode` / `getMessage` + `isTimedOut`（78）/ `isFailure`（83） |

`JudgementPipeline:64` **先判 `isTimedOut()`** 再判 `isFailure()` —— 两者同时为真时归因为超时。

编排器对它的用法见 `03` 篇 §5.1。

### 8.3 `DefaultLogMasker`（184 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 50 | `DEFAULT_MASK_FIELDS` | 默认需脱敏的字段名集合（密码 / 证件 / 卡号类） |
| 55 | `PAIR` | 匹配 `"key":"value"` 的键值对（脱敏按**字段名**判断，不猜内容） |
| 58 / 59 / 60 | `PHONE` / `ID_CARD` / `BANK_CARD` | 按**形态**识别敏感值（字段名没命中时兜底） |
| 65 / 74 | 两个构造 | 默认规则表 / 自定义 `maskFields` + `excludeFields`（**排除优先**） |
| 80 | `mask(json)` | 主入口；`null` 原样返回；用 `PAIR` 逐对处理 |
| 97 | `maskPair(key, rawValue)` | 命中字段名 → 整值替换；否则 `maskByPattern` |
| 118 | `isPlain(value)` | 纯数字/字母等"无需模式识别"的短值直接跳过 |
| 122 | `maskByPattern(value)` | 手机/身份证/卡号三段正则替换 |
| 136 / 149 | `maskName` / `headTail(value, head, tail)` | 姓名脱敏（保留首字）/ 通用保留头尾 |

**为什么脱敏在落库前**（而不是查询时）：日志表是给运维直查的，落库即裸文等于脱敏没做。

### 8.4 `SnowflakeIdGenerator`（140 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 39–45 | 位分配 | `EPOCH=1577836800000L`（2020-01-01）、`WORKER_ID_BITS=10`、`SEQUENCE_BITS=12`、`TIMESTAMP_SHIFT=22` |
| 47 / 49 | 容忍参数 | `MAX_BACKWARD_MS=5`（允许的回拨窗口）、`SPIN_SLEEP_MS=1` |
| 55 | 构造 | `workerId` 越界（>1023 或 <0）→ `IllegalArgumentException` |
| 68 / 73 / 78 | 三个工厂 | `shared()`（单例）/ `fromEnvironment()`（读 env/sysprop）/ `workerIdFromHostname(host)`（主机名哈希兜底） |
| 99 | `nextId()` | `synchronized`；回拨 ≤ 5ms 等待、> 5ms 抛 `IfmapConfigException`（**不静默**） |
| 120 | `waitNextMillis` | 同毫秒自旋到下一毫秒 |

### 8.5 `HeaderTenantResolver`（82 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 30 / 33 | 两个默认值 | `DEFAULT_HEADER="X-Tenant-Id"` / `DEFAULT_TENANT_ID="-1"` |
| 38 / 42 / 46 | 三个构造 | 默认 / 只改 header 名 / header 名 + 默认租户 |
| 53 | `resolve(request)` | 从 header 取（**大小写不敏感**，走 `IfmapRequest.getHeader`）；取空 → 默认租户 `"-1"` |

### 8.6 `HolidayCalendar` / `ClockProvider` / `IdGenerator` / `LogMasker` 等小接口

| 文件 | 行 | 方法 / 关键点 |
|---|---|---|
| `HolidayCalendar` | 31 | `boolean isWorkday(LocalDate)`（:30）—— **只有这一个方法**，`DateRules.workdayAdd` 靠它；不传时按周一~周五 |
| `ClockProvider` | 27 | `long currentTimeMillis()`（:26） |
| `SystemClockProvider` | 32 | `INSTANCE` 单例（:26）+ 直接 `System.currentTimeMillis()`（:29） |
| `IdGenerator` | 34 | **`Long nextId()`**（:30，返回装箱 `Long` 允许 null）+ `AUTO_INCREMENT = () -> null`（:33，把"交给数据库自增"表达成一个实现） |
| `LogMasker` | 30 | `String mask(String json)`（:29） |
| `TenantResolver` | 32 | `String resolve(IfmapRequest)`（:31） |
| `ExecutionLogSink` | 35 | `void write(ExecutionLog)`（:34） |
| `PartnerServiceGateway` | 47 | `String exchange(PartnerCall)`（:36，老接口）+ **`default exchangeForOutcome(PartnerCall)`**（:44，新接口；默认实现把 `exchange` 的返回值包成 `ofBody`，所以老实现不用改） |
| `RepositoryExecutionLogSink` | 41 | 构造收 `ConfigRepository`（:30），`write` 直接转 `saveExecutionLog`（:38） |
| `ResultJudge` | 38 | `name()`（:34）+ `judge(ResultJudgeContext)`（:37） |
| `ResultJudgeContext` | 61 | 构造 `(config, responseJson, jsonOps)`（:32）+ 四个 getter（`getTenantId` 在 :58） |

---

## 9. 看起来多余但别删的点

| 点 | 位置 | 为什么保留 |
|---|---|---|
| `StrategyHookRegistry` 的 `aliases` 双 key | `:50` | 存量配置里 `pre_strategy_name` 写的是类名/首字母小写名，一次性改完不现实；`aliasHits` 让"该改的配置"可见 |
| `size()` 只数权威 key、`keys()` 给并集 | `:126-135` | 直接把两个数混在一起会让人以为注册了 3 个策略 |
| `FullParamStrategyRegistry` 的四段回退 | `:82-96` | 「只有一个通用组包」和「每个节点一组」都只是特例，不需要两套机制 |
| `assemble` 永不返回 null | `:99-106` | 调用方 `requestParams.putAll(...)` 直接可用 |
| `LogicBranchStrategyRegistry.size()` 数策略而非 flag | `:107-113` | 同一 flag 多策略时，`keys().size()` 会低估 |
| `ActionRegistry.register(handler)` 缺注解直接抛 | `:51-55` | 注册期就炸，比运行期"动作找不到"好定位 |
| `ActionRegistry.execute` key 为空时无视开关 | `:91-93` | `method_flag` 为空是**配置缺陷**，不是"没注册"；静默跳过会让人以为分支执行过了 |
| `StrategyContext.getParams()` 只读 + `putParam` 写入 | `:95-111` | 防止策略直接改 Map 后编排器不知道（重渲染时机是编排器控制的） |
| `PartnerOutcome.isTimedOut()` 单独区分 | `:45` | 超时与连接失败在运维口径上是两件事 |
| 17 个 SPI 里 7 个有自带实现 | — | 让纯 Java 接入可以「只实现 repository + gateway」就跑起来，其余全有默认 |

---

## 10. 与新设计（P1~P4）的对应关系

| 设计文档条款 | 落地位置 |
|---|---|
| 前后序策略拆两个字段 | `IfmapConfig.preStrategyName` / `postStrategyName`（`02` 篇 DDL 29 列） |
| `StrategyContext.Phase` | `StrategyContext:37` |
| 池保留键 `pool` | `IfmapOrchestrator` 的 `POOL_KEY`（`StrategyContext` 不持有池，池在编排器） |
| 跨步骤共享池 | `runPlan:188` + `executeOne:227` 外部传池 |
| 预置池 = `payload.pool` | `initialPool:334` |
| 池只在判定成功时入池 | `executeOne:256-258` |
| `queryConfigsByBusiNode`（R1 破坏性变更） | `ConfigRepository` 接口 + `JdbcConfigRepository` 实现 |
