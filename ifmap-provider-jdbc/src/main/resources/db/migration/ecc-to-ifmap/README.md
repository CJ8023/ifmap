# 存量表 → ifmap 迁移脚本（M1：数据迁移）

> 这是随 `ifmap-provider-jdbc` jar 一起发布的**运维脚本包**，不会被自动执行：
> 它不参与 Liquibase changelog（主入口只有 `db/changelog/db.changelog-master.yaml`），
> 文件名也不符合 Flyway 的 `V<版本>__<描述>.sql` 规范，所以两种迁移工具都会忽略它 ——
> **必须由 DBA / 运维按本文档手动执行**。

---

## 1. 这个目录是什么

把「已经在跑的存量资方接口配置表」升级成 ifmap 目标结构的**分步 SQL**。

- 存量表前缀在本套脚本里统一写成 `bankint_`（ifmap 前身工程的存量前缀）；
- **其它存量部署请先替换前缀**：

```bash
# 例：存量前缀是 acme_（Windows Git Bash / Linux 通用）
sed -i 's/bankint_/acme_/g' *.sql
```

迁移之所以是「改结构」而不是「搬数据」，原因是字段遵循 **P1「只增不映射」** 原则
（唯一的例外是下面那 **2 处列改名**）：

| 维度 | 是否变化 | 说明 |
|---|---|---|
| 表名 | **变** | `bankint_*` → `ifmap_*`（项目更名后 DDL 同步更名） |
| 列名 | **2 处改名** | `bank_code` → `partner_code`、`bank_name` → `partner_name`（标识符不再锁死在「银行」：同一列要承载银行 / 保理 / 信托 / 小贷 / 保险等各类合作机构）；**其余列名原样保留** → 没有 ETL、没有字段映射表 |
| 列定义 | 部分变化 | 新增 6 列、改类型/长度/可空 32 列、加 9 个索引/唯一键 |

所以整个迁移 = **`ADD COLUMN` → 回填 → 改列名与列类型 / 加唯一键与索引 → `RENAME TABLE`**。

> ⚠️ **列改名是这套脚本里唯一的「破坏性」变更**：改完之后表里的列只叫 `partner_code` /
> `partner_name`，任何仍写 `bank_code` / `bank_name` 的 SQL 会立刻报 `Unknown column`（1054）。
> 共存期（下面 §2 的**路径 A**）必须**先决定**存量模块怎么处置（三种做法见 §2 末尾），
> 而且要写进变更单 —— 「回滚了就不影响」在这里不成立，回滚同样会打断已经改好的调用方。

---

## 2. 先选路径：要不要改表名？

| 路径 | 做法 | 适用场景 | 本套脚本怎么用 |
|---|---|---|---|
| **A. 前缀指向**（共存期首选） | ifmap 侧配 `ifmap.table-prefix=bankint_`，直接读写存量表 | 灰度期：新旧两条链路要用**同一批**配置 | 只执行 `00 → 01 → 02 → 03`（+ 05 的结构核对），**不执行 04**；同时别让 starter 自动建表（表已存在，`ifmap.ddl.auto=false`） |
| **B. `RENAME TABLE`**（切换期首选，本 kit 的完整流程） | 存量表改名成 `ifmap_*` | 存量模块已停用下线 | `00 → 01 → 02 → 03 → 05(结构) → 【切换时刻】04 → 05(全量)` |
| **C. 新表 + 搬迁** | 用 `db/changelog/v1.0.0/*.sql` 建新表，再 `INSERT ... SELECT` 搬配置 | 两套要完全独立、要求随时可回滚 | 本套脚本不覆盖此路径；字段映射见 `docs/10-迁移指南.md` |

**路径 A 下的列改名怎么处置**（03 之后列名只叫 `partner_code` / `partner_name`，存量模块的
`bank_code` 会报 1054）。三种做法，**执行 03 之前必须选定一种**：

1. **让存量模块同步改列名** —— 存量模块能发版时的**首选**。改动小（就是两个列名）、行为零变化，
   而且不用留任何"过渡期兼容物"。
2. **给存量模块建兼容视图**（存量模块不能发版、且对它只做单表读写时）：
   视图不能与表同名，所以先把真表改名、再建同名视图把新列名映射回旧列名：

   ```sql
   -- 03 已执行完（列已改名）。先改表名，再建同名视图：
   RENAME TABLE bankint_config TO ifmap_config;
   CREATE VIEW bankint_config AS
     SELECT key_id, tenant_id, interface_no, /* ...其余列原样... */,
            partner_code AS bank_code, partner_name AS bank_name
       FROM ifmap_config;
   ```

   单表简单映射的视图是**可更新**的（`ALGORITHM=MERGE`），读写在存量侧都能跑；
   但要注意两点：**别对视图做 DDL**（改结构要落到基表），以及存量模块若依赖
   `SHOW CREATE TABLE` / 索引名会看到差异。
3. **共存期不做列改名** —— 把 03 里那两条 `CHANGE COLUMN` 挪到 `04` 的停写窗口（和 `RENAME TABLE`
   一起执行）。代价是共存期这张表的列还是旧名，**ifmap 侧读不了它**（M3 影子运行也就无从读配置），
   等于放弃路径 A。只有在 1、2 都做不到、又必须走路径 A 时才用。

> ⚠️ **反向兼容视图只是过渡物，用完必须拆**（`CREATE VIEW 旧名 AS SELECT * FROM 新名`）：
> 技术上是可行的 —— 对**单表简单映射**，MySQL 把视图当作 *updatable view*，`INSERT/UPDATE` 都能落到基表
> （所以上一小节把它列为可选做法）。真正要防的是"留成永久依赖"：① 视图在，`SHOW TABLES` 就一直有旧名字，
> **分不清迁移到底做完没有**；② 存量模块若依赖 `SHOW CREATE TABLE` / 索引名 / 对它做 DDL，会看到差异。
> 因此用了视图就必须把「存量模块下线后删除视图」写进任务清单。
>
> ⚠️ **路径 B 的安全顺序**：① 停存量模块的定时任务/入口 → ② 停存量 `bankintconfig` 模块
> → ③ 执行 04 → ④ 起 ifmap → ⑤ 观察。任何一步出问题都能 `RENAME` 回去（毫秒级）。

---

## 3. 执行顺序（一步一个文件）

| 序号 | 文件 | 做什么 | 算法 / 锁（MySQL 5.7） | 预期耗时 | 回滚 |
|---|---|---|---|---|---|
| 0 | `00-precheck.sql` | 只读体检：环境参数、表体量、列清单基线、**唯一键冲突**、类型可转换性、长度/NULL 超限、零值时间、兜底分支、时区 | 只读 | 配置表秒级；日志表看体量 | 不需要 |
| 1 | `01-add-columns.sql` | 加 5 个新列（config 3 / branch 2 / log 1），全部带 `DEFAULT` | **`ALGORITHM=INPLACE, LOCK=NONE`**（纯 `ADD COLUMN`：允许并发 DML） | 秒级~分钟级 | `DROP COLUMN`（同样在线） |
| 2 | `02-backfill.sql` | 回填 `deleted_seq`（历史软删行）与 `logic_branch_order`；**人工复核分支顺序** | 普通 `UPDATE` | 秒级 | 回填值可重算 |
| 3 | `03-modify-and-index.sql` | **改列名**（`bank_code`/`bank_name` → `partner_code`/`partner_name`）+ 改类型/长度/可空 + 建唯一键与索引 | 配置表：**`ALGORITHM=COPY, LOCK=SHARED`**（改名与类型变更合并进**同一条** `ALTER`，不多花一次重建）；日志表：索引单独走在线 DDL，类型变更看体量 | 配置表秒级；日志表看体量（大表走 gh-ost） | 反向 `CHANGE COLUMN` 改回旧列名 + 按 `00` 留档的 `COLUMN_TYPE` 反向 `MODIFY`（先 `DROP INDEX`） |
| 4 | `04-rename-tables.sql` | `RENAME TABLE bankint_* → ifmap_*`（**切换时刻**） | 元数据操作 | 毫秒级 | 再 `RENAME` 回去 |
| 5 | `05-verify.sql` | 迁移后核对：列数/列定义、索引、回填完整性、行数指纹、时间抽样、分支顺序、查询冒烟 | 只读 | 秒级 | 不需要 |

**为什么 `RENAME` 放最后**：改名之后存量代码（SQL 里写死 `bankint_config`）立刻失效。
正确顺序是「共存期先把结构改完、把新链路验证完，切换那一刻才改名」；
反过来「先改名再慢慢改结构」= 切换期间旧模块已不可用、新模块又没就绪。

一条命令跑一步（按需替换连接串）：

```bash
mysql -h<host> -P3306 -u<user> -p'<pwd>' <db> < 00-precheck.sql   # 全绿才继续
mysql -h<host> -P3306 -u<user> -p'<pwd>' <db> < 01-add-columns.sql
mysql -h<host> -P3306 -u<user> -p'<pwd>' <db> < 02-backfill.sql
mysql -h<host> -P3306 -u<user> -p'<pwd>' <db> < 03-modify-and-index.sql   # 低峰执行
mysql -h<host> -P3306 -u<user> -p'<pwd>' <db> < 05-verify.sql             # 改名前的结构核对
mysql -h<host> -P3306 -u<user> -p'<pwd>' <db> < 04-rename-tables.sql      # 切换时刻
mysql -h<host> -P3306 -u<user> -p'<pwd>' <db> < 05-verify.sql             # 改名后全量核对
```

---

## 4. MySQL 5.7 的算法与锁：本套脚本的写法依据

| 操作 | In Place | 重建表 | 允许并发 DML | 本 kit 写什么 |
|---|---|---|---|---|
| `ADD COLUMN` | 是 | 是（5.7 无 `INSTANT`） | **是** | `ALGORITHM=INPLACE, LOCK=NONE` |
| `ADD KEY`（普通/唯一） | 是 | 否 | **是** | `ALGORITHM=INPLACE, LOCK=NONE` |
| 仅把列改成可空 | 是 | 是 | 是 | 未单独使用（本 kit 不需要） |
| **改列名**（`CHANGE COLUMN`，**定义不变**） | 是 | 否 | **是\*** | 本 kit 不单独使用（与下一行的类型变更合并） |
| **改变列数据类型** | **否** | 是 | **否** | **`ALGORITHM=COPY, LOCK=SHARED`** |
| `RENAME TABLE` | — | 否 | 是 | 直接 `RENAME TABLE` |

\* 上表「改列名」那一行的官方脚注是：*"To permit concurrent DML, keep the same data type and
only change the column name."* —— 本 kit 的两条 `CHANGE COLUMN` 都顺带改了列注释（`bank_name`
还收窄了类型），**不满足**这个前提，所以实际仍然走 `COPY`；好在它与类型变更写在同一条
`ALTER` 里，不存在"多一次重建"。（出处：MySQL 5.7 手册 Table 14.12 *Online DDL Support for
Column Operations*。）

> ⚠️ **最容易踩的坑**：把"改类型"写成 `ALGORITHM=INPLACE, LOCK=NONE`。
> `LOCK=NONE` 的语义是「支持并发就做，**不支持就报错**」（官方原文：*If supported, permit
> concurrent reads and writes. Otherwise, an error occurs.*），而且非默认值会
> *halt the operation if the requested degree of locking is not available* ——
> 所以类型变更 + `LOCK=NONE` = **直接失败**（错误文本：`LOCK=NONE is not supported ... Try LOCK=SHARED`），
> 不是"自动降级成阻塞"。
> `ALGORITHM=COPY, LOCK=SHARED` 在 5.7 与 8.0 上都成立，所以本 kit 统一用它；
> 想追求 8.0 上的更高并发度请自行按 8.0 手册调整，**不要直接照搬 `LOCK=NONE`**。

**三条 5.7 硬约束**（本 kit 严格遵守）：

1. 同一张表的多次变更**合并成一条 `ALTER TABLE`** —— 5.7 每条独立语句都可能重建一次表；
2. 每条 DDL 显式写 `ALGORITHM=` 与 `LOCK=` —— 防静默降级成 `COPY`，产生长事务拖垮从库；
3. 会话先 `SET SESSION lock_wait_timeout = 10` —— 默认等锁上限 1 年，遇到长事务会一直挂着。

**5.7 特有风险**：DDL 非原子，执行中被 `KILL` 会残留 `#sql-ib*.ibd` 孤儿表并导致数据字典不一致。
→ **绝不要 `KILL` 正在跑的 DDL**；确认"卡住"之前先看 `SHOW PROCESSLIST` 与 `information_schema.INNODB_TRX`。

---

## 5. 大表（执行日志）怎么迁

`ifmap_execution_log` 是唯一会持续变大的表。按体量二选一：

| 体量 | 路径 |
|---|---|
| < 50 万行 | 低峰直接执行 `03-modify-and-index.sql` 的 C-2 段（`COPY`，写阻塞：秒级~分钟级），再执行 C-3 加索引 |
| ≥ 50 万行 | 用 **gh-ost** 做那几个 `MODIFY`（在线、可暂停、可限流），索引单独走在线 DDL |

```bash
gh-ost \
  --host=<db_host> --port=3306 --user=<user> --password=<pwd> \
  --database=<db_name> --table=bankint_execution_log \
  --alter="MODIFY COLUMN \`request_param\` json DEFAULT NULL, \
           MODIFY COLUMN \`response_param\` mediumtext DEFAULT NULL, \
           MODIFY COLUMN \`execution_result\` varchar(16) DEFAULT NULL, \
           MODIFY COLUMN \`remark\` varchar(512) NOT NULL DEFAULT '', \
           MODIFY COLUMN \`add_user_id\` varchar(64) NOT NULL DEFAULT '', \
           MODIFY COLUMN \`add_time\` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), \
           MODIFY COLUMN \`modify_user_id\` varchar(64) NOT NULL DEFAULT '', \
           MODIFY COLUMN \`modify_time\` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), \
           ROW_FORMAT=DYNAMIC" \
  --allow-on-master --initially-drop-ghost-table --initially-drop-old-table \
  --chunk-size=1000 --max-load='Threads_running=50' --critical-load='Threads_running=200' \
  --max-lag-millis=1500 --throttle-control-replicas='<replica_host>' \
  --exact-rowcount --verbose --dry-run
# 确认 dry-run 输出无误后，把最后的 --dry-run 换成 --execute
```

前置条件：`binlog_format=ROW` + `binlog_row_image=FULL`，账号有 `REPLICATION SLAVE` / `REPLICATION CLIENT` 权限。
pt-osc 同理（`--alter` 内容完全一样，加 `--no-drop-old-table` 便于回滚）。
`gh-ost --alter` 里**不能**写 `ALGORITHM=` / `LOCK=` 子句（工具自己管），依赖 `ROW` binlog 做增量同步。

> 迁移前建议先归档/清理历史日志（见 `docs/08-日志与合规.md` 的保留期清理）：表越小越安全。
> 另外 `innodb_online_alter_log_max_size`（默认 128M）超限会让在线 DDL 报 1799 并回滚，
> 大表在线加索引前先确认这个值够大，或控制执行窗口的低峰程度。

---

## 6. 常见错误与处置

| 错误文本（号） | 原因 | 处置 |
|---|---|---|
| `LOCK=NONE is not supported ... Try LOCK=SHARED`（1846 附近） | 在"改变列类型"这种不支持并发 DML 的操作上写了 `LOCK=NONE` | 改成 `ALGORITHM=COPY, LOCK=SHARED`（本 kit 已如此） |
| `ALGORITHM=INPLACE is not supported ... Try ALGORITHM=COPY`（1845 附近） | 同上（写了 `INPLACE`） | 改成 `ALGORITHM=COPY` |
| `Duplicate entry '...' for key 'uk_ifmap_config_biz'`（1062） | `00-precheck` 第 6 步没通过就跑了 03 | 处理重复数据（拉开 `interface_order` / 停用多余行）后重跑 6.1、6.3 与 03 |
| `Data too long for column 'xxx'`（1406） | 列宽收窄（`varchar(255) → 64/128`、`longtext → mediumtext`）但有超长数据 | 按 `00-precheck` 第 8.1 条逐列清理，或放宽目标列宽 |
| `Invalid use of NULL value`（1138）/ `Column 'x' cannot be null`（1048） | 目标为 `NOT NULL` 但存量有 NULL | 按第 8.2 条补默认值（**不要**靠列的 `DEFAULT`：显式写 NULL 不生效） |
| `Incorrect datetime value`（1292） | 存在 `'0000-00-00 00:00:00'` 零值时间 | 先修值再重跑（第 9 条） |
| `Incorrect integer value: 'xx'`（1292） | `interface_order` 里有非数字值 | 按第 7.1 条清理（非数字/空 → 0） |
| `Unknown column 'bank_code' in 'field list'`（**1054**） | 03 已把列名改成 `partner_code`，但存量模块的 SQL 还在用旧列名 | 按 §2「路径 A 下的列改名怎么处置」三选一。已执行完想临时救回来：`CHANGE COLUMN partner_code bank_code ...` 反向改回即可（列名变更不动数据，无损失） |
| `Specified key was too long; max key length is 767 bytes`（1071） | 老表 `ROW_FORMAT=COMPACT`，唯一键超 767 字节 | 本 kit 的 03 已带 `ROW_FORMAT=DYNAMIC`（COPY 时顺带转行格式）；若仍报错，先单独 `ALTER TABLE ... ROW_FORMAT=DYNAMIC` |
| `Lock wait timeout exceeded`（1205） | 长事务占用表 | 查 `information_schema.INNODB_TRX`，结束长事务后再执行；不要靠调大超时绕过去 |
| `Table 'xxx.bankint_config' doesn't exist` | 04 已执行但存量模块还在跑 | 立刻 `RENAME` 回去，先停存量模块再切 |
| 表空间异常增长 / 出现 `#sql-ib*` 中间表 | DDL 执行中被中断（5.7 DDL 非原子） | 不要 `KILL`；确认无会话在用后由 DBA 清理中间表，并在验证数据一致后重跑 |

---

## 7. 迁移后必须确认的**行为对齐**（这才是最容易出事的地方）

表结构改完 ≠ 行为一致。下面 7 条要在 M2（规则对齐）/ M3（影子运行）阶段逐条确认：

| # | 语义点 | 存量行为 | ifmap 行为 | 迁移动作 |
|---|---|---|---|---|
| 1 | `interface_order` 排序 | 该列**从未被引擎使用**，执行顺序取决于 DB 返回顺序（不确定） | 引擎显式排序：`interface_order` 升序，同值按 `key_id` | ① `char(2) → smallint` 后 `'10' < '2'` 的字符串排序问题消失；② **必须**按业务预期核对顺序，建议整理成 10/20/30 留白写法 |
| 2 | 逻辑分支顺序 | "标志为空"= 兜底分支，靠 DB 返回顺序决定谁先命中 | 兜底分支**不参与常规匹配**，只在所有常规分支都未命中时生效 | `02` 已回填并人工复核；**顺序错 = 命中错**，属 P0 |
| 3 | 兜底/动作两个语义 | 两个含义混在 `method_flag` 上判断 | `logic_branch_flag` 空 = **兜底分支**；`method_flag` 空 = **该分支不执行动作**（两件事，别混） | 配置数据零改动；宿主把 `method_flag` 指向的动作注册成 `@IfmapAction` bean |
| 4 | 规则名 | 存量自定义规则库里有一部分名字与 ifmap 内置规则不一致，取不到值时会**静默变成空值** | core 内置 18 个规则（同名者行为一致）；其余需宿主用 `@IfmapRule` 注册 | 用 admin 端 `/audit` 做启动期契约自检（要求 0 违规），把存量规则名逐个对上 |
| 5 | 模板非法 JSON | 存进去也能跑（取不到值 → 空） | `ContractValidator` 判定为违规（admin 保存会被拒） | `00-precheck` 第 10.2 条先扫出来 |
| 6 | 时间语义 | `timestamp` 随会话时区显示 | `datetime(3)` 固定值 | 迁移前后抽样逐行比对（`00` 第 9.3 条 vs `05` 第 6 节） |
| 7 | 日志清理 | 存量没有自动清理 | ifmap 默认启用「保留期 + 分批清理」 | 不想动存量历史数据就先 `ifmap.log.clean.enabled=false`，确认后再开 |
| 8 | 日志失败原因 | 日志表没有失败原因列 | 新增 `error_msg` | 无需动作（新写入自动带；历史行留空） |

---

## 8. 迁移 checklist

- [ ] **备份**：三张表结构（`SHOW CREATE TABLE`）+ 配置数据（`mysqldump` 两张配置表）已留档
- [ ] 磁盘余量 ≥ 最大表大小 × 1.5（`COPY` / 在线加索引期间新旧两份并存）
- [ ] 记录当前会话时区，且全流程固定 `SET time_zone='+08:00'`
- [ ] `00-precheck.sql` 全部"期望 0 / ≤ 1"通过，输出留档（这是迁移前的**唯一**基线）
- [ ] `01-add-columns.sql` 执行完成，自检 5 个新列都在
- [ ] `02-backfill.sql` 执行完成，自检全 0；**分支顺序已人工复核**
- [ ] `03-modify-and-index.sql` 执行完成：**`bank_code`/`bank_name` 已改名为 `partner_code`/`partner_name`**（用 `SHOW FULL COLUMNS` 复核）、9 条索引都在；大表走 gh-ost 且已 `--dry-run`
- [ ] **存量模块的列名已处置**（§2 路径 A 的三种做法选一种），并用存量侧的**真实 SQL** 跑过冒烟
- [ ] `05-verify.sql` 第 1~4 节（结构 / 索引 / 回填 / 唯一键效果）通过
- [ ] M2 规则与策略对齐完成：启动期契约自检 0 违规
- [ ] M3 影子运行达到退出条件：连续 **7 天**零差异，或差异均有明确解释
- [ ] 切换到路径 B：停存量模块 → `04-rename-tables.sql` → 起 ifmap → `05-verify.sql` 全量通过
- [ ] 观察 1~2 个业务日：执行日志成功率/耗时正常，无 `ifmap_config` 相关异常
- [ ] 保留存量备份与回滚脚本至少一个发布周期

完整方法论（M1/M2/M3/M4、共存方案、影子运行用法、退出条件、回滚与排错）见仓库根的
`docs/10-迁移指南.md`。

---

## 9. 免责声明（重要）

**存量的建表 DDL 不在 ifmap 仓库里**（两张配置表由存量服务创建，各环境版本可能不同），
因此本套脚本按设计文档推断的存量结构书写，**以实际数据库为准**：

1. 执行前必须先跑 `00-precheck.sql`，特别是第 3 步的「列定义全量清单」；
2. 用它比对 `03-modify-and-index.sql` 里的列宽/类型：**现状比脚本假设更宽是安全的**
   （等价于收窄迁移，先跑第 8 条体检），**更窄则必须先改脚本**；
3. 若实际列名/列数与本套脚本不符（例如某环境没有 `remark`），
   把对应行删掉即可 —— 迁移只要求「目标列存在且定义一致」，多改几列无害；
4. **列改名（`bank_code`/`bank_name` → `partner_code`/`partner_name`）是脚本里唯一的破坏性变更**：
   它假设存量模块会被同步处置（§2）。如果你的环境做不到，**执行 03 之前先改脚本**，
   把那两条 `CHANGE COLUMN` 挪到 `04` 的停写窗口。
