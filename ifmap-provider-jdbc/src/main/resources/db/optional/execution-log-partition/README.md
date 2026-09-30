# 执行日志：按月分区 + 冷热分离（可选手册）

> 这是**可选**的运维改造手册，不属于 ifmap 的默认部署动作：升级 jar 不会执行这里的任何脚本。
> 它对应设计文档 §6.4 的方案 B（按月分区）与方案 C（冷热分离归档）。

本目录脚本按序号执行，每一步都可以单独 review、单独回滚：

| 步骤 | 文件 | 作用 | 是否改数据 |
| --- | --- | --- | --- |
| 0 | `00-precheck.sql` | 体检：表体量、月度分布、主键/索引、外键/触发器、是否已是分区表 | 只读 |
| 1 | `01-partition-hot.sql` | 把热表改造成按月 RANGE 分区（**重建表**，唯一"重"的一步） | 改结构 + 搬数据 |
| 2 | `02-add-monthly-partition.sql` | 每月加下个月分区（用 `REORGANIZE` 拆 `pmax`） | 改结构 |
| 3 | `03-verify.sql` | 校验：分区清单、pmax 为空、主键、分区裁剪、对账 | 只读 |
| 4 | `04-create-archive-table.sql` | 建归档冷表 `ifmap_execution_log_archive` | 改结构（新增表） |
| 5 | `05-archive-and-drop.sql` | 归档 + 保留期回收（Java 归档器 / 裸 SQL / `DROP PARTITION`） | 改数据 |

表前缀：脚本以 `ifmap_` 为例。存量部署（例如从 ECC 迁过来的 `bankint_`）先整体替换：

```bash
sed -i 's/ifmap_/bankint_/g' *.sql
```

---

## 1. 先判断"我该不该做这套改造"

ifmap 的**默认**日志策略是"保留期 + 分批删除"（`JdbcExecutionLogCleaner`，见 docs/08）。
它在绝大多数场景下就够了，而且不需要任何 DBA 操作。只有下面这些情况才值得做本手册的改造：

| 你的痛点 | 用哪个方案 | 收益 | 代价 |
| --- | --- | --- | --- |
| 日志表涨到千万级，`DELETE` 清理越来越慢、主从延迟明显 | 方案 A（保留期清理）+ 调小批大小 | 零 DBA 操作 | 删除仍会产生 undo/binlog；空间不立即回收 |
| 需要"留 5 年但业务只查近 3 个月"（合规） | 方案 C（冷热分离） | 热表恒定小；冷表可搬到便宜存储 | 多一张表、多一个归档任务；跨表搬迁不是事务性的（可重入） |
| 保留期到期时要**秒级**回收，且不想产生删除 binlog | 方案 B（按月分区）+ `DROP PARTITION` | 回收是 DDL，秒级、几乎零开销 | 必须重建表（全表 COPY）；主键要改成 `(key_id, add_time)`；要维护每月加分区 |
| 两者都要 | B + C：热表分区、老分区先归档到冷表再 `DROP PARTITION` | 兼顾合规与性能 | 两套运维动作都要做 |

**结论**：日志量不到千万级时，先别做这套改造，把 `ifmap.log.clean-batch-size` 调小即可
（每批 500~1000、批间 sleep 50ms 是安全的默认值）。

---

## 2. 方案 B：热表改造成按月分区

### 2.1 为什么必须重建表

MySQL（5.7 与 8.0 都是）**不支持**在线地把普通表改成分区表：
`ALTER TABLE ... PARTITION BY ...` 与改主键都属于 `ALGORITHM=COPY`，会全表重建、长时间持锁，
且期间需要额外磁盘（新旧表共存）。因此只有两条路：

* **路线 A：停写窗口**（推荐给中小表，或可在维护窗口停日志写入的系统）
  用 `01-partition-hot.sql` 的手工流程：建影子表 → 分批搬数据 → **原子 `RENAME TABLE`** → 校验 → DROP 旧表。
* **路线 B：在线工具**（表大、不能停写）：`pt-online-schema-change`
  它在影子表上建触发器同步增量、最后原子换名。注意它**默认沿用原主键**，
  必须显式写 `DROP PRIMARY KEY, ADD PRIMARY KEY (key_id, add_time)` 与 `PARTITION BY ...`，
  否则会因为"唯一键未包含分区列"直接失败（错误 1503）。gh-ost 对分区变更的支持随版本而异，
  用前先核对你们版本的文档。

### 2.2 分区方案

```sql
PARTITION BY RANGE (TO_DAYS(`add_time`)) (
  PARTITION p202601 VALUES LESS THAN (TO_DAYS('2026-02-01')),
  ...
  PARTITION pmax    VALUES LESS THAN MAXVALUE   -- 兜底分区，必须保留
);
```

* 分区列只能是 `ID`（本项目没有自增 ID）或主键列中的列 → 选 `add_time`。
* **兜底分区 `pmax` 不能省**：没有它，插入超出边界的时间会报 `1526 Table has no partition for value`，
  日志写入会直接失败（这是生产事故级）。
* 主键必须变成 `(key_id, add_time)`：**分区表要求每个唯一键都包含全部分区列**。
  这对 ifmap 是安全的：`add_time` 是 `NOT NULL DEFAULT CURRENT_TIMESTAMP(3)`；
  执行日志只按 `(tenant_id, biz_id|interface_no, add_time)` 或 `add_time` 查询，
  **从不按 `key_id` 单独查**；`key_id` 的唯一性由雪花 ID 生成器保证。
* 每个分区一个表空间文件，要求 `innodb_file_per_table = 1`（MySQL 5.7/8.0 默认已是 1）。

### 2.3 加分区（每月必做）

```sql
ALTER TABLE `ifmap_execution_log`
  REORGANIZE PARTITION pmax INTO (
    PARTITION p202604 VALUES LESS THAN (TO_DAYS('2026-05-01')),
    PARTITION pmax    VALUES LESS THAN MAXVALUE
  );
```

* 有 `pmax` 时不能用 `ADD PARTITION`（会报 `VALUES LESS THAN value must be strictly increasing`），
  必须 `REORGANIZE` 拆 `pmax`。
* **要在月初之前加好**：数据一旦落进 `pmax`，拆它就要 COPY 数据，而这发生在日志写入路径上。
* 每月加完分区后跑一遍 `03-verify.sql` 的"pmax 行数为 0"检查，并给它配告警
  （`pmax` 非空 = 加分区漏了）。

### 2.4 用分区做保留期回收

```sql
-- 先归档再 DROP（数据要留）；不要留就直接 DROP（不可恢复）
ALTER TABLE `ifmap_execution_log` DROP PARTITION p202501;
```

`DROP PARTITION` 是 DDL，秒级完成，不产生删除 binlog、不产生 undo —— 这是分区方案最大的收益。
注意：`DROP PARTITION` 后分区文件在 `innodb_file_per_table = 1` 时才被真正删除，
磁盘水位会滞后（IBUF/undo 清理是异步的）。

---

## 3. 方案 C：冷热分离归档

冷表由 `04-create-archive-table.sql` 建好，结构与热表逐列一致，额外多一列 `archive_time`
（`add_time` 保留原业务时间，**不改成归档时间**），主键是 `key_id`。

归档由 `JdbcExecutionLogArchiver` 完成（`cn.cj.ifmap.jdbc`）：

```java
JdbcExecutionLogArchiver archiver = new JdbcExecutionLogArchiver(dataSource, "ifmap_");
LogArchiveResult result = archiver.archive(180, 1000, 1000, 50L);
// 参数：180 天以前的数据算冷数据、每批 1000 行、单次最多 1000 批、批间停顿 50ms
// 返回：写入冷表行数 / 从热表删除行数 / 批数
```

它有三条有意为之的设计（都有单测守着）：

1. **可重入**：先 `INSERT` 后 `DELETE`，两条语句各自提交。若在中间进程被杀，数据会同时留在两张表；
   下次重跑时"冷表已有"的行会被跳过、热表照删 —— 结果仍然正确。因此它**不**用跨表事务，
   避免把大批量搬迁变成大事务。
2. **列清单写死并自检**：归档时显式列出 17 个列（不用 `SELECT *`，列序漂移会静默错位）；
   跑之前核对热表实际列与内置清单**完全一致**，将来给执行日志表加列却忘了同步归档器会**直接失败**，
   而不是悄悄少归档一列。
3. **失败信息可执行**：冷表不存在时会提示"请先执行 `04-create-archive-table.sql`"，
   列不齐会列出缺哪些列。

并发约束：**同一时刻只能有一个归档任务在跑**（多实例部署用分布式锁/选举）。
并发跑会在冷表主键上冲突报错 —— 这是有意的失败，而不是静默写重。

不要用 ifmap 自带的保留期清理去清冷表（`JdbcExecutionLogCleaner` 固定操作热表）；
冷表自己的留存到期用 `05-archive-and-drop.sql` 的 5.4 节（未分区就分批 DELETE、
已分区就 `DROP PARTITION`）。

---

## 4. 常见事故与排错

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 日志写入报 `1526 Table has no partition for value` | 没有 `pmax` 兜底分区，或新月份分区没加 | 立刻 `REORGANIZE PARTITION pmax INTO (...)` 补分区；查加分区任务为何漏跑 |
| `ALTER TABLE ... ADD PARTITION` 报 `VALUES LESS THAN value must be strictly increasing` | 有 `pmax` 时不能用 `ADD PARTITION` | 改用 `REORGANIZE PARTITION pmax INTO (...)` |
| 建分区表报 `1503 A PRIMARY KEY must include all columns in the table's partitioning function` | 分区的唯一键没包含分区列 | 主键改成 `(key_id, add_time)`，见 2.2 |
| 归档报"冷表不存在/缺列" | 没执行 `04-create-archive-table.sql`，或冷表结构漂移 | 按报错提示建表/补列；归档器不会自动建表 |
| 归档报主键冲突 | 有两个归档任务在并发跑 | 加分布式锁；确认只有单实例在跑 |
| `EXPLAIN` 里 partitions 列列出全部分区 | 查询在分区列上套了函数（`DATE(add_time)`）或前模糊匹配 | 改成范围条件 `add_time >= ? AND add_time < ?` |
| `DELETE` 清理越来越慢、从库延迟大 | 批太大、频率太高 | 调小 `ifmap.log.clean-batch-size`、加大 `clean-batch-sleep-millis`；或改用分区 `DROP PARTITION` |
| 分区改造后磁盘没释放 | 只 DROP 了分区/表，`innodb_file_per_table` 或异步清理未完成 | 确认 `innodb_file_per_table = 1`；等待 IBUF/undo 清理 |

---

## 5. 变更单模板（把这几条填上再执行）

```
改造对象：<库>.<表>            变更类型：分区改造（重建表）+ 归档冷表新增
改造前：行数 ______、大小 ______ MB、oldest ______、newest ______
路线：A 停写窗口（起止 ______ ~ ______）/ B 在线工具 ______
预置分区：______ 至 ______（月度），是否含 pmax：是
对账方式：停写窗口内 COUNT(*) 相等 / 逐月计数相等
回滚方案：RENAME TABLE 换回 ifmap_execution_log_old（保留窗口 ______ 天）
后续任务：每月 __ 号加下月分区；归档任务每天 __:__ 跑；pmax 非空告警已配
```

---

## 6. 与 Java 侧的对应关系

| 本手册脚本 | Java / 配置 |
| --- | --- |
| 方案 A 保留期清理 | `JdbcExecutionLogCleaner` + starter 的 `ifmap.log.clean-*`（内置，默认开） |
| 方案 C 归档 | `JdbcExecutionLogArchiver`（**不自动调度**，由宿主自己的调度器调用） |
| 冷表表名 | `TableNameResolver.executionLogArchiveTable()`（固定为 `<前缀>execution_log_archive`） |
| 归档列清单 | `JdbcExecutionLogArchiver.ARCHIVED_COLUMNS`（与热表 DDL 一致性有单测/运行时自检） |
