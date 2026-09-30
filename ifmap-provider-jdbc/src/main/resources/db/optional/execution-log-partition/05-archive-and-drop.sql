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
-- 步骤 5/6：归档 + 保留期回收的两条路（**推荐第 5.1 节的 Java 归档器**）
--
-- 表前缀：本套脚本以 `ifmap_` 为例，存量部署请先整体替换：
--         sed -i 's/ifmap_/bankint_/g' *.sql
--
-- 前置：归档冷表已建（04-create-archive-table.sql）。
--
-- 两条路的区别：
--   5.1 Java 归档器（推荐）：`JdbcExecutionLogArchiver`，可重入、分批、批间停顿、失败可重跑，
--       单测在 H2 上跑的就是真实生产 DDL。**要长期跑就用它**（宿主自己的调度器里每天/每月调一次）。
--   5.2 本节的裸 SQL：给"只想让 DBA 手工搬一次"或"想写自己的存储过程"的场景。
--       ⚠️ 裸 SQL 没有重入保护，也没有"搬完才删"的一致性保证：
--          每批务必**先 INSERT 再 DELETE**，且不要跨月手工拼一堆批次（拼错区间会漏数据）。
--   ★ 两条路都会把冷数据留在 ifmap_execution_log_archive 里，热表变小、冷表只增不改。
--
-- 保留期回收（清理）三选一：
--   A. 未分区：用 ifmap 自带的保留期清理（`ifmap.log.clean-enabled=true`，分批 DELETE）。
--   B. 已分区 + 数据要留：**先归档、再 DROP PARTITION**（本节 5.3），秒级且不产生删除 binlog。
--   C. 已分区 + 数据不要留：直接 DROP PARTITION（跳过归档就是永久删除，不可恢复！）。
-- =============================================================================

SET SESSION time_zone = '+08:00';
SET SESSION lock_wait_timeout = 10;

-- -----------------------------------------------------------------------------
-- 5.1 用 Java 归档器（推荐）
-- -----------------------------------------------------------------------------
--   JdbcExecutionLogArchiver archiver = new JdbcExecutionLogArchiver(dataSource, "ifmap_");
--   LogArchiveResult result = archiver.archive(180, 1000, 1000, 50L);
--   // olderThanDays=180（搬 180 天以前的），batchSize=1000，maxBatches=1000，批间停顿 50ms
--
--   放进宿主调度（示例：Spring 里每天 04:10 跑一次；别开 @EnableScheduling —— 见 docs/07 的踩坑说明）：
--     @Bean IfmapLogArchiveJob ifmapLogArchiveJob(JdbcExecutionLogArchiver archiver) { ... }
--   多实例部署时**同一时刻只能有一个实例在跑**（分布式锁/ShedLock/选举），
--   并发跑会在冷表主键上冲突报错（有意的失败，不是静默写重）。

-- -----------------------------------------------------------------------------
-- 5.2 裸 SQL 手工搬一批（把 <天数> 换成保留天数，例如 180）
--     ① 先看这批要搬多少（能走分区/索引裁剪，很快）
-- -----------------------------------------------------------------------------
SELECT COUNT(*) AS rows_to_archive
  FROM `ifmap_execution_log`
 WHERE `add_time` < DATE_SUB(NOW(), INTERVAL 180 DAY);

--     ② 搬一批（LIMIT 限制单批规模，避免大事务；按 add_time 升序，老数据优先）
INSERT INTO `ifmap_execution_log_archive`
      (`key_id`,`tenant_id`,`interface_no`,`biz_id`,`request_param`,`response_param`,`execution_time`,
       `execution_result`,`error_msg`,`remark`,`del_status`,`add_user_id`,`add_time`,`add_request_id`,
       `modify_user_id`,`modify_time`,`modify_request_id`)
SELECT `key_id`,`tenant_id`,`interface_no`,`biz_id`,`request_param`,`response_param`,`execution_time`,
       `execution_result`,`error_msg`,`remark`,`del_status`,`add_user_id`,`add_time`,`add_request_id`,
       `modify_user_id`,`modify_time`,`modify_request_id`
  FROM `ifmap_execution_log`
 WHERE `add_time` < DATE_SUB(NOW(), INTERVAL 180 DAY)
 ORDER BY `add_time`
 LIMIT 1000;

--     ③ 对账：这一批的边界（搬之前记下最小 add_time，搬之后比对两侧计数）
SELECT MIN(`add_time`) AS oldest_left_in_hot FROM `ifmap_execution_log`;
SELECT (SELECT COUNT(*) FROM `ifmap_execution_log`)         AS hot_rows,
       (SELECT COUNT(*) FROM `ifmap_execution_log_archive`) AS cold_rows;

--     ④ 再删热表里"已经进冷表"的那批（**用 key_id 精确删除**，不要按 add_time 范围删：
--        范围删除会把边界上没搬走的行一起删掉）。
--        下面子查询读的是**冷表**，所以没有问题；反过来写
--        `DELETE FROM 热表 WHERE key_id IN (SELECT key_id FROM 热表 WHERE add_time < ?)`
--        会被 MySQL 以 1093 拒绝（不允许在子查询里读被删的同一张表）——
--        ifmap 的 Java 归档器因此是"先查 key_id、再按 IN 删"两步走（H2 上同样合法）。
--        前提：冷表主键是 key_id（04 建表脚本已如此），否则这个 IN 子查询会全表扫冷表。
DELETE FROM `ifmap_execution_log`
 WHERE `key_id` IN (
        SELECT `key_id` FROM `ifmap_execution_log_archive`
         WHERE `add_time` < DATE_SUB(NOW(), INTERVAL 180 DAY)
       );
--     ⑤ 重复 ②~④ 直到 ① 的计数归零（每批之间 sleep 几十毫秒，给从库追 binlog 留时间）。

-- -----------------------------------------------------------------------------
-- 5.3 已分区：先归档该分区，再整分区回收（秒级、不产生删除 binlog）
--     顺序不能反！DROP PARTITION 是**不可恢复的物理删除**。
-- -----------------------------------------------------------------------------
--     ① 确认要回收的分区（示例 p202501）确实整月都超过保留期
SELECT MIN(`add_time`) AS oldest, MAX(`add_time`) AS newest, COUNT(*) AS rows_in_partition
  FROM `ifmap_execution_log` PARTITION (p202501);

--     ② 归档该分区：把 5.2② 的语句体换成下面的 SELECT 部分（WHERE 用**分区所在的自然月**，
--        分区裁剪会把扫描范围限制在这一个分区里；仍然要 LIMIT 分批，理由同 5.2）。
INSERT INTO `ifmap_execution_log_archive`
      (`key_id`,`tenant_id`,`interface_no`,`biz_id`,`request_param`,`response_param`,`execution_time`,
       `execution_result`,`error_msg`,`remark`,`del_status`,`add_user_id`,`add_time`,`add_request_id`,
       `modify_user_id`,`modify_time`,`modify_request_id`)
SELECT `key_id`,`tenant_id`,`interface_no`,`biz_id`,`request_param`,`response_param`,`execution_time`,
       `execution_result`,`error_msg`,`remark`,`del_status`,`add_user_id`,`add_time`,`add_request_id`,
       `modify_user_id`,`modify_time`,`modify_request_id`
  FROM `ifmap_execution_log`
 WHERE `add_time` >= '2025-01-01 00:00:00.000' AND `add_time` < '2025-02-01 00:00:00.000'
 ORDER BY `add_time`
 LIMIT 1000;   -- 反复执行直到"本次影响行数 = 0"

--     ③ 对账：该分区的行数 = 冷表里该时间段的行数，两者必须相等
SELECT (SELECT COUNT(*) FROM `ifmap_execution_log` PARTITION (p202501)) AS hot_rows_in_partition,
       (SELECT COUNT(*) FROM `ifmap_execution_log_archive`
         WHERE `add_time` >= '2025-01-01 00:00:00' AND `add_time` < '2025-02-01 00:00:00') AS cold_rows_in_range;

--     ④ 相等后再 DROP（这一步之后就回不去了；建议先 `SHOW CREATE TABLE` 存档一份分区定义）
-- ALTER TABLE `ifmap_execution_log` DROP PARTITION p202501;

--     ★ 别忘了：DROP PARTITION 不会释放磁盘文件，只有 `OPTIMIZE TABLE` 或
--       （innodb_file_per_table = 1 时）分区文件被删除后空间才回收；
--       IBUF/undo 的清理是异步的，磁盘水位会滞后几分钟到几十分钟。

-- -----------------------------------------------------------------------------
-- 5.4 冷表自身的留存到期（合规要求"留 5 年"）
--     * 冷表未分区：分批 DELETE（同 ifmap 的 JdbcExecutionLogCleaner 思路，只是换个表名）。
--     * 冷表按 add_time 分区（04 里的变体 A）：`ALTER TABLE ... DROP PARTITION pYYYYMM` 秒级回收。
--     * 冷表可以搬到更便宜的存储/对象存储：冷表按 add_time 分区后，
--       导出某个月只需 `SELECT ... FROM archive PARTITION (pYYYYMM)`（见 docs/08-日志与合规.md）。
-- =============================================================================
