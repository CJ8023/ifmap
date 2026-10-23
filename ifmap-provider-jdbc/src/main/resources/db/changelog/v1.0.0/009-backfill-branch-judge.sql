--liquibase formatted sql
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

-- =====================================================================================
-- 逻辑分支判定列换血（2/3）：把旧的 logic_branch_flag / logic_branch_value 机械映射成 branch_judge
--
-- 映射规则（与设计 §4.5.1、迁移 kit 02-backfill 完全一致）：
--   flag 空                        → {"op":"always"}                     （旧"兜底分支"）
--   flag 有值、value 空            → {"any":[{"op":"strategy","value":flag}]}
--   两者都有                       → {"any":[{"op":"strategy","value":flag},
--                                            {"op":"judged","value":[...],"ignoreCase":true}]}
--   flag 空、value 非空（脏数据）  → {"op":"always"}（旧代码里 flag 空就不参与常规匹配，
--                                     value 本来也没生效 ⇒ 迁成 always 不改行为；由 precheck 报出来）
--
-- 【为什么是"机械 1:1"而不是"顺手清洗"】迁移脚本只做能证明等价的变换。
-- 任何"看起来更合理"的重写（比如把 flag 当 JsonPath 用）都会改变分支选择，
-- 而分支选错只会表现为"跑了另一个动作"，运行时不会报错 —— 这是最难发现的一类事故。
-- 等价性由 BranchJudgeEquivalenceTest 双跑对照守住。
--
-- 【分隔符】按引擎真实口径 `[;,]`（不是 002 注释里写的 `|`，那是文档笔误）。
-- 元素逐个 TRIM（旧代码 `v.trim().equalsIgnoreCase(actual.trim())`），空段丢弃。
--
-- 【为什么用临时表】MySQL 不允许在 UPDATE 的子查询里引用被更新的表（1093）。
-- 展开多值需要按行聚合，所以先落到临时表再 JOIN 回写。
-- 【为什么不是 UPDATE ... LIMIT 循环】需要保持幂等且不依赖存储过程。
-- =====================================================================================

--changeset caijun:009-logic-branch-backfill-prepare
SET SESSION group_concat_max_len = 8192;

--changeset caijun:009-logic-branch-backfill-tmp
CREATE TEMPORARY TABLE `${tablePrefix}tmp_branch_judged` (
  `key_id` bigint       NOT NULL,
  `ord`    smallint     NOT NULL,
  `piece`  varchar(512) NOT NULL,
  KEY `idx_${tablePrefix}tmp_branch_judged` (`key_id`, `ord`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

--changeset caijun:009-logic-branch-backfill-split
INSERT INTO `${tablePrefix}tmp_branch_judged` (`key_id`, `ord`, `piece`)
SELECT s.`key_id`, n.`n`, TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(s.`joined`, ';', n.`n`), ';', -1))
FROM (SELECT `key_id`, REPLACE(`logic_branch_value`, ',', ';') AS `joined`
        FROM `${tablePrefix}logic_branch_config`
       WHERE COALESCE(`branch_judge`, '') = ''
         AND COALESCE(TRIM(`logic_branch_flag`), '') <> '') s
JOIN (SELECT 1 AS `n` UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
      UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
      UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL SELECT 16) n
  ON n.`n` <= 1 + LENGTH(s.`joined`) - LENGTH(REPLACE(s.`joined`, ';', ''))
WHERE TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(s.`joined`, ';', n.`n`), ';', -1)) <> '';

--changeset caijun:009-logic-branch-backfill-update
UPDATE `${tablePrefix}logic_branch_config` b
LEFT JOIN (SELECT `key_id`,
                  CONCAT('{"op":"judged","value":[',
                         GROUP_CONCAT(JSON_QUOTE(`piece`) ORDER BY `ord` SEPARATOR ','),
                         '],"ignoreCase":true}') AS `judged`
             FROM `${tablePrefix}tmp_branch_judged`
            GROUP BY `key_id`) j ON j.`key_id` = b.`key_id`
SET b.`branch_judge` = CASE
      WHEN COALESCE(TRIM(b.`logic_branch_flag`), '') = '' THEN '{"op":"always"}'
      WHEN j.`judged` IS NULL THEN CONCAT('{"any":[{"op":"strategy","value":',
             JSON_QUOTE(TRIM(b.`logic_branch_flag`)), '}]}')
      ELSE CONCAT('{"any":[{"op":"strategy","value":', JSON_QUOTE(TRIM(b.`logic_branch_flag`)),
             '},', j.`judged`, ']}')
    END
WHERE COALESCE(b.`branch_judge`, '') = '';

--changeset caijun:009-logic-branch-backfill-drop-tmp
DROP TEMPORARY TABLE `${tablePrefix}tmp_branch_judged`;
