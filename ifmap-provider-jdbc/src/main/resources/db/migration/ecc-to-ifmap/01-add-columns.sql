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
-- 1) 接口配置表：+3 列（status / version / deleted_seq）
--    AFTER 用来对齐目标 DDL 的列顺序；列名不存在会直接报 1054（安全失败，不会静默错位）。
-- ---------------------------------------------------------------------------
ALTER TABLE `bankint_config`
  ADD COLUMN `status`      tinyint(1) NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用' AFTER `strategy_name`,
  ADD COLUMN `version`     int        NOT NULL DEFAULT 0 COMMENT '乐观锁版本' AFTER `status`,
  ADD COLUMN `deleted_seq` bigint     NOT NULL DEFAULT 0 COMMENT '软删除唯一化：未删除=0，删除时=key_id' AFTER `del_status`,
  ALGORITHM=INPLACE, LOCK=NONE;

-- ---------------------------------------------------------------------------
-- 2) 逻辑分支表：+2 列（logic_branch_order / deleted_seq）
-- ---------------------------------------------------------------------------
ALTER TABLE `bankint_logic_branch_config`
  ADD COLUMN `logic_branch_order` smallint NOT NULL DEFAULT 0 COMMENT '分支匹配顺序，升序，先命中先生效；兜底分支不受顺序影响（建议仍设为最大，便于阅读）' AFTER `logic_branch_value`,
  ADD COLUMN `deleted_seq`        bigint   NOT NULL DEFAULT 0 COMMENT '软删除唯一化：未删除=0，删除时=key_id' AFTER `del_status`,
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
-- 4) 执行后自检：5 个新列都应存在
-- ---------------------------------------------------------------------------
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('bankint_config', 'bankint_logic_branch_config', 'bankint_execution_log')
   AND COLUMN_NAME IN ('status', 'version', 'deleted_seq', 'logic_branch_order', 'error_msg')
 ORDER BY TABLE_NAME, COLUMN_NAME;
-- **期望 5 行**。少于 5 行说明有语句没执行成功，不要继续。

-- ---------------------------------------------------------------------------
-- 回滚（如需）：
--   ALTER TABLE `bankint_config` DROP COLUMN `deleted_seq`, DROP COLUMN `version`, DROP COLUMN `status`,
--     ALGORITHM=INPLACE, LOCK=NONE;
--   ALTER TABLE `bankint_logic_branch_config` DROP COLUMN `deleted_seq`, DROP COLUMN `logic_branch_order`,
--     ALGORITHM=INPLACE, LOCK=NONE;
--   ALTER TABLE `bankint_execution_log` DROP COLUMN `error_msg`, ALGORITHM=INPLACE, LOCK=NONE;
-- （DROP COLUMN 同样是 In Place + 允许并发 DML；但 01 之后通常已进入 02/03，回滚请整体考虑。）
-- ---------------------------------------------------------------------------
