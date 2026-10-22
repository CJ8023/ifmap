# 08 · 管理端（REST + 内嵌 UI）

`ifmap-admin-spring-boot-starter`：44 文件 / 7,357 行（main 33 / test 11）。

| 分组 | 文件 | 行数 |
|---|---|---|
| `admin` 根包（自动配置 / Service / 校验 / 巡检 / 快照 / 字典 / 异常） | 16 | 2,392 |
| `admin.dto` | 7 | 446 |
| `admin.spi` | 2 | 129 |
| `admin.web`（4 个 Controller） | 4 | 464 |
| UI 静态资源（`index.html` / `app.js` / `app.css` / `AutoConfiguration.imports`） | 4 | 1,545 |
| 测试 | 11 | 2,382 |
| **合计** | **44** | **7,357** |

**定位**：给运维/实施用的**内网离线**配置台 —— 零 CDN、零前端框架、零构建链（`app.js` 是手写 ES5，
`node --check` 可校验语法）。它是"能用、可查、改得动"的工具，不是产品级前端。

---

## 1. 自动配置

### 1.1 `IfmapAdminAutoConfiguration`（223 行）

**五个条件同时满足才生效**（72–75）：`DispatcherServlet` + `JdbcTemplate` + `IfmapOrchestrator` 都在 classpath、
是 **Servlet Web 应用**、`ifmap.admin.enabled=true`（**默认 false**）、有 `DataSource`。

| 行 | bean | 说明 |
|---|---|---|
| 88 | `AdminConfigRepository` | 只是包了 `JdbcConfigRepository` 的薄壳（见 §2.7） |
| 95 | `JdbcConfigHistoryRepository` | 历史表读写 |
| 104 | `ConfigSnapshotMapper` | 快照序列化（依赖 `JsonOps`） |
| 111 | `ConfigValidator` | 保存前校验；`ContractValidator` 用 `ObjectProvider`（**可选**） |
| 123 | `ConfigAuditor` | 全量巡检（`maxConfigs` 来自 `ifmap.admin.audit-max-configs`） |
| 131 | `ConfigAdminService` | 业务编排 |
| 147 | `IfmapAdminWebConfigurer` | 统一路径前缀 + 静态资源映射 |
| 163 | `IfmapEnumCatalog` | 宿主字典（`ObjectProvider<IfmapEnumProvider>`，**不要求注册**） |
| 181 | `Jackson2ObjectMapperBuilderCustomizer` | **雪花 ID 序列化成字符串**（见 §6.4） |
| 190 | `IfmapUiController` | `/ui` → 302 到 `/ui/index.html` |
| 196 | `IfmapConfigAdminController` | `/configs` |
| 202 | `IfmapBranchAdminController` | `/branches` |
| 208 | `IfmapMetaAdminController` | `/rules` `/actions` `/strategies` `/enums` `/audit` |
| 221 | `IfmapAdminExceptionHandler` | 统一错误映射 |

全部 `@ConditionalOnMissingBean`：宿主要覆盖哪个就声明同类型 bean。

### 1.2 `IfmapAdminWebConfigurer`（73 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 37 | `UI_LOCATION = "classpath:/META-INF/ifmap-admin-ui/"` | 静态页**打包进 jar**，不依赖外部 CDN 或宿主静态目录 |
| 46 | `configurePathMatch` | 给本包的 Controller 统一加 `basePath` 前缀（`addPathPrefix(...)`），所以 Controller 里只写 `/configs` |
| 57 | `addResourceHandlers` | `{base}/ui/**` → `classpath:` 位置 |
| 65 | 资源路径拼装 | 前缀为空时退化成 `/ui/**` |

### 1.3 `IfmapAdminProperties`（96 行）

| 键 | 默认 | 说明 |
|---|---|---|
| `ifmap.admin.enabled` | **`false`** | 必须显式打开（安全默认） |
| `ifmap.admin.base-path` | `/ifmap/admin` | 归一化在 `normalizedBasePath:83`（去尾斜杠、补前导斜杠） |
| `ifmap.admin.history-limit` | `50` | 历史查询默认/最大条数 |
| `ifmap.admin.audit-max-configs` | `5000` | 单次巡检最大配置数（**防止把大表全量拉进内存**） |

### 1.4 `IfmapAdminExceptionHandler`（92 行）

**一个异常类型一个方法**（一个方法里做 `instanceof` 分支过不了 SpotBugs `BC_UNCONFIRMED_CAST`）：

| 行 | 异常 | 状态码 |
|---|---|---|
| 46 | `IfmapValidationException` | **422** + 完整违规清单 |
| 56 | `IfmapConfigException` 等运行时 | **400** |
| 62 | `IfmapRemoteException` | **502** |
| 69 | `MissingServletRequestParameterException` | **400**（客户端漏参数，**不打 ERROR 栈**） |
| 77 | `MethodArgumentTypeMismatchException` | **400**（如 `status=abc`） |
| 87 | 兜底 `Exception` | **500** + 打日志 |

### 1.5 `IfmapAdminHeaders`（33 行）

`OPERATOR_ID = "X-Operator-Id"`（26）/ `REQUEST_ID = "X-Request-Id"`（29）—— 落库到 `*_user_id` / `*_request_id`。

---

## 2. 业务层

### 2.1 `ConfigAdminService`（379 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 86 | `list(query)` | 分页查询 |
| 94 | `get(keyId)` | 不存在 → `IfmapConfigException` |
| 100 | `branches(...)` | 分支列表（可含已删除） |
| 108 | `history(keyId, limit)` | 历史视图（limit 走配置上限） |
| 122 | `validate(config, creating)` | **配置本身 + 该接口的分支集合**一起校验（两段结果 `merge`） |
| 132 | `dryRun(request)` | 见下 |
| 152 | `audit(tenantId, busiNode)` | 委托 `ConfigAuditor` |
| 160 | `create(...)` | `@Transactional`；校验 → `writer.insert` → 回读 → 记 CREATE 历史 → **失效缓存** |
| 180 | `update(...)` | 必须带 `expectedVersion`；缺租户时从 before 补；`writer.update` 返回 false = 版本冲突 |
| 209 | `delete(...)` | 软删 + 记 DELETE 历史（**历史里保留删除前快照**） |
| 226 | `changeStatus(...)` | 上下线，同样乐观锁；记 `ENABLE`/`DISABLE` |
| 252 | `rollback(keyId, historyId, ...)` | 见下 |
| 289 / 306 / 333 | `createBranch` / `updateBranch` / `deleteBranch` | 分支写操作 |
| 345 | `record(...)` | 统一写历史行（快照 + diff + 操作人 + 请求号） |
| 360 / 365 | `evict` / `evictByInterface` | 见下 |
| 374 | `requireBlank(value, message)` | **名字有坑**：实际是"必填"，`value` 为空才抛 |

**`dryRun`（132）的三个硬约束**：
1. `interfaceNo` 必须给，`busiNode` 必须给（`:136` 用上面那个名字反了的 `requireBlank`）；
2. 必须有 `IfmapOrchestrator`（`ObjectProvider` 取不到 → 明确抛"需要 DataSource"）；
3. `mockResponse` 空 → `"{}"`；**`orchestrator.execute(..., mock)` 传 mock 就不会出网**（03 篇 `executeOne:248-249` 的 mock 分支）。

**`rollback`（252）的安全点**：
- 必须带 `expectedVersion`；
- 历史记录必须**属于该配置**（`:259-261` 校验 `configKeyId`，防止拿 A 的历史覆盖 B）；
- 回滚后**也要过一遍 `validate`**（`:268`）—— 老快照可能已经不符合新校验规则；
- 最后记一条 `UPDATE` 历史，`changeReason = "回滚到历史 #N：原因"`。

**`evictByInterface`（365）**：只对 `CachingConfigRepository` 生效（`instanceof`），把 `(tenantId, interfaceNo)` 的缓存项删掉。
理由见 :364 注释 —— **改完立刻失效，否则 TTL 内引擎仍按旧配置跑**。

### 2.2 `ConfigValidator`（630 行）· 管理端最重的一块

`POOL_ROOT = "$.pool."`（57）。校验分七段，**顺序决定报错优先级**：

| 行 | 段 | 检查什么 |
|---|---|---|
| 93 | `validate(config, creating)` | 入口：必填字段 → 模板契约 → 判定规格 → **调用方向** → 路径 → 策略名 → 唯一键（`:102` 注释里的「下面六步」） |
| 115 | `validateBranches(tenantId, interfaceNo)` | 该接口下所有分支逐个校验 + 互斥检查 |
| 147 | `validateBranch(branch, creating)` | 单分支：必填 + `methodFlag` 是否存在 |
| 185 | `checkTemplateContract` | 复用 `ContractValidator`（与引擎启动期自检**同一口径**） |
| 203 | `checkJudgeSpec` | 复用 `ResultJudgeSpec.validate`（**三处口径同源**） |
| 236 | `checkCallDirection` | **被调方向专属**：3 个 error（应答模板空 / 请求模板非空 / 前序策略非空）+ 3 个 warning（判定是 `always` / 尚无逻辑分支 / 排在出网步骤之后）。`OUTBOUND` 完全不受影响 |
| 272 | `checkInboundIsFirstStep` | 同 `busiNode` 内存在 `interfaceOrder` 更小的**非 inbound** 行 → warning |
| 294 | `judgeIsAlways` | 解析失败返回 false（不重复报错，语法错由 `checkJudgeSpec` 管） |
| 303 | `checkTemplateJson(template, column, result)` | 模板必须是合法 JSON |
| 315 | `checkJsonPaths` | 模板里出现的每个 `$.xxx`，在**同类配置的请求模板**里要能找到来源 |
| 365 | `checkPoolRefs(config, requestPaths, result)` | **池引用静态校验**：`$.pool.X` 的 X 必须由**前序**步骤产出 |
| 419 | `poolRefName` | 从 `$.pool.xxx.yyy` 取出 `xxx` |
| 441 | `checkExpressions(template, column, result)` | 复用 `TemplateScanner.expressionProblems`（`@FUN(` 未注册、语法错） |
| 451 / 466 / 485 | `collectPaths` / `walk` / `collectFromText` | 从模板里抽 `$.` 路径（含文本里的） |
| 496 / 502 | `checkStrategyName` / `checkStrategy` | `pre_strategy_name` / `post_strategy_name` 必须在 `StrategyHookRegistry` 里 |
| 516 | `liveConfigs(tenantId, interfaceNo)` | 取该接口现存配置（判唯一键 / 判池引用都要看"别人") |
| 524 | `liveRows(query)` | 同上，走 `ConfigQuery` |
| 535 | `isSelf` | 编辑自己时排除自己（否则"和自己冲突"） |
| 551 | `checkUniqueKey(config, result)` | **同租户 + 同节点 + 同 `interface_order` 不能重复**（对应 `uk_config_order`） |
| 590 | `sameOrder` | null 安全比较 |
| 595 | `checkMethodFlag(row, result)` | 分支动作必须已注册（`ActionRegistry`） |
| 610 | `isDefaultBranch(row)` | `logic_branch_flag` 空 = 兜底分支 |
| 615 / 622 / 627 | `required` / `asTenantId` / `isBlank` | 小工具 |

> `checkCallDirection`（236）是**被调接口**新增的：所有规则都只在 `INBOUND` 下触发，存量 `OUTBOUND` 配置的行为零变化。
> 为什么不是"4 个 error"：`request_param_template` 非空在 INBOUND 下确实永不生效，属于 error；但 `result_judge=always`
> 与"无逻辑分支"只是**容易配错**，不是"必然不工作"，所以给 warning —— 硬凑 error 会让用户改配置改不动。

> `checkPoolRefs`（365）是**池引用**校验：`$.pool.X` 在运行时才能确认有没有值，但**静态就能确认"有没有前序步骤会产出 X"**。
> 这条校验把"配完跑一遍才发现池键拼错"提前到保存时。`poolRefName:419` 负责从 `$.pool.xxx.yyy` 里取出 `xxx`。

### 2.3 `ConfigAuditor`（129 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 48 | `audit(tenantId, busiNode)` | 全量巡检：逐配置 `validator.validate`，结果合并进 `AuditReport` |
| 88 | `loadAll(...)` | 分页加载；**超过 `maxConfigs` 就停下并置 `truncated=true`**（不静默截断） |
| 121 | `location(config)` | 报错定位串（"租户/接口#顺序"） |
| 126 | `isBlank` | — |

### 2.4 `ConfigSnapshotMapper`（247 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 51 | `toSnapshot(config)` | 配置 → JSON 快照 |
| 59 | `fromSnapshot(snapshot)` | 快照 → 配置（回滚用）；**兼容老格式**（`splitLegacyValues:137`） |
| 137 | `splitLegacyValues(expected)` | 老 `success_value` 是分隔串，拆成列表 |
| 165 | `diff(before, after)` | 字段级差异 → JSON（只记变化的字段） |
| 188 | `toMap(config)` | 配置 → `Map`（29 个字段，diff 的基础） |
| 210 | `parseJsonOrNull(json)` | 容错解析 |
| 221 / 225 / 229 / 243 | `equals` / `asString` / `asLong` / `asInt` | null 安全的类型归一化 |

### 2.5 `ValidationResult`（114 行）

`errors` + `warnings` + 可选 `contract`（`ContractReport`）+ `failOnContractViolations` 开关。

| 行 | 成员 | 说明 |
|---|---|---|
| 46 | `isPassed()` | `errors` 为空（**warnings 不影响通过**） |
| 62 / 69 | `error(msg)` / `warning(msg)` | 链式 |
| 77 | `merge(other)` | 合并另一份结果（配置校验 + 分支校验就用它） |
| 89 | `contract(report)` | 挂契约报告 |
| 95 | `failOnContractViolations()` | 打开后契约违规也算 error（由 `strict-types` 决定） |

### 2.6 `AuditReport`（120 行）

`configCount` / `branchCount` / `truncated` + `errors` / `warnings`；`toMarkdown()`（89）给「复制 Markdown 报告」用。

### 2.7 `AdminConfigRepository`（53 行）

只包了 `JdbcConfigRepository` 的薄壳（`get()` 返回 delegate）。存在理由：**让管理端与引擎侧对同一 bean 类型有不同
`@ConditionalOnMissingBean` 语义**（宿主可以只覆盖管理端那份，不动引擎的 `ConfigRepository`）。

---

## 3. DTO 与字典 SPI（9 文件 / 575 行）

| 文件 | 行 | 字段 |
|---|---|---|
| `dto/ApiError` | 64 | `error`（一句话）/ `type`（异常简名）/ `errors` / `warnings`；`isOk():45` |
| `dto/ConfigSaveRequest` | 56 | `config` + **`expectedVersion`** + `reason` |
| `dto/DryRunRequest` | 85 | `tenantId` / `interfaceNo` / `busiNode` / `bizId` / `params` / `mockResponse` |
| `dto/HistoryView` | 92 | `keyId` / `configKeyId` / `interfaceNo` / `changeType` / `changeReason` / `operatorId` / `requestId` / `addTime` / `snapshot` / `diff` |
| `dto/RollbackRequest` | 52 | `historyId` + `expectedVersion` + `reason` |
| `dto/StatusChangeRequest` | 52 | `status` + `expectedVersion` + `reason` |
| `dto/ValidateRequest` | 45 | `config` + **`isCreate`**（默认 `TRUE`，注意字段名是 `isCreate` 不是 `creating`） |
| `spi/EnumOption` | 73 | `value` / `label` + `equals`/`hashCode`（去重靠它） |
| `spi/IfmapEnumProvider` | 56 | 宿主字典 SPI：`Map<String, List<EnumOption>> options()` |

### 3.1 `IfmapBuiltinEnums`（95 行）· 框架自带的字典

| 行 | 成员 | 说明 |
|---|---|---|
| 52 / 55 / 58 | `STRATEGY_NAME_KEY = "strategyName"` / `STATUS_KEY = "status"` / `CALL_DIRECTION_KEY = "callDirection"` | 页面 `dict` 用的键名 |
| 85 | `strategies` | 策略名取自 `StrategyHookRegistry.keys()`（**不硬编码**），为空时**不放这个键** |
| 87 | `status` | 固定 `1 启用 / 0 停用` |
| 90 | `callDirection` | `OUTBOUND`＝「我方调对方（出网）」/ `INBOUND`＝「对方调我方（被调）」—— **值必须与枚举名一致**，否则存库再 `fromName` 会判非法 |
| 93 | `TreeSet` | **去重 + 排序**：下拉顺序不该随 bean 注册顺序抖动（同一份配置两次打开页面对不上） |

### 3.2 `IfmapEnumCatalog`（122 行）· 合并策略

| 行 | 成员 | 说明 |
|---|---|---|
| 57 / 66 | `of(provider)` / `of(provider, builtin)` | 工厂 |
| 80 | `isPresent()` | **只反映宿主 SPI 是否存在**（页面用它决定提示文案，不决定要不要给内置值） |
| 90 | `options()` | 先放内置字典，再 `putAll(宿主字典)` —— **同名 key 宿主覆盖，不是拼接** |
| 100 | `sanitize(raw)` | 防御性拷贝：null key / 空白 key / null 列表 / null 项全跳过，坏一项不影响其它字段 |

> 内置字典**不依赖** `isPresent()`：宿主没注册 SPI 时 `strategyName` / `status` 仍然有值，这是 A+C 修法的核心。

### 3.3 `IfmapValidationException`（46 行）

携带 `ValidationResult`（`getResult():36`），消息由 `buildMessage:40` 拼成一段可读文本。
`IfmapAdminExceptionHandler:46` 据此返回 **422 + 完整违规清单**。

---

## 4. Web 层（4 Controller / 464 行）

全部以 `@RequestMapping("/configs")` 这类**相对路径**声明，前缀由 `IfmapAdminWebConfigurer.configurePathMatch` 统一加。

### 4.1 `IfmapConfigAdminController`（198 行）

| 行 | 端点 | 说明 |
|---|---|---|
| 69 | `GET /configs` | 分页列表；8 个查询参数**全部 `required = false`** |
| 91 | `GET /configs/{keyId}` | 单条 |
| 102 | `POST /configs/validate` | 保存前校验（不落库） |
| 116 | `POST /configs/dry-run` | 试跑（不出网） |
| 122 | `POST /configs` | 新增 |
| 136 | `PUT /configs/{keyId}` | 修改（乐观锁） |
| 152 | `DELETE /configs/{keyId}` | 逻辑删除（`reason` 走 query） |
| 163 | `POST /configs/{keyId}/status` | 启用/停用 |
| 179 | `GET /configs/{keyId}/history` | 变更历史 |
| 186 | `POST /configs/{keyId}/rollback` | 回滚 |

> **口径铁律**：`@RequestParam` 一律 `required = false`，必填校验放在 service 里，客户端错误由
> `IfmapAdminExceptionHandler` 映射成 **400 + WARN**。用 `required = true` 会让 Spring 在进方法前就抛
> `MissingServletRequestParameterException`，此前正是这么出了一个 **500**。

### 4.2 `IfmapBranchAdminController`（95 行）

`GET /branches`（55）/ `POST`（67）/ `PUT /{keyId}`（78）/ `DELETE /{keyId}`（90）。

### 4.3 `IfmapMetaAdminController`（119 行）

| 行 | 端点 | 返回 |
|---|---|---|
| 78 | `GET /rules` | `List<RuleDescriptor>`（规则名 / 签名 / 说明 / 示例） |
| 84 | `GET /actions` | `Set<String>`（动作 key） |
| 90 | `GET /strategies` | `{strategyHooks, fullParams, logicBranches, actions}`（**key 名与前端 `dictOptions` 约定一致**） |
| 107 | `GET /enums` | 宿主字典；未注册时返回 `{}` 而**不是 404**（页面只需一条普通分支） |
| 113 | `GET /audit` | `AuditReport`；`markdown=true` 时返回 `{markdown: "..."}` |

### 4.4 `IfmapUiController`（52 行）

`GET /ui` 与 `/ui/` → **302** 到 `{basePath}/ui/index.html`（46–51）。

---

## 5. 内嵌 UI

### 5.1 `index.html`（177 行）

四个 tab（30–34）：**配置** / **逻辑分支** / **巡检** / **规则与字典**。

| 区块 | 行 | 要点 |
|---|---|---|
| 顶栏 | 22–27 | `base-path-label` + 操作人工号输入（`#operator`，只在前端存 localStorage） |
| 配置筛选 | 40–51 | 租户/接口编号/业务节点/**合作机构代码**/状态/含已删除 + 查询 |
| 配置列表 + 分页 | 54–55 | — |
| 配置编辑 | 59–82 | `#editor-fields`（由 `CONFIG_FIELDS` 动态生成）+ 原因输入 + 6 个按钮（校验/试跑/历史/启停/删除/保存）+ `#editor-messages` |
| 试跑面板 | 86–98 | 租户/接口/节点/业务主键/params/mock |
| 分支页 | 103–135 | 列表 + 编辑（`#branch-fields`） |
| 巡检页 | 140–148 | 运行 + 复制 Markdown |
| 元数据页 | 154–165 | 规则表 / 动作 / 策略 / 字典四个容器 |

**没有「新建配置」按钮在筛选行**：`#btn-config-new` 在 `.card-head` 里、默认 `hidden`，只在编辑态显示且改名「清空表单」
（此前它和筛选行的「查询」重复，已修）。

### 5.2 `app.js`（1,199 行）· 按功能分段

| 行 | 函数 | 说明 |
|---|---|---|
| 34–60 | `el` / `esc` / `text` / `json` / `setHtml` | DOM 与转义小工具（**`esc` 是所有输出必经之路**） |
| 62 | `query(params)` | 拼 query string（跳过空值） |
| 76 | `toast(message, kind)` | 轻提示 |
| 90 / 96 | `operator()` / `requestId()` | 两个 header 的来源 |
| 100 | `errorMessage(status, payload, raw)` | 把后端 `ApiError` 翻译成一句人话 |
| 112 | `api(method, path, body)` | **唯一的网络出口**：统一拼 basePath、带两个 header、非 2xx 抛错 |
| 146 | `fail(error)` | 统一失败展示 |
| 155 / 165 / 174 | `table` / `chips` / `statusLabel` | 渲染辅助 |
| 185 | `dictOptions(key)` | **字典来源**：`'#actions'` → `META.actions`；其余 → `META.enums[key]` |
| 221 | `judgeSpecParts(raw)` | 判定规格 → 「三选一」表单状态 |
| 253 | `judgeOpOptions(current)` | 条件算子选项（与后端 `JudgeOp` 顺序一致） |
| 259 | `judgeFieldHtml(prefix, spec, value)` | 判定编辑器 |
| 300 / 306 / 326 / 354 | `judgeMode` / `judgeHint` / `syncJudgeEditor` / `readJudgeSpec` | 判定编辑器的状态机 |
| 383 | `fieldHtml(prefix, spec, value)` | **单个表单项渲染**（见下） |
| 419 | `readFields(prefix, specs)` | 表单 → 对象（`type:'number'` 空串 → null） |
| 436 | `messages(id, result)` | 422 违规清单渲染 |
| 450 | `output(id, title, value, kind)` | 试跑/巡检结果渲染 |
| 497 / 510 | `configQuery()` / `loadConfigs(page)` | 列表查询 |
| 521 | `renderConfigList()` | 表格渲染（表头「合作机构」口径） |
| 542 | `renderPager()` | 分页 |
| 565 | `renderEditor(config)` | 编辑区（`CONFIG_FIELDS` 动态生成） |
| 594 | `currentConfigPayload()` | 表单 → 保存请求体 |
| 604 | `needCurrent(action)` | 未选中配置时统一提示 |
| 612 / 629 / 663 | `validateConfig` / `saveConfig` / `loadConfig` | 校验 / 保存 / 载入 |
| 670 | `dryRun()` | 试跑 |
| 697 | `valueOf(id, fallback)` | 取值兜底 |
| 703 / 720 | `loadHistory` / `rollback` | 历史与回滚 |
| 745 / 769 | `toggleStatus` / `deleteConfig` | 启停 / 删除 |
| 787 | `loadBranches()` | 分支列表（**空参守卫**：接口号为空时不发请求） |
| 819 / 839 / 877 | `renderBranchEditor` / `saveBranch` / `deleteBranch` | 分支写 |
| 894 / 920 / 933 | `runAudit` / `copyAuditMarkdown` / `copyText` | 巡检与复制 |
| 951 / 967 / 981 | `loadMeta` / `renderMeta` / `renderEnums` | 元数据（`/rules` `/actions` `/strategies` `/enums`） |
| 998 / 1021 / 1095 / 1154 | `bindTabs` / `bindConfigs` / `bindBranches` / `bindAudit` | 事件绑定 |
| 1163 | `init()` | 入口：读 basePath → 绑定 → 加载元数据 → 加载列表 |

**`fieldHtml`（383）的三条分支**：

| 类型 | 渲染 |
|---|---|
| `type:'judge'` | 交给 `judgeFieldHtml` |
| `type:'textarea'` | `<textarea rows=4 spellcheck=false>` |
| `spec.dict` 且 `dictOptions()` **非空** | `<select>`，并给"字典未覆盖的当前值"补一个 `(字典未覆盖)` 选项（`extra`，:401）——否则编辑老数据时下拉会落到第一项，保存即静默改值 |
| `spec.dict` 但 `dictOptions()` **为空** | **退化成普通 `<input>`**（:411-415；注释写死了理由：只剩一个「(空)」选项等于把字段锁死） |

**`CONFIG_FIELDS`（461–481，19 项）**：`tenantId` / `interfaceNo` / `interfaceCode` / `interfaceName` / `projectCode` /
`busiNode` / `partnerCode`(dict) / `partnerName` / `financingMode`(dict) / `interfaceOrder` / **`callDirection`**(dict) / `judgeName` /
`resultJudge`(judge) / `preStrategyName`(dict:strategyName) / `postStrategyName`(dict:strategyName) / `status`(dict) /
`remark` / `requestParamTemplate` / `responseParamTemplate`；新建默认值 `NEW_CONFIG_DEFAULTS`（`:495`）里 `callDirection: 'OUTBOUND'`。
列表多一列「方向」（`directionLabel`，`:546`：`INBOUND` → 被调，其余 → 出网）。

**`BRANCH_FIELDS`（483–492，8 项）**：`tenantId` / `interfaceNo` / `methodFlag`(dict:`#actions`) / `logicBranchName` /
`logicBranchFlag` / `logicBranchValue` / `logicBranchOrder` / `remark`。

**`JUDGE_*` 常量（203–211）**：`JUDGE_OPS`（`eq/ne/in/notIn/exists/notBlank/regex`，与后端 `JudgeOp` 同序）、
`JUDGE_LIST_OPS`（`in`/`notIn` 的 value 是数组）、`JUDGE_NO_VALUE_OPS`（`exists`/`notBlank`）、
`JUDGE_ALWAYS = '{"op":"always"}'`。

### 5.3 `app.css`（168 行）

| 行 | 规则 | 说明 |
|---|---|---|
| 56 | `.inline` | label + 控件同一行 |
| 98 | `.fields` | `display:grid` + `repeat(auto-fill, minmax(320px,1fr))` |
| 104–107 | 三条约束 | `.fields` 子项 `min-width: 0`、**不允许 `nowrap` 撑破格子**（此前正是它把"合作机构代码"一行挤掉） |
| — | `grid-column: 1 / -1` | 判定编辑器与多行模板跨整行 |

### 5.4 静态资源热读盘

`IfmapAdminWebConfigurer.addResourceHandlers` 走 `classpath:` → Spring 的 `ClassPathResource` 每次请求**重新读文件**，
所以改 `app.js` / `app.css` 后 **Ctrl+F5 即时生效**；但改 Java 类必须重启 JVM。

---

## 6. 四个容易被误判的设计

### 6.1 为什么前端只有「三选一」的判定编辑器

`judgeSpecParts`（221）只把 `always` 与**单条件** `{"all":[条件]}` 表达成可点选的表单；
嵌套 `any`、多条件一律落到 `raw` 模式并**明确提示"非法态"**。

理由写在 :213-219 的注释里：**静默丢条件 = 判定变宽**，是最难查的一类事故。宁可让人肉眼看到 JSON。

### 6.2 为什么 `/enums` 未注册时返回 `{}` 而不是 404

页面只需要一条"没有枚举"的普通分支（`renderEnums:981` 就是这句），不用区分两种失败。
配套的是 `fieldHtml` 的空字典降级 —— **文档承诺"退化成输入框"，代码必须真的退化**。

### 6.3 为什么 `dictOptions` 的 `'#actions'` 是个特例

分支的 `methodFlag` 来源是 `ActionRegistry`（`/actions`），不是宿主字典。用一个以 `#` 开头的伪 key
把它和 `META.enums` 分开，规范里写清楚；`#` 开头的 key 不可能出现在真实字段名/字典键里，不会冲突。

### 6.4 为什么雪花 ID 必须序列化成字符串

`JS` 的 `Number` 只有 53 位精度，而雪花 ID 是 64 位（`899855950645559300`）。直接下发 number 会被前端舍入，
回传时就成了另一个 ID（此前实测 **400 配置不存在**）。

`JsSafeLongSerializer`（53 行）+ `ifmapAdminJsSafeLongCustomizer`（181）的做法是：**超过 `2^53-1` 才写成字符串**，
小整数保持 number（避免 `status: "1"` 这种无谓的字符串污染）。

两条配套约束：
- 定制器只加在 **admin 自动配置**里，`ifmap-core` 零 Jackson 依赖；
- **前端禁止 `Number(keyId)`**（会再舍入一次）；
- 契约测试必须断言**原始 JSON 字符串**（`assertTrue(json.contains("\"899855950645559296\""))`），用对象断言会看不到。

---

## 7. 测试（11 文件 / 2,382 行）

| 文件 | 行 | 覆盖 |
|---|---|---|
| `ConfigAdminServiceTest` | 411 | 增删改查 / 乐观锁 / 回滚 / 缓存失效 / dry-run 参数校验 |
| `ConfigValidatorTest` | 479 | 七段校验的每条规则（含池引用静态校验、唯一键、分支互斥、被调方向 3 error + 3 warning） |
| `IfmapAdminWebEndpointTest` | 341 | 全部端点的状态码与返回体（含 400/422 分支） |
| `IfmapAdminUiScriptTest` | 254 | 静态契约测试：`app.js` 里必须存在的函数/常量、按钮清单、**不允许出现的写法** |
| `IfmapAdminAutoConfigurationTest` | 169 | 五个条件的行为 + `@ConditionalOnMissingBean` 可覆盖 |
| `IfmapEnumCatalogTest` | 177 | 内置字典（`status` + `callDirection`）+ 宿主覆盖 + 空字典 |
| `ConfigSnapshotMapperTest` | 144 | 快照往返 / 老格式兼容 / diff |
| `AdminTestSupport` | 145 | 测试基座 |
| `IfmapAdminUiEndpointTest` | 140 | `/ui` 302 与静态资源可取 |
| `ConfigAuditorTest` | 121 | 巡检 / 截断标记 |
| `AdminTestApplication` | 81 | 集成测试启动类（`cn.cj.ifmap.ittest`） |

> `IfmapAdminUiScriptTest` 是**没有 node 环境时的替代品**：既然不能 `node --check`，就用 Java 断言
> 函数名/常量/关键字符串存在。写 UI 时改函数名要同步改它。

---

## 8. 看起来多余但别删的点

| 点 | 位置 | 为什么保留 |
|---|---|---|
| 内嵌 UI 整个目录 | `META-INF/ifmap-admin-ui/` | **硬约束：内网离线可用，零 CDN / 零框架 / 零构建链**。换成前端工程就要引入 node + 构建产物 |
| `AdminConfigRepository` 薄壳 | `:33` | 让管理端与引擎的 `ConfigRepository` 有各自的可覆盖点 |
| `IfmapAdminExceptionHandler` 六个方法 | `:46-92` | 一个方法里 `instanceof` 分支过不了 SpotBugs `BC_UNCONFIRMED_CAST` |
| `IfmapUiController` 只做 302 | `:46` | 不带尾斜杠的 `/ui` 必须能进；静态页本身交给资源处理器 |
| `dictOptions` 的 `'#actions'` 特例 | `:189` | `methodFlag` 不是字典字段，来源是 `ActionRegistry` |
| `fieldHtml` 的「字典未覆盖」补选项 | `:401` | 不补的话，编辑老配置时下拉框会显示第一项，保存即**静默改值** |
| `fieldHtml` 空字典退化成 input | `:411-415` | 只剩「(空)」的 select = 字段被锁死，用户连手输都做不到 |
| `judgeSpecParts` 的 raw 模式 | `:221` | 静默丢条件 = 判定变宽，最难查 |
| `CONFIG_FIELDS` / `BRANCH_FIELDS` 数组 | `:461/:483` | 字段清单的**唯一来源**；`renderEditor`、`readFields`、`currentConfigPayload` 共用 |
| `IfmapAdminWebConfigurer.configurePathMatch` | `:46` | 让 Controller 只写相对路径；改前缀不用改 4 个 Controller |
| `JsSafeLongSerializer` 只对大整数生效 | `:53` | 全量字符串化会让 `status`/`order` 变成字符串，宿主 JSON 消费方会坏 |
| `ConfigValidator.checkPoolRefs` | `:365` | 把"池键拼错"从运行期提前到保存期 |
| `ConfigValidator.liveConfigs` | `:516` | 唯一键与池引用的校验都必须看"这个节点下别人怎么配的" |
| `ConfigAuditor` 的 `truncated` 标记 | `:64` | 截断必须让调用方知道，否则"巡检通过"是假绿 |
| `requireBlank` 这个名字 | `ConfigAdminService:387` | 命名确实误导（实际是必填校验），但**改名前先确认没被外部引用**；当前只在 dry-run 用一次 |
