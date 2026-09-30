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
-- 步骤 3/6：分区/冷热分离改造后的校验（**只读**）
--
-- 表前缀：本套脚本以 `ifmap_` 为例，存量部署请先整体替换：
--         sed -i 's/ifmap_/bankint_/g' *.sql
--
-- 校验三件事：
--   ① 结构对：分区按月、有 pmax 兜底、主键/索引是预期的；
--   ② 数据对：总行数与新旧表对账结果一致、pmax 里没有数据（说明每月加分区没漏）；
--   ③ 行为对：时间范围查询真的被分区裁剪了（EXPLAIN 的 partitions 列只出现相关分区）。
-- 这三条通过后再把 01 里的"回滚窗口"用完 DROP 旧表。
-- =============================================================================

SET SESSION time_zone = '+08:00';

-- -----------------------------------------------------------------------------
-- 3.1 分区清单（应当：每个自然月一个 pYYYYMM + 最后一个 pmax）
-- -----------------------------------------------------------------------------
SELECT partition_name,
       partition_method,
       partition_expression,
       partition_description,
       partition_ordinal_position,
       table_rows AS estimated_rows,
       ROUND(data_length / 1024 / 1024, 1) AS data_mb
  FROM information_schema.partitions
 WHERE table_schema = DATABASE()
   AND table_name = 'ifmap_execution_log'
 ORDER BY partition_ordinal_position;

-- 断言 1：最后一行必须是 pmax（兜底分区）；断言 2：没有 partition_name 为 NULL 的行
SELECT COUNT(*)                                        AS partition_count,
       SUM(partition_name IS NULL)                     AS not_partitioned_rows,
       SUM(partition_name = 'pmax')                    AS has_fallback
  FROM information_schema.partitions
 WHERE table_schema = DATABASE()
   AND table_name = 'ifmap_execution_log';

-- -----------------------------------------------------------------------------
-- 3.2 主键 / 索引（主键必须是 (key_id, add_time)：seq_in_index 1 = key_id、2 = add_time）
-- -----------------------------------------------------------------------------
SELECT index_name, non_unique, seq_in_index, column_name
  FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND table_name = 'ifmap_execution_log'
 ORDER BY index_name, seq_in_index;

-- -----------------------------------------------------------------------------
-- 3.3 数据对账：
--     a) pmax 必须为空（非空 = 加分区漏了，新数据正落进兜底分区，赶紧补 02 的分区）
--     b) 逐月计数与业务日志条数对得上（对账基线取 00-precheck.sql 的输出）
-- -----------------------------------------------------------------------------
SELECT COUNT(*) AS rows_in_pmax FROM `ifmap_execution_log` PARTITION (pmax);

SELECT DATE_FORMAT(add_time, '%Y-%m') AS month, COUNT(*) AS rows_in_month
  FROM `ifmap_execution_log`
 GROUP BY DATE_FORMAT(add_time, '%Y-%m')
 ORDER BY month;

-- 与归档冷表对账（启用冷热分离后；热表 + 冷表 = 应保留总量）
SELECT (SELECT COUNT(*) FROM `ifmap_execution_log`)         AS hot_rows,
       (SELECT COUNT(*) FROM `ifmap_execution_log_archive`) AS cold_rows;

-- -----------------------------------------------------------------------------
-- 3.4 分区裁剪验证：EXPLAIN 的 partitions 列必须只出现 1 个（或少数几个）分区，
--     出现"全部 pYYYYMM + pmax"说明没有裁剪 —— 通常是查询条件写成了
--     `DATE(add_time) = ...` 或 `add_time LIKE '2026-01%'`（列上套函数/前模糊匹配都会失效）。
-- -----------------------------------------------------------------------------
EXPLAIN SELECT * FROM `ifmap_execution_log`
 WHERE `add_time` >= '2026-03-01 00:00:00' AND `add_time` < '2026-04-01 00:00:00';

-- 对照：ifmap 的清理/归档语句就长这样（`WHERE add_time < ?` + `ORDER BY add_time`），
-- 也应当被裁剪；下面的 EXPLAIN 用来确认归档不会全表扫。
EXPLAIN SELECT `key_id` FROM `ifmap_execution_log`
 WHERE `add_time` < DATE_SUB(NOW(), INTERVAL 180 DAY)
 ORDER BY `add_time` LIMIT 1000;

-- -----------------------------------------------------------------------------
-- 3.5 收尾清单（贴在变更单上，逐条打勾）
--   [ ] 分区清单按月连续、最后一个分区是 pmax
--   [ ] pmax 行数为 0
--   [ ] 主键 = (key_id, add_time)，三个业务索引都在
--   [ ] 热表行数 = 改造前行数（含归档冷表则热表 + 冷表 = 应保留总量）
--   [ ] EXPLAIN 显示分区裁剪生效
--   [ ] 应用日志写入正常（跑一笔真实接口，看热表有没有新行、add_time 是否是"现在"）
--   [ ] 月度加分区任务已排上（02-add-monthly-partition.sql），并配了 pmax 非空告警
--   [ ] 旧表（ifmap_execution_log_old）已过回滚窗口并 DROP，磁盘已释放
-- =============================================================================
