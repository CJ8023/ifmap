# ifmap 过度设计诊断（v1）

> 适用版本：`0.1.0-SNAPSHOT`　作者：caijun　性质：**内部审查档**
> 审查范围：全仓 11 个模块（main ≈ 1.9 万行 / test ≈ 1.05 万行）
> 行号取自本次审查时的快照，仅供参考
> **后续更新**：本档成文后，编排定序做过一次破坏性重构（删 `front_interface_no`、`strategy_name` 拆前 / 后两列、
> `InterfaceOrdering` 收缩为 `enabled + sort`，见 [`CHANGELOG.md`](../CHANGELOG.md) 的
> 「Changed（编排定序、跨步骤共享池与前后序策略）」）。其中与本次审查结论相关的两处已就地补注（§2.1 顺带修的文档问题、
> §3 判「非过度设计」清单里的 `InterfaceOrdering` 行）。

**结论一句话**：8 条 P1 候选里，**6 条复核后撤回、1 条改判为「补文档」、只有 1 条是真缺陷（已修）**。
本档把「撤回的证据」一并写下来 —— 那才是这份审查真正的产出，因为下次还会有人提出同样的候选。

---

## 0. 结论速览

| 编号 | 候选（原判） | 结论 | 处置 | 依据 |
|---|---|---|---|---|
| ~~P1-1~~ | `ConfigCache` 两套实现（Caffeine / InMemory）= 同一件事两套做法 | **撤回** | 不动 | core 零第三方依赖，纯 Java 接入必须有自带实现 |
| ~~P1-2~~ | 迁移 kit + 分区手册（2190 行）放进 main jar | **撤回** | 不动 | 文档承诺「随 jar 发布」，且被 5 处常量/测试引用 |
| ~~P1-3①~~ | 策略注册表「第三别名」多余 | **撤回** | 不动 | 存量 P0-7 的真实修复（38 个实现里 11 个踩坑） |
| **P1-3②** | `aliasHits` 无去重无上界 | **成立（真缺陷）** | **已修** `0571a94` | 在每笔请求路径上，内存随流量线性增长 |
| ~~P1-4~~ | 模板「List 实参自动逐元素映射」是隐式行为 | **撤回** | 不动 | `docs/02` §3.1 有独立小节，是公开契约 |
| ~~P1-5~~ | 管理端内嵌 UI（1307 行）是附件混进主产物 | **撤回** | 不动 | 一等交付物 + 硬约束 + 2 个静态契约测试 |
| P1-6 | 零实现 SPI（`HolidayCalendar` / `IfmapEnumProvider`）要处理 | **改判** | **已做** `7c662fb` | 改为补「扩展点（SPI）总览」表 |
| ~~P1-7~~ | `JsonConfigMapper` 抄了一遍 DDL 默认值 | **撤回** | 不动 | `docs/11` §5 把它写成契约：与 JDBC 读出的对象逐字段一致 |
| ~~P1-8~~ | 25 个配置键砍到 12 | **撤回** | 不动 | 键全有消费点、全在配置表；`clean-*` 是文档指定的排错手段 |

另有 3 项在审查早期就已处理完毕（影子运行框架、`ConditionValueResolver` 死链、重复的分区 DDL），见 §3。

---

## 1. 判据与证据要求

### 1.1 六条候选判据（C1 ~ C6）

| 判据 | 含义 |
|---|---|
| **C1** 无调用方 | 生产代码里没有任何调用点 |
| **C2** 无装配入口 | 没有任何 `@Bean` / `@ConditionalOn*` / Builder 链把它装起来 |
| **C3** 单实现 SPI | 定义了扩展点却永远只有 1 个实现，且无第二实现的可能 |
| **C4** 同一件事两套做法 | 同一个能力存在两条并行实现路径 |
| **C5** 配置面 > 使用面 | 暴露的配置键远多于实际会被改动的键 |
| **C6** 一次性交付物进主产物 | 迁移/运维的一次性物料混在运行期 jar 里 |

### 1.2 定案前必须凑齐的三类证据（**v1 最重要的产出**）

| | 证据 | 怎么取 |
|---|---|---|
| **E1** | 有调用方？ | `grep -rn "\.method(" --include=*.java \| grep -v /test/` |
| **E2** | 有装配入口？ | 找 `@Bean` / `@ConditionalOn*` / Builder 链 |
| **E3** | **有对外承诺？** | `grep` 覆盖 `docs/*.md` + `README.md` + `CHANGELOG.md` + 测试的 **`@DisplayName`** + **生产常量/javadoc 里的路径引用** |

- E1 + E2 回答的是「**现在有没有人用**」；
- E3 回答的是「**对外答不答应过**」；
- **只有 E1、E2、E3 全空，才能进「可删」清单。**

> **v1 的 6 条误报，全部是漏了 E3。** 其中 3 条（P1-2 / P1-5 / P1-7）最典型：代码看上去就是"一次性物料 / 抄了一遍默认值 / 一个附件"，但它们都是文档里白纸黑字的承诺。

---

## 2. 已处理项

### 2.1 影子运行框架（`3d02a85`）

**规模**：14 个文件 / 1930 行（其中 1247 行代码 + 27 个测试），另清理约 15 处文档引用。

**依据**：它是「Scientist 式双跑 + diff」的迁移护栏（退出条件：连续 7 天零差异，3 条硬契约）。而迁移阶段链已经从 `M0 → M1 → M2 → M3 影子运行 → M4` **收敛为 `M0 摸底 → M1 表结构 → M2 规则/策略对齐 → M3 切换与回滚`** —— 阶段没了，框架就是纯负债（C1 + C2 全空）。

### 2.2 `ConditionValueResolver` 死链（`44ac2eb`）

**规模**：3 个文件 / 136 行。

| 文件 | 行数 |
|---|---|
| `ifmap-core/.../core/spi/ConditionValueResolver.java` | 33 |
| `ifmap-core/.../core/spi/AttributeConditionValueResolver.java` | 52 |
| `IfmapOrchestrator.java` 里的 6 处**只写不读**引用（import / 字段 / 构造赋值 / Builder 字段 / `Builder.conditionResolver(..)` 公开 setter / null 兜底） | 51 |

**关键判据（E3）**：它对外承诺的解析语法 `attr:` / `payload:` / `header:` 在 `docs/` + `README` 里**0 命中** —— 从未答应过。
**能力未丢**：`IfmapOrchestrator:218` 的 `.attributes(request.getAttributes())` → `RuleContext` 保留了同一条路径，且有文档（`docs/03:17/113/187-189`）、有测试（`BuiltinRulesTest:199`）、有真实使用（`DateRules.workday*`）。
**顺带**：修正 `IfmapRequest:92` 的 javadoc 指向（原指向已删的 `ConditionValueResolver`）。

### 2.3 重复的单文件分区 DDL（`44ac2eb`）

**规模**：`ifmap-provider-jdbc/src/main/resources/db/mysql8/partition-execution-log.sql`（51 行，目录随之删空）。

**依据**：它被后来那套 **6 脚本 + README 的完整手册**（`db/optional/execution-log-partition/`，696 行）取代，而手册**完全不引用**它。两份并存只会让运维不知道该照哪份执行（单文件版还缺「存量表怎么改」与回滚）。
**顺带**：`JdbcExecutionLogCleaner:32` 的 javadoc 改为指向手册 README，避免悬空引用。

### 2.4 P1-6 → 改判为补「扩展点（SPI）总览」（`7c662fb`）

**原判**：`HolidayCalendar`、`IfmapEnumProvider` 是零实现 SPI，应当处理。
**复核结果：前提不成立。** 两个接口的 javadoc 本来就写清了"未提供时的行为"（`HolidayCalendar`：按「周一~周五为工作日」的简化口径；`IfmapEnumProvider`：没有 bean 时端点返回 `{}`、页面退化成纯文本输入），`docs/03:109-114` 与 `docs/07` §11.3 也各自写了。

**真正的缺口**：**没有一个地方把所有扩展点列在一起**，说明"不提供它会怎样"。信息散在 `docs/03` / `05` / `06` / `07`，而 `README.md` 里 `TenantResolver` / `PartnerServiceGateway` / `ClockProvider` **一次都没提** —— 这恰恰是新接入方问的第一个问题。

**交付**：`docs/06` 新增 `## 11. 扩展点（SPI）总览` —— 12 个扩展点一张表（扩展点 / 职责 / 内置实现 / **未提供时的行为**），并列明 **4 个刻意不给默认实现**的 SPI（`ConfigRepository`、`PartnerServiceGateway`、`HolidayCalendar`、`IfmapEnumProvider`）及其理由。

> 口径修正：`ConfigRepository` 属于**必需**（`Builder.build()` 直接抛 `IllegalArgumentException`），`PartnerServiceGateway` 缺失时 starter 降级为 dry-run + WARN，这两条与另外两个"可选项"性质不同，表里分开写了。

### 2.5 P1-3② → `aliasHits` 改 `Set`（`0571a94`）

**缺陷**：`SpecialDealStrategyRegistry.aliasHits` 是普通 `ArrayList`，`lookup()` 命中宽松 key 时**无条件 `add`、不去重、无上界**；而 `lookup()` 在 `IfmapOrchestrator.applySpecialDeal()`（`:244`）里**每笔请求**都会被调用 —— 只要某个接口的 `strategy_name` 一直用宽松写法，每笔请求就多留一个字符串，**内存随流量线性增长**。

**同时是死代码**：`getAliasHits()` 只有测试在用，javadoc 声称的"启动期审计报表"并不存在（管理端用的是 `keys()`）。对照 `getConflicts()` **不删** —— 它只在注册期写入，不随请求增长，且是三个注册表（SpecialDeal / FullParam / Action）的统一模式。

**修法（比"直接删"更好）**：改 `LinkedHashSet`。
记录值只可能取自 `aliases` 的 key（`lookup` 仅在 `aliases.get(name) != null` 时记录），**去重后上界天然就是「已注册别名数」**，不需要额外常数；且**不破坏 public API**（`getAliasHits()` 签名与返回形态不变），**4 条现有测试断言原样通过** —— 这本身就是"可观测行为未变"的证明。

**顺带修掉的两处文档问题**：
1. `docs/10-迁移指南.md` §4.1 的 `strategy_name` 行「默认 **warn 后跳过**（可配成快速失败）」**两半都反了**：`IfmapProperties.Orchestrator.failOnMissingStrategy` 默认 `true` → `IfmapOrchestrator:246` 抛 `IfmapStrategyException`，"warn 后跳过"是 `false` 那一支（`:249`）。`docs/05` §2 写的是对的，两份文档矛盾。
   *（后续：该列已拆为 `pre_strategy_name` / `post_strategy_name`，`docs/10` §4.1 行已随之改写；本条的"默认快速失败"结论对两列同样成立，代码位置改到 `IfmapOrchestrator.applySpecialDeal` / `applyPostDeal`。）*
2. 补上「两个宽松别名」的公开说明（`docs/06` §3 指针 + `docs/10` §4.1 全文）：**代码此前比文档容忍得多**，宽容行为没有写出来。

---

## 3. 撤回项（附证据）

### 3.1 P1-1 · `ConfigCache` 两套实现 → 撤回

**原判**：`CaffeineConfigCache` 与 `InMemoryConfigCache` 是"同一件事两套做法"（C4）。

**证据**：`IfmapAutoConfiguration.CacheConfiguration` 上的 `@ConditionalOnClass(Caffeine)` + `@ConditionalOnMissingBean` 是**刻意降级**：
- `ifmap-core` 是**零第三方运行时依赖**（只有 `slf4j-api`）的模块，必须有自带的纯 Java 实现；
- `ifmap-demo-pure-java` 就是靠 `InMemoryConfigCache` 跑起来的；
- 有 Caffeine 才升级到 Caffeine —— 这是"核心无依赖 + 按需增强"，不是重复实现。

**结论**：两套实现是**能力梯度**，不是冗余。

### 3.2 P1-2 · 迁移 kit + 分区手册放进 main jar → 撤回

**原判**：`db/migration/ecc-to-ifmap/`（6 SQL + README）与 `db/optional/execution-log-partition/`（6 SQL + README，合计 2190 行）是一次性交付物混进运行期产物（C6）。

**证据（6 条）**：

| # | 证据 |
|---|---|
| 1 | `docs/08-日志与合规.md:132` **明写「随 jar 发布」**：`ifmap-provider-jdbc/src/main/resources/db/optional/execution-log-partition/`（随 jar 发布，看该目录的 `README.md`） |
| 2 | `README.md:44` 特性表指向 `db/optional/execution-log-partition/`；`docs/04:24/276` 文件树 + 特性表都列出它 |
| 3 | **生产常量指向该脚本**：`JdbcExecutionLogArchiver:90` `public static final String ARCHIVE_DDL_HINT = "db/optional/execution-log-partition/04-create-archive-table.sql"`（错误信息里提示用户去哪找 DDL） |
| 4 | **testkit 常量指向**：`ifmap-testkit:103` `TestDatabases.ARCHIVE_DDL` 同路径 → `JdbcExecutionLogArchiverTest` 在 H2(MySQL 模式) 上真跑搬迁 |
| 5 | **测试直接把它当 fixture 读**：`ExecutionLogPayloadFidelityTest:61/67` `TestDatabases.read("db/migration/ecc-to-ifmap/03-modify-and-index.sql")` 后断言 mediumtext |
| 6 | **测试为它专门放宽规则**：`LicenseHeaderTest:109` 注释「该规则只对 changelog 脚本成立：`db/migration/` 下的迁移 kit 是给运维手动执行的普通脚本」 |

**结论**：这是**离线交付形态**的一部分（与 §3.5 内嵌 UI 的「零构建链、内网离线可用」是同一个约束的两个面）。搬出 main resources 就必须**新增一条"运维物料包"的分发渠道** —— 那是净增复杂度，且会打断上面 4 处引用。

### 3.3 P1-3① · 策略注册表「第三别名」→ 撤回

**原判**：`SpecialDealStrategyRegistry` 的第三个别名（`decapitalizeLoose(simpleName)`）多余。

**证据**：它是正式设计决策，用来修正式立项的 **P0-7 缺陷**（详见内部档 `bankintconfig模块优化方案_v1.md`，该档未纳入版本库）：

- 存量 `strategy_name` 多按「**类名首字母小写**」人工填写；
- 而 Spring 的真实 bean 名在**前两个字母皆大写时保持不变**（类 `CMBApplyStrategy` 的 bean 名仍是 `CMBApplyStrategy`，不是 `cMBApplyStrategy`）；
- 存量 **38 个策略实现里有 11 个**两种写法不一致 → 运行到该接口抛「接口执行策略实现类不存在」。

**三 key 注册**（bean 名 → 类简单名 → 首字母小写变体，后两者命中记 WARN）让这 11 个接口**一行配置都不用改**。

**结论**：删它 = 破坏性变更。**处置改为：保留代码 + 补文档**（已随 `0571a94` 落到 `docs/06` §3 与 `docs/10` §4.1）。

### 3.4 P1-4 · 模板「List 实参自动逐元素映射」→ 撤回

**原判**：这是隐式行为，应当删掉（C4 / 隐式魔法）。

**证据**：它是**完整的公开契约**：
- `docs/02-模板DSL语法.md` §3.1 有**独立小节**，另加 2 个模板示例，并明确写了「**嵌套深度上限 3**」；
- 另见 `docs/02:33/:298`、`docs/03:18/:143/:227`、`README.md:194`；
- 4 处测试覆盖：`RuleRegistryTest:183`、`BuiltinRulesTest:199`、`TemplateEngineTest:87/91`。

**结论**：删它 = 破坏性变更。

### 3.5 P1-5 · 管理端内嵌 UI → 撤回

**原判**：`META-INF/ifmap-admin-ui/app.js`（980 行）+ `app.css`（153）+ `index.html`（174）= 1307 行内嵌前端，是一次性附件混进主产物（C6）。

**证据**：
- `docs/07-管理端REST.md:40` 与 §11 把它写成交付物；
- 设计上有**硬约束**：「**零 CDN / 零前端框架 / 零构建链**」、内网离线可用；
- `IfmapAdminWebConfigurer.UI_LOCATION` 是生产常量；
- 2 个静态契约测试守着它：`IfmapAdminUiEndpointTest`、`IfmapAdminUiScriptTest`（后者含 `node --check` 语法校验）。

**结论**：一等交付物。删掉内嵌资源 = 管理端在内网离线环境不可用。

### 3.6 P1-7 · `JsonConfigMapper` 抄了一遍 DDL 默认值 → 撤回

**原判**：`ifmap-provider-remote/.../JsonConfigMapper.java:111/117-121` 里的 `orEmpty(...)` / `integer(row, "status", source, 1)` / `…, "delStatus", …, 0` 等，是把 DDL 默认值抄了一遍（C4）。

**证据**：`docs/11-远端配置源.md:159` 把它写成了契约，一字不差：

> \| **缺失的可选字段** \| 按建表 DDL 的默认值补齐（**与 JDBC 仓储读出来的对象逐字段一致**）：`interface_order / version / del_status / deleted_seq → 0`、`status → 1`、`result_flag / success_value / pre_strategy_name / post_strategy_name / remark → ''` \|

配套三处：
- `:160` 必填字段的判定口径 = 「DDL 里 `NOT NULL` 且**没有** `DEFAULT` 的列」← **DDL 被指定为映射契约的唯一真源**；
- `:171` `result_flag` / `success_value` 缺失补空串 = 「没有约定字段 → **默认成功**」← **改这个会翻转业务判定结果**；
- `:214` 「缺列 → 按 DDL 默认值补齐，不影响运行」。

测试 `JsonConfigMapperTest:116` 的 `@DisplayName` 就是这句话原样：
`"缺失列按建表 DDL 默认值补齐（与 JdbcConfigRepository 读出的对象一致）"`

**结论**：这不是 DRY 违例，而是这个模块存在的全部意义 —— 同一个配置行，走 JDBC 读和走远端读，必须产出**逐字段相同的对象**，否则换个 `ConfigRepository` 实现就会改变行为。

### 3.7 P1-8 · 25 个配置键砍到 12 → 撤回

**原判**：`IfmapProperties` 有 25 个叶子配置键，配置面大于使用面（C5）；建议把 `ifmap.log.clean-*` 的批处理参数折叠成代码常量。

**证据 1 · 每个键都有真实消费点，没有死键**：

| 消费方式 | 键 | 数量 |
|---|---|---|
| `@ConditionalOnProperty` 注解字符串 | `ifmap.enabled` / `ifmap.ddl.auto` / `ifmap.log.enabled` / `ifmap.log.clean-enabled` / `ifmap.cache.enabled` | 5 |
| getter，生产调用点 ≥ 1 | 其余 20 个（`table-prefix` / `id-strategy` / `worker-id` / `null-policy` / `strict-types` / `tenant-header` / `default-tenant-id` / `cache.maximum-size` / `cache.ttl` / `orchestrator`×3 / `log.truncate-threshold` / `log.mask-fields` / `log.exclude-fields` / `log.retention-days` / `log.clean-batch-size` / `log.clean-max-batches` / `log.clean-batch-sleep-millis` / `log.clean-cron`） | 20 |

> ⚠️ **假阴性陷阱**：`isEnabled()` / `isAuto()` / `isCleanEnabled()` 的 getter 调用数是 **0** —— 只数 getter 会把它们误判成死键，实际走的是 `@ConditionalOnProperty`。

**证据 2 · 25 个键全部在文档的配置表里**：`docs/05` §2 列 19 个 + `docs/08` 的配置表补 6 个 `clean-*` = 25，无遗漏。

**证据 3 · `clean-*` 不是"没人改的旋钮"，而是文档明确指定的运维处置手段**：

| 位置 | 原文 |
|---|---|
| `docs/08:236`（排错表） | 清理后主从延迟飙升 → **调小 `clean-batch-size`、调大 `clean-batch-sleep-millis`**，并避开业务高峰 |
| `docs/08:235`（排错表） | 每次清理只删了一小部分 → 达到 `clean-max-batches × clean-batch-size` 上限（日志有 WARN）→ **调大上限或缩短调度周期** |
| `docs/08:220`（合规自查清单第 10 条） | 大表清理不引起主从延迟 → 监控 `Seconds_Behind_Master`，**必要时调小 `clean-batch-size`、调大 `clean-batch-sleep-millis`** |
| `docs/08:125` | 「把 `clean-batch-size` 调到 500~1000、批间 sleep 50ms 是安全默认值」 |

主从延迟是真库运维的日常问题。折叠成常量 = **把运维唯一的调节手段焊死**。另有 9 个单测盯着这套组合（配置绑定、非法 cron 启动期快速失败、`clean-enabled=false` 只关任务、`retention-days=0` 不删数据、失败降级返回 −1…）。

**唯一还站得住的观察**（不构成"删"）：`clean-max-batches=1000 × clean-batch-size=1000` = 单次最多 100 万行，默认值偏激进 —— 这是**默认值调优**，不是过度设计。

---

## 4. 明确「不是过度设计」的保护名单

以下项在审查中被扫到过，但**有明确理由保留**，后续不要再提为精简候选：

| 项 | 位置 | 为什么保留 |
|---|---|---|
| 两套 `ConfigCache` | `IfmapAutoConfiguration` / `core.cache` | core 零依赖，纯 Java 接入必需（§3.1） |
| 管理端内嵌 UI（1307 行） | `admin/.../META-INF/ifmap-admin-ui/` | 硬约束「零 CDN / 零框架 / 零构建链」+ 2 个契约测试（§3.5） |
| 迁移 kit + 分区手册（2190 行） | `provider-jdbc/.../db/` | 文档承诺「随 jar 发布」+ 5 处常量/测试引用（§3.2） |
| `JsonConfigMapper` 的 DDL 默认值 | `provider-remote` | 「与 JDBC 读出对象逐字段一致」的契约（§3.7） |
| 25 个配置键 | `IfmapProperties` | 全部有消费点与文档；`clean-*` 是运维旋钮（§3.7） |
| 策略注册表三 key + `getConflicts()` | `SpecialDealStrategyRegistry` | P0-7 修复；`conflicts` 是三个注册表的统一模式（§3.3） |
| ~~`InterfaceOrdering` 拓扑排序 + 环检测~~ | `core.ordering` | 当时判"前置接口链的正确性基础，非排序工具"；**后续该机制连同 `front_interface_no` 一起被删除**（`interface_order` 单一定序 + 起点截断 + 跨步骤共享池取代，见顶部更新说明），本行结论已作废 |
| `Values` 数字格式化 | `core.template` | 数字保真契约（`10.00` 不得变 `10.0`、不得出科学计数法） |
| `JsonOps` SPI + `ifmap-json-tck` | `json-tck` / `json-*` | 换 JSON 库时的一致性套件，只进测试范围 |
| `ContractValidator` / `TemplateScanner` / `StrictTypes` | `core.contract` | 保存前校验的三大件，对应 docs/02 §7 |
| 归档器 / 清理器 / Liquibase changelog | `provider-jdbc` | 合规要求（保留期 + 冷热分离 + 回滚） |
| 其余 17 个 SPI | 各模块 | 均为宿主扩展点，见 `docs/06` §11 |

---

## 5. 度量基线（供下次审查比对）

### 5.1 各模块规模

| 模块 | main 行 | test 行 |
|---|---:|---:|
| `ifmap-core` | 9218 | 3544 |
| `ifmap-json-tck` | 727 | 0 |
| `ifmap-json-jackson` | 298 | 464 |
| `ifmap-json-fastjson` | 588 | 626 |
| `ifmap-testkit` | 804 | 283 |
| `ifmap-provider-jdbc` | 1602 | 1273 |
| `ifmap-provider-remote` | 686 | 837 |
| `ifmap-spring-boot-starter` | 1544 | 1323 |
| `ifmap-admin-spring-boot-starter` | 3024 | 2073 |
| `ifmap-demo-pure-java` | 130 | 0 |
| `ifmap-demo-spring-boot3` | 363 | 122 |

### 5.2 装配面与配置面

| 项 | 值 |
|---|---|
| starter `@Bean` 出现次数 | 27 |
| starter `@ConditionalOn*` 出现次数 | 43 |
| admin `@Bean` 出现次数 | 14 |
| admin `@ConditionalOn*` 出现次数 | 19 |
| `IfmapProperties` 叶子键 | 25（5 个走注解 + 20 个走 getter） |

> `@ConditionalOn*` 次数远多于 `@Bean`，是"每个 bean 都可覆盖"的设计（`@ConditionalOnMissingBean` 逐项配对）的必然结果，**不是**配置面膨胀。

### 5.3 测试数

| 档 | 模块数 | 用例数 |
|---|---:|---:|
| JDK 17（`mvn clean verify`） | 12 | **508**（core 160 / jackson 69 / fastjson 76 / testkit 14 / jdbc 53 / remote 27 / starter 44 / admin 61 / demo-sb3 4） |
| JDK 8（`mvn clean verify`） | 9 | **399**（160 + 69 + 76 + 14 + 53 + 27） |

---

## 6. 方法复盘：为什么会误报 6/8

三类误报形态，下次直接照单排查：

| 形态 | 表现 | 本次命中 |
|---|---|---|
| **(a) 把「刻意的降级 / 容错」当成「两套做法 / 余量」** | 看到两个实现、三个别名、两层兜底就想合并 | P1-1（能力梯度）、P1-3①（P0-7 修复） |
| **(b) 把「文档承诺的交付形态」当成「一次性物料混进主产物」** | 看到 SQL/前端资源/默认值复制就判 C6 / C4 | P1-2（随 jar 发布）、P1-5（零构建链）、P1-7（逐字段一致） |
| **(c) 把「运维可调旋钮」当成「配置面 > 使用面」** | 数配置键数量，超过直觉值就砍 | P1-8 |

### 提交「可删」清单前的检查表

1. `grep` 生产调用点（E1）—— **排除 `/test/` 目录**；
2. 找 `@Bean` / `@ConditionalOn*` / Builder 链（E2）—— 注意 `@ConditionalOnProperty` 消费的键，getter 调用数会是 0；
3. **`grep` 五处承诺（E3）**：`docs/*.md`、`README.md`、`CHANGELOG.md`、测试 `@DisplayName`、**生产常量与 javadoc 里的路径/字符串**；
4. 上面三类证据全空，才写进「可删」；
5. 有条目进清单时，**在清单里为每条附一行「文档/测试证据」** —— 证据为空才是删的理由，否则就是破坏性变更。

> 第 3 步的价值在本次得到了验证：`docs/08:132`「随 jar 发布」、`JsonConfigMapperTest` 的 `@DisplayName`、`JdbcExecutionLogArchiver.ARCHIVE_DDL_HINT` —— 这三条全部**不在**"代码像不像过度设计"的视野里，却直接否掉了 3 条候选。

---

## 7. 遗留

| 项 | 状态 |
|---|---|
| P1 清单 | **已收敛，无剩余待动项** |
| 本轮未做 | 无 |
| 内部档仍在 `.gitignore` 之外 | `docs/ifmap-内置规则审计与优化_v1.md`、`docs/bankintconfig模块优化方案_v1.md`（内含 P0-7 / D8 的原始决策）、`docs/audit_report.md`、`docs/tools/` |
| 本档与 `docs/ifmap-整体设计_v1.md` | 已纳入版本跟踪；后者性质仍是内部档，新增内容前按 `docs/09` §5 口径自查敏感度 |
| P1-8 的默认值观察 | `clean-max-batches × clean-batch-size` = 100 万行/次，可择机下调（非缺陷） |
