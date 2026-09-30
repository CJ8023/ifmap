# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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
