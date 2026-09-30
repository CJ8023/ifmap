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
-- 前置：01-add-columns.sql 已执行完成（5 个新列都存在）。
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
-- 4) status 归位（双保险；ADD COLUMN 的 DEFAULT 1 已保证存量行为 1）
-- ---------------------------------------------------------------------------
UPDATE `bankint_config` SET `status` = 1 WHERE `status` IS NULL OR `status` NOT IN (0, 1);

-- ---------------------------------------------------------------------------
-- 5) 回填完整性自检（**期望 bad = 0**）
-- ---------------------------------------------------------------------------
SELECT 'config 软删行未回填' AS chk, COUNT(*) AS bad
  FROM `bankint_config` WHERE `del_status` = 1 AND `deleted_seq` = 0
UNION ALL
SELECT 'branch 软删行未回填', COUNT(*)
  FROM `bankint_logic_branch_config` WHERE `del_status` = 1 AND `deleted_seq` = 0;

-- ---------------------------------------------------------------------------
-- 6) ★ 人工复核：分支顺序（必须逐接口确认，尤其是含兜底分支的接口）
--    kind='兜底' 的行应当排在每个接口的最后。
-- ---------------------------------------------------------------------------
SELECT tenant_id, interface_no, key_id, logic_branch_order,
       CASE WHEN logic_branch_flag IS NULL OR logic_branch_flag = '' THEN '兜底' ELSE '常规' END AS kind,
       logic_branch_flag, logic_branch_value, method_flag
  FROM `bankint_logic_branch_config`
 WHERE `del_status` = 0
 ORDER BY tenant_id, interface_no, logic_branch_order, key_id;
-- 复核要点：
--   ① 每个接口的常规分支顺序是否符合业务预期（引擎按 logic_branch_order 升序取首个命中）；
--   ② 兜底分支是否只有 1 条且排在最后；
--   ③ 顺序值有重复也不影响功能（同值按 key_id 兜底），但建议手工整理成 10/20/30 的留白写法。

-- ---------------------------------------------------------------------------
-- 大表分批说明（仅当某张配置表 > 10 万行时使用）：
--   UPDATE `bankint_config` SET `deleted_seq` = `key_id`
--    WHERE `del_status` = 1 AND `deleted_seq` = 0 AND `key_id` > <上次最大 key_id>
--    ORDER BY `key_id` LIMIT 1000;   -- 循环执行，直到 affected rows = 0
-- （不要写 DELETE/UPDATE ... LIMIT 的"边删边查同表"语句，5.7 会报 1093。）
-- ---------------------------------------------------------------------------
