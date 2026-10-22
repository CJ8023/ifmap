# 01 · core：契约与模型

覆盖 `ifmap-core` 的 6 个包：`exception` / `json` / `model` / `config` / `cache` / （SPI 见 04 篇）。

这一层是**内核的边界**：里面的接口签名就是对外承诺，改动即破坏性变更。阅读重点不是实现，
而是「哪些约束写在 javadoc 里但代码不检查」。

---

## 1. `exception`（9 个文件，~350 行）

继承结构（全部 `RuntimeException`，不需要在签名里声明）：

```
RuntimeException
 └─ IfmapException                                    25-36   根，只有 (message) / (message, cause)
     ├─ IfmapConfigException                          23-35   配置非法 / 找不到配置
     │   ├─ RuleNotFoundException                     26-33   模板引用了不存在的规则名
     │   └─ StartupValidationException                33-67   启动期契约自检失败（带 missingRules + problems）
     ├─ IfmapRemoteException                          26-37   出网失败（可重试语义）
     ├─ IfmapStrategyException                        25-37   策略 / 动作使用错误
     ├─ RuleArgumentException                         23-35   规则实参类型不符（strictTypes 抛这个）
     └─ RuleInvocationException                       23-35   规则方法内部抛错（包装 InvocationTargetException）
```

| 文件 | 行 | 说明 |
|---|---|---|
| `exception/IfmapException.java` | 25–36 | **不带 errorCode**。早期设计想给每个异常挂错误码，实际落地时错误码放在了 `Judgement.withErrorCode` / `IfmapResult.getFailType` 上 —— 异常只负责"谁来接"。 |
| `exception/IfmapConfigException.java` | 23–35 | 引擎抛得最多的异常：找不到配置、起点不在计划里、雪花 ID 回拨、workerId 越界。 |
| `exception/IfmapRemoteException.java` | 26–37 | **语义是"宿主应该包装 IO/超时/非 2xx 后抛它"**（`PartnerServiceGateway` javadoc 明写）。引擎自己不会 new 它。 |
| `exception/IfmapStrategyException.java` | 25–37 | 动作缺失（`ActionRegistry.failOnMissingAction`）、策略注册非法。 |
| `exception/RuleArgumentException.java` | 23–35 | `StrictTypes.FAIL_FAST` 下容器类误用抛它；`WARN` 下只 `log.error`。 |
| `exception/RuleInvocationException.java` | 23–35 | `RuleMethod.invoke` 把被调用方法抛的 `InvocationTargetException` 拆包成它。 |
| `exception/RuleNotFoundException.java` | 26–33 | 继承 `IfmapConfigException`（不是 `IfmapException`）—— 因为"引用了不存在的规则"本质是配置错误。 |
| `exception/StartupValidationException.java` | 33–67 | 两个载荷：`missingRules`（模板 → 缺失规则集合）与 `problems`（文本清单）；`getMessage()` 里拼成可读报告。 |

**踩点**：`RuleNotFoundException` 与 `StartupValidationException` 的直接父类不同（前者 `IfmapConfigException`，
后者也是 `IfmapConfigException`），所以 `catch (IfmapConfigException)` 会一并抓到它们；如果只想抓
"配置行不存在"，要么用更精确的类型，要么判 message。目前代码里没有依赖这个区分的逻辑。

---

## 2. `json`（3 个文件）

core 对任意 JSON 库**零依赖**：模板求值、判定、日志都只认 `JsonOps`。

### 2.1 `json/JsonOps.java`（82 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 46 | `interface JsonOps` | 全部能力的唯一入口 |
| 49 | `name()` | SPI 实现名（`jackson` / `fastjson`），日志与管理端展示用 |
| 57 | `parse(String)` | 解析成 JDK 原生结构（`Map`/`List`/`String`/`Boolean`/`Number`/null） |
| 60 | `toJson(Object)` | 序列化 |
| 68 | `readContext(String)` | **一次解析、多次取数**（存量实现每条 JsonPath 都重解析源 JSON） |
| 71 | `isJson(String)` | 合法 JSON 判断（管理端保存前校验） |
| 79–81 | `isValidPath(String)` | **default 返回 true** —— 实现方可以不支持路径校验，校验会退化为"不校验路径" |

javadoc 27–42 行写的是**实数契约**，由 `ifmap-json-tck` 固定，四条要背下来：

1. 小数（含科学计数法写法）解析后必须是 `BigDecimal`，且 **scale 等于源文字的小数位**（`10.00` → scale 2）；
   整数解析成 `Integer`/`Long`/`BigInteger`，**不得因位数多而变成浮点**；
2. `toJson` 输出的小数**永不出现科学计数法**，且保留小数位；
3. 数组/对象内部的数字与顶层同一口径（递归生效）；
4. **只改写法不改类型**：数字仍是 JSON number，不被引号包成字符串。

> 这两条是**成对**的：只开「解析成 BigDecimal」会在最外层序列化时丢尾零；只开「按 plain 输出」
> 又因为值已经是 `Double` 而回不到源文字。实现细节见 05 篇。

### 2.2 `json/JsonReadContext.java`（42 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 36 | `read(String path, boolean leafToNull)` | 必须支持完整 JsonPath：通配 `*`、递归下降 `..`、过滤器 `[?(@.x=='y')]`、切片 `[0:2]` |
| 41 | `read(String path)` | 等价于 `read(path, true)`（叶子缺失补 null） |

`leafToNull` 的用途是**保证列表等长、下标对齐**：`$.items[*].amount` 里某个元素没有 `amount` 时，
补 null 才能让「金额数组」和「币种数组」下标对得上。

### 2.3 `json/JsonOpsHolder.java`（92 行）

无 Spring 场景的实现持有器（`demo-pure-java` 靠它）：

| 行 | 成员 | 说明 |
|---|---|---|
| 32 | `HINT` | 找不到实现时的报错文案（点名「请任选其一」） |
| 42 | `get()` | 取实例；未设置时走 `loadFromSpi()` |
| 60 | `set(JsonOps)` | 手动注入（测试/自定义库） |
| 68 | `isPresent()` | 是否已有实例 |
| 73 | `reset()` | 清空（**测试专用**，让下一个用例回到 ServiceLoader 路径） |
| 77 | `loadFromSpi()` | `ServiceLoader<JsonOps>` 兜底；多个实现时报错要求显式 `set` |

---

## 3. `model`（4 个文件）

### 3.1 `model/IfmapRequest.java`（244 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 35–42 | 字段 | `tenantId` / `operatorId` / `requestId` / `bizId` + `headers`(String) / `payload`(Object) / `attributes`(Object) + **`rawBody`**（被调方向的对方报文原文） |
| 42 | 私有构造 | 三个 Map 都做 `Collections.unmodifiableMap(new LinkedHashMap<>(...))` |
| 52 / 57 / 62 | `builder()` / `of(payload)` / `empty()` | 三个入口：完整构造、只带 payload、全空 |
| 66–79 | `getTenantId` / `getOperatorId` / `getRequestId` / `getBizId` | 租户、操作人、请求号（落日志列）、业务号 |
| 83–95 | `getHeaders` / `getPayload` / `getAttributes` | 都是不可变视图 |
| 99–112 | `getHeader(name)` | **两段找**：先 `Map.get`，找不到再遍历 `equalsIgnoreCase`（HTTP 头大小写不敏感） |
| 107 | `getRawBody()` | 对方报文原文；判定与应答模板都按它的路径取值 |
| 115 | `getAttribute(key)` | 扩展属性 |
| 120–127 | `withAttribute(k, v)` | 派生新实例（不修改原对象） |
| 144–151 | `withRawBody(body)` | `executeInbound` 用它把原文塞进来（同样是派生新实例） |

**为什么 payload 与 attributes 分开**：`payload` 是业务报文（模板 `$.xxx` 的根），
`attributes` 是引擎/宿主用的旁路信息，两者混在一起会让模板取到不该看到的字段。
跨步骤共享池（`payload.pool`）走的也是 `payload`。

### 3.2 `model/IfmapResult.java`（184 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 33–40 | 字段 | `success` / `data` / `rawResponse` / `executedInterfaces` / `matchedBranch` / `elapsedMs` / `errorMessage` / `failType` |
| 53 / 58 / 63 | `builder()` / `success(data)` / `failure(msg)` | 三个入口 |
| 67–113 | 各 getter | `getFailType()` 说明失败归因口径（`TRANSPORT`/`HTTP_4XX`/… 见 03 篇） |
| 122–184 | `Builder` | 不可变构建 |

**`executedInterfaces`** 是排查「这条流水线到底跑到第几步」的关键字段（多步骤计划里前面的步骤成功、
后面的失败，只有这个列表能告诉你是哪几步真跑了）。

### 3.3 `model/PartnerCall.java`（109 行）

出网 SPI 的入参，4 个字段：`config`（含 `interfaceNo`/`partnerCode`）/ `request` / `requestJson`（渲染后报文）/
`params`（当前参数表，宿主 SDK 有时要取非报文参数）。

| 行 | 成员 | 说明 |
|---|---|---|
| 36 | 私有构造 | 值对象 |
| 43 | `of(config, request, requestJson, params)` | 便捷工厂 |
| 47 | `builder()` | 完整构造 |
| 76–109 | `Builder` | — |

### 3.4 `model/IfmapConfigHistory.java`（150 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 32–41 | 常量 | `CREATE` / `UPDATE` / `DELETE` / `ENABLE` / `DISABLE` 五种 `change_type` |
| 43–53 | 字段 | `keyId` / `configKeyId` / `tenantId` / `interfaceNo` / `changeType` / `changeReason` / `snapshot` / `diff` / `addUserId` / `addTime` / `addRequestId` |
| 55–150 | getter/setter | 纯 POJO |

`snapshot` / `diff` 是 **JSON 文本**，序列化由管理端负责（引擎不依赖 JSON 库，见 `ConfigHistoryRepository` javadoc）。

---

## 4. `config`（7 个文件）

### 4.1 `config/ConfigRepository.java`（69 行）—— 全篇最重要的契约

| 行 | 方法 | 硬约束（写在 javadoc 里，代码**不检查**） |
|---|---|---|
| 26–31 | 总述 | 四条约定：排序、只返回有效行、租户用 String、`busiNode` 空返空 |
| 56 | `queryConfigsByBusiNode(tenantId, busiNode)` | **执行计划唯一入口**：按 `interfaceOrder` 升序、同值 `keyId` 兜底；只返回 `del_status=0 && status=1`；`busiNode` 空 → **空列表（不是全表）** |
| 59 | `queryConfigs(tenantId, interfaceNo, busiNode)` | 管理端单条查询 / demo 用；同样升序 |
| 62 | `findConfig(tenantId, interfaceNo, busiNode, order)` | 单条，`Optional` |
| 65 | `queryLogicBranches(tenantId, interfaceNo)` | 按 `logicBranchOrder` 升序、`keyId` 兜底 |
| 68 | `saveExecutionLog(log)` | 落日志 |

**为什么排序契约写在这里**：引擎在本地只再兜底排一次（`InterfaceOrdering.sort`），
**没有别的机制会替你纠正顺序**；仓储实现乱序会让"前序步骤的产物"出现在后序步骤之后。

`tenantId` 用 `String` 是为了兼容"租户 ID 非数字"的宿主机；单租户传 `"-1"`。
解析失败时实现方应抛 `IfmapConfigException`。

### 4.2 `config/ConfigHistoryRepository.java`（42 行）

| 行 | 方法 | 说明 |
|---|---|---|
| 30 | `interface` | 管理端"版本对比 / 一键回滚 / 审计"的数据来源；**引擎运行期不读** |
| 38 | `list(configKeyId, limit)` | **按时间倒序**（最新在前）；`limit <= 0` 视为默认 50 |
| 41 | `findByKeyId(keyId)` | 回滚用 |

### 4.3 `config/IfmapConfig.java`（361 行）

主配置行（29 列），引擎运行期**只读**。

| 行 | 分组 | 说明 |
|---|---|---|
| 33–35 | 类声明 | `Serializable`；列名下划线转驼峰，供 `BeanPropertyRowMapper` 直映（**所以字段名必须与列名严格对应**） |
| 38–50 | 标识 | `keyId` / `tenantId` / `interfaceNo` / `interfaceCode` / `projectCode` / `interfaceName` / `busiNode` |
| 57–63 | 合作机构 | `partnerCode` / `partnerName`（由旧列 `bank_code`/`bank_name` 改名而来，DB 列是 `partner_code`/`partner_name`） |
| 65 | `financingMode` | 融资模式（`varchar`，存量库可能没有此列 —— 迁移时是 ADD 不是 MODIFY） |
| 67 | `interfaceOrder` | **执行顺序的唯一来源** |
| 69–73 | 模板与判定 | `requestParamTemplate` / `responseParamTemplate` / `resultJudge` / `judgeName`（默认 `ruleSpec`） |
| 74 | `callDirection` | **调用方向**（`CallDirection` 枚举：`OUTBOUND` 出网 / `INBOUND` 被调），空值等价 `OUTBOUND`；`inbound()`（`117`）是编排器唯一读取入口 |
| 77–79 | 策略 | `preStrategyName` / `postStrategyName`（= Spring bean 名） |
| 81–85 | 元数据 | `status`（1 启用）/ `version` / `remark` |
| 87–101 | 删除标记 + 审计列 | `delStatus` / `deletedSeq`（逻辑删除）；`addUserId` / `addTime` / `addRequestId` / `modifyUserId` / `modifyTime` / `modifyRequestId`（注意 `addTime` 是 **`LocalDateTime`**，不是 `Date`） |

字段共 **29 个**，与 `ifmap_config` 的 29 列一一对应。

**踩点**：`IfmapConfig` 是 `BeanPropertyRowMapper` 的映射目标，加字段时**必须**同时确认
① DDL 有该列（29 列）② `JdbcConfigRepository` 的 SELECT 列表包含它 ③ INSERT/UPDATE 的占位数跟着变。
三者任缺其一，运行期表现为「字段永远是 null」而不是报错。

### 4.4 `config/ExecutionLog.java`（221 行）

| 行 | 分组 | 说明 |
|---|---|---|
| 32–38 | 常量 | `RESULT_SUCCESS` / `FAIL` / `SKIP` / `TIMEOUT` |
| 74 | `success(...)` | 便捷工厂 |
| 84 | `fail(...)` | 便捷工厂 |
| 41–71 | 字段 | 基本列：`keyId` / `tenantId` / `interfaceNo` / `bizId` / `requestParam` / `responseParam` / `executionTime` / `executionResult` / `errorMsg` / `remark` / `addUserId` / `addRequestId` |
| 59–65 | 后加列 | `httpStatus` / `failType` / `errorCode` / `trackId`（失败归因，见 03 篇判定管线） |

`requestParam` / `responseParam` 在落库前已经过 `LogMasker` 脱敏 + `Logs.truncate` 截断。

### 4.5 `config/LogicBranchConfig.java`（153 行）

| 行 | 字段 | 说明 |
|---|---|---|
| 39–45 | `keyId` / `tenantId` / `interfaceNo` / `methodFlag` | `method_flag` = 命中后要执行的动作 key（`ActionRegistry`） |
| 47–53 | `logicBranchName` / `logicBranchFlag` / `logicBranchValue` / `logicBranchOrder` | 判定两选一：**策略命中**（`flag` 走 `LogicBranchStrategyRegistry`）或**值命中**（`value` 按 `;`/`,` 切分逐个比较） |
| 55–59 | `remark` / `delStatus` / `deletedSeq` | 逻辑删除 |

### 4.6 `config/ConfigQuery.java`（142 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 32 | `MAX_SIZE = 200` | 单页上限（防「查全库」） |
| 34–38 | 过滤字段 | `tenantId` / `interfaceNo` / `busiNode` / `partnerCode` / `status` |
| 40 / 46 | `includeDeleted` / `allTenants` | 管理端专用开关 |
| 47–48 | `page=1` / `size=20` | 默认分页 |
| 139 | `offset()` | `(page-1)*size` 计算，供 provider 用 |

### 4.7 `config/PageResult.java`（72 行）

`total` / `page` / `size` / `rows` + `getPages()`（64 行，向上取整，`size<=0` 时返回 0）。
不可变（`rows` 由调用方传入）。

---

## 5. `cache`（3 个文件）

### 5.1 `cache/ConfigCache.java`（85 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 37 | `get(key, loader)` | 未命中时调 `loader` 加载并写缓存；**`loader` 抛异常时不写缓存，异常原样抛出** |
| 40 | `invalidate(key)` | 单 key 失效（管理端保存/删除后调） |
| 43 | `invalidateAll()` | 清空 |
| 46 | `stats()` | 命中/未命中/淘汰/条目数 |
| 49–84 | `CacheStats` | 不可变快照值对象 |

### 5.2 `cache/InMemoryConfigCache.java`（167 行）

零依赖默认实现（`ifmap-core` 不依赖 Caffeine 的那一半）。

| 行 | 成员 | 说明 |
|---|---|---|
| 39 | `DEFAULT_MAXIMUM_SIZE = 1000` | 条目上限 |
| 42 | `DEFAULT_TTL_MILLIS = 60_000` | 默认 TTL 60 秒 |
| 44–56 | `CacheEntry` | value + expireAt |
| 60 | `store` | `LinkedHashMap`（accessOrder 供 LRU 淘汰） |
| 61 | `lock` | `new boolean[0]` —— **零成本锁对象**（`synchronized (lock)`），不锁 `this`，避免外部误用本对象做锁 |
| 67 / 75 | 两个构造 | 默认值 / 显式上限 + TTL（上限 <= 0 抛 `IfmapConfigException`） |
| 81–93 | `store` 初始化 | `LinkedHashMap(16, 0.75f, true)` = **accessOrder LRU**；`removeEldestEntry` 里必须写 `super.size()` —— 匿名类同时能看到外部类的 `size()` |
| 98–127 | `get` | 锁内：命中未过期→`hitCount++` 返回；过期→删。**加载在锁外**（避免查库期间阻塞其他 key），代价是同一 key 并发未命中会重复加载（结果一致，最后写入胜出）→ 锁内再写回 |
| 130 | `invalidate` | 删单 key |
| 140 | `invalidateAll` | 清空（`evictionCount` 不计） |
| 147 | `stats` | 快照 |
| 154 / 160 / 164 | `size` / `getMaximumSize` / `getTtlMillis` | 观测用 |

### 5.3 `cache/CachingConfigRepository.java`（112 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 45 | `KEY_PREFIX = "ifmap:logicBranch:"` | 只有**逻辑分支**进缓存 |
| 50–59 | 构造 | delegate / cache 均非空 |
| 62–65 | `cacheKey(tenantId, interfaceNo)` | `ifmap:logicBranch:<tenant>:<interfaceNo>` —— **含租户**，这是"跨租户串配置"的根治点 |
| 68–76 | `queryLogicBranches` | loader 里返回 `unmodifiableList(new ArrayList<>(loaded))`（**防御性拷贝**，避免缓存被外部改） |
| 79–81 | `invalidate(tenantId, interfaceNo)` | 管理端保存分支后调 |
| 84–96 | `queryConfigsByBusiNode` / `queryConfigs` / `findConfig` | **直通，不缓存** |
| 99–101 | `saveExecutionLog` | 直通 |
| 104 / 109 | `delegate()` / `cache()` | 观测用 |

**为什么只缓存分支不缓存配置**：配置决定"跑哪条流水线"，改了配置必须立刻生效；
分支只在步骤内部用，且管理端保存分支后会显式 `invalidate`。把配置也缓存会让
"管理端改完配置、引擎还在跑老流程"变成常态。

> `CaffeineConfigCache` 在 `ifmap-spring-boot-starter`（见 07 篇）：`@ConditionalOnClass(Caffeine)`
> + `@ConditionalOnMissingBean`，即「有 Caffeine 就用它，没有就用本类的 `InMemoryConfigCache`」。

---

## 6. 本篇的 5 个"看起来多余但别删"的点

| 点 | 位置 | 为什么保留 |
|---|---|---|
| `JsonOps.isValidPath` 的 default 返回 true | `json/JsonOps.java:79` | 让第三方实现可以只做解析不做路径校验，退化为"不校验"而不是"编译不过" |
| `IfmapRequest` 三个 Map 全部不可变 | `model/IfmapRequest.java:42` | 编排器会把 payload 传给模板与策略；可变 Map 会让"谁改了我的入参"无法排查 |
| `CachingConfigRepository` 只缓存分支 | `cache/CachingConfigRepository.java:68` | 见 §5.3 |
| `InMemoryConfigCache.lock` 是 `boolean[0]` | `cache/InMemoryConfigCache.java:61` | 不锁 `this`，防止外部 `synchronized(cache)` 与本类内部锁互相卡死 |
| `IfmapConfigHistory` 在 `core` 而非 `admin` | `model/IfmapConfigHistory.java` | `ConfigHistoryRepository` 是 core 的 SPI，实现方（provider）必须能引用这个类型 |
