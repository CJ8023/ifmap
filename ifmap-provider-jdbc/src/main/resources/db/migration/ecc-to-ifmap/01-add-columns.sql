-- Copyright 2026 caijun
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- =============================================================================
-- 步骤 1/6：新增列（只加列：不改类型、不动数据、不影响写入）
-- 本次改动带来的**唯一必做项**：逻辑分支表的 `branch_judge`（第 2 段的 ★ 那条）。
-- 旧的两列 `logic_branch_flag` / `logic_branch_value` 在这一步**不动**（回填的输入）。
-- 前置：00-precheck.sql 全部通过；建议先备份（见 README 第 8 节 checklist）。
--
-- 算法与锁（MySQL 5.7 官方"Online DDL Operations"表格）：
--   ADD COLUMN = In Place 是 / 重建表 是 / **允许并发 DML 是**
--   → 显式写 ALGORITHM=INPLACE, LOCK=NONE：迁移期间业务读写不受影响。
--   （`LOCK=NONE` 的语义是"支持并发就做，不支持就报错"，所以它绝不会静默降级成长时间锁表。）
--
-- 三条 5.7 铁律：
--   1) 同表的多处变更合并成**一条** ALTER（5.7 没有 ALGORITHM=INSTANT，分开写会各重建一次表）；
--   2) 每条 DDL 显式写 ALGORITHM / LOCK（防静默降级成 COPY，产生长事务拖垮从库）；
--   3) 会话先设 lock_wait_timeout（默认 1 年）。
--
-- 每一列都带 DEFAULT：存量行在 ALTER 期间直接拿到默认值（MySQL 5.7 语义），
-- 所以新增列**不需要**额外的"补 NULL"语句。
-- =============================================================================

SET SESSION lock_wait_timeout = 10;
SET SESSION time_zone = '+08:00';

-- ---------------------------------------------------------------------------
-- 1.0) ★ 接口配置表：条件列 `pre_strategy_name` / `post_strategy_name`
--      【必须先执行本条】：下面 1) 里 `status` 的 AFTER 锚点就是 `post_strategy_name`。
--
--      目标 DDL 把旧的一列 `strategy_name` 拆成了**前后序两列**，而且 ifmap 的配置读写 SQL
--      **显式带了这两列**（`JdbcConfigWriter` 的 INSERT/UPDATE、`IfmapRowMappers` 的 SELECT）
--      —— 迁完没有它们，运行期每次读写配置都会 1054。
--
--      ⚠️ 存量环境分两种（与 1.1 的 `financing_mode` 同一套路）：
--        · 存量**没有**这两列（只有旧 `strategy_name`）→ 执行下面这条；
--          旧 `strategy_name` 的数据**不搬**：本 kit 只做结构同步，策略名需要人工按新口径重配
--          （旧值在语义上是"一个策略"，拆成前后序是业务决策，脚本猜不出来）；
--        · 存量**已有**这两列 → **删掉下面这条语句**，否则报 `1060 Duplicate column name`；
--          已有时的类型交给 03 的 MODIFY 归一成 `varchar(128) NOT NULL DEFAULT ''`。
--
--      单独一条 ALTER：存量已有该列 / 该列冲突时，失败范围只限本条，
--      不会把 1) 的 status / version / deleted_seq 一起拖下水。
-- ---------------------------------------------------------------------------
ALTER TABLE `bankint_config`
  ADD COLUMN `pre_strategy_name`  varchar(128) NOT NULL DEFAULT '' COMMENT '前序策略标识（=Spring bean 名），出网前执行'  AFTER `success_value`,
  ADD COLUMN `post_strategy_name` varchar(128) NOT NULL DEFAULT '' COMMENT '后序策略标识（=Spring bean 名），回参产物之后执行' AFTER `pre_strategy_name`,
  ALGORITHM=INPLACE, LOCK=NONE;

-- ---------------------------------------------------------------------------
-- 1) 接口配置表：+3 列（status / version / deleted_seq）
--    AFTER 用来对齐目标 DDL 的列顺序；列名不存在会直接报 1054（安全失败，不会静默错位）。
-- ---------------------------------------------------------------------------
ALTER TABLE `bankint_config`
  ADD COLUMN `status`      tinyint(1) NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用' AFTER `post_strategy_name`,
  ADD COLUMN `version`     int        NOT NULL DEFAULT 0 COMMENT '乐观锁版本' AFTER `status`,
  ADD COLUMN `deleted_seq` bigint     NOT NULL DEFAULT 0 COMMENT '软删除唯一化：未删除=0，删除时=key_id' AFTER `del_status`,
  ALGORITHM=INPLACE, LOCK=NONE;

-- ---------------------------------------------------------------------------
-- 1.1) ★ 接口配置表：条件列 `financing_mode`
--      目标 DDL 有 `financing_mode varchar(32)`，而且 ifmap 的配置读写 SQL **显式带了这一列**
--      （`JdbcConfigWriter` 的 INSERT/UPDATE、`IfmapRowMappers` 的 SELECT）—— 迁完没有它，
--      运行期每次读写配置都会 1054。所以它必须存在。
--
--      ⚠️ 存量环境分两种（实测，见 README §9.4）：
--        · 存量**没有**该列 → 执行下面这条（先跑 00-precheck 第 3.1 步，清单里会出现 financing_mode）；
--        · 存量**已有**该列 → **删掉下面这条语句**，否则报 `1060 Duplicate column name 'financing_mode'`；
--          存量已有时它的类型交给 03 的 MODIFY 归一成 varchar(32)。
--
--      单独一条 ALTER（不并进下面那条）：万一存量已有该列，失败范围只限本条，
--      不会把 status / version / deleted_seq 三个 ADD COLUMN 一起拖下水。
-- ---------------------------------------------------------------------------
ALTER TABLE `bankint_config`
  ADD COLUMN `financing_mode` varchar(32) DEFAULT NULL COMMENT '融资模式' AFTER `bank_name`,
  ALGORITHM=INPLACE, LOCK=NONE;

-- ---------------------------------------------------------------------------
-- 2) 逻辑分支表：+3 列（logic_branch_order / deleted_seq / branch_judge）
--    其中 `branch_judge` 是本次唯一的**语义列**：旧的 `logic_branch_flag` + `logic_branch_value`
--    两列合并成一列判定规格（详见 README §3.3 与 02-backfill 第 4 步）。
--    ⚠️ 这里先建成 `text NULL` 是**刻意的**：02 只回填"能机械映射"的行，
--       03 才把它收紧成 `text NOT NULL`（DDL 定义与 db/changelog/v1.0.0/008 一致，只是分两步走，
--       免得 ADD COLUMN ... NOT NULL 在非严格模式下把存量行静默填成 ''）。
-- ---------------------------------------------------------------------------
ALTER TABLE `bankint_logic_branch_config`
  ADD COLUMN `logic_branch_order` smallint NOT NULL DEFAULT 0 COMMENT '分支匹配顺序，升序，先命中先生效；兜底分支不受顺序影响（建议仍设为最大，便于阅读）' AFTER `logic_branch_value`,
  ADD COLUMN `deleted_seq`        bigint   NOT NULL DEFAULT 0 COMMENT '软删除唯一化：未删除=0，删除时=key_id' AFTER `del_status`,
  ADD COLUMN `branch_judge`       text     DEFAULT NULL COMMENT '分支判定规格（与 result_judge 同构的 DSL）：兜底分支写 {"op":"always"}；其余按 all/any 组合 path/strategy/judged 条件' AFTER `logic_branch_name`,
  ALGORITHM=INPLACE, LOCK=NONE;

-- ---------------------------------------------------------------------------
-- 3) 执行日志表：+1 列（error_msg）
--    ⚠️ 日志表可能很大：ADD COLUMN 虽然是在线操作，但仍会重建表文件（5.7 无 INSTANT），
--       会在磁盘上多占一份表大小。执行前确认磁盘余量（information_schema.TABLES 的 data_mb）。
-- ---------------------------------------------------------------------------
ALTER TABLE `bankint_execution_log`
  ADD COLUMN `error_msg` varchar(1024) DEFAULT NULL COMMENT '失败原因（截断）' AFTER `execution_result`,
  ALGORITHM=INPLACE, LOCK=NONE;

-- ---------------------------------------------------------------------------
-- 4) 执行后自检：9 个新列都应存在
-- ---------------------------------------------------------------------------
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('bankint_config', 'bankint_logic_branch_config', 'bankint_execution_log')
   AND COLUMN_NAME IN ('financing_mode', 'pre_strategy_name', 'post_strategy_name',
                       'status', 'version', 'deleted_seq', 'logic_branch_order', 'error_msg',
                       'branch_judge')
 ORDER BY TABLE_NAME, COLUMN_NAME;
-- **期望 9 行**；存量本来就有 `financing_mode` / `pre_strategy_name` + `post_strategy_name`、
-- 按上面说明删掉了对应 ADD COLUMN 的，则相应减少（常见是 6 或 7 行）。
-- `branch_judge` 这一行必须出现，否则 02 的回填会 1054。
-- 少于期望说明有语句没执行成功，不要继续。

-- ---------------------------------------------------------------------------
-- ★ 2.1) 存量"只有官方 Liquibase 自动 DDL、没跑过本 kit"的库
--      （即 ifmap 启动时 `ifmap.ddl.auto=true` 已经建过表/加过列）：
--      这类库 `branch_judge` 已经存在（008 加过），但**内容是空串**，
--      且 `logic_branch_flag` / `logic_branch_value` 还在（010 没有进自动 DDL）。
--      处理：
--        ① 跳过第 2 段里 `branch_judge` 那一行 ADD（否则 1060）；
--        ② 照常跑 02（回填以旧两列为输入，空串 → 有值即可）；
--        ③ 照常跑 03（收紧 NOT NULL + 可选 DROP 旧两列）。
--      判据（一跑就知道走哪条路）：
--        SELECT COUNT(*) FROM information_schema.COLUMNS
--         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bankint_logic_branch_config'
--           AND COLUMN_NAME = 'branch_judge';
--        -- 1 = 走 2.1；0 = 正常走第 2 段。

-- ---------------------------------------------------------------------------
-- 回滚（如需）：
--   ALTER TABLE `bankint_config` DROP COLUMN `financing_mode`, ALGORITHM=INPLACE, LOCK=NONE;   -- 仅当本条 ADD 执行过
--   ALTER TABLE `bankint_config` DROP COLUMN `pre_strategy_name`, DROP COLUMN `post_strategy_name`,
--     ALGORITHM=INPLACE, LOCK=NONE;                                                           -- 仅当 1.0 执行过
--   ALTER TABLE `bankint_config` DROP COLUMN `deleted_seq`, DROP COLUMN `version`, DROP COLUMN `status`,
--     ALGORITHM=INPLACE, LOCK=NONE;
--   ALTER TABLE `bankint_logic_branch_config` DROP COLUMN `deleted_seq`, DROP COLUMN `logic_branch_order`,
--     DROP COLUMN `branch_judge`, ALGORITHM=INPLACE, LOCK=NONE;
--   ALTER TABLE `bankint_execution_log` DROP COLUMN `error_msg`, ALGORITHM=INPLACE, LOCK=NONE;
-- （DROP COLUMN 同样是 In Place + 允许并发 DML；但 01 之后通常已进入 02/03，回滚请整体考虑。）
-- ---------------------------------------------------------------------------
