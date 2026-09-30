# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
- **W2 配置仓储**（本版本）：
  - `ifmap-provider-jdbc`：`ConfigRepository` 的 JDBC 实现（`JdbcConfigRepository`），查询按 `interface_order` / `logic_branch_order` 升序，只返回启用且未删除的配置
  - `ifmap-provider-jdbc`：管理端写操作 `JdbcConfigWriter`（新增 / 乐观锁更新 / 原子软删除）
  - `ifmap-provider-jdbc`：**建表脚本 ×4**（`ifmap_config` 28 列 / `ifmap_logic_branch_config` 17 列 / `ifmap_execution_log` 17 列 / `ifmap_config_history` 11 列），MySQL 5.7 与 8.0 兼容（`bigint` 无显示宽度、显式 `COLLATE=utf8mb4_general_ci`、`datetime(3)`、不用 `DEFAULT (expr)`）
  - `ifmap-provider-jdbc`：Liquibase `db.changelog-master.yaml`（`${tablePrefix}` 参数，默认 `ifmap_`；复用存量表时填 `bankint_`）
  - `ifmap-provider-jdbc`：`db/mysql8/partition-execution-log.sql`（执行日志按月分区模板，**默认不启用**）
  - `ifmap-core`：`SnowflakeIdGenerator`（10 位 workerId / 12 位序列号 / 2020 纪元 / 时钟回拨保护）+ JVM 共享单例 `shared()`
  - 文档：[`docs/04-接入与建表.md`](docs/04-接入与建表.md)

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
