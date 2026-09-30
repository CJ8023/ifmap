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
-- 步骤 2/6：给分区表**加下个月的分区**（建议做成每月固定任务，月初 1 号执行）
--
-- 表前缀：本套脚本以 `ifmap_` 为例，存量部署请先整体替换：
--         sed -i 's/ifmap_/bankint_/g' *.sql
--
-- ⚠️ MySQL **没有** `ADD PARTITION IF NOT EXISTS`：
--    ① 若已存在 `pmax`（VALUES LESS THAN MAXVALUE）兜底分区，`ADD PARTITION` 会直接报错
--       （"VALUES LESS THAN value must be strictly increasing"），必须用下面的 REORGANIZE 拆 pmax；
--    ② 若目标分区已经存在，REORGANIZE 也会报 "Duplicate partition name"。
--    因此本脚本的第一步是**只读检查**，人工确认后再执行第 2 步（宁可按错，也不要盲跑）。
--
-- ⚠️ 一定要在月初前加：新数据落到 pmax 之后，再拆 pmax 就要搬数据（REORGANIZE 会 COPY 该分区），
--    而这一步发生在线上的日志写入路径上。
-- =============================================================================

SET SESSION time_zone = '+08:00';
SET SESSION lock_wait_timeout = 10;

-- -----------------------------------------------------------------------------
-- 2.1 只读检查：现有分区清单 + pmax 里是否已经有数据
-- -----------------------------------------------------------------------------
SELECT partition_name, partition_description, table_rows AS estimated_rows,
       ROUND(data_length / 1024 / 1024, 1) AS data_mb
  FROM information_schema.partitions
 WHERE table_schema = DATABASE()
   AND table_name = 'ifmap_execution_log'
 ORDER BY partition_ordinal_position;

-- pmax 必须为空（estimated_rows = 0 / 无数据）：非空说明上个月忘了加分区
SELECT COUNT(*) AS rows_in_pmax
  FROM `ifmap_execution_log` PARTITION (pmax);
-- 若不为 0：先用 02.2 把 pmax 拆成 [老边界, 今天) 与 pmax 两段，再立刻加下个月分区，
--           并检查应用侧为什么漏加了分区（应当有告警）。

-- -----------------------------------------------------------------------------
-- 2.2 加下个月分区（示例：加 2026-04）。把 4 月换成你要加的月份，
--     边界一律写成"下下个月 1 号"——这样每个分区的区间就是完整自然月 [本月1号, 下月1号)。
-- -----------------------------------------------------------------------------
ALTER TABLE `ifmap_execution_log`
  REORGANIZE PARTITION pmax INTO (
    PARTITION p202604 VALUES LESS THAN (TO_DAYS('2026-05-01')),
    PARTITION pmax    VALUES LESS THAN MAXVALUE        -- 兜底分区必须保留，不能丢
  );

-- -----------------------------------------------------------------------------
-- 2.3 顺手把"保留期之外"的分区归档后 DROP（秒级，不产生删除 binlog）：
--     归档语句与 DROP 顺序见 05-archive-and-drop.sql（**先归档再 DROP**，否则数据直接没了）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 【可选】一次生成"未来 N 个月"的加分区 DDL（减少人工漏加）。
-- 本段只生成**文本**：要在主库执行的是生成出来的那条 ALTER。
-- ⚠️ 把 `INTERVAL 0 MONTH` 改成"还没建的第一个月"：生成结果里不能出现已存在的分区名，
--    否则会报 Duplicate partition name。
-- -----------------------------------------------------------------------------
SELECT CONCAT('ALTER TABLE `ifmap_execution_log` REORGANIZE PARTITION pmax INTO (',
              GROUP_CONCAT(CONCAT('PARTITION p', DATE_FORMAT(d, '%Y%m'),
                                  ' VALUES LESS THAN (TO_DAYS(''', DATE_ADD(d, INTERVAL 1 MONTH), ''')),')
                           ORDER BY d SEPARATOR '\n    '),
              '\n    PARTITION pmax VALUES LESS THAN MAXVALUE);') AS generated_ddl
  FROM (
        SELECT DATE_ADD(DATE_FORMAT(CURDATE(), '%Y-%m-01'), INTERVAL n MONTH) AS d
          FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2) AS seq
       ) AS months;
-- n 取 0/1/2 → 生成"本月 + 往后两个月"三段；本月已经在 2.2 示例里加过的话，
-- 就从 INTERVAL 1 MONTH 开始，只生成后两个月。
