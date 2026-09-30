# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
- **W4 执行编排与策略 SPI**（本版本）：
  - `ifmap-core`：**编排器 `IfmapOrchestrator`** —— 租户解析 → 配置加载（**递归前置接口** `front_interface_no`，深度护栏 64 + 环检测）→ 拓扑排序（`interface_order`）→ 组包 → 渲染 → 特殊处理 → 出网 → 判定 → 分支动作 → 落执行日志；`stopOnFailure` 控制失败即停；第四参数 `mockResponse` 支持 **dry-run 试跑（不出网）**
  - `ifmap-core`：公开模型 `IfmapRequest`（不可变 + Builder，租户/业务号/操作人/请求头/attributes）与 `IfmapResult`（`executedInterfaces` / `matchedBranch` / `elapsedMs`），`BankCall`
  - `ifmap-core`：**策略 SPI** —— `SpecialDealStrategy`（按 bean 名，对应 `strategy_name`）、`FullParamStrategy`（`bank|busi` 查找顺序：精确 → `bank|*` → `*|busi` → `*|*`）、`LogicBranchStrategy`（`match(context)`）、`IfmapActionHandler`（`@IfmapAction`，替代 `method_flag` 反射调用）、`IfmapCallbackHandler`（`@IfmapCallback`，替代 17 个回调方法）
  - `ifmap-core`：注解 `@IfmapAction` / `@FullParam` / `@LogicBranch` / `@IfmapCallback` + `CallbackRegistry` / `ActionRegistry` / 三个策略注册表（含冲突与别名诊断）
  - `ifmap-core`：SPI `TenantResolver`（`HeaderTenantResolver`：上下文 > 请求头 > 默认 `-1`）/ `ClockProvider` / `LogMasker` / `ExecutionLogSink` / `BankServiceGateway` / `ConditionValueResolver`
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
