# 07 · Spring Boot Starter

`ifmap-spring-boot-starter`：13 文件 / 3,055 行（main 7 文件 / 1,544 行，test 5 文件 / 1,323 行，+1 行 imports）。

职责只有一个：**把 `ifmap-core` 的 17 个 SPI 装配成一套能开箱用的 bean 图**，全部 `@ConditionalOnMissingBean`，
宿主声明同类型 bean 即可完全接管。

---

## 1. 装配全景（`IfmapAutoConfiguration`，350 行）

类条件（78–79）：`@ConditionalOnClass(IfmapEngine.class)` + `ifmap.enabled`（`matchIfMissing = true`）。

### 1.1 基础 bean（83–188）

| 行 | bean | 依赖 | 说明 |
|---|---|---|---|
| 85 | `TableNameResolver` | `IfmapProperties` | 前缀来自 `ifmap.table-prefix` |
| 92 | `RuleRegistry` | — | **`builtins(false)`**：内置规则不在这里注册（由 `IfmapRuleRegistrar` 统一收集，避免重复注册） |
| 98 | `IfmapRuleRegistrar` | — | **必须是 `static`**：`BeanPostProcessor` 要早于普通 bean 实例化 |
| 104 | `IfmapStrategyRegistrar` | — | 同上 |
| 110 | `JsonOps` | `@ConditionalOnClass(JacksonJsonOps)` | 有 Jackson 就用它；两个实现都在 classpath 时**Jackson 优先** |
| 117 | `IfmapEngine` | `RuleRegistry` + `JsonOps` + props | `nullPolicy` / `strictTypes` 从配置取 |
| 129 | `IdGenerator` | props | `id-strategy` = `SNOWFLAKE`（默认）或 `AUTO_INCREMENT` |
| 140 | `TenantResolver` | props | `HeaderTenantResolver(tenantHeader, defaultTenantId)` |
| 146 | `LogMasker` | props | `DefaultLogMasker`（`maskFields` / `excludeFields`） |
| 154 | `ClockProvider` | — | `SystemClockProvider.INSTANCE` |
| 160 | `StrategyHookRegistry` | — | — |
| 166 | `FullParamStrategyRegistry` | — | — |
| 172 | `LogicBranchStrategyRegistry` | — | — |
| 178 | `ActionRegistry` | props | 把 `failOnMissingAction` 传进去 |
| 186 | `ContractValidator` | `IfmapEngine` | 部署前契约自检（**不在启动时扫库**，由管理端调用） |

### 1.2 `OrchestrationConfiguration`（191–241）

条件：`@ConditionalOnBean(ConfigRepository)` —— **没有仓库就不装配编排器**（纯模板渲染场景不引入它）。

| 行 | bean | 说明 |
|---|---|---|
| 198 | `ExecutionLogSink` | `RepositoryExecutionLogSink`；另有 `ifmap.log.enabled` 开关 |
| 204 | `IfmapOrchestrator` | 13 个依赖全走构造参数；`gateway` / `logSink` 用 `ObjectProvider`（**可选**）；三个开关从 `ifmap.orchestrator.*` 取 |
| 238 | `toThreshold(long)` | `<= 0` → `Integer.MAX_VALUE`（= 不截断）；超 int 上限时截断 |

### 1.3 `CacheConfiguration`（243–265）

条件：`ifmap.cache.enabled`（`matchIfMissing = true`）。

| 行 | bean | 条件 |
|---|---|---|
| 257 | `CaffeineConfigCache` | `@ConditionalOnClass(name = "com.github.benmanes.caffeine.cache.Cache")`（**用名字字符串**，不硬依赖） |
| 264 | `InMemoryConfigCache` | 兜底（core 零依赖实现） |

两个都是 `@ConditionalOnMissingBean(ConfigCache.class)` —— Caffeine 存在时前者先建，后者因 MissingBean 不成立而跳过。
这是**刻意的降级设计**，不是"同一件事两套做法"。

### 1.4 `DdlConfiguration`（267–278）

条件：有 `DataSource` + `JdbcTemplate` + `ifmap.ddl.auto=true`（`matchIfMissing = true`）→ `IfmapSchemaInitializer`。

> 用户把 `ifmap.ddl.auto` 设为 `false` 时，本配置整块不生效（生产由 DBA 执行 SQL）。

### 1.5 `LogCleanConfiguration`（281–303）与 `LogCleanSchedulingConfiguration`（306–325）

拆成两块是刻意的：

| 块 | 条件 | 内容 |
|---|---|---|
| `LogCleanConfiguration` | 有 DataSource + JdbcTemplate | `JdbcExecutionLogCleaner` **bean 常驻**（关掉定时任务仍可手工调用） |
| `LogCleanSchedulingConfiguration` | 再加 `ifmap.log.enabled` + `ifmap.log.clean-enabled` | `IfmapLogCleanTask` + `IfmapLogCleanScheduler` |

**为什么不用 `@EnableScheduling`（见 305–307 的注释，很重要的架构决策）**：
- 打开宿主的全局调度设施属于越界；
- Spring 在无 `TaskScheduler` bean 时会创建**非守护线程** → 非 Web / 批处理应用 `main()` 返回后 **JVM 不退出**（本仓库 demo 实测过）；
- 所以用自带**守护线程**的 `IfmapLogCleanScheduler` 驱动，应用该退出就退出。

> 这条与 `docs/08` 的验收口径「非 Web 应用可退出 / `java -jar` exit=0」是同一条约束的两种表达，**别改回去**。

### 1.6 `RepositoryConfiguration`（327–350）

| 行 | bean | 说明 |
|---|---|---|
| 331 | `ConfigRepository` | `JdbcConfigRepository`；若存在 `ConfigCache` 则包一层 `CachingConfigRepository`；**`:339` 先 `schemaInitializers.getIfAvailable()` 强制建表 bean 先完成**（否则仓储可能在表还不存在时被业务提前使用） |
| 345 | `JdbcConfigWriter` | 写路径单独成 bean |

---

## 2. `IfmapProperties`（386 行）

`@ConfigurationProperties(prefix = "ifmap")`（54）。**25 个配置键**，分 5 组：

### 2.1 顶层（66–103）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 关掉 = 整个自动配置不生效 |
| `table-prefix` | `"ifmap_"` | 直接拼进 SQL；只允许字母/数字/下划线，≤ 32（校验在 `TableNameResolver:65`） |
| `id-strategy` | `SNOWFLAKE` | 枚举 `SNOWFLAKE` / `AUTO_INCREMENT` |
| `worker-id` | `null` | 空 → 系统属性 `ifmap.worker-id` → 环境变量 `IFMAP_WORKER_ID` → `0` |
| `null-policy` | `SKIP_FIELD` | 模板 null 策略 |
| `strict-types` | `WARN` | `WARN` / `FAIL_FAST` |
| `tenant-header` | `"X-Tenant-Id"` | — |
| `default-tenant-id` | `"-1"` | 取不到租户时 |
| `ddl` / `orchestrator` / `log` / `cache` | 嵌套对象 | 见下（`final` 字段，setter 不覆盖） |

### 2.2 `ifmap.ddl.*`（186–198）

| 键 | 默认 | 说明 |
|---|---|---|
| `auto` | `true` | 启动建表（幂等）；生产可置 `false` |

### 2.3 `ifmap.cache.*`（201–234）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 只缓存**逻辑分支列表**（`CachingConfigRepository:68`），不缓存配置计划 |
| `maximum-size` | `1000` | — |
| `ttl` | `60s` | `Duration`；**0 = 永不过期** |

### 2.4 `ifmap.orchestrator.*`（237–271）

| 键 | 默认 | 说明 |
|---|---|---|
| `stop-on-failure` | `true` | 某接口失败即停；`false` 则跑完并合并成功步骤产物 |
| `fail-on-missing-strategy` | `true` | `pre/post_strategy_name` 指向的策略 bean 不存在 → 抛（**不再静默跳过**） |
| `fail-on-missing-action` | `true` | 逻辑分支动作未注册 → 抛 |

> 注意：`ActionRegistry.execute` 里 **`actionKey` 为空是无条件抛**（不受这个开关控制），见 03 篇坑 #7。

### 2.5 `ifmap.log.*`（274–385）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 是否写执行日志（同时影响 `ExecutionLogSink` bean 与定时清理） |
| `truncate-threshold` | `65536` | 单字段最大长度；`<= 0` 不截断 |
| `mask-fields` | 空 | 按**字段名**脱敏（姓名类）；空 → 内置默认 `acctName`/`realName`/... |
| `exclude-fields` | 空 | 这些字段**整体**替换 `***`，连长度都不落库 |
| `clean-enabled` | `true` | 定时清理开关（关掉后 cleaner bean 仍在） |
| `retention-days` | `90` | 必须 ≥ 1（`requireRetention` 兜底） |
| `clean-batch-size` | `1000` | 每批删除行数 |
| `clean-max-batches` | `1000` | 单次最多几批 |
| `clean-batch-sleep-millis` | `50` | 批间停顿 |
| `clean-cron` | `"0 30 3 * * ?"` | **Spring 6 段式**（秒 分 时 日 月 周），默认每天 03:30 |

---

## 3. 其余 5 个类

### 3.1 `IfmapSchemaInitializer`（405 行）· 最重要

启动建表 + 升级，**与 Liquibase changelog 共用同一份 SQL 文件**（不分叉）。

| 行 | 成员 | 说明 |
|---|---|---|
| 66 | `DDL_FILES` | 4 个建表脚本（001–004） |
| 83 | `ALTER_FILES` | 2 个升级脚本（005/006） |
| 89 | `ALTER_COLUMN_PATTERN` | 只认 `ALTER TABLE t ADD\|DROP COLUMN c` 两种形式；其他语句**直接抛**（`applyAlterScripts:177`） |
| 93 | `PREFIX_PLACEHOLDER = "${tablePrefix}"` | 与 Liquibase 的 property 同名 |
| 96 | `MYSQL_TAIL_PATTERN` | 剥掉 `) ENGINE=...` |
| 107 | `JSON_COLUMN_PATTERN` | 非 MySQL 时 `json` 列降级成 `text`（见下） |
| 126 | `afterPropertiesSet()` | → `createTablesIfAbsent()` |
| 135 | `createTablesIfAbsent()` | 逐表 `tableExists` → 不存在才建；**最后统一 `applyAlterScripts()`** |
| 171 | `applyAlterScripts()` | 逐语句：`ADD` 只在列**不存在**时执行、`DROP` 只在列**存在**时执行（`add == exists` 就跳过） |
| 199 | `columnExists(table, column)` | 投一条零行查询（`SELECT c FROM t WHERE 1=0`），MySQL / H2 通用，**不依赖 `information_schema`** |
| 209 | `executeDdl(table, ddl)` | 并发建表（另一实例抢先建好）→ 不报错 |
| 223 | `tableExists(table)` | 同上，不依赖 `information_schema` |
| 233 | `isMysqlFamily()` | 决定是否剥表尾选项 |
| 238 | `readRaw(location)` | 去空行/`--` 注释行，**保留分号**（后面按语句切） |
| 262 | `splitStatements(sql)` | 按分号切，**跳过单引号内的分号**（列注释里就有 `;`） |
| 300 | `readDdl(location)` | 读 + 前缀替换 + 表尾适配 + 去注释；**断言必须是单条语句** |
| 337 | `hasStatementSeparator(sql)` | 校验辅助 |
| 362 | `adaptDdl(ddl, mysqlFamily)` | MySQL 原样；非 MySQL 再 `downgradeJsonColumns` |
| 375 | `downgradeJsonColumns(ddl)` | **为什么**：H2 1.4.200 的 `JSON` 与 MySQL 的 `json` 语义不等价 —— JDBC `setString("{\"a\":1}")` 时 H2 会存成「JSON 字符串值」（多一层引号 + 反转义），读回来不再是对象，历史快照/差异回滚直接解析失败 |
| 390 | `detectMysqlFamily(dataSource)` | 读 `DatabaseMetaData.getDatabaseProductName()` |

**为什么 005/006 不并回 001（76–81 的注释）**：Liquibase 按 changeset checksum 判定是否重跑，改 001 会让所有已部署库重跑建表而失败 —— 便宜的做法是**新增 changeset**。

**为什么用「列是否存在」而不是版本表（165–167）**：`ifmap.ddl.auto=true` 的用户可能是全新建库、也可能是手工跑过 Liquibase 的存量库；列探针两种情况都对，且不用引入 Liquibase 依赖。

### 3.2 `IfmapLogCleanScheduler`（136 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 54 | 类 | `SmartInitializingSingleton` + `DisposableBean` |
| 65 | 构造 | `CronExpression` **构造期解析**（非法 cron 启动就失败，不等到第一次触发） |
| 77 | `afterSingletonsInstantiated()` | 上下文装配完再排第一次（避免被 bean 创建过程的干扰） |
| 83 | `isScheduled()` | 自检/测试用 |
| 89 | `cron()` | — |
| 94 | `destroy()` | 关 executor |
| 99 | `scheduleNext()` | 按 cron 算下次延迟 |
| 113 | `runThenReschedule()` | 跑任务 → 再排下次（**上一次没跑完不会堆叠**） |
| 125 | `DaemonThreadFactory` | **守护线程**（JVM 可退出） |

### 3.3 `IfmapLogCleanTask`（77 行）

薄封装：`cleanNow()`（64）调 `JdbcExecutionLogCleaner.clean(4 参)`；`getRetentionDays()`（74）给日志/自检用。

### 3.4 `IfmapRuleRegistrar`（104 行）

`BeanPostProcessor` + `BeanFactoryAware`：扫每个 bean 的类，有 `@IfmapRule` 方法就注册进 `RuleRegistry`。

| 行 | 成员 | 说明 |
|---|---|---|
| 58 | `ruleTypeCache` | 按 `Class` 缓存"这个类有没有规则方法"，避免同一类多个 bean 重复反射 |
| 59 | `registeredBeanNames` | 已注册 bean 名（排障/测试断言） |
| 64 | `setBeanFactory` | 只保存 `BeanFactory`（真正的取用推迟到下面） |
| 69 | `postProcessAfterInitialization` | 主逻辑；`:70` 先跳过 `RuleRegistry` 自身与 `IfmapEngine`；`:73` 用 `ClassUtils.getUserClass` 拆代理；`:77` 此刻才 `beanFactory.getBean(RuleRegistry.class)` |
| 84 | `hasRuleMethod(type)` | 反射扫描（带缓存） |
| 101 | `registeredBeanNames()` | — |

### 3.5 `IfmapStrategyRegistrar`（111 行）

同上，但注册**四类**策略：`IfmapStrategyHook` / `FullParamStrategy` / `LogicBranchStrategy` / `IfmapActionHandler`。

| 行 | 成员 | 说明 |
|---|---|---|
| 70 | `postProcessAfterInitialization` | 主逻辑 |
| 99 | `isInfrastructure(bean)` | **注册表自身与编排器不是策略**，跳过以免自注册（`IfmapOrchestrator` 恰好实现了若干接口时会踩到） |
| 108 | `registeredBeanNames()` | — |

### 3.6 `CaffeineConfigCache`（86 行）

`ConfigCache` 的 Caffeine 实现，与 core 的 `InMemoryConfigCache` 同契约。

| 行 | 成员 | 说明 |
|---|---|---|
| 40 | 构造 | `(maximumSize, Duration ttl)` + **恒定 `recordStats()`**；`ttl` 为 null / 0 / 负数 → 不设过期 |
| 52 | `get(key, loader)` | `getIfPresent` 命中直接返回；否则**在锁外调 loader**，只有 `!= null` 才 `put`（与 `InMemoryConfigCache` 同口径：空结果不缓存、loader 抛异常不落缓存） |
| 65 | `invalidate(key)` | — |
| 72 | `invalidateAll()` | — |
| 77 | `stats()` | Caffeine `CacheStats` |
| 83 | `unwrap()` | 暴露底层实例（需要自定义淘汰策略时） |

### 3.7 `META-INF/spring/...AutoConfiguration.imports`（1 行）

Spring Boot 3 的自动配置入口（只写 `IfmapAutoConfiguration` 全限定名）。

---

## 4. 测试（5 文件 / 1,323 行）

| 文件 | 行 | 覆盖 |
|---|---|---|
| `IfmapAutoConfigurationTest` | 298 | bean 图完整性 + `@ConditionalOnMissingBean` 可覆盖性 + 各开关关闭后的效果 |
| `IfmapOrchestratorAutoConfigurationTest` | 298 | 编排器的 15 个依赖是否都注入到位；缺 gateway/logSink 时仍能建 |
| `IfmapSchemaInitializerTest` | 273 | 建表幂等 / ALTER 幂等 / 前缀替换 / json 列降级 / 分号切分 |
| `IfmapLogCleanTaskTest` | 256 | 保留天数 / 批大小 / 中断 |
| `IfmapSplitDatasourceTest` | 258 | 宿主自带 `JdbcTemplate`/多数据源时不冲突 |

---

## 5. 看起来多余但别删的点

| 点 | 位置 | 为什么保留 |
|---|---|---|
| `Registrar` 两个 `@Bean` 都是 `static` | `:98/:104` | `BeanPostProcessor` 必须在普通 bean 之前实例化；写成实例方法会有一条 warning 且行为不确定 |
| `RuleRegistry` 用 `builtins(false)` | `:92` | 内置规则由 `IfmapRuleRegistrar` 统一收集；再 `builtins(true)` 会与宿主重载同名规则，触发"同名覆盖"警告 |
| `CacheConfiguration` 两个 bean | `:257/:264` | 刻意降级：Caffeine 有无都能跑，不是重复实现 |
| `LogCleanConfiguration` 与 `LogCleanSchedulingConfiguration` 拆开 | `:287/:309` | 关掉定时任务仍要能手工调 cleaner |
| **不用 `@EnableScheduling`** | `:305-307` | 会打开宿主全局调度并创建非守护线程，导致非 Web 应用不退出 |
| `toThreshold(long)` 的钳位 | `:245` | `int` 字段 + `long` 配置；`<=0` 要映射成"不截断"而不是 0 长度 |
| `schemaInitializers.getIfAvailable()` | `:345` | 只为**触发**建表 bean 先完成，返回值不用 —— 看着像死代码，删了会让"表还不存在就被查"重现 |
| `columnExists` 用零行查询而不是 `information_schema` | `:199` | MySQL 与 H2 通用；`information_schema` 在 H2/其他库口径不同 |
| `splitStatements` 跳过引号内分号 | `:262` | 列 COMMENT 里就写着 `;`，粗暴 `split(";")` 会把注释切成两条语句 |
| `ALTER_COLUMN_PATTERN` 只认两种形式 | `:89` | 是为"能在目标库上幂等重放"设的窄口；放开就等于把任意 DDL 交给启动过程执行 |
| `downgradeJsonColumns` | `:375` | H2 的 `JSON` 与 MySQL `json` 语义不等价，不降级则历史快照往返直接坏 |
| `IfmapStrategyRegistrar.isInfrastructure` | `:106` | 注册表/编排器实现了策略接口时会把自己注册进去（自注册 → 无限递归/诡异行为） |
| `CaffeineConfigCache.unwrap()` | `:83` | 宿主可能要自定义淘汰策略或取统计，但不该去反射拿内部字段 |
