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
-- 步骤 4/6：表名切换（**只在真正的切换时刻执行**）
--
-- 前置条件（缺一不可，全部满足再往下走）：
--   ① 存量模块已停用/下线（读 `bankint_*` 的那套代码）——否则改完名它立刻报
--      `Table 'xxx.bankint_config' doesn't exist`；
--   ② 03-modify-and-index.sql 已完成，05-verify.sql 的"列 / 索引 / 回填"三节核对通过；
--   ③ M2（规则与策略对齐）完成：启动期契约自检 0 违规；
--   ④ M3（影子运行）达到退出条件：连续 N 天（建议 7）零差异或差异均有明确解释；
--   ⑤ 已备份（至少备份三张表结构 + 配置数据）。
--
-- 为什么 RENAME 放在最后：
--   共存期先用「路径 A：ifmap 配 ifmap.table-prefix=bankint_」直接读写存量表，
--   把结构改完、把新链路验证完；切流那一刻才改名（元数据操作，毫秒级）。
--   反过来"先改名再慢慢改结构"= 切换期间旧模块已不可用、新模块又没就绪。
--
-- RENAME TABLE 一条语句里可以改多张表，按书写顺序从左到右执行；本身是元数据操作。
-- =============================================================================

SET SESSION lock_wait_timeout = 10;

RENAME TABLE
  `bankint_config`              TO `ifmap_config`,
  `bankint_logic_branch_config` TO `ifmap_logic_branch_config`,
  `bankint_execution_log`       TO `ifmap_execution_log`;
-- 若存量还有历史表（例如 `bankint_config_history`），把它一并加进这条语句。

-- ---------------------------------------------------------------------------
-- 回滚（秒级，随时可用；改回后存量模块仍需要重新启用）
-- ---------------------------------------------------------------------------
-- RENAME TABLE
--   `ifmap_config`              TO `bankint_config`,
--   `ifmap_logic_branch_config` TO `bankint_logic_branch_config`,
--   `ifmap_execution_log`       TO `bankint_execution_log`;

-- ---------------------------------------------------------------------------
-- 自检：三张表都改到 ifmap_ 前缀
-- ---------------------------------------------------------------------------
SELECT TABLE_NAME, TABLE_ROWS, ROUND(DATA_LENGTH / 1024 / 1024, 1) AS data_mb
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'ifmap%' ORDER BY TABLE_NAME;
-- **期望 3 行**（若存量本来就有 ifmap_* 表会更多，注意甄别）。
-- 第 4 张 `ifmap_config_history` 由 ifmap 启动时按目标 DDL 创建（见 05-verify.sql 说明）。
-- 注意：RENAME 不会改索引名 —— 本 kit 的索引在 03 里就已经按 ifmap_* 命名，所以无需二次重建。

-- ---------------------------------------------------------------------------
-- 收尾：确认应用侧前缀配置
--   Spring Boot：`ifmap.table-prefix=ifmap_`（默认值，不配也对）
--   纯 Java：`TableNameResolver` 使用默认前缀
-- ---------------------------------------------------------------------------
