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
-- 逻辑分支判定列换血（3/3）：删掉旧的 logic_branch_flag / logic_branch_value
--
-- 【可裁剪段】这两条 DROP 意味着本脚本**不适用于**
-- 「复用存量表且存量模块仍在跑」的场景（ifmap.table-prefix=bankint_ 的共存期路径 A）：
-- 那种场景请注释掉本文件在 db.changelog-master.yaml 里的 include —— 旧列留着不影响 ifmap
-- （它只读写 branch_judge），但会让两套模块都能读写同一张表。
--
-- 【为什么删】留着两列等于「同一件事两套做法」：新写入只填 branch_judge，旧列永远是陈旧的默认值，
-- 谁读旧列谁就拿到错的结论。判据是"删掉会不会丢信息"——不会：009 已经把等价信息搬进 branch_judge。
--
-- 【索引】唯一键 uk_${tablePrefix}logic_branch 与查询索引 idx_${tablePrefix}logic_branch_query
-- 都不含这两列，删列不需要重建索引。
-- =====================================================================================

--changeset caijun:010-logic-branch-drop-flag
ALTER TABLE `${tablePrefix}logic_branch_config` DROP COLUMN `logic_branch_flag`;

--changeset caijun:010-logic-branch-drop-value
ALTER TABLE `${tablePrefix}logic_branch_config` DROP COLUMN `logic_branch_value`;
