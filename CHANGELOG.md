# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Changed
- **标识符改名 `bank*` → `partner*`（Java / API / UI / DDL 同步变更）**：把「银行」这个具体机构类型从**标识符**里去掉 —— 引擎本来就要覆盖银行、保理、信托、小贷、保险等各类对手方，`bank` 从第一天起就名不副实。**只改标识符，不动业务字段**：
  - **DDL 与迁移脚本**：列 `bank_code` → `partner_code`、`bank_name` → `partner_name`（`db/changelog/v1.0.0/001-create-ifmap-config.sql`，迁移 kit 的 `03-modify-and-index.sql` 用 `CHANGE COLUMN`、`00-precheck.sql` 的宽度体检按**源端旧列名**继续体检）；`idx_..._config_list` 索引列同步。**迁移脚本因此从「0 处改名」变成「2 处改名」**：kit 的 `README.md`、[`docs/10`](docs/10-迁移指南.md)、[`docs/11`](docs/11-远端配置源.md)、[`docs/04`](docs/04-接入与建表.md) 全部改口径，并新增「列改名是唯一的破坏性变更、会打断共存期（路径 A）」的三种处置办法（同步改存量 SQL / 建可更新兼容视图 / 把改名挪到停写窗口）
  - **Java**：`IfmapConfig` / `ConfigQuery` 的 `getPartnerCode()` / `setPartnerCode()` / `getPartnerName()` / `setPartnerName()`；`BankCall` → `PartnerCall`、`BankServiceGateway` → `PartnerServiceGateway`、`DemoBankGateway` → `DemoPartnerGateway`；`FullParam` 拼参顺序注释 `bank|*` → `partner|*`
  - **管理端 API / 页面**：字段 `partnerCode` / `partnerName`；页面标签与输入框提示「银行代码 / 银行名称」→「合作机构代码 / 合作机构名称」
  - **远端配置源**：`JsonConfigMapper` 认 `partnerCode` / `partner_code` / `partnerName` / `partner_name`，**并兼容存量键名** `bankCode` / `bank_code` / `bankName` / `bank_name`（**新键优先、旧键兜底**）→ 远端服务不用改、不用同步发版；`bank_code` 仍是**必填**字段（报错文案用新键名 `partnerCode`）。新增 2 个测试钉住「旧键能收」「新旧同现以新键为准」
  - **刻意不改**：真·银行领域字段（`bankOrgNo` / `bankAcctNo` / `bankCard`）、存量表前缀与存量类名（`bankint_*` / `IBankintConfigApi` / `BankintConfigManager` / `BankRules`）、迁移脚本里的**源端列名**（`00-precheck.sql` 的 `bank_code` 就是用来体检旧列的，改了反而错）
  - **MySQL 5.7 依据**：5.7 没有 `RENAME COLUMN`（8.0.28+ 才有），只能 `CHANGE COLUMN`。官方在线 DDL 表里「Renaming a column」虽是 In Place / 不重建表，但脚注限定 *"keep the same data type and only change the column name"* —— 本 kit 顺带改了列注释（`bank_name` 还收窄类型），故**仍然走 `COPY`**；已与类型变更合并进同一条 `ALTER`，不额外多一次重建

### Added
- **W12 远端配置源 + 配置库/日志库分离（设计 Q10，本版本）**：
  - **新模块 `ifmap-provider-remote`**：把 `ConfigRepository` 落到**远端 HTTP 接口**上 —— 存量系统已经有"配置中心接口"时（ECC 即如此）**一行表都不用迁**就能把引擎跑起来：配置仍留在老服务里，ifmap 只读，模板 / 分支 / 规则零改动
  - **模块名刻意不叫设计稿里的 `provider-feign`**：不硬依赖 Spring Cloud OpenFeign（Feign 的坐标与默认实现随 Spring Cloud 大版本变过，硬依赖等于把版本矩阵强加给宿主；而宿主自己的 Feign/RestTemplate 里已经有鉴权、超时、重试、熔断）。改为定义**函数式 SPI `ConfigFetcher`**（`get(uri, query)` / `postJson(uri, body)`，返回响应体原文）由宿主注入 —— 适配代码 10~20 行，模块依赖只有 `ifmap-core`（Java 8、**零 Spring 依赖**）。文档给 Feign / RestTemplate / 纯 Java 三种接法
  - **`ConfigFetcher` 的硬约定**（写进接口 javadoc）：返回响应体原文、查不到返回 `null`/空串、**非 2xx 与超时必须抛异常**（不许吞成空串 —— 否则"远端挂了"会表现成"没有配置"→ 业务静默不执行）、不在适配层做重试
  - **`JsonConfigMapper`（容错映射，7 条规则）**：① 键名归一化（去 `_`/`-` + 小写）→ `interfaceNo` / `interface_no` / `INTERFACE-NO` 等价，远端**加字段**不需要同步改 ifmap；② 缺失字段**按 DDL 默认值补齐**（与 JDBC 仓储读出来的对象逐字段一致）；③ 必填判定 = **DDL 里 `NOT NULL` 且无 `DEFAULT` 的列**（配置 5 个 / 分支 2 个），缺失或空白抛异常并指明"第 N 条缺哪个字段"；④ 数字容忍 JSON 数字与数字串（远端 VO 的 `keyId` / `interfaceOrder` 常是 `String`），小数 / 超 `int`·`long` / 非法串抛异常；⑤ 时间容忍 ISO-8601 与 `yyyy-MM-dd HH:mm:ss[.SSS]`；⑥ **文本列只接受字符串/数字/布尔，对象或数组直接抛异常**（不允许 `{a=1}` 这种 Java `toString` 悄悄落库）；⑦ **`logic_branch_order` 缺失时按下标（从 1 起，已删除行也占位）补齐** —— 保住存量"按返回顺序生效"的语义（一律填 0 会退化成按主键兜底，顺序可能变）
  - **响应体形状**：`null` / 空串 / 空白 / `"null"` / `"[]"` → 空列表；非数组 / 元素非对象 / `[null]` / 非法 JSON → `IfmapConfigException`（消息带响应体前 200 字符）
  - **`RemoteConfigRepository` 的三个"不信任远端"兜底**：① 映射后再本地筛一遍（配置 `del_status=0 且 status=1`、分支 `del_status=0`），远端 SQL 条件被改动时行为不跟着漂；② 本地排序（`interface_order` / `logic_branch_order` 升序、同值按 `key_id`）与 JDBC 实现的 `ORDER BY` 逐字一致；③ **空白查询条件不拼进查询串**（远端若按 `busi_node=''` 过滤会查不到）。`findConfig` 用 `Integer.valueOf(order).equals(...)`（包装类型不比引用）
  - **租户防串**：映射时**以调用方查询的租户为准**，远端报文里的 `tenantId` 键被忽略；空白 → 单租户 `-1`，非数字 → 抛异常（与 JDBC 仓储同口径）
  - **`saveExecutionLog`**：`POST {baseUri}/insert_log`，**远端显式回 `false` 时抛异常**（不静默丢审计；引擎侧照旧只降级为 WARN，不影响业务结果）
  - **Q10 的交付形态 = 不做多数据源框架**：接缝本来就是两个独立 SPI（`ConfigRepository` 读配置 / `ExecutionLogSink` 写日志），且所有自动配置 bean 都是 `@ConditionalOnMissingBean` —— 宿主写一个 `logDataSource` + 一个 `ifmapExecutionLogSink` bean 就完成分离（Spring Boot 官方"Configure Two DataSources"写法，`docs/05` §3.5）。不引 `dynamic-datasource`、不做 `@DS` 注解、**不做跨库事务**（两者本来就不在一个事务里）
  - **反向断言**：日志库连不上/表都没建时业务照常成功，且**配置库的日志表仍为 0 行** —— 证明"分离"不会静默回落到配置库（否则"分离"名存实亡）
  - **测试：新增 30 个** —— `ifmap-provider-remote` **25** 个（`JsonConfigMapperTest` 12：键名风格 / 默认值补齐 / 必填与消息 / 数字与时间 / 文本列拒对象 / 响应体形状 / 租户覆盖；`RemoteConfigRepositoryTest` 10：端点与查询参数、空白值不拼接、本地排序与筛选、远端 `false` 抛错、`baseUri` 归一、租户非法报错；`RemoteConfigRepositoryEngineTest` 3：**远端配置 → 模板渲染 → 出网 → 判定 → 分支动作 → 脱敏日志回写**全链路端到端）+ `IfmapSplitDatasourceTest` **3**（双 H2 库分离读写 / 日志库不可用不回落 / 单库场景不受影响）+ `ResponseJudgeTest` **+2**
  - 测试总数：JDK 17 全 reactor **436 个**（core 157 / json-jackson 46 / json-fastjson 53 / provider-jdbc 48 / **provider-remote 25** / starter 44 / admin 59 / demo-sb3 4），JDK 8 侧 **329 个**；双 JDK `clean verify` 均 `BUILD SUCCESS`，**10 个模块 SpotBugs 全 0 缺陷**（含新模块），新模块字节码 `major version: 52`
  - 文档：新增 [`docs/11-远端配置源.md`](docs/11-远端配置源.md)（SPI 约定 / 三种接法 / 映射规则 / 三个兜底 / 与存量接口的差异 / 自测）；[`docs/05-SpringBoot集成.md`](docs/05-SpringBoot集成.md) 新增 §3.5「配置库与日志库分离」（双数据源示例 + 做/不做清单）；[`docs/04-接入与建表.md`](docs/04-接入与建表.md) §2.4「不建表：配置在远端 HTTP 接口里」与 [`docs/10-迁移指南.md`](docs/10-迁移指南.md) §3.1 增加"第三条路：干脆不迁表"；[`docs/08-日志与合规.md`](docs/08-日志与合规.md) 交叉引用（分库 / 写回远端）；README 特性表 +2 行、模块表 +1 行、文档表 +1 行

- **W11 fastjson 兼容实现（设计 Q5 的"兼容模块"，v1.0）**（本版本）：
  - **新模块 `ifmap-json-fastjson`**：把 `JsonOps` 落到 **fastjson 1.2.84 + fastjson JSONPath** 上，让**存量用 fastjson 取值工具（如 ECC 的 `JsonParseUtils`）的项目可以零改动迁移**（模板里那些路径表达式照旧）。**定位是 compat-only**：新项目请用默认的 `ifmap-json-jackson`（不同 JsonPath 方言踩坑更少），模块 description 与文档都这么写
  - **版本选 1.2.84 而不是设计初稿写的 1.2.83**：`1.2.68~1.2.83` 在**默认配置下**存在可被利用的 AutoType RCE，1.2.84 是 backport 加固版；`pom.xml` 里把原因写进了注释，避免以后被人"顺手降版本"
  - **不碰全局状态**：模块只调 `JSON.parse(String, Feature...)` / `JSON.toJSONString(...)` / `JSONPath.eval(...)`，**不修改 `ParserConfig` 全局实例**、不用 `parseObject(Class)`（否则等于把 RCE 面搬回来）。带 `META-INF/services`（供纯 Java 场景 SPI 发现；`JsonOpsHolder` 发现多个实现会**直接报错**并提示 `JsonOpsHolder.set(...)`，不会静默挑一个）
  - **两个"不做就会错"的 fastjson 开关**：解析必须带 `Feature.OrderedField`（否则 Map 键序不可控 → 模板渲染顺序/快照指纹不可重复）；序列化必须带 `SerializerFeature.WriteMapNullValue`（默认会把**值为 null 的键整个丢掉**，破坏"键存在、值为 null"语义）
  - **安全语义：`@type` 报文显式失败**。fastjson 加固后遇到含 `@type` 自动类型信息的报文会**返回 null**（不抛异常）——原实现会把它变成"静默空值"。现在 `parseDocument` 区分"字面量 `null`"与"非 `null` 文本却解析成 null"，后者抛 `JSONException`（外层转 `IllegalArgumentException` / `IfmapConfigException`），与项目"禁静默取空值"的口径一致
  - **返回值的类型纪律**：递归把 `JSONObject`/`JSONArray` 转成 `LinkedHashMap`/`ArrayList`，公开 API 里**不出现任何 fastjson 类型**（有测试递归断言），键序保留
  - **方言差异的补偿与"不补偿"**（每条都有测试钉住）：① 通配/过滤器路径命中为空 → fastjson 返回 `null`，按契约转**空列表**（与"绝对路径不存在 → null"区分）；② `leafToNull=true` 时，仅对**"有界多值段 + 属性链"**形状（`$.items[*].opt`、`$.items[?(@.qty > 1)].sku`、`$.items[*].buyer.address.city`）逐父节点重取叶子补 `null` —— 递归下降 `$..x` 的父集合无界（各家实现顺序都不一样），**刻意不补偿**
  - **`isValidPath` 不能只靠 `JSONPath.compile`**：实测 fastjson 对 `$[?(` **编译期不报错**（到求值才抛 `StringIndexOutOfBoundsException`）。故先做"以 `$` 开头 + 引号外括号配对"语法体检再编译 —— 比 Jackson 实现更严（`$.a[` 在 fastjson 侧直接判非法，jayway 编译能过），**属已知差异、文档如实写**
  - **已知差异清单（6 条，都写进文档并被测试钉住）**：① 对象根上的 `$[*].x` 不展开为各 value（fastjson 原生，模板请写具体字段或 `$.field[*]`）；② `parse`/`isJson` 沿用 fastjson 宽松语法（单引号/无引号键/尾逗号都算合法）；③ `$..x + leafToNull` 不补 `null`；④ `@type` 报文抛异常；⑤ `isValidPath` 更严；⑥ `toJson` 的 POJO/`Date` 形态按 fastjson 默认（快照/日志以纯 JSON 结构为准）。**取值语义（类型、null、空列表、数字不隐式转字符串）已由 TCK 统一**，6 条之外若不一致即两边测试都红
  - 测试：新增 **53 个** —— `FastjsonJsonOpsConformanceTest` 24（**与 Jackson 实现同一套 TCK 契约，首跑即全绿**）、`FastjsonJsonOpsTest` 8（SPI 发现 / 无 fastjson 类型泄漏 / `@type` 转异常 / 对象根通配 / `$..`+leafToNull / 宽松 JSON / 属性链补 null）、`FastjsonPathsTest` 4（语法体检、多值判定、多值段拆分边界）、`IfmapEngineTest` 5 + `TemplateEngineTest` 12（**把 Jackson 侧的同名端到端用例原样再跑一遍**，证明"换 JSON 实现不改模板语义"）；json-fastjson 模块 **36** 个单测全绿
  - 测试总数：JDK 17 全 reactor **406 个**（core 155 / json-jackson 46 / **json-fastjson 53** / provider-jdbc 48 / starter 41 / admin 59 / demo-sb3 4），JDK 8 侧 **302 个**；`clean verify` 双 JDK 都是 `BUILD SUCCESS`、SpotBugs 仍为 **0 缺陷**
  - 文档：[`docs/05-SpringBoot集成.md`](docs/05-SpringBoot集成.md) 新增 §3.2「JSON 实现：默认 Jackson，兼容 fastjson」（换法 + 已知差异表 + "为什么必须自己声明 bean"）；[`docs/10-迁移指南.md`](docs/10-迁移指南.md) 新增 §4.5「JSON 取值实现（存量用 fastjson 时看这里）」+ 行为对齐清单 +1 条 + 排错 +1 条；**修正 [`docs/07-管理端REST.md`](docs/07-管理端REST.md) §5 的错误陈述**（原写"JsonPath 编译期就能拦住 `$.a[`"，实测 **jayway 拦不住**、而 fastjson 实现能拦住 —— 文档与实现不一致比没文档更糟）；README / `docs/index.md` / 设计文档 §4.2 §4.5 §11.1 §12.2 同步
- **W10 执行日志分区 + 冷热分离（可选运维能力，默认不启用）**（本版本）：
  - **可选手册套件**（`ifmap-provider-jdbc/src/main/resources/db/optional/execution-log-partition/`，7 个文件）：`README.md`（三方案选型、改造与回滚、加分区、排错表、变更单模板）+ 6 个编号脚本：`00-precheck.sql`（只读体检：版本/参数/长事务、表体量与精确边界、月度分布、主键与索引、外键/触发器/例程、是否已分区）、`01-partition-hot.sql`（**路线 A 停写窗口**：影子表 + 逐月 `INSERT ... SELECT` + **原子 `RENAME TABLE`** + 回滚窗口；**路线 B `pt-online-schema-change`**：强调必须显式写 `DROP PRIMARY KEY, ADD PRIMARY KEY (key_id, add_time)` 与分区子句，否则报 1503）、`02-add-monthly-partition.sql`（`REORGANIZE PARTITION pmax INTO (...)` 加下月分区 + `pmax` 必须为空的断言 + 一次生成未来 N 个月 DDL 的只读语句）、`03-verify.sql`（分区清单 / `pmax` 为空 / 主键与索引 / 逐月计数对账 / `EXPLAIN` 分区裁剪 / 收尾清单）、`04-create-archive-table.sql`（冷表 `ifmap_execution_log_archive`：热表 17 列 + `archive_time`，`PRIMARY KEY (key_id)`，3 个查询索引；注释里给出"冷表也按月分区"与"冷表放独立库"两个变体）、`05-archive-and-drop.sql`（裸 SQL 手工搬一批的完整示例 + **先归档再 `DROP PARTITION`** 秒级回收流程）
  - **`JdbcExecutionLogArchiver`（归档器，provider-jdbc）**：`archive(olderThanDays[, batchSize, maxBatches, batchSleepMillis])` → 不可变结果 `LogArchiveResult`（`archived` / `removed` / `batches`）。每批三步：查 `key_id` → 剔除冷表已有 → `INSERT`(17 列显式列举) + `DELETE`。**刻意不做跨表事务**：两条语句各自提交，中断后重跑靠"剔除已存在 + 照常删"收敛 —— 用正确定义的重入语义换掉大事务（binlog 暴涨 / 主从延迟 / 长锁）。同时**避开** `DELETE ... WHERE key_id IN (SELECT ... FROM 同一张热表)` 这种 MySQL 1093 写法，因此单测能用 H2 跑真实生产 DDL
  - **两边结构漂移会直接失败**：跑之前用 `SELECT * ... WHERE 1 = 0` 读元数据核对**热表列与内置 `ARCHIVED_COLUMNS` 完全相等**（多列/少列都报错，消息里列出差异列名与"三处需同步"的位置）；冷表必须包含全部归档列（可多 `archive_time`）；冷表不存在时报错指向 `04-create-archive-table.sql`。目的：将来给执行日志表加列却忘了同步归档器，是**构建/运行期直接失败**，而不是悄悄少归档一列
  - **不自动调度**（与"分区 DDL 默认不启用"同口径）：归档改变数据留存形态、属运维决策，由宿主自己的调度体系调用（文档给 `@Scheduled` / xxl-job 示例），多实例部署须自行加分布式锁；并发跑会在冷表主键上冲突报错（有意的失败，不是静默写重）
  - `TableNameResolver` 新增 `executionLogArchiveTable()`（固定 `<前缀>execution_log_archive`，不可单独配置），`tables()` 由 4 张变 5 张
  - 文档：[`docs/08-日志与合规.md`](docs/08-日志与合规.md) §5.1 改为三方案选型表 + 新增 §5.2「按月分区（可选手册）」（三个最容易踩的点：必须有 `pmax` 兜底否则写入报 1526、有 `pmax` 就不能 `ADD PARTITION` 只能 `REORGANIZE`、改分区=重建表）+ §5.3「冷热分离」（归档器 API、宿主调度示例、三条设计理由、并发约束），§8 排错 +4 条、§7 合规清单 +1 条；[`docs/04-接入与建表.md`](docs/04-接入与建表.md) 文件树与策略表同步；设计文档 §6.4 方案表 / 附录文件树 / §11.1 roadmap 由「roadmap」改为「已交付（W10）」
  - 测试：新增 **15 个** `JdbcExecutionLogArchiverTest`（只搬过期行 / 搬迁保真（逐列读回 + 时间戳）/ 阈值边界 / 分批（5 行 batchSize=2 → 3 批）/ `maxBatches` 护栏 / **可重入幂等**（冷表已有同 `key_id` → `archived=0`、`removed=1`）/ 冷表不存在报错指向脚本 / 热表列漂移报错 / 冷表缺列报错 / 表前缀 `bankint_` / 阈值 &le; 0 拒绝 / 线程中断只搬一批 / `ARCHIVED_COLUMNS` 与生产 DDL 一致 / `cutoffBefore` 换算 / 冷表 DDL 形状），`TableNameResolverTest` 同步（前缀拼名 + `tables()` 5 张）；provider-jdbc 由 33 → **48 个**，全 reactor **353 个全绿**
- **W9 管理端可视化页面 + 字典 SPI**（本版本）：
  - **零构建管理页面**（`ifmap-admin-spring-boot-starter` 自带的静态资源，`src/main/resources/META-INF/ifmap-admin-ui/`）：`index.html` + `app.css` + `app.js`（约 900 行原生 JS），浏览器打开 `{ifmap.admin.base-path}/ui/`（`/ui` 自动 302 到 `index.html`）。四个页签：**配置**（分页筛选 / 新建 / 编辑 / 保存前校验 / 试跑 / 启停 / 删除 / 历史 / 回滚）、**逻辑分支**（含兜底分支标记）、**巡检**（分条展示 + 一键复制 Markdown 报告）、**规则与字典**（`/rules`、`/actions`、`/strategies`、`/enums` 全部服务端驱动，页面不硬编码）。**无 CDN、无前端框架、无构建链**（内网离线可用）；页面顶部「操作人」存 `localStorage`，写请求自动带 `X-Operator-Id` / `X-Request-Id`；静态资源 `Cache-Control: no-cache`（升级后刷新即新版）
  - **前缀绝不硬编码**：`BASE` 从 `location.pathname` 反推（写死 `/ifmap/admin` 会在改 `base-path` 的部署上整页 404），有测试盯着这条
  - **字典 SPI（可选，设计 Q8）**：`IfmapEnumProvider`（函数式接口，`Map<String, List<EnumOption>>`，key = 模板字段名）+ 不可变值对象 `EnumOption`（`value` 必填、`label` 缺省用 `value`）+ `IfmapEnumCatalog`（无 provider / `null` / 脏项都安全降级）。新增端点 `GET /enums`：**没注册也返回 `200 {}`（不是 404）**，页面只多一条「没有字典」分支；`IfmapEnumCatalog` **恒为 bean**（而不是两条 `@ConditionalOnBean` 分支），避免「有/无 provider」两套装配路径
  - 静态资源通过 `addResourceHandlers` 挂在 `{base-path}/ui/**`（不受 `addPathPrefix` 影响），另加一个 `@Controller` 只为 `/ui`（无尾斜杠）做 302
  - 文档：[`docs/07-管理端REST.md`](docs/07-管理端REST.md) 新增 §11「可视化页面」（能力表、为什么不引框架/CDN、字典 SPI 约定、页面自身的护栏测试），并修正 §3.4 **审计头文档与实现不一致**（实现里是 `X-Operator-Id` / `X-Request-Id`，文档原写作 `X-Ifmap-Operator` / `X-Ifmap-Request-Id`）；§9 补两条排错（反代必须整段转发 `{base-path}`、字典空是正常的）
  - 测试：新增 **17 个**（`IfmapEnumCatalogTest` 6 —— 无 provider / 脏数据 / 防御性拷贝；`IfmapAdminUiEndpointTest` 4 —— 真 Tomcat 下 302、三个静态资源可取且类型正确、静态资源**不越界**、`/enums` 带出宿主字典；`IfmapAdminUiScriptTest` 5 —— 页面脚本静态契约；`IfmapAdminAutoConfigurationTest` +2 —— 字典 bean 两态）；`IfmapAdminWebEndpointTest` 补断言「`X-Operator-Id` 真的落到历史 `operatorId`」；JDK 17 全 reactor **338 个全绿**（core 155 / json-jackson 46 / provider-jdbc 33 / starter 41 / admin 59 / demo-sb3 4）
  - **页面脚本的静态契约测试**（`IfmapAdminUiScriptTest`，手写 JS 无法靠编译期拦错）：① `app.js` 里每个接口路径都在白名单内（写错一个字母 = 空白页）；② 字符串字面量里不许出现 `/ifmap` 开头的硬编码前缀（注释里讲清楚风险不算）；③ `el('id')` 引用的元素必须在 `index.html` 里存在（否则交互静默失效，动态生成的 `ed-*` / `br-*` / 分页按钮除外）；④ 页面无任何外链（`src`/`href` 不得指向 `//` 或 `http(s)://`）；⑤ 有 `node` 时跑 `node --check`（环境无 node 则 `Assumptions` 跳过，不让构建挂）
  - 开发期调试用的页面冒烟脚本（假 DOM + 假 fetch，43 项断言）只放在仓库外的临时目录，**不随仓库提交**；落盘的只有上面两个基于 Spring / 真实资源的测试
- **W8 迁移预热**（本版本）：
  - **存量表迁移 SQL kit**（`ifmap-provider-jdbc/src/main/resources/db/migration/ecc-to-ifmap/`，7 个文件）：`README.md`（执行手册：三条路径、每步算法/锁语义、大表 gh-ost 命令、回滚、排错表、checklist、行为对齐清单、免责声明）+ `00-precheck.sql`（只读体检：唯一键冲突按 `CAST(interface_order AS UNSIGNED)` 分组、非数字顺序号、NULL→NOT NULL 列、长度超限、零值时间、时区、基线快照）/ `01-add-columns.sql`（纯 `ADD COLUMN`，`ALGORITHM=INPLACE, LOCK=NONE`）/ `02-backfill.sql`（软删唯一化 + 分支顺序自连接回填）/ `03-modify-and-index.sql`（类型变更 `ALGORITHM=COPY, LOCK=SHARED` + 唯一键/索引；日志大表按体量走 gh-ost；索引单独在线 `INPLACE, LOCK=NONE`）/ `04-rename-tables.sql`（切换时刻的 `RENAME TABLE` + 回滚）/ `05-verify.sql`（列数 28/17/17、索引 9 条、回填完整性、行数 + `SUM(key_id)` 指纹、时间抽样、分支顺序、查询冒烟）。**随 jar 发布、不会被 Liquibase/Flyway 自动执行**（不在 changelog 主入口目录，文件名也不符合 Flyway 规范）；库中表名以 `bankint_` 前缀书写，其它前缀用 `sed -i 's/bankint_/yourprefix_/g' *.sql` 全局替换
  - **`ifmap-json-tck` 模块**（新）：`JsonOpsConformanceTestBase`（**24 个契约用例**）+ `JdkNativeValues`（递归断言返回值只含 JDK 原生类型）。任何 `JsonOps` 实现继承基类即得全套一致性检查（含“路径不存在 → null”与“过滤器/通配不命中 → 空列表”的语义区分、数字-字符串不隐式转换、上下文可复用、非法路径抛 `IfmapConfigException`）；`JacksonJsonOpsConformanceTest` 为 Jackson 实现的空壳继承 —— 换 JSON 库只需覆写一个方法
  - **影子运行框架**（`cn.cj.ifmap.core.shadow`，12 个类）：`ShadowRunner` + `ShadowRequest`/`ShadowTarget`/`ShadowEngine`/`ShadowExtractor`/`ShadowOutcome`/`ShadowComparer`/`ShadowFieldDiff`/`ShadowDiffSink`/`LoggingShadowDiffSink`/`ShadowRunResult`/`ShadowOptions`。同一笔业务双跑，**主链路结果原样返回、异常原样上抛**；影子链路异常与差异出口异常**只记录不外泄**；`legacyRawResponse` 回填存量原始响应后影子**不再对资方出网**（单测用“出网计数为 0”钉住）；支持采样率、`ignoreKeys`、数字宽容比对、字符串去空格、差异条数上限；差异出货前统一走 `LogMasker` 脱敏。引擎侧抽象为 `ShadowEngine`（而非直接依赖 `final` 的 `IfmapOrchestrator`）以便单测替换与迁移期换实现
  - **公开迁移指南** [`docs/10-迁移指南.md`](docs/10-迁移指南.md)：M0 摸底 → M1 表结构 → M2 规则/策略对齐 → M3 影子运行 → M4 切换与回滚；共存三路径（前缀指向 / `RENAME` / 新表搬迁，并说明为什么不能用视图兜底）；M2 的“配置字符串 → 注册点”对照表与启动期自检动作；影子跑退出条件（连续 7 天零差异）与假差异排查顺序；10 条行为对齐清单与排错速查
  - 测试：新增 **53 个**（core `ShadowRunnerTest` 22 + `ShadowEngineIntegrationTest` 5 + `IfmapOrchestratorTest` 兜底分支 2 + json-jackson `JacksonJsonOpsConformanceTest` 24（TCK 契约套件））；JDK 17 全 reactor **321 个全绿**（core 155 / json-jackson 46 / provider-jdbc 33 / starter 41 / admin 42 / demo-sb3 4），JDK 8 侧 **234 个全绿**；两轮 `clean verify` 都是 `BUILD SUCCESS`，SpotBugs 仍为 **0 缺陷**
- **W7 开源工程化**（同版本，前一批）：
  - `NOTICE`（版权 + 第三方组件许可说明）；`CONTRIBUTING.md`（贡献入口）与 [`docs/09-参与贡献.md`](docs/09-参与贡献.md)（开发环境、双 JDK 构建、TDD 流程、代码/测试/文档约定、开源合规、质量门禁、PR 检查表）
  - **许可头全量落地**：166 个 `*.java` + 5 个 `*.sql` 全部带 Apache-2.0 许可头（Liquibase changelog 的 `--liquibase formatted sql` 仍保持在首行，许可头插在它之后）
  - **`LicenseHeaderTest`（core 测试，3 例）**：`mvn test` 即校验「LICENSE/NOTICE 存在且非空」「每个 `.java` 都以许可头开始」「SQL 也带许可头且 changelog 标记行未被顶掉」。用测试而不是 `license-maven-plugin`，是为了"零新增构建期依赖也能拦住漏加"，且 JDK 8 矩阵同样会跑到
  - **SpotBugs 质量门禁**：绑定 `verify` 阶段（`effort=max`、`threshold=medium`、不分析测试代码），**JDK 11+ 自动启用**（SpotBugs 4.8.x 运行时要求 Java 11；JDK 8 矩阵不跑），临时跳过用 `-Dspotbugs.skip=true`；排除清单 [`spotbugs-exclude.xml`](spotbugs-exclude.xml) **逐条写明理由**（最终 6 个模块全部 **0 条**；口径 = 各模块 `target/spotbugsXml.xml` 里 `<BugInstance ` 的条数，复现：`mvn -B -ntp spotbugs:spotbugs`）（只放行 `EI_EXPOSE_REP*` / `CT_CONSTRUCTOR_THROW` / `MS_SHOULD_BE_FINAL` 三族，并说明"放行规则族"对应的人工 review 三条）
  - **文档站**：`mkdocs.yml`（MkDocs + Material，10 页 nav，`strict: true`）+ `docs/index.md`（站点首页）+ `docs-requirements.txt`；`exclude_docs` 排除内部文档，保证开发机本地 `--strict` 与 CI 行为一致
  - **CI 矩阵扩展**（`.github/workflows/ci.yml`）：JDK 8/11/17/21 构建矩阵（每个 JDK 都跑 `clean verify`，即含 SpotBugs）+ 独立 `static-analysis` job（`spotbugs:check`，让静态分析失败一眼可见）+ 独立 `docs` job（`mkdocs build --strict`，文档相对链接写错即挂）
  - README：CI / License / JDK / docs 四枚徽章；状态表补「静态分析 / 开源合规 / 文档站」三行
- **W6 日志与合规**（本版本）：
  - `ifmap-provider-jdbc`：新增 `JdbcExecutionLogCleaner` —— 按保留期**分批**删除过期执行日志（设计 §6.4 方案 A / ADR-10）。先 `SELECT key_id ... LIMIT n` 再 `DELETE ... WHERE key_id IN (...)`：`DELETE ... LIMIT` 是 MySQL 方言、`DELETE ... IN (SELECT 同表)` 在 MySQL 上报 1093，两步走 MySQL/H2 都合法（单测因此能用 H2 跑真实生产 DDL）。带**批间停顿**（给从库追 binlog）、**`maxBatches` 护栏**（没删完留给下个周期并 WARN）、**中断安全**（被中断即停止本轮并保留中断标记）、**表名前缀生效**（复用 `bankint_execution_log` 也认）
  - `ifmap-spring-boot-starter`：新增 `IfmapLogCleanTask`（`cleanNow()` 可手工触发；清理异常只 WARN 返回 `-1`，**不让后台任务异常影响业务**）与 `IfmapLogCleanScheduler`（`CronExpression` 驱动，默认每天 03:30）。**不用 `@EnableScheduling`**：自动配置不该打开宿主的全局调度设施，且 Spring 无 TaskScheduler 时建的是**非守护**线程 → 非 Web/批处理应用 `main()` 返回后 JVM 不退出（demo 实测卡死；改守护线程后 `java -jar` exit=0）
  - 自动装配：新增 `LogCleanConfiguration`（清理器 bean，只要求 `DataSource`，关掉定时任务后仍可手工调用）与 `LogCleanSchedulingConfiguration`（任务 + 调度器，受 `ifmap.log.enabled` 与 `ifmap.log.clean-enabled` 双重开关门控）
  - 配置项：`ifmap.log.clean-enabled=true` / `retention-days=90` / `clean-batch-size=1000` / `clean-max-batches=1000` / `clean-batch-sleep-millis=50` / `clean-cron=0 30 3 * * ?`；**保留期 &le; 0 直接拒绝执行**（防配成 0 退化为"删全表"）
  - 文档：[`docs/08-日志与合规.md`](docs/08-日志与合规.md)（落什么/不落什么、脱敏、截断、失败降级、保留期清理与分区方案对比、异常体系、10 条合规自查清单、排错表）；`docs/06` 补交叉引用
  - 测试：新增 **19 个**（provider-jdbc `JdbcExecutionLogCleanerTest` 8（保留期边界、按批续删、`maxBatches` 封顶、`retentionDays<=0` 拒绝、表前缀、中断即停）+ starter `IfmapLogCleanTaskTest` 11（装配三态、配置绑定、非法 cron 启动期失败、**每秒 cron 真触发清理**、**调度线程是守护线程**、真实清理、失败降级、`retention-days=0` 不删数据、宿主覆盖））；JDK 17 全 reactor **264 个测试全绿**，JDK 8 侧 **178 个全绿**

- **W5 管理端 REST**（本版本）：
  - 新模块 `ifmap-admin-spring-boot-starter`：把"改资方接口配置"从**改库 + 发版**变成**页面点点 + 留痕 + 可回滚**（设计 §8）。**默认关闭**（`ifmap.admin.enabled=true` 才装配）+ 三重门控（Web + `JdbcTemplate` + `DataSource`）；**不内置鉴权**，需宿主自行把前缀挂到网关/`SecurityFilterChain`
  - 配置端点：分页列表 / 详情 / 新增 / 修改（乐观锁）/ 逻辑删除 / 启停 / **保存前校验**（`POST /configs/validate`，不过则 422 + `errors`/`warnings` 明细）/ **试跑**（`POST /configs/dry-run`，与线上同一条链路，只把出网换成假应答）/ 变更历史 / **一键回滚**
  - 逻辑分支端点：`GET/POST/PUT/DELETE /branches`；元数据端点：`/rules`（`@FUN` 可用清单）、`/actions`、`/strategies`、`/audit`（全量巡检，可返回 Markdown 报告）
  - `ConfigValidator`：11 项保存前校验（必填列 / 模板 JSON 合法 / `$.path` 合法 / `@FUN` 规则与重载可匹配 / `strategy_name` 已注册 / 前置链成环 / 唯一键冲突 / 分支默认兜底 ≤ 1、顺序唯一、动作已注册）
  - `ConfigSnapshotMapper`：历史快照用**显式业务列 → 有序 Map → `JsonOps.toJson`**（不用 Bean 反射序列化：少 JSR-310 模块依赖、不写进 `version`/审计列、字段增删不静默丢字段），并给出字段级 diff
  - `ConfigAuditor`：运行期全量巡检（跨租户），`audit-max-configs` 上限 + `truncated` 标记"报告不完整"
  - `ConfigAdminService`：每次写操作 = 校验 → 落库（乐观锁）→ **回读** → 写历史 → **失效缓存**；回滚 = 读快照 → **先校验** → 走乐观锁写回 → 再记一条 `UPDATE` 历史
  - `ifmap-core`：`ConfigQuery`（分页/筛选，`MAX_SIZE=200`，`allTenants` 跨租户开关）/ `PageResult<T>` / `ConfigHistoryRepository` / `IfmapConfigHistory`（含 `CREATE/UPDATE/DELETE/ENABLE/DISABLE`）/ `JsonOps.isValidPath`
  - `ifmap-provider-jdbc`：`JdbcConfigHistoryRepository`（写/查历史）；`JdbcConfigRepository` 增 `queryConfigs` / `countConfigs` / `queryConfigsByInterface` / `queryConfigsByBusiNode` / `queryLogicBranches(includeDeleted)` / `findLogicBranch`；`JdbcConfigWriter` 增 `updateStatus` / `update(LogicBranchConfig)` / `softDeleteBranch` / `findByKeyId`
  - 配置项：`ifmap.admin.*`（`enabled` / `base-path` / `history-limit` / `audit-max-configs`）
  - 文档：[`docs/07-管理端REST.md`](docs/07-管理端REST.md)
  - 测试：新增 **41 个**（`ConfigAdminServiceTest` 14 / `ConfigValidatorTest` 10 / `IfmapAdminWebEndpointTest` 5（H2 + 真 Tomcat + 真 HTTP）/ `IfmapAdminAutoConfigurationTest` 5 / `ConfigSnapshotMapperTest` 4 / `ConfigAuditorTest` 3）、starter +1（非 MySQL `json` 列降级）；JDK 17 全 reactor **245 个测试全绿**，JDK 8 侧 **170 个全绿**

### Fixed（W12）
- **`success_value` 的多值分隔符：文档写错，以实现为准回写**。文档（[`docs/04`](docs/04-接入与建表.md) 列说明、[`docs/06`](docs/06-执行编排与策略扩展.md) §7 示例、`001-create-ifmap-config.sql` 列注释）写作 `|`，而实现是 `ResponseJudge.MULTI_VALUE_SEPARATORS = {";", ","}`（**不含 `|`**）→ 全部改为 `,` / `;`。核实存量 ECC：`BankintConfigManager.SEG_STR = ","`，真实数据里 `|` 只出现在 JSONPath 的 `||` 里 —— `|` 是设计稿的臆测。**`0000|S` 是"一个候选值"而不是两个**（已用 `ResponseJudgeTest.pipeIsNotASeparator()` 钉住）
- **如实文档化"取反"缺口（本轮不实现）**：存量 `success_value` 支持 `!值`（ECC `NON_STR = "!"`，语义"不等于该值即成功"），ifmap **不支持** → 在 [`docs/06`](docs/06-执行编排与策略扩展.md) §7 与 [`docs/10-迁移指南.md`](docs/10-迁移指南.md) §7 行为对齐清单（新增 row 12）写明"迁移前先扫 `success_value like '!%'` 改成等价写法"，并用 `ResponseJudgeTest.negationPrefixIsNotSupported()` 把缺口钉住 —— 防止以后被"顺手实现"成一半（`!0000` 现在只是一个永不命中的字面候选值，属已知语义差异而非静默错误）

### Fixed（W7）
- `ConfigValidator.checkUniqueKey` 用 **`Integer == Integer`** 比较顺序号 → `Integer` 只缓存 `-128..127`，**顺序号 ≥ 128 时"唯一键冲突"静默漏判**（校验形同虚设，重复配置能被写入）。改为 null 安全的 `sameOrder()`（`equals`）；并补**在旧代码上确认会红**的回归测试 `uniqueKeyDetectsConflictBeyondIntegerCache`（顺序号 200）
- `IfmapConfigHistory#getAddTime/setAddTime` 直接进出 `java.util.Date` → 调用方能改到内部状态；改为存取都做副本
- `ListRules.SmartComparator` 未实现 `Serializable` → 它会被 `Collections.reverseOrder(...)` 包进**默认序列化**的 `ReverseComparator`，序列化外层结果时会抛异常；补 `implements Serializable` + `serialVersionUID`
- `IfmapOrchestrator.StepOutcome#getData`、`PageResult#getRows` 直接交出内部集合 → 改为"拷贝 + 不可变视图"（与 `IfmapResult` 一致）
- `InMemoryConfigCache` 匿名 `LinkedHashMap` 子类里 `size()` 的实际调用对象有歧义（外层类也有 `size()`）→ 显式写 `super.size()`
- `ApiError.ok` 是恒为 `false` 的实例字段（SpotBugs `SS_SHOULD_BE_STATIC`）→ 删掉字段，`isOk()` 直接返回 `false`，JSON 里仍有 `"ok": false`

### Fixed（W8）
- `IfmapOrchestrator` 的**兜底分支从不生效**：`logic_branch_flag` 为空的分支在常规匹配里被判 `false` 并打 WARN，导致“存量用空标志表达兜底分支”的配置迁移后**命中不到任何分支 → 业务回调静默不执行**（最危险的一类静默差异）→ 改为**两趟匹配**：先按 `logic_branch_order` 升序找首个常规分支命中，全部未命中才执行兜底分支（**兜底分支排在前面也不会抢命中**，因此免疫人工配错顺序）；存量配置**数据零改动**即可行为一致（TDD：先写 2 个用例红，再改引擎转绿）
- 兜底分支的**文档/注释口径**统一为两个正交维度：`logic_branch_flag` 空 = **兜底分支**（最后生效），`method_flag` 空 = **该分支不执行动作**；修正 `LogicBranchConfig` javadoc（5 处）、`002-create-logic-branch.sql` 列注释、`docs/07` 与 `CHANGELOG` 相应描述
- `LicenseHeaderTest` 的 Liquibase 标记规则过宽（`^\d{3}-.*\.sql$` 会误拦 `db/migration/` 下的迁移脚本，逼着给非 changelog 脚本加假标记）→ 收敛为“路径含 `db/changelog/` 且文件名形如 `NNN-*.sql`”

### Changed（W6）
- provider-jdbc 测试基座 `TestSchema` 与生产 `IfmapSchemaInitializer` 对齐：非 MySQL 方言下把 `json` 列降级为 `text`（断言式改写，预期列数不符即失败）—— 测试库若保留 H2 的 `JSON` 类型，测的就不是生产语义

### Fixed（W5）
- `JdbcConfigWriter.insert/update(LogicBranchConfig)` 把 `logic_branch_flag` / `method_flag` 当必填 → 但 DDL 里 `logic_branch_flag` 是 `NOT NULL DEFAULT ''`、`method_flag` 允许为空（**空 = 该分支不执行动作**，与「兜底分支」不是一回事，后者看 `logic_branch_flag`），真实数据会被拒 → 只校验 `interfaceNo` + `logicBranchName`，其余统一 `null → ""` 落库
- `ConfigValidator.validateBranch` 要求 `method_flag` 必填 → `method_flag` 合法为空，改为只校验 `interfaceNo` + `logicBranchName`；并补「**兜底分支最多 1 条**」的**单条**校验（引擎取首个命中者，第二条永远不生效，属"配了但没用"的静默陷阱）
- `ConfigValidator.checkTemplateContract` 在没有 `ContractValidator`（无引擎）时**完全跳过模板检查** → 改为至少做 JSON 语法兜底校验：历史脏数据（手改 SQL / 旧版本写入的非法 JSON）不能静默放过
- `JdbcConfigRepository.whereClause` **总是**过滤 `tenant_id = ?`（`null` → `-1`）→ 全量巡检实际只看 `-1` 租户、结果恒为 0 条；新增 `ConfigQuery.allTenants`，巡检按行取各自租户
- `GET /configs/{keyId}` 不存在时抛的是 `IfmapConfigException`（400），但 Controller 注释写的是 404/409 → 统一为「**200 + `ok=false`** 表示业务没做成（乐观锁冲突等），400 表示用错接口/目标不存在，422 表示校验未通过」并写进文档
- `POST /configs/validate` 校验不通过也返回 200 → 改为 **422 + `errors`/`warnings`**（与新增/修改走同一套判定，前端可直接展示）
- `IfmapSchemaInitializer` 在非 MySQL 方言下直接执行 `json` 列 → **H2 1.4.200 的 `JSON` 类型与 MySQL 的 `json` 语义不等价**：JDBC 写字符串会被包成 JSON 字符串字面量（`{"a":1}` 读出成 `"{\"a\":1}"`），历史快照/回滚必然解析失败 → 非 MySQL 分支把 `json` 列降级为 `text`（断言式改写，注释里的 "json" 不受影响），H2 上读写口径与 MySQL 一致

### Added
- **W4 执行编排与策略 SPI**（本版本）：
  - `ifmap-core`：**编排器 `IfmapOrchestrator`** —— 租户解析 → 配置加载（**递归前置接口** `front_interface_no`，深度护栏 64 + 环检测）→ 拓扑排序（`interface_order`）→ 组包 → 渲染 → 特殊处理 → 出网 → 判定 → 分支动作 → 落执行日志；`stopOnFailure` 控制失败即停；第四参数 `mockResponse` 支持 **dry-run 试跑（不出网）**
  - `ifmap-core`：公开模型 `IfmapRequest`（不可变 + Builder，租户/业务号/操作人/请求头/attributes）与 `IfmapResult`（`executedInterfaces` / `matchedBranch` / `elapsedMs`），`PartnerCall`
  - `ifmap-core`：**策略 SPI** —— `SpecialDealStrategy`（按 bean 名，对应 `strategy_name`）、`FullParamStrategy`（`partner|busi` 查找顺序：精确 → `partner|*` → `*|busi` → `*|*`）、`LogicBranchStrategy`（`match(context)`）、`IfmapActionHandler`（`@IfmapAction`，替代 `method_flag` 反射调用）、`IfmapCallbackHandler`（`@IfmapCallback`，替代 17 个回调方法）
  - `ifmap-core`：注解 `@IfmapAction` / `@FullParam` / `@LogicBranch` / `@IfmapCallback` + `CallbackRegistry` / `ActionRegistry` / 三个策略注册表（含冲突与别名诊断）
  - `ifmap-core`：SPI `TenantResolver`（`HeaderTenantResolver`：上下文 > 请求头 > 默认 `-1`）/ `ClockProvider` / `LogMasker` / `ExecutionLogSink` / `PartnerServiceGateway` / `ConditionValueResolver`
  - `ifmap-core`：**合规脱敏** `DefaultLogMasker`（手机 / 证件 / 卡号按值形态保留 6+4 或 3+4、姓名字段按字段名、`excludeFields` 整体 `***`、幂等、非 JSON 安全）+ `Logs.truncate`（超出阈值尾部打标 `...truncated`）
  - `ifmap-core`：**判定器 `ResponseJudge`**（`result_flag` 为空 = 成功；`success_value` 多值 `;`/`,`、忽略大小写；响应非 JSON 只判失败不抛异常）
  - `ifmap-core`：**契约自检 `ContractValidator` / `ContractReport`**（模板 JSON 合法性 + `@FUN` 规则名存在 + 参数个数/类型可匹配重载，输出可直接贴工单的 Markdown）
  - `ifmap-core`：异常 `IfmapStrategyException` / `IfmapRemoteException`
  - `ifmap-core`：`util/Annotations.find`（沿父类链 + 接口链查注解，**CGLIB 代理安全**）
  - `ifmap-spring-boot-starter`：`IfmapStrategyRegistrar`（`BeanPostProcessor`）**自动收集 5 类策略 bean**；实现策略接口却**缺注解 → 启动期快速失败**（报错含 bean 名 + 类名）
  - `ifmap-spring-boot-starter`：编排相关 bean（`TenantResolver` / `LogMasker` / `ClockProvider` / 5 个注册表 / `ContractValidator` / `ExecutionLogSink` / `IfmapOrchestrator`），全部 `@ConditionalOnMissingBean`；`OrchestrationConfiguration` 用 `@ConditionalOnBean(ConfigRepository.class)` 门控（无数据源时不装配编排器）
  - `ifmap-spring-boot-starter`：配置项 `ifmap.tenant-header` / `ifmap.default-tenant-id` / `ifmap.orchestrator.*`（3 个开关）/ `ifmap.log.*`（`enabled` / `truncate-threshold` / `mask-fields` / `exclude-fields`）
  - `ifmap-provider-jdbc`：`JdbcConfigWriter.insert(LogicBranchConfig)`
  - 文档：[`docs/06-执行编排与策略扩展.md`](docs/06-执行编排与策略扩展.md)；`docs/05` 增补 §3.3 与配置项
  - 测试：core **52 → 123（+71）**、provider-jdbc 25（+1）、starter **21 → 29（+8）**、demo-sb3 4（编排端到端）；JDK 17 全 reactor **203 个测试全绿**，JDK 8 侧 **170 个全绿**

### Fixed（W4）
- `DefaultLogMasker` 的默认字段集误含 `mobile` / `phone` / `telephone`，会把手机号按姓名规则脱敏成 `1**********` → 默认只保留姓名类字段，手机/证件/卡号统一走值形态识别
- `DefaultLogMasker(maskFields, excludeFields)` 传空集合时会**把默认脱敏整体关掉**（原始姓名落库）→ 空/null 一律回退内置默认（要关闭请自定义 `LogMasker` bean）
- 编排器原先**不递归加载** `front_interface_no` 前置链（仓储按单一 `interface_no` 查询，前置接口是另一个接口号）→ 改为广度递归收集 + 深度护栏
- 策略注册表用 `getClass().getAnnotation(...)` 查注解，**Spring CGLIB 代理类取不到类注解** → 统一改为 `Annotations.find`（父类链 + 接口链）
- `ContractValidator` 漏检「模板不是合法 JSON」→ 补检并报「模板非法：不是合法 JSON 文本」

### Added
- **W3 Spring Boot Starter**（本版本）：
  - `ifmap-spring-boot-starter`：Spring Boot 3 自动配置（`IfmapAutoConfiguration`），引入依赖 + 几行 yml 即完成建表 / 仓储 / 引擎 / 规则收集
  - `ifmap-spring-boot-starter`：配置项 `ifmap.*`（`enabled` / `table-prefix` / `id-strategy` / `worker-id` / `null-policy` / `ddl.auto` / `cache.*`），全部有默认值
  - `ifmap-spring-boot-starter`：**启动期自动建表** `IfmapSchemaInitializer` —— 执行的就是 `ifmap-provider-jdbc` 里的生产 DDL（与 Liquibase changelog 同一份文件），按表是否存在幂等跳过，多实例并发安全
  - `ifmap-spring-boot-starter`：**方言自适应建表** —— MySQL / MariaDB 原文执行；其它库（H2 等）剥掉表尾 `ENGINE= / CHARSET= / COLLATE= / ROW_FORMAT= / COMMENT=`（断言式，结构不符即失败），列定义 / 主键 / 唯一键 / 索引 / 列注释原样保留
  - `ifmap-spring-boot-starter`：`IfmapRuleRegistrar` —— `BeanPostProcessor` 扫描宿主机 bean，自动注册 `@IfmapRule` 方法（无需手工 `register`），CGLIB 代理归一化、按类去重
  - `ifmap-spring-boot-starter`：全部 bean 带 `@ConditionalOnMissingBean`，宿主声明同类型 bean 即完全接管；`ifmap.enabled=false` 整体关闭
  - `ifmap-spring-boot-starter`：缓存实现 Caffeine 优先（`optional` 依赖）、`ifmap-core` 的 `InMemoryConfigCache` 兜底，`ifmap.cache.enabled=false` 时直连数据库
  - `ifmap-core`：`ConfigCache` SPI + `InMemoryConfigCache`（LRU + 惰性 TTL，零第三方依赖）+ `CachingConfigRepository`（只缓存 `queryLogicBranches`，key 含 `tenantId` 防跨租户串配置）
  - `ifmap-core`：`IfmapConfig` / `LogicBranchConfig` / `ExecutionLog` 配置模型（审计列不由引擎读取）
  - `ifmap-demo-spring-boot3`：可运行示例（H2 内存库，`java -jar` 即跑通「建表 → 配置入库 → 渲染」全链路）
  - 文档：[`docs/05-SpringBoot集成.md`](docs/05-SpringBoot集成.md)
- **W2 配置仓储**（上一版本）：
  - `ifmap-provider-jdbc`：`ConfigRepository` 的 JDBC 实现（`JdbcConfigRepository`），查询按 `interface_order` / `logic_branch_order` 升序，只返回启用且未删除的配置
  - `ifmap-provider-jdbc`：管理端写操作 `JdbcConfigWriter`（新增 / 乐观锁更新 / 原子软删除）
  - `ifmap-provider-jdbc`：**建表脚本 ×4**（`ifmap_config` 28 列 / `ifmap_logic_branch_config` 17 列 / `ifmap_execution_log` 17 列 / `ifmap_config_history` 11 列），MySQL 5.7 与 8.0 兼容（`bigint` 无显示宽度、显式 `COLLATE=utf8mb4_general_ci`、`datetime(3)`、不用 `DEFAULT (expr)`）
  - `ifmap-provider-jdbc`：Liquibase `db.changelog-master.yaml`（`${tablePrefix}` 参数，默认 `ifmap_`；复用存量表时填 `bankint_`）
  - `ifmap-provider-jdbc`：`db/mysql8/partition-execution-log.sql`（执行日志按月分区模板，**默认不启用**）
  - `ifmap-core`：`SnowflakeIdGenerator`（10 位 workerId / 12 位序列号 / 2020 纪元 / 时钟回拨保护）+ JVM 共享单例 `shared()`
  - 文档：[`docs/04-接入与建表.md`](docs/04-接入与建表.md)

### Changed（W3）
- 父 POM 用 `<jdk>[17,)</jdk>` 剖面装载 Spring Boot 3 模块（starter / demo-sb3），JDK 8/11 上 `mvn test` 自动跳过，CI 四版本矩阵无需分支
- `ifmap-provider-jdbc` 的 `spring-jdbc` 版本改为**就地声明**（父 POM 不再托管），避免覆盖 Spring Boot 3 传递来的 6.2.x 而运行期 `NoSuchMethodError`；代码只使用 Spring 5.3 / 6.2 都存在的 `JdbcTemplate` API
- starter 模块字节码目标 Java 17（Spring Boot 3 要求），`ifmap-core` 仍是 Java 8

### Fixed
- **写入 `NOT NULL DEFAULT ''` 列的 `null` 会被 MySQL 严格模式（或 H2）直接拒绝**：SQL 默认值只在**省略该列**时生效。仓储写入前统一归一化 `null` → `''`（`JdbcValues.orEmpty`）；实测中由 `insertNormalisesNullForNotNullColumns` 断言固定
- `saveExecutionLog` 未把生成的雪花 ID 回写到入参对象，调用方拿不到主键 → 现已回写

### Added（W1 骨架）
- **W1 骨架**（本版本）：
  - `ifmap-core`：模板 DSL 引擎（`@FUN` / `$.path` / `@array` / `@array@` / `@and@` / `@or@` / `@concat@` / `@append@` / `@sum@` / `$.seqNo`）
  - `ifmap-core`：规则 SPI（`@IfmapRule` 注解 + `RuleRegistry`，支持重载、参数强转、显式覆盖、启动期校验）
  - `ifmap-core`：内置规则 18 个（字符串 / 码值 / 日期 / 金额 / 集合）
  - `ifmap-core`：`JsonOps` SPI（JSON 能力可插拔，公开 API 不出现任何 JSON 库类型）
  - `ifmap-core`：`NullPolicy`（修掉"参数缺失时把模板原文写进报文"的历史缺陷）
  - `ifmap-json-jackson`：`JsonOps` 的 Jackson + JsonPath 实现（SPI 自动发现）
  - `ifmap-demo-pure-java`：纯 Java（非 Spring）可运行示例
  - 文档：`README.md`、`docs/01-快速开始.md`、`docs/02-模板DSL语法.md`、`docs/03-规则清单与扩展.md`
  - CI：`.github/workflows/ci.yml`（JDK 8 / 11 / 17 / 21 矩阵，`mvn -B -ntp clean verify`）

### Changed
- 内置规则口径：**18 个规则名 / 30 个规则方法**（其中 11 个方法有重载；收敛前 `ConvertRule` 为 52 个规则名）
- 表名随项目名统一为 `ifmap_*`（索引同步 `uk_ifmap_*` / `idx_ifmap_*`）；**列名原样保留**，因此从存量表迁移只需 `ALTER` + 回填，无需重写 SQL

### 设计要点（相对 ECC 存量引擎的修正）
- 规则不存在 → **启动期失败**（不再静默返回 null）
- 参数缺失 → 按 `NullPolicy` 处理（**不再**输出 `@FUN(...)` 字面量）
- 参数类型 → **可强转**匹配（不再 `getClass()` 精确匹配）
- `@or@` 全部落空 → 返回 `null`（不再回落成模板原文）
- 源 JSON **只解析一次**（不再每条 JsonPath 重复解析）
