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
-- 步骤 2/6：数据回填（只 UPDATE，不改结构）
-- 前置：01-add-columns.sql 已执行完成（新列都存在，尤其 `branch_judge`）。
-- 数据量：两张配置表都是"小表"（通常每张 < 1 万行），单条 UPDATE 即可；
--         若某张表超过 10 万行，请按 key_id 分批执行（见脚本末尾说明）。
-- =============================================================================

SET SESSION time_zone = '+08:00';
SET SESSION sql_safe_updates = 0;   -- 部分客户端默认打开安全更新模式，会拒绝"WHERE 不带主键"的 UPDATE

-- ---------------------------------------------------------------------------
-- 1) 历史软删除行：deleted_seq = key_id
--    唯一键含 deleted_seq：未删除行恒为 0，已删除行取 key_id（必然唯一）。
--    不回填就直接加唯一键 → 同一组多条已删除行会撞唯一键（1062）。
-- ---------------------------------------------------------------------------
UPDATE `bankint_config`              SET `deleted_seq` = `key_id` WHERE `del_status` = 1 AND `deleted_seq` = 0;
UPDATE `bankint_logic_branch_config` SET `deleted_seq` = `key_id` WHERE `del_status` = 1 AND `deleted_seq` = 0;

-- ---------------------------------------------------------------------------
-- 2) logic_branch_order：按 (tenant_id, interface_no, key_id) 升序回填 0,1,2,...
--    存量没有这个列（分支顺序原先取决于 DB 返回顺序，不确定），只能按 key_id
--    近似"现状顺序"。MySQL 5.7 没有窗口函数，用自连接计数实现（配置表行数小，代价可接受）。
-- ---------------------------------------------------------------------------
UPDATE `bankint_logic_branch_config` b
  JOIN (
        SELECT a.`key_id`, COUNT(*) - 1 AS ord
          FROM `bankint_logic_branch_config` a
          JOIN `bankint_logic_branch_config` c
            ON c.`tenant_id`    = a.`tenant_id`
           AND c.`interface_no` = a.`interface_no`
           AND c.`key_id`      <= a.`key_id`
           AND c.`del_status`   = 0
         WHERE a.`del_status`   = 0
         GROUP BY a.`key_id`
       ) x
    ON x.`key_id` = b.`key_id`
   SET b.`logic_branch_order` = x.ord;

-- ---------------------------------------------------------------------------
-- 3) 兜底分支（logic_branch_flag 为空）排到最后
--    引擎语义：兜底分支不参与常规匹配，只在所有常规分支都未命中时生效，
--    所以它排第一也不会"抢命中"；这里给个大值只是为了让人看到顺序更直观
--    （与目标 DDL 注释"建议仍设为最大"一致）。
--    ⚠️ 不要在这里改 logic_branch_flag 的值：空值本身就是"兜底"的表达。
-- ---------------------------------------------------------------------------
UPDATE `bankint_logic_branch_config`
   SET `logic_branch_order` = 30000
 WHERE `del_status` = 0
   AND (`logic_branch_flag`  IS NULL OR `logic_branch_flag`  = '')
   AND (`logic_branch_value` IS NULL OR `logic_branch_value` = '');

-- ---------------------------------------------------------------------------
-- 4) ★ branch_judge 回填：把旧的 logic_branch_flag / logic_branch_value 机械映射成判定规格
--
--    映射规则（与 db/changelog/v1.0.0/009-backfill-branch-judge.sql、设计 §4.5.1 **完全一致**）：
--      flag 空                     → {"op":"always"}                       （旧"兜底分支"）
--      flag 有值、value 空         → {"any":[{"op":"strategy","value":flag}]}
--      两者都有                    → {"any":[{"op":"strategy","value":flag},
--                                           {"op":"judged","value":[…],"ignoreCase":true}]}
--      flag 空、value 非空（脏数据）→ {"op":"always"}（旧代码里 flag 空也走兜底，value 从未生效）
--
--    ⚠️ 只做**能证明等价**的变换：分支选错不会报错，只会"跑了另一个动作"，这是最难发现的一类事故。
--       等价性由 core 的 BranchJudgeEquivalenceTest 双跑对照守住。
--    ⚠️ 分隔符按引擎真实口径 `[;,]`（不是 002 注释里的 `|`，那是笔误）；元素逐个 TRIM、空段丢弃。
--    ⚠️ 数字派生表只到 16：一个单元格超过 16 个值会被截断 —— 00-precheck 第 10.3.1 条已把它做成阻塞项。
--    ⚠️ MySQL 不允许 UPDATE 的子查询引用被更新表（1093）⇒ 先落临时表再 JOIN 回写。
-- ---------------------------------------------------------------------------
SET SESSION group_concat_max_len = 8192;

DROP TEMPORARY TABLE IF EXISTS `tmp_branch_judged`;
CREATE TEMPORARY TABLE `tmp_branch_judged` (
  `key_id` bigint       NOT NULL,
  `ord`    smallint     NOT NULL,
  `piece`  varchar(512) NOT NULL,
  KEY `idx_tmp_branch_judged` (`key_id`, `ord`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO `tmp_branch_judged` (`key_id`, `ord`, `piece`)
SELECT s.`key_id`, n.`n`, TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(s.`joined`, ';', n.`n`), ';', -1))
  FROM (SELECT `key_id`, REPLACE(`logic_branch_value`, ',', ';') AS `joined`
          FROM `bankint_logic_branch_config`
         WHERE COALESCE(`branch_judge`, '') = ''
           AND COALESCE(TRIM(`logic_branch_flag`), '') <> '') s
  JOIN (SELECT 1 AS `n` UNION ALL SELECT 2  UNION ALL SELECT 3  UNION ALL SELECT 4
        UNION ALL SELECT 5  UNION ALL SELECT 6  UNION ALL SELECT 7  UNION ALL SELECT 8
        UNION ALL SELECT 9  UNION ALL SELECT 10 UNION ALL SELECT 11 UNION ALL SELECT 12
        UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15 UNION ALL SELECT 16) n
    ON n.`n` <= 1 + LENGTH(s.`joined`) - LENGTH(REPLACE(s.`joined`, ';', ''))
 WHERE TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(s.`joined`, ';', n.`n`), ';', -1)) <> '';

UPDATE `bankint_logic_branch_config` b
  LEFT JOIN (SELECT `key_id`,
                    CONCAT('{"op":"judged","value":[',
                           GROUP_CONCAT(JSON_QUOTE(`piece`) ORDER BY `ord` SEPARATOR ','),
                           '],"ignoreCase":true}') AS `judged`
               FROM `tmp_branch_judged`
              GROUP BY `key_id`) j ON j.`key_id` = b.`key_id`
   SET b.`branch_judge` = CASE
         WHEN COALESCE(TRIM(b.`logic_branch_flag`), '') = '' THEN '{"op":"always"}'
         WHEN j.`judged` IS NULL THEN CONCAT('{"any":[{"op":"strategy","value":',
                JSON_QUOTE(TRIM(b.`logic_branch_flag`)), '}]}')
         ELSE CONCAT('{"any":[{"op":"strategy","value":', JSON_QUOTE(TRIM(b.`logic_branch_flag`)),
                '},', j.`judged`, ']}')
       END
 WHERE COALESCE(b.`branch_judge`, '') = '';

DROP TEMPORARY TABLE `tmp_branch_judged`;
-- 幂等：只回填 `branch_judge` 为空的行，重复执行不会覆盖已有值（已回填的行不会被二次改写）。
-- 若某个库"已经是新结构"（本来就没有旧两列），把第 4 步整段跳过（见 01 的第 2.1 段）。

-- ---------------------------------------------------------------------------
-- 5) status 归位（双保险；ADD COLUMN 的 DEFAULT 1 已保证存量行为 1）
-- ---------------------------------------------------------------------------
UPDATE `bankint_config` SET `status` = 1 WHERE `status` IS NULL OR `status` NOT IN (0, 1);

-- ---------------------------------------------------------------------------
-- 6) 回填完整性自检（**期望全部 bad = 0**）
-- ---------------------------------------------------------------------------
SELECT 'config 软删行未回填' AS chk, COUNT(*) AS bad
  FROM `bankint_config` WHERE `del_status` = 1 AND `deleted_seq` = 0
UNION ALL
SELECT 'branch 软删行未回填', COUNT(*)
  FROM `bankint_logic_branch_config` WHERE `del_status` = 1 AND `deleted_seq` = 0
UNION ALL
SELECT 'branch 判定规格未回填', COUNT(*)
  FROM `bankint_logic_branch_config` WHERE COALESCE(`branch_judge`, '') = ''
UNION ALL
SELECT 'branch 判定规格不是合法 JSON', COUNT(*)
  FROM `bankint_logic_branch_config`
 WHERE COALESCE(TRIM(`branch_judge`), '') <> '' AND JSON_VALID(`branch_judge`) = 0;

-- ---------------------------------------------------------------------------
-- 7) ★ 人工复核：分支顺序 + 判定规格（逐接口确认，尤其是含兜底分支的接口）
--    kind='兜底' 的行应当排在每个接口的最后。
--    这里把**旧两列**和**新列**并排打出来，是"最后一处能直接对着原文核对"的地方 ——
--    03 之后旧两列会被删掉（除非你选择保留），届时只能看规格。
-- ---------------------------------------------------------------------------
SELECT tenant_id, interface_no, key_id, logic_branch_order, method_flag,
       CASE WHEN `branch_judge` = '{"op":"always"}' THEN '兜底' ELSE '常规' END AS kind,
       logic_branch_flag AS old_flag, logic_branch_value AS old_value, branch_judge
  FROM `bankint_logic_branch_config`
 WHERE `del_status` = 0
 ORDER BY tenant_id, interface_no, logic_branch_order, key_id;
-- 复核要点：
--   ① 每个接口的常规分支顺序是否符合业务预期（引擎按 logic_branch_order 升序取首个命中）；
--   ② 兜底分支是否只有 1 条、排最后，且 `branch_judge` 恰好是 {"op":"always"}；
--   ③ 逐个核对：old_flag 空 ↔ branch_judge = always；old_flag 有值 ↔ 规格里第一个条件是
--      {"op":"strategy","value":old_flag}；old_value 非空 ↔ 规格里跟着一个 judged，
--      其 value 数组 = old_value 按 [;,] 切开、去空段后的值（顺序可能被 GROUP BY 重排，值集合必须一致）；
--   ④ 顺序值有重复也不影响功能（同值按 key_id 兜底），但建议手工整理成 10/20/30 的留白写法。

-- ---------------------------------------------------------------------------
-- 大表分批说明（仅当某张配置表 > 10 万行时使用）：
--   UPDATE `bankint_config` SET `deleted_seq` = `key_id`
--    WHERE `del_status` = 1 AND `deleted_seq` = 0 AND `key_id` > <上次最大 key_id>
--    ORDER BY `key_id` LIMIT 1000;   -- 循环执行，直到 affected rows = 0
-- （不要写 DELETE/UPDATE ... LIMIT 的"边删边查同表"语句，5.7 会报 1093。）
-- ---------------------------------------------------------------------------
