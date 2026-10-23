# 09 · 两个 demo 与 testkit

三个"非产品"模块，但每个都承担了对外演示/测试基础设施的职责：

| 模块 | 文件（含 pom） | 行数 | 定位 |
|---|---|---|---|
| `ifmap-demo-pure-java` | 4 | 219 | **零 Spring** 的引擎用法演示（证 core 零依赖） |
| `ifmap-demo-spring-boot3` | 13 | 1,064 | 引入 starter 的**完整编排**用法（出网 + 被调两个方向）+ 管理端宿主 |
| `ifmap-testkit` | 5 | 1,217 | 真库/内存库测试基座 + **自动清理** |

---

## 1. `ifmap-demo-pure-java`（130 行主类 + 2 个资源）

**为什么存在**：`ifmap-core` 号称零 Spring / 零 JSON 库依赖 —— 这个 demo 就是那条承诺的**证据**：
`pom.xml`（43 行）只依赖 `ifmap-core` + `ifmap-json-jackson` + `h2`（test）。

### 1.1 `IfmapQuickStart`（130 行）五步演示

| 行 | 步骤 | 说明 |
|---|---|---|
| 39 | banner 1 | `IfmapEngine.createDefault()` —— `ServiceLoader` 自动发现 `JsonOps`，自动注册 **18 个内置规则** |
| 47 | banner 2 | `engine.validateTemplate(...)` 启动期校验（规则名写错在这里就炸，不用等运行） |
| 51 | banner 3 | `engine.render(template, source, context)` |
| 59 | banner 4 | **故意写错规则名** `@FUN(farmatDate,...)`，断言抛 `StartupValidationException`（`:62` 是"这行不该被打印"的反向断言） |
| 69 | banner 5 | `engine.describeRules()` 输出规则清单 |
| 79 | `pretty(json)` | 极简 JSON 美化（**不引第三方库**，因为这个 demo 的卖点就是"依赖极少"） |
| 113 | `readResource(path)` | 读 classpath 资源，缺失直接抛 |

> banner 4 的注释（:64）写的是产品的核心卖点：**存量引擎遇到错规则名会静默返回 null 或把 `@FUN` 原文回写，
> ifmap 直接阻断启动**。

### 1.2 两个演示资源

| 文件 | 行 | 覆盖的 DSL 记号 |
|---|---|---|
| `demo/template.json` | 29 | `$.path` 直取 / `@FUN(...)` 5 类规则（`dateFormat` `dateEndOfMonth` `workdayAdd` `numSum` `numFormat` `dictVal` `strDefault` `strMask` `concat` `numOffset` `numRound`）/ `key@array`（单值转数组）/ `key@array@$.items`（**列转行**）/ `key@array` 对象形式（**行转列**，靠 `$.items[*].sku` 取整列）/ `$.items.length()` / null 值 |
| `demo/source.json` | 17 | 嵌套对象、数字数组含**字符串元素**（`"99.99"`）、`remark: null` |

`items@array@$.items` 这个写法是模板引擎里**最难理解**的一处，这里给了可运行样例。

---

## 2. `ifmap-demo-spring-boot3`（9 个 Java 主类 + 2 个 yml + 1 个测试）

### 2.1 `IfmapDemoApplication`（35 行）

标准 `@SpringBootApplication`，无额外配置。启动后：starter 建表 → `DemoRunner` 写演示配置 → 渲染 → 编排 → 退出。

### 2.2 `BankRules`（43 行）· 自定义规则

`@Component` + `@IfmapRule(value="bankOrgNo", desc=..., example=...)`。
**方法上不用手写 `registry.register(...)`** —— `IfmapRuleRegistrar` 扫 bean 自动收集（07 篇 §3.4）。

### 2.3 `DemoPartnerGateway`（38 行）· 外呼网关

只实现 `exchange(PartnerCall)`（`:34`），直接返回固定响应。演示"网关是最简单的一个 SPI"。

### 2.4 `DemoStrategy`（61 行）· 一个类实现两个策略接口

| 行 | 成员 | 关键点 |
|---|---|---|
| 43 | `@FullParam(partnerCode = "CMB", busiNode = "apply")` | 整包组包策略按 `(partnerCode, busiNode)` 注册 |
| 48 | `apply(StrategyContext)` | `IfmapStrategyHook`：**按 bean 名注册**（类 `DemoStrategy` → bean 名 `demoStrategy`，配置里 `pre_strategy_name` 直接写它） |
| 56 | `assemble(StrategyContext)` | `FullParamStrategy`：（这里的实现与 `apply` 不同，正好演示两者语义差别） |

> 一个 bean 同时实现多个策略接口是被允许的：`IfmapStrategyRegistrar` 按接口逐个注册。

### 2.5 `DemoSubmitAction`（44 行）· 逻辑分支动作

`@IfmapAction("submit")` + `execute(StrategyContext)`。注解值是 `ifmap_logic_branch_config.method_flag`
（**不再按方法名反射**，方法改名不会静默失效）。

### 2.6 `InboundNoticeDemo`（213 行）· 被调方向演示例

`@Component @Order(2) implements ApplicationRunner`，与 `DemoRunner`（`@Order` 更小，先跑）**互不干扰**：
另一个业务节点（`notice`）、另一个接口编号（`BIZ_NOTICE`）、另一个租户内的一组表行。

| 行 | 成员 | 说明 |
|---|---|---|
| 65–67 | 常量 | `TENANT="1001"` / `INTERFACE_NO="BIZ_NOTICE"` / `BUSI_NODE="notice"` |
| 78 | `ACK_TEMPLATE` | 应答模板，根是**对方原文**：`$.applyNo` / `$.['seqNo']` 取对方字段，`"0000"` 是字面量 |
| 91 | `INBOUND_JUDGE` | `{"any":[eq SUCCESS, eq FAILED]}` —— 判定口径是「报文**可受理**」，不是「业务成败」 |
| 107 | `run(...)` | 播种 + 三个场景 |
| 136 | `call(scene, rawBody)` | **唯一入口** `executeInbound(...)`；`ackOf` 取不到东西就说明被判失败了 |
| 162 | `ackOf(result)` | 池里有没有本步产物 = 有没有应答（判失败的步骤不入池） |
| 172 | `seedIfAbsent()` | 幂等：先查再插 |
| 178 | `setCallDirection(CallDirection.INBOUND)` | + `interfaceOrder=1` + 请求模板/前序策略留空 |
| 200 | `insertBranch(...)` | 两条分支：`branch_judge` = `any[$.noticeStatus eq SUCCESS]` → `noticeAccepted`，`eq FAILED` → `noticeFailed`（`branch_judge` **必填**，兜底才写 `always`） |

三个场景覆盖了被调方向的三个终态（`docs/12` §7）：

| 场景 | `noticeStatus` | 判定 | 分支（`matchedBranch`） | 应答 |
|---|---|---|---|---|
| 成功通知 | `SUCCESS` | 过 | `受理分支` | `0000` + 动作补 `approveNo` |
| 失败通知 | `FAILED` | 过（业务失败也是有效推送） | `失败分支` | 动作把 `code` 覆盖成 `2001` + `retryable` |
| 状态不认识 | `PENDING` | **不过** | 不执行 | **没有**（宿主自己回 `1xxx`，引擎不兜底） |

> 应答模板里 `"partnerSeqNo":"$.['seqNo']"` 与 `"ourSeqNo":"$.seqNo"` 并列**不是笔误**：
> `$.seqNo` 是模板 DSL 的保留路径（生成我方流水号，不看源报文），取源报文同名字段必须写方括号形式
> `$.['seqNo']`；少写那个点（`$['seqNo']`）会被当成**字面量**原样输出。这条坑已记进 `docs/12` §9 第 12 条。

### 2.7 `NoticeAcceptedAction`（58 行）· 受理分支动作

`@Component @IfmapAction("noticeAccepted")`。`execute` 里把 `context.getResponseData()` 强转成
`Map<String,Object>` **原地 `put`** —— 这个 Map 与池里、返回值里、日志里的是**同一个对象**，
所以 `approveNo` 补上去以后三处同时可见。重新赋值（`data = new HashMap<>()`）则完全无效（`docs/12` §6）。

### 2.8 `NoticeFailedAction`（61 行）· 处理失败分支动作

`@IfmapAction("noticeFailed")`。把模板里的 `code=0000` **覆盖成 `2001`**，并加 `retryable=true`。

> 演示的重点是「**业务失败不要抛异常**」：动作在 `writeLog` 之前执行，抛出去会带走执行日志，
> 而且宿主拿不到应答 Map（表现为对方超时 + 我方无日志）。见 `docs/12` §9 第 10 条。

### 2.9 `DemoRunner`（141 行）· 完整链路演示例

| 行 | 成员 | 说明 |
|---|---|---|
| 45–47 | 常量 | `TENANT="1001"` / `INTERFACE_NO="BIZ_APPLY"` / `BUSI_NODE="apply"` |
| 49 | `REQUEST_TEMPLATE` | 5 个字段：`bankOrgNo`（自定义规则）/ `$.applyNo` / `dateFormat` / `strMask(NAME)` / `$.channelCode`（**由策略钩子补**） |
| 57 | `RESPONSE_TEMPLATE` | `{"applyNo":"$.data.applyNo"}` |
| 73 | `run(args)` | 主流程 |
| 76–80 | 查配置 + 查分支 | 顺带验证"表已由 starter 自动建好"与缓存装饰器生效 |
| 88 | 仅渲染 | 不经编排，单独演示 `engine.render` |
| 92–102 | **完整编排** | `orchestrator.execute(request, interfaceNo, busiNode)` |
| 108 | `seedIfAbsent()` | **幂等写入**：先查再插，重复启动不插重 |

`seedIfAbsent` 里几个值得抄的细节：
- `:123` `resultJudge` 写成 `{"all":[{"path":"$.resultCode","op":"eq","value":"0000"}]}`；
- `:125` `setPreStrategyName("demoStrategy")` —— **bean 名首字母小写**，写类名会找不到；
- `:130-139` 分支：`methodFlag="submit"` + `logicBranchName="成功分支"` + `branchJudge`（`{"any":[{"path":"$.resultCode","op":"eq","value":"0000"}]}`）——动作键、分支名、判定规格三者各自含义不同，见 03 篇。

### 2.10 两个 yml

**`application.yml`（30 行）· 默认档（H2 内存库）**

| 行 | 键 | 说明 |
|---|---|---|
| 8 | `spring.main.web-application-type: none` | **CLI 示例**：admin starter 虽在 classpath，这里显式关掉内嵌容器 → `DemoRunner` 跑完 JVM 退出（`docs/08` 与设计文档承诺的 `exit=0`） |
| 11 | `spring.datasource.url` | H2 `MODE=MySQL`（让 `KEY idx_...` / `tinyint(1)` 能解析） |
| 17 | `ifmap.table-prefix: ifmap_` | 注释：复用存量表改成 `bankint_` |
| 19 | `ifmap.ddl.auto: true` | 启动建表 |
| 20–26 | `id-strategy` / `worker-id` / `null-policy` / `cache.*` | 一份最小可跑的配置 |

**`application-mysql.yml`（42 行）· 真库档**

| 行 | 键 | 说明 |
|---|---|---|
| 1 | 用法 | `--spring.profiles.active=mysql` |
| 21–22 | `server.port: 8090` | 本档**兼做管理端宿主**，端口避开 8080/8081 |
| 26–27 | `web-application-type: servlet` | **覆盖**默认档的 `none`，起内嵌 Tomcat |
| 29 | `spring.datasource.url` | `${IFMAP_DB}` 只给"库地址这一截"；`useUnicode` / `useSSL` / `allowPublicKeyRetrieval` / **成对时区** |
| 30–31 | 用户名密码 | `${IFMAP_JDBC_USER}` / `${IFMAP_JDBC_PASSWORD}`，**绝不写进仓库** |
| 36 | `ifmap.table-prefix: ifmap_` | 一套前缀一套表，多环境可共用一个库 |
| 38 | `ifmap.ddl.auto: false` | **生产由 DBA 执行 SQL**（用户明确要求保留这个值） |
| 41 | `ifmap.admin.enabled: true` | REST 在 `/ifmap/admin`，页面 `/ifmap/admin/ui/` |

> yml 头部 9–16 行是这份文件最有价值的部分：`serverTimezone` 与 `sessionVariables=time_zone` **必须成对**，
> 且 `+` 在 URL 里会被解码成空格 → 必须写 `%2B08:00`（两处）。
> 这段注释就是 `CannotGetJdbcConnectionException` 那次的结案记录，**别删**。

### 2.11 `IfmapDemoApplicationTest`（213 行，8 例）

用 H2 起完整上下文。出网方向 4 例：建表、`DemoRunner` 幂等、规则收集与整链路渲染；
被调方向 4 例：

| 行 | 用例 | 断言要点 |
|---|---|---|
| 135 | `inboundDemoSeeded` | `CallDirection.INBOUND` / `interfaceOrder=1` / 请求模板 null / 前序策略空串 / 分支 2 条 |
| 153 | `inboundAcceptedFillsAck` | 判定过 + `matchedBranch="受理分支"` + `partnerSeqNo=SEQ-9001`（方括号路径）+ `ourSeqNo` 20 位 + 动作补的 `approveNo` |
| 168 | `inboundRejectedOverwritesAck` | 业务失败**仍然** `isSuccess()`，`matchedBranch="失败分支"`，`code=2001`、`retryable=true` |
| 180 | `inboundUnknownStatusHasNoAck` | `VALUE_MISMATCH` + `matchedBranch=null` + 池里没有本步产物 |

> 前序策略那一条断言写成"null 或空串"：列定义是 `NOT NULL DEFAULT ''`，写 `null` 会被库兜成空串，
> 两者在引擎里等价（都表示"没配"）—— 不要用 `assertNull` 把它写死。

---

## 3. `ifmap-testkit`（822 + 48 行主代码）

### 3.1 它解决的问题

真库测试必须"用完即清"，否则 CI 门禁会残表。`TestDatabases` 提供**一档两态**：

| 档位 | 触发 | 库 | 表 |
|---|---|---|---|
| H2 档（默认） | 未设 `IFMAP_JDBC_URL` | `jdbc:h2:mem:ifmap_testkit_<n>`，`MODE=MySQL` | 前缀 = hint 本身 |
| 真库档 | 设了 `IFMAP_JDBC_URL` | 真实 MySQL | 前缀 = `itt_<hint≤18>_<hex8>_` |

两个环境变量（87/90/93）：`IFMAP_JDBC_URL` / `IFMAP_JDBC_USER` / `IFMAP_JDBC_PASSWORD`。

### 3.2 `TestDatabases`（822 行）成员表

| 行 | 成员 | 说明 |
|---|---|---|
| 84 | 类 | `final` + 私有构造（纯静态工具） |
| 101 | `PRODUCTION_DDL` | 4 个**生产**建表脚本（与 starter 的 `IfmapSchemaInitializer` **读同一份文件**） |
| 110 | `ARCHIVE_DDL` | 归档冷表脚本（可选运维产物） |
| 113 | `TEST_PREFIX = "itt_"` | 与生产前缀 `ifmap_` 天然不冲突 |
| 116 | `TEST_PREFIX_PATTERN` | `^itt_[a-z0-9_]{0,18}_[0-9a-f]{8}_$` |
| 119 | `TEST_PREFIX_HEAD` | 表名（= 前缀 + 后缀，后缀里也有 `_`）的开头匹配 |
| 126 | `STALE_MINUTES = 30` | 残留表"陈旧"阈值 |
| 131 | `PREFIX_CACHE` | hint → 前缀（同一 hint 在同一 JVM 固定） |
| 134 | `H2_URL_CACHE` | hint → H2 URL（同一 hint 同库） |
| 137 | `MANAGED` | **本 JVM 创建过、待清理**的前缀 |
| 140 / 141 | `HOOK_INSTALLED` / `SWEPT` | 只装一次钩子 / 只扫一次残表 |
| 151 | `mysqlEnabled()` | 真库档开关 |
| 186 | `prefixFor(hint)` | **幂等重登记**（见下） |
| 213 | `dataSource()` | 裸数据源，**不登记**（诊断用） |
| 218–243 | `fresh` / `freshWithTables` / `freshWithArchive`（各带 hint 重载） | 返回 `Schema`（数据源 + 前缀必须配套） |
| 247 | `build(hint, ddl)` | 内部组装 |
| 260 | `applyProductionDdl(ds, prefix)` | 建全部生产表；真库档**先按前缀删干净再建**（走 `dropTables` 而不是 `drop`） |
| 271 | `applyOne(...)` | 单表：读 DDL → 前缀替换 → 平台适配 → 执行 |
| 282 | `splitStatements(ddl)` | 按分号切语句（跳过注释） |
| 319 | `toH2Statement(...)` | json 列降级 |
| 337 | `execute(ds, ddl)` | 执行 |
| 351 / 375 | `springProperties(hint)` / `springPropertyArray(hint)` | 给 `@SpringBootTest(properties=...)` 用 |
| 388 | `read(classpath)` | 读 SQL，缺失即断言失败 |
| 407 / 416 | `toMysql` / `toH2` | 前缀替换 + 平台适配（**列类型原样保留**，除 json 降级） |
| 435 | `downgradeJsonColumns(ddl, expected)` | 断言替换数量 == `expected`（防漏替换） |
| 457 | `expectedJsonColumns(classpath)` | 统计 DDL 里 `json` 列个数 |
| 461 | `normalize(ddl)` | 去 `--` 注释行 + 去尾分号 |
| 468 | `drop(prefix)` | **删表 + 注销登记** |
| 484 | `dropTables(prefix)` | **只删表、不动登记**（关键区别，见 §3.3） |
| 505 | `sweepStaleTestTables(minutes)` | 按 `information_schema` 的 `CREATE_TIME` 扫陈旧残表 |
| 540 | `sweepStaleOnce()` | 每次 JVM 只扫一次 |
| 552 | `cleanupAll()` | 清理 `MANAGED` 里全部前缀 |
| 567 | `dropAllTestTables()` | 无差别删所有 `itt_` 表（手工兜底） |
| 583 | `tablesWithPrefix(prefix)` | 列表 |
| 603 | `requireManagedPrefix(prefix)` | **防误删**：前缀不合规直接抛 |
| 609 | `installShutdownHook()` | 注册退出钩子（`ifmap-testkit-cleanup` 线程） |
| 627 / 637 | `h2DataSource` / `mysqlDataSource` | 数据源 |
| 655 | `mysqlUrl()` | **缺省参数补齐**（见下） |
| 684 | `timeZoneParam()` | 当前 JVM 偏移 |
| 692 | `sanitizeHint(hint)` | 小写化 + 去非法字符 + 截断到 18 |
| 710 | `hex8()` | 8 位随机十六进制 |
| 714 | `env(name)` | 环境变量 |
| 724 | `main(args)` | **手工清理入口**：`IFMAP_JDBC_URL=... java -cp ... TestDatabases` |
| 736 | `Schema` | `dataSource()` / `prefix()`（真库档给 `itt_..._`） |
| 762 | `SimpleDataSource` | 极简 `DataSource`，**刻意不依赖 spring-jdbc**（避免把 Spring 5.3 传进 SB3 模块） |

### 3.3 三条踩过坑才加上的设计

**① `prefixFor` 命中缓存时必须幂等重登记（:191-199）**

`cleanupAll()` / `drop()` 会把前缀从 `MANAGED` 摘掉，但 `PREFIX_CACHE` 里的 hint → 前缀映射还在。
若命中缓存就直接 return，之后用同一 hint 建出来的表**再也没人清**。

> 这条只在"某个会调 `cleanupAll()` 的测试类跑在前"的顺序下触发 —— 本地默认顺序恰好相反，所以本地全绿、CI 红。
> 复现手段：`-Dsurefire.runOrder=reversealphabetical`。

**② 建表前的清理必须调 `dropTables` 而不是 `drop`（:477-492）**

`fresh()` 为了"先删干净再建"要删表，但如果顺手调 public 的 `drop(prefix)`，末尾的 `MANAGED.remove(prefix)`
会把登记抹掉 → 监听器/钩子遍历到空集合 → 日志打印"清理完成"、库里留一整组 `itt_*` 表（实测残留 8 张）。

**诊断手法**（值得复用）：在监听器里同时打印 `MANAGED.size()` 和真实表数 —— `managed=0` 而 `itt=8` 一秒定位；
再打印两侧 `TestDatabases.class.getClassLoader()` 排除"类加载器隔离导致静态状态不共享"这个通常的第一嫌疑人。

**③ 时区参数必须成对（:641-652）**

驱动按 `serverTimezone` 解释返回的 `datetime`，而库里 `CURRENT_TIMESTAMP(3)` 默认值按**会话**时区生成。
只设一半 → 几小时系统性偏移（实测：服务器 OS 是 UTC、JVM 是 +08:00，只设 `serverTimezone=+08:00`
→ 归档时间比当前时间早 8 小时）。所以 `serverTimezone=<偏移>` + `sessionVariables=time_zone='<同一偏移>'` 必须成对。

顺带避开 MySQL 5.7 服务器时区缩写 `CST` 的歧义报错。

### 3.4 `TestDataCleanupListener`（48 行）

`implements LauncherSessionListener`，通过
`META-INF/services/org.junit.platform.launcher.LauncherSessionListener`（1 行）**自动装配**，使用方零配置。

**为什么不能只靠 shutdown hook**（:27-29 的注释）：surefire 的 fork 进程可能以 `Runtime.halt()` 结束 ——
**halt 不执行 shutdown hook** → 表留在库里。监听器挂在 JUnit 会话生命周期上，与 JVM 怎么退无关。

`:44-46` 清理失败只 WARN，不影响测试结论（还有 `sweepStaleTestTables` 兜底）。

### 3.5 依赖（`pom.xml` 63 行）

| 依赖 | scope | 说明 |
|---|---|---|
| `h2` | **compile** | 测试基座本身要能建库 |
| `mysql-connector-j` | **compile** | 真库档 |
| `junit-platform-launcher` | provided + **optional** | 只为编译 `LauncherSessionListener`；**不传染给使用方** |
| `junit-jupiter` | test | 自测 |

> `junit-jupiter` 在父 POM 的 `dependencyManagement` 里是 `<scope>test</scope>` —— 要让测试基类打进 jar，
> 本模块必须自己重写 scope；`junit-platform-launcher` 则用 `optional` 保证不传给 SB3 模块（那里用的是 Spring 管的 JUnit 版本）。

### 3.6 `TestDatabasesTest`（283 行）

自测：前缀形状、`fresh()` 后表存在、`cleanupAll()` 后表为空、`drop` 与 `dropTables` 的登记差异、
陈旧清扫不误伤新表、`requireManagedPrefix` 拒绝非法前缀。

> **这类断言必须放在能看到真实表的模块里**（testkit 能读生产 DDL 资源）。放在 core 会因为读不到 DDL 变成 ERROR 而非 FAILURE。

---

## 4. 看起来多余但别删的点

| 点 | 位置 | 为什么保留 |
|---|---|---|
| pure-java demo 的 `pretty()` | `IfmapQuickStart:79` | 整个 demo 的卖点是"依赖极少"，用 Jackson 美化就自打脸 |
| demo 的 banner 4（故意写错规则名） | `IfmapQuickStart:59` | 这是产品对存量引擎的**核心差异演示**，删了 demo 就没论点了 |
| `DemoStrategy` 同时实现两个接口 | `DemoStrategy:44` | 演示"一个 bean 可以承担多个策略角色"，且 `apply` 与 `assemble` 语义不同 |
| `DemoRunner.seedIfAbsent` 的幂等 | `:108` | 不幂等的话重复启动就 1062；演示"配置写入要判重" |
| `InboundNoticeDemo` 第三个场景（`PENDING`） | `InboundNoticeDemo:125` | 演示"判定失败时**没有**应答"，比只演成功路径更能说明"引擎不兜底错误码"的设计选择 |
| ACK 模板里 `$.['seqNo']` 与 `$.seqNo` 并列 | `InboundNoticeDemo:78` | 一个是"对方的字段"、一个是"我方生成的流水号"；这对写法是可运行的反例教材，别改成统一写法 |
| `application.yml` 的 `web-application-type: none` | `:8` | `docs/08` 与设计文档承诺「非 Web 应用可退出 / `java -jar` exit=0」—— 改回去就破坏契约 |
| `application-mysql.yml` 头部 9–16 行注释 | `:9-16` | `CannotGetJdbcConnectionException` 的结案记录（成对时区 + `%2B`） |
| `application-mysql.yml` 的 `ddl.auto: false` | `:38` | 生产由 DBA 执行 SQL（用户明确要求保留） |
| testkit 的 `SimpleDataSource` | `TestDatabases:762` | 用 `spring-jdbc` 会把 Spring 5.3 传进 SB3 模块，版本冲突 |
| `drop` 与 `dropTables` 两个方法 | `:468/:484` | 语义不同：一个"删表+注销"、一个"只删表"。合并就会重现"建表时把自己登记抹掉"的残表缺陷 |
| `prefixFor` 缓存命中分支的重登记 | `:197` | 幂等登记；删掉则"先 cleanupAll、后用同一 hint"的表永远没人清 |
| `TEST_PREFIX_HEAD` 与 `TEST_PREFIX_PATTERN` 两个正则 | `:116/:119` | 一个匹配"前缀"、一个匹配"表名开头"（表名后缀里也有 `_`）；合成一个会误判 |
| `sweepStaleTestTables` 的 30 分钟阈值 | `:126` | 远大于单个测试类的前缀存活时间，才不会误伤并行构建 |
| `sweepStaleTestTables` 里 `LIKE 'itt\_%'` 的转义 | `:520` | `_` 在 LIKE 里是通配符，不转义会匹配到别的表 |
| `LauncherSessionListener` 监听器 | `TestDataCleanupListener` | surefire 用 `Runtime.halt()` 结束 fork 进程，**halt 不执行 shutdown hook** |
| `junit-platform-launcher` 用 `optional` | `pom.xml:60` | 不加 optional 会把 launcher 传给使用方，与 Spring 管的 JUnit 版本打架 |
| testkit `main()` 手工清理入口 | `:724` | 真库里已经积了一堆残表时，运维需要一条命令能清（CI 挂掉后最常用） |
