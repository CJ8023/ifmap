# 03 · core：编排与判定

覆盖 `IfmapEngine`（255 行）、`orchestrator`（3 文件）、`orchestrator/judgement`（8 文件）。

本篇是引擎的**主干**：一次 `execute` 怎么走完全程、每一步谁负责、失败怎么归因。
`00-总览` 里给过时序图，这里给**行号级**的落地细节。

---

## 1. `IfmapEngine.java`（255 行）

引擎本体很薄 —— 它不执行接口，只做「渲染」和「校验」。编排在 `IfmapOrchestrator`。

| 行 | 成员 | 说明 |
|---|---|---|
| 50–52 | 三个依赖 | `jsonOps` / `registry`（规则）/ `templateEngine` |
| 54 | 私有构造 | 只能经 `Builder` 创建 |
| 70 | `createDefault()` | 自动发现 `JsonOps` + 内置规则 + `SKIP_FIELD`（纯 Java 场景的一行入口） |
| 74 | `builder()` | 构造器入口 |
| 79 | `render(template, source)` | 默认上下文（`RuleContext.empty()`） |
| 84 | `render(template, source, ctx)` | 渲染成 JSON 文本 |
| 89 | `renderToObject(...)` | 渲染成 JDK 结构（`Map`/`List`/标量） |
| 95 | `renderToMap(...)` | 渲染成 `Map`；模板根不是对象 → `IfmapConfigException` |
| 110 | `validateTemplate(id, json)` | **两件事分开查**：① `registry.assertRulesExist` 抓「未注册规则」；② `TemplateScanner.expressionProblems` 查「表达式错语法」。两者都有才合并成一条 `StartupValidationException`（保留 `missingRules` 与 `problems` 两份结构，管理端 422 才能分列展示） |
| 125 | `validateTemplates(map)` | 批量版；problems 前面拼模板 id |
| 146 / 150 | `describe` / `describeBlock` | 拼接人读报错文本（单个 / 批量两种措辞） |
| 165 | `describeRules()` | 生成规则清单 Markdown 表格（`/rules` 与文档同源） |
| 186 | `escape(text)` | Markdown 表格转义（`\|`） |
| 190/194/198 | 三个 getter | `getJsonOps` / `getRegistry` / `getTemplateEngine` |
| 203 | `Builder` | `jsonOps` / `registry` / `nullPolicy` / `strictTypes`；缺省自行兜底 |

> **注意 `validateTemplate` 的注释（107–108）**：表达式错语法**不受 `strict-types` 控制**。
> 理由写得很直白 —— `@sum@$.a,$.b` 在运行期只静默求和成 0，`@FUN(` 未闭合会把整串原样写进报文，
> 「错的东西不该能存进去」。所以哪怕宿主配了 `WARN`，保存时照样 422。

---

## 2. `orchestrator/IfmapOrchestrator.java`（867 行）

整篇最重要的类。分四段读：入口与计划（130–234）、单步执行（236–306）、
策略与基础设施（308–692）、两个内部类（694–866）。

### 2.1 入口与执行计划（130–234）

| 行 | 方法 | 说明 |
|---|---|---|
| 133 | `execute(request, interfaceNo, busiNode)` | 3 参重载 → 转调 4 参（`mockResponse=null`） |
| 140 | `execute(request, interfaceNo, busiNode, mockResponse)` | ① 记 `start`；② `tenantResolver.resolve`；③ `loadPlan`；④ **计划为空立刻抛**（`148–151`，消息带三要素）；⑤ `runPlan` |
| 164 | `executeInbound(request, interfaceNo, busiNode, rawBody)` | 被调方向便捷入口 = `execute(request.withRawBody(rawBody), ...)`；**方向仍以配置为准**（传了原文但配置是 OUTBOUND，照旧出网） |
| 179 | `loadPlan` | `repository.queryConfigsByBusiNode` → `InterfaceOrdering.enabled` → `InterfaceOrdering.sort`；`interfaceNo` 只当**起点**：命中则 `subList(i, size)` 截断，**未命中抛异常**（`193–194`）；空/空串 → 整个节点 |
| 202 | `runPlan` | **本方法持有跨步骤共享池**（`205`）；逐步 `executeOne`；每步把 `interfaceNo`/`matchedBranch`/`rawResponse` 汇总进 `result`（`212–218`）；失败时记 `failType` 并按 `stopOnFailure`（默认 `true`）决定是否中断（`220–227`）；末尾 `flattenPool(pool)` + `data.put(POOL_KEY, pool)`（`230–231`，既保扁平语义又提供可往返结构） |

**为什么「起点」就能表达断点续跑**（`168–178` 的 javadoc）：一个 `busi_node` 就是一条流水线，
起点位置就是进度 —— 把上次最后成功的接口号当起点传进来即可，**不需要额外字段或状态表**。

### 2.2 单步执行（236–306）

`executeOne` 有**两个**版本：

| 行 | 签名 | 说明 |
|---|---|---|
| 242 | `public executeOne(req, tenant, config, mock)` | 独立调用，**自建池**（`initialPool`），只服务本条 |
| 247 | `private executeOne(..., pool)` | 计划内调用，**外部传池** |

单步的 12 步（按行号顺序就是执行顺序）：

| 行 | 动作 | 要点 |
|---|---|---|
| 250 | 建 `RuleContext` | `tenantId` / `interfaceNo` / `request.attributes` |
| 255 | 建 `StrategyContext` | 带 `request` / `config` / `ruleContext` / `params=initialParams(request, config)` |
| 258 | 参数三层合成 | `fullParams.assemble(partnerCode, busiNode, context)` 起底 |
| 262 | **池覆盖** | `requestParams.putAll(flattenPool(pool))` —— 池在后，**覆盖** payload 与组包策略的同名顶层键 |
| 263 | 保留键 | `requestParams.put(POOL_KEY, pool)`，模板可用 `$.pool.*` |
| 266 | **方向分流** | `boolean inbound = config.inbound()`：一次分支决定后面「渲染 / 出网」两件事 |
| 268–270 | 被调：原文即请求 | `requestJson = inboundSource(request)`，**不渲染请求模板、不跑前序策略** |
| 272–276 | 出网：渲染请求 | 模板为空 → 原样透传（`render`，`586`）；再交 `applyPreStrategy` 可改 `requestJson` |
| 281–282 | 出网 / 回参 | `mockResponse != null` 时**不出网**且**两方向都优先**；否则 `inbound ? ofBody(requestJson) : exchange(...)` |
| 287 | 产物 | `applyPostStrategy(context, renderResponse(...))` |
| 289 | **判定** | `pipeline.judge(config, response, outcome)` —— **读原始回参 `response`**，不是产物 |
| 291–293 | 入池 | 判定成功才 `pool.put(interfaceNo, data)` |
| 296 | 产物回填 | `context.setResponseData(data)` **无条件**设（`applyPostStrategy` 早返回时不设 ⇒ 动作里拿不到产物） |
| 298 | 分支 | 成功才 `applyBranch`（**失败不跑动作**） |
| 299 | 失败原因 | `failureMessage(judgement)` |
| 303 | 落库 | `writeLog(..., inbound ? toJsonQuietly(data) : response, ...)` —— 被调方向记的是**我方应答** |

### 2.3 前序策略（308–341）

| 行 | 说明 |
|---|---|
| 316 | `setPhase(PRE)` —— 策略里可据此分支 |
| 317–320 | `pre_strategy_name` 为空 → 直接返回原 `requestJson` |
| 321–329 | 查不到策略：`failOnMissingStrategy`（默认 `true`）抛 `IfmapStrategyException`，否则 WARN + 原样 |
| 330–334 | 返回 `null`/空 Map → 原样（**"没额外参数"不是错误**） |
| 335–340 | 有额外参数 → `context.putParams(extra)` → **用同一个请求模板重渲染一遍** → 更新 `requestJson` |

**重渲染，而不是"往 JSON 里打补丁"**：模板里可能用 `@FUN(...)` 依赖新参数，
打补丁会让模板逻辑失效。

### 2.4 后序策略（343–382）

| 行 | 说明 |
|---|---|
| 352–355 | `post_strategy_name` 为空 → 返回原产物（**早返回，不设 `responseData`**，所以 `executeOne:296` 要补设） |
| 356–364 | 查不到：同上（`failOnMissingStrategy` 控制） |
| 366–367 | `setPhase(POST)` + `setResponseData(data)` |
| 368–377 | 合并：策略通过 `setResponseData` **整体替换**（必须是 `Map` 才生效，否则忽略）→ 以替换后的为底 |
| 378–380 | 策略返回值 `extra` 覆盖同键（**返回键优先**） |

**判定读原始回参**（288 行注释）：后序策略只能加工产物，改不动成败。

### 2.5 入参与池（384–472）

| 行 | 方法 | 说明 |
|---|---|---|
| 391 | `initialParams(request, config)` | 出网方向 / payload 非空 → 直接用 `payload`；**只有「被调方向 + payload 为空」**才宽松解析对方原文（同一接口不会时而读原文时而读 payload） |
| 412 | `inboundSource(request)` | 被调方向的「回参」= `rawBody`；没传则退化成 `payload` 的序列化形态；都没有 → null（交给判定给 `NOT_JSON`） |
| 422 | `toJsonQuietly(data)` | 只为写日志；序列化失败 WARN + null，**绝不影响业务返回** |
| 435 | `initialPool(request)` | `payload.pool` 是 `Map` 时**浅拷**一份（`String.valueOf` 键）—— 不写回、不持有宿主对象 |
| 453 | `flattenPool(pool)` | 按池的插入顺序 reduce，**同名键后者胜**：预置项（断点续跑回放）在前、本次成功步骤按计划顺序在后。要精确取某步字段用 `$.pool.{interface_no}.xxx` |

### 2.6 出网与产物（474–520）

| 行 | 方法 | 说明 |
|---|---|---|
| 474 | `exchange` | `gateway == null` → WARN + `PartnerOutcome.ofBody(null)`（只做本地渲染 / dry-run） |
| 481 | | 组 `PartnerCall.of(config, request, requestJson, params)` |
| 482–488 | | `gateway.exchangeForOutcome(call)`；**`IfmapRemoteException` 收敛成判定失败**（返回 `PartnerOutcome.failure`），其他 `RuntimeException` 照原样传播 |
| 497 | `renderResponse` | 响应体为 null 或模板为空 → `emptyMap()`；否则渲染 → `parseLenient` → 只接受 `Map` 根，其他返回 `emptyMap()` |
| 594 | `parseLenient` | 解析失败 DEBUG 后返回 null（**响应不是 JSON 不算崩溃**，交给判定层归因 `NOT_JSON`） |

`IfmapRemoteException` 与非 `IfmapRemoteException` 的区别是**刻意的**：
传输失败要能落库归因、要遵守 `stopOnFailure`；配置/编程错误不该被判定层吞掉。

### 2.7 逻辑分支（522–583）

| 行 | 方法 | 说明 |
|---|---|---|
| 522 | `applyBranch` | 取 `queryLogicBranches(tenantId, interfaceNo)`；空 → DEBUG 返回 null |
| — | **先全量校验** | 遍历所有分支的 `branch_judge`，任一非法 ⇒ 整步 `FAIL`（`fail_type=SPEC_INVALID`）、不执行任何分支、**不抛异常**（fail-closed 优先于"跳过坏的继续跑"） |
| — | **两趟扫描** | 第一趟只挑出兜底分支（规格 `{"op":"always"}`，`BranchJudgeSpec.isAlways` 判编译后的根节点），并跳过它；其余按 `logic_branch_order` 升序求值，首个 `MATCHED` 立即执行 |
| — | 兜底 | 常规全不中才走兜底（**即使它排在前面也不会抢常规分支的命中**） |
| — | 都没有 | WARN（带分支条数与 `actualValue`）返回 null |
| — | `executeBranch` | `setMatchedBranch(logicBranchName)`（**改记分支名，原记 flag**）；动作键 = `methodFlag`，为空 = **不执行动作**（原「为空退回用 flag 当动作名」的隐藏行为已删） |
| — | `BranchJudge` | 规格编译一次进 1024 条 LRU（**解析失败也缓存**）；求值根是**对方原文**，与 `result_judge` 共用 `JsonReadContext`；三分 `MATCHED` / `MISSED` / `invalid` |

### 2.8 渲染与日志（586–692）

> 回调通道（`callback(...)` + `CallbackRegistry`）已下线，被调场景改由 `executeInbound` + `call_direction=INBOUND` 承载。

| 行 | 方法 | 说明 |
|---|---|---|
| 586 | `render` | **模板为空 → 原样返回 source**（这是"直通"语义，不是渲染空） |
| 613 / 625 | `writeLog` 两个重载 | 判定版（`Judgement`，外层转调）/ **全参唯一实现**（`626` 起） |
| 627–629 | `logSink == null` | 直接返回（不装配就不落库） |
| 631–648 | 组装 | `tenantId` 用 `parseTenantId`（**非法 → `-1`**）；请求/响应都过 `protect`（脱敏 + 截断）；有判定结论才写 `failType`/`errorCode`/`trackId`/`httpStatus` |
| 649–651 | catch | 写日志失败只 WARN —— **落库不能影响业务** |
| 655 | `failureMessage` | 「判定口径」+「对方原文」两段拼接（`reason：message`） |
| 668 | `protect` | `logMasker.mask` → `Logs.truncate(masked, threshold)` |
| 674 | `parseTenantId` | 空/非数字 → `-1` |
| 686 | `trimToNull` | 内部工具 |

`error_msg` 两段式的理由（`606–611` 注释）：只写判定口径，运维还得翻响应报文；
只写对方原话，又不知道是判定规格问题还是通道问题。

### 2.9 两个内部类（694–866）

| 行 | 类 | 说明 |
|---|---|---|
| 695 | `StepOutcome` | 单步结果：`success`/`data`/`rawResponse`/`errorMessage`/`matchedBranch`/`failType`；`data` 是 `unmodifiableMap(new LinkedHashMap(...))`（**拷一份再包**，只包不拷调用方仍能改到内容） |
| 743 | `Builder` | 13 个可选依赖 + `stopOnFailure=true` / `failOnMissingStrategy=true` / `truncateThreshold`（`758–760`） |
| 842 | `build()` | 只强制 `engine`/`repository`/`tenantResolver`；**其余注册表缺省自行 new 空实例**（所以纯 Java 只给三个必需依赖就能跑） |

---

## 3. `orchestrator/InterfaceOrdering.java`（93 行）

| 行 | 方法 | 说明 |
|---|---|---|
| 42 | `enabled(configs)` | 只留 `status == null \|\| status == 1`，且 `delStatus == null \|\| delStatus == 0` |
| 59 | `sort(configs)` | 按 `interface_order` **升序**，同值用 `key_id` 兜底，`null` 排最后；返回新 List（不改入参） |

javadoc 明确写了「**顺序只有一个来源**」—— 拓扑排序那层（环检测 / 深度护栏 / 4 类校验）已删，
因为 `interface_order` 已经是唯一顺序来源，再做一遍拓扑是重复设计。

---

## 4. `orchestrator/Judgement.java`（165 行）

判定结论的**不可变值对象**。

| 行 | 成员 | 说明 |
|---|---|---|
| 33 | `Stage` | `TRANSPORT`（传输层）/ `BUSINESS`（业务层）—— 归因时区分"网络问题"还是"对方说不行" |
| 37–46 | 10 个字段 | `success`/`stage`/`failType`/`actualValue`/`expectedValue`/`errorCode`/`httpStatus`/`trackId`/`message`/`reason` |
| 63 | `success(actual, expected, reason)` | 成功（`failType=null`） |
| 68 / 75 | `failure(failType, stage, ...)` | 两个重载（带/不带 actrual+expected） |
| 85 | `of(...)` | 布尔分发 |
| 137/142/147 | `withHttpStatus` / `withTrackId` / `withErrorCode` | 派生新实例（不可变风格） |
| 153 | `withExtraction(message, trackId)` | 抽取段专用：**只补信息，不改 success / failType / errorCode** |

---

## 5. `orchestrator/judgement`（8 文件）

### 5.1 判定管线的四段（`JudgementPipeline`，105 行）

| 行 | 段 | 说明 |
|---|---|---|
| 62–67 | ① 超时 | `outcome.isTimedOut()` → `TIMEOUT` + `withHttpStatus` |
| 68–70 | ② 传输失败 | `outcome.isFailure()` → `TRANSPORT`（**不带走 httpStatus**，因为没拿到状态码） |
| 71–81 | ③ HTTP 状态码 | `JudgeFailType.forHttpStatus` 非 null → `HTTP_4XX`/`HTTP_5XX`，并带上对方 message 与 trackId |
| 82 | | 到这里 `responseJson` **一律改成 `outcome.getBody()`** |
| 85–89 | ④ 业务判定 | `judges.lookup(trimToNull(judgeName))`；查不到 → `SPEC_INVALID`（「判定器未注册」） |
| 90 | | 委派 `ResultJudge.judge(ResultJudgeContext)` |
| 92–95 | ⑤ 抽取段 | 无论成败都补 `message` / `trackId` / `httpStatus`（`ResponseExtractor`） |

`outcome` 为 `null` 时等价于"老实现"—— 跳过 ①②③，只做业务判定（向后兼容）。

### 5.2 `ResultJudgeRegistry`（87 行）

| 行 | 方法 | 说明 |
|---|---|---|
| 38–39 | 字段 | `judges`（`LinkedHashMap` 保序）+ `defaultName = RuleSpecJudge.NAME` |
| 42 | `withDefaults(jsonOps)` | 装好 `ruleSpec` 的默认注册表 |
| 49 | `register(name, judge)` | 同名覆盖（后注册者胜） |
| 60 / 64 / 73 | `defaultName` / `setDefaultName` / `lookup` | `lookup` 传空 → 返回默认判定器 |

### 5.3 `ResultJudge` / `ResultJudgeContext`（小接口）

`ResultJudge`：`String name()` + `Judgement judge(ResultJudgeContext)`；
`ResultJudgeContext`：`config` + `responseJson` + `jsonOps`（**只读三元组**，判定器不碰编排状态）。

### 5.4 `RuleSpecJudge`（95 行）

| 行 | 说明 |
|---|---|
| 38 / 40 | `NAME = "ruleSpec"` / `ALWAYS_SUMMARY = "always"` |
| 60–64 | 规格非法 → `SPEC_INVALID`（**只报第一条错误**，完整清单留给管理端） |
| 66–69 | `always` → 直接成功（「该接口不做结果判定」） |
| 71–74 | 响应为空 → `NOT_JSON` |
| 76–81 | `parsed.evaluate(...)` 抛异常 → `NOT_JSON`（带原因） |
| 82–85 | 命中 → 成功（`actual` + `parsed.describe()`） |
| 86–90 | `missedPath != null` → `FIELD_MISSING`（「取不到判定字段 X」） |
| 91–93 | 其他 → `VALUE_MISMATCH` + `withErrorCode(actual)`（**把对方实际值塞进 error_code**，落库后可检索） |

### 5.5 `ResultJudgeSpec`（416 行）

| 行 | 方法 | 说明 |
|---|---|---|
| 90 | `parseNode(node, depth, errors, jsonOps, top)` | **顶层只允许 `op: always` 且不得带其他键**（93–104）；条件算子必须写在 `all`/`any` 里 |
| — | `validate(spec, jsonOps)` | 返回错误清单（管理端 422 与 `ContractValidator` 共用） |
| — | `parse(spec, jsonOps)` | 校验不过抛 `IllegalArgumentException` |
| — | `evaluate(ctx)` | 求值，产出 `Evaluation(actual/matched/missedPath)` |
| — | `isAlways()` / `describe()` | 是否"不做判定" / 人读摘要 |

详细算子语义见 `01` 篇 §`judgement` 表与 `docs/04-结果判定规格.md`。

### 5.6 `JudgeCondition` / `JudgeOp` / `JudgeFailType`

| 文件 | 行 | 说明 |
|---|---|---|
| `JudgeFailType.java` | 36 | 闭合枚举 9 个：`TRANSPORT`/`TIMEOUT`/`HTTP_4XX`/`HTTP_5XX`/`SPEC_INVALID`/`NOT_JSON`/`FIELD_MISSING`/`VALUE_MISMATCH`/`EXTRACT_FAILED`；`forHttpStatus`（30 行）：2xx → null、≥500 → `HTTP_5XX`、其余 → `HTTP_4XX` |
| `JudgeOp.java` | 102 | `EQ`/`NE`/`IN`/`NOT_IN`/`EXISTS`/`NOT_BLANK`/`REGEX`/`ALWAYS`；`fromName`（39）去下划线 + 忽略大小写（`not_in` / `NOT_IN` / `notIn` 都认）；`requiresValue`/`requiresValueList` 给校验用 |
| `JudgeCondition.java` | 91 | 单个条件：`path`/`op`/`value`/`ignoreCase`/`values`/惰性 `pattern`；`describe()`（77）输出人读文本 |

### 5.7 `ResponseExtractor`（84 行）

| 行 | 说明 |
|---|---|
| 33 / 35 | `MESSAGE_PATHS` / `TRACK_ID_PATHS` 两组候选路径（不同对方系统的字段名不一样） |
| 38 | `MESSAGE_MAX = 200`（落库长度上限） |
| 44 / 49 | `message` / `trackId`：按候选路径**取第一个命中** |
| 53 | `first(...)`：容错解析（非 JSON → null，不抛） |

---

## 6. 本篇的坑清单（写代码/排查时先看这里）

| # | 现象 | 根因 | 位置 |
|---|---|---|---|
| 1 | 「模板里写了 `$.pool.xxx` 但取到 null」 | 池只在**判定成功**时入池；失败步骤不写池 | `IfmapOrchestrator:256-258` |
| 2 | 「`$.applyNo` 取到的是旧值」 | `flattenPool` 同名键**后者胜**（按池插入顺序 reduce），预置项在前 | `:351` |
| 3 | 「后序策略改了字段，但成败没变」 | 判定读**原始回参**，不看产物 | `:255` |
| 4 | 「前序策略返回空 Map，请求没变」 | 空/`null` 视为"没额外参数"，直接返回原报文 | `:283` |
| 5 | 「配了 `pre_strategy_name` 启动就抛异常」 | `failOnMissingStrategy` 默认 `true`（刻意：配了名字却没注册，静默跳过更危险） | `:275` |
| 6 | 「兜底分支排在第一条，抢走了所有命中」 | 不会 —— 兜底分支被单独拎到 `catchAll`，只在常规全不中时生效 | `:417-438` |
| 7 | 「分支动作没执行」 | 动作键 = `methodFlag`，**为空 = 该分支不执行动作**（原「退回用 `logic_branch_flag` 当动作名」已随该列一起删除）；配了个没注册的动作名 → `ActionRegistry.execute` 直接抛（不受 `failOnMissingAction` 控制） | `executeBranch` + `ActionRegistry:91-93` |
| 7.1 | 「分支一条都没命中」 | `branch_judge` 非法（含空串）是 `SPEC_INVALID` 整步失败；规格合法但不匹配才是"未命中"（落 WARN，落到兜底分支） | `BranchJudge` + `ConfigValidator.checkBranchJudge` |
| 8 | 「`ifmap_interface_exec_log.error_msg` 只有对方原话」 | `failureMessage` 只拼非空段 | `:549` |
| 9 | 「日志表 `tenant_id` 是 -1」 | `parseTenantId` 解析失败/空一律 `-1` | `:566` |
| 10 | 「dry-run 里 `gateway=null` 却报成功」 | 无网关时只做本地渲染，`PartnerOutcome.ofBody(null)` → 判定层按"响应为空"归因 | `:372-375` |
| 11 | 「`IfmapRemoteException` 抛到了宿主」 | 只有它被收敛成失败；其他 `RuntimeException` 照原样传播 | `:380` |
| 12 | 「起点接口不在计划内却跑了整个节点」 | 不会 —— 非空起点未命中直接抛 `IfmapConfigException` | `:176` |

---

## 7. 看起来多余但别删的点

| 点 | 位置 | 为什么保留 |
|---|---|---|
| `execute` 的 3 参与 4 参两个重载 | `:134` / `:141` | 4 参的 `mockResponse` 是管理端 dry-run 与单测的唯一入口，不是调试残留 |
| `executeOne` 的两个版本（自建池 / 外部传池） | `:222` / `:227` | 前者是「单独跑一步」的公开契约（宿主可能只重跑失败那步），后者是计划内共享池 |
| `StepOutcome` 里 `data` 先拷再包 | `:598-600` | 只 `unmodifiableMap(data)` 的话，调用方仍能通过原引用改到内容 |
| `IfmapResult.data` 里既有扁平字段又有 `pool` | `:209-211` | 扁平保留 `getData().get("applyNo")` 的老写法；`pool` 提供可往回传的结构（断点续跑要回放） |
| `failureMessage` 两段拼接 | `:549` | 运维既要「怎么判的」也要「对方怎么说」 |
| `parseLenient` 不抛 | `:488` | 对方返回非 JSON 是**正常运行期情况**，必须落库归因为 `NOT_JSON`，不是异常路径 |
| `Judgement.withExtraction` 不改成败 | `:153` | 抽取失败（`EXTRACT_FAILED`）只影响「有没有对方原话」，不该翻转判定结论 |
| `Builder.build()` 给注册表兜底 new | `:749-763` | 纯 Java 接入只想要三个必需依赖，其余空实现天然可用 |
