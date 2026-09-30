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
-- 步骤 0/6：执行日志"分区 + 冷热分离"改造前体检（**只读**，不改任何数据）
--
-- 表前缀：本套脚本以 `ifmap_` 为例，存量部署请先整体替换：
--         sed -i 's/ifmap_/bankint_/g' *.sql
--
-- 为什么先体检：
--   1. 分区改造 = 重建表（DDL 全表 COPY），必须先知道表多大、能不能在窗口内做完；
--   2. 分区列（add_time）必须进主键 → 要确认现有主键/唯一键、以及有没有外键引用；
--   3. 重建表 **不会** 带走触发器 / 视图 / 存储过程里的旧表名引用 → 必须先查出来；
--   4. 要确认当前写入的 add_time 区间与月度分布，才能决定预置哪几个分区。
--
-- ⚠️ 第 2、3 段会扫表/扫索引。**大表请在从库或低峰期执行**，
--    且不要把本文件的查询拆成"一列一条 SQL"循环跑（那会把大表扫很多遍）。
--
-- 输出请整段贴进变更评审单：改造前后的分区清单、行数、主键定义要能逐条对上。
-- =============================================================================

SET SESSION time_zone = '+08:00';        -- 时间列按会话时区解释，后续每一步都要一致
SET SESSION lock_wait_timeout = 10;      -- 后面几步的 DDL 等锁上限（默认 1 年，遇长事务会一直挂着）
SET SESSION group_concat_max_len = 100000;

-- -----------------------------------------------------------------------------
-- 1. 环境与前提
-- -----------------------------------------------------------------------------
SELECT VERSION()                        AS mysql_version,
       @@innodb_page_size               AS page_size,
       @@innodb_file_per_table          AS file_per_table,   -- 分区表要求每分区独立表空间（1 = 满足）
       @@character_set_server           AS charset_server,
       @@time_zone                      AS server_time_zone,
       @@sql_mode                       AS sql_mode;

-- 有没有长事务在跑？（有长事务时 DDL 拿不到元数据锁，会一直等）
SELECT trx_id, trx_mysql_thread_id AS thread_id, trx_started,
       TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS running_seconds, trx_rows_locked, trx_state
  FROM information_schema.innodb_trx
 ORDER BY trx_started
 LIMIT 10;

-- -----------------------------------------------------------------------------
-- 2. 表体量（用统计信息估算，不做 COUNT(*) —— 千万级表上 COUNT(*) 本身就是事故）
-- -----------------------------------------------------------------------------
SELECT table_name,
       engine,
       row_format,
       table_rows                                             AS estimated_rows,
       ROUND((data_length + index_length) / 1024 / 1024, 1)   AS total_mb,
       ROUND(data_length / 1024 / 1024, 1)                    AS data_mb,
       ROUND(index_length / 1024 / 1024, 1)                   AS index_mb,
       ROUND(data_free / 1024 / 1024, 1)                      AS free_mb,
       create_time,
       update_time
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('ifmap_execution_log', 'ifmap_execution_log_archive');

-- 精确边界用索引就能拿到（很快）；COUNT(*) 单独一条，按需再跑
SELECT COUNT(*)  AS exact_rows,
       MIN(add_time) AS oldest,
       MAX(add_time) AS newest
  FROM ifmap_execution_log;

-- -----------------------------------------------------------------------------
-- 3. 月度分布：决定预置哪些分区（覆盖 oldest ~ newest，且留出下个月）
--    GROUP BY 表达式会走 idx_*_log_time 的索引扫描（不回表），但仍是全索引扫描。
-- -----------------------------------------------------------------------------
SELECT DATE_FORMAT(add_time, '%Y-%m') AS month,
       COUNT(*)                       AS rows_in_month
  FROM ifmap_execution_log
 GROUP BY DATE_FORMAT(add_time, '%Y-%m')
 ORDER BY month;

-- -----------------------------------------------------------------------------
-- 4. 主键 / 唯一键 / 索引现状（改造后主键必须变成 (key_id, add_time)）
--    分区表硬约束：唯一键（含主键）必须包含全部分区列，否则建表时报 1503。
-- -----------------------------------------------------------------------------
SELECT index_name, non_unique, seq_in_index, column_name, cardinality
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND table_name = 'ifmap_execution_log'
 ORDER BY index_name, seq_in_index;

-- 确认没有外键（分区表不允许外键；被别的表引用也会让重建后的数据完整性变复杂）
SELECT constraint_name, table_name, referenced_table_name, column_name
  FROM information_schema.key_column_usage
 WHERE table_schema = DATABASE()
   AND (table_name = 'ifmap_execution_log'
        OR referenced_table_name = 'ifmap_execution_log')
   AND referenced_table_name IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 5. 触发器 / 视图 / 例程：重建表会丢触发器，视图与例程里的旧表名引用要一起改
-- -----------------------------------------------------------------------------
SELECT trigger_name, event_manipulation, event_object_table
  FROM information_schema.triggers
 WHERE event_object_schema = DATABASE()
   AND event_object_table LIKE 'ifmap_execution_log%';

SELECT table_name AS view_name
  FROM information_schema.views
 WHERE table_schema = DATABASE()
   AND (view_definition LIKE '%ifmap_execution_log%');

SELECT routine_type, routine_name
  FROM information_schema.routines
 WHERE routine_schema = DATABASE()
   AND routine_definition LIKE '%ifmap_execution_log%';

-- -----------------------------------------------------------------------------
-- 6. 是否已经是分区表？（本脚本应当只在"分区列为空"时被继续执行）
-- -----------------------------------------------------------------------------
SELECT partition_name, partition_method, partition_expression, partition_description,
       table_rows AS estimated_rows
  FROM information_schema.partitions
 WHERE table_schema = DATABASE()
   AND table_name = 'ifmap_execution_log'
 ORDER BY partition_ordinal_position;
-- 输出的第一行 partition_name 为 NULL、partition_method 为空 → 说明还是普通表，
-- 可以按 01-partition-hot.sql 改造；若已列出 p202601 之类的分区名，说明已经改过，
-- 跳过第 1 步，直接看 02/03。
