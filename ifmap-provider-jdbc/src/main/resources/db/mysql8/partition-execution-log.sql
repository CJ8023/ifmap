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

-- ============================================================================
-- 可选：ifmap_execution_log 按月分区（**默认不启用**）
--
-- 为什么默认不启用：分区表要求分区列进主键，会把主键从 `key_id` 改成
-- `(key_id, add_time)`，并带来"分区维护/跨分区查询/DROP PARTITION 归档"的运维成本。
-- 默认方案改为引擎内置 LogCleaner（按 add_time 保留 N 天分批删除，见设计文档 §6.4）。
--
-- 适用场景：单表日志量达到亿级且需要"按月快速归档"。
-- 执行前提：① 业务低峰期；② 该表已有 PRIMARY KEY(key_id, add_time)；
--          ③ MySQL 5.7 / 8.0 均支持 RANGE(TO_DAYS(...)) 分区。
-- 注意：MySQL 分区列必须是主键的一部分，故必须同步改主键（下面的 ALTER 已包含）。
-- ============================================================================

-- 1) 把主键改为 (key_id, add_time)——大数据量表的 ALTER，请遵循发布侧 DDL 规范
--    （同表多变更合并成一条 ALTER；上百万行用 gh-ost/pt-osc，不要原生 ALTER）
ALTER TABLE `ifmap_execution_log`
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (`key_id`, `add_time`),
  ALGORITHM=INPLACE, LOCK=NONE;

-- 2) 建分区（示例：2026-09 ~ 2026-12 各一个月 + pmax 兜底；请按实际保留策略调整）
ALTER TABLE `ifmap_execution_log`
  PARTITION BY RANGE (TO_DAYS(`add_time`)) (
    PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
    PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
    PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION pmax    VALUES LESS THAN (MAXVALUE)
  );

-- 3) 归档 = 直接丢分区（秒级，代价远低于 DELETE）
-- ALTER TABLE `ifmap_execution_log` DROP PARTITION p202609;

-- 4) 新月份需手工/定时任务补分区（否则数据都进 pmax，失去归档意义）：
-- ALTER TABLE `ifmap_execution_log` REORGANIZE PARTITION pmax INTO (
--   PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
--   PARTITION pmax    VALUES LESS THAN (MAXVALUE)
-- );
