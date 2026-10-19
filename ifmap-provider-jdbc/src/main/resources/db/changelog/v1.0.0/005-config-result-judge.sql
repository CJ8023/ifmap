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
-- 配置表判定列换血：result_flag + success_value → result_judge + judge_name
--
-- 为什么不是改 001：001 已在存量库执行过，改它会让 Liquibase checksum 校验失败
-- （部署方被迫 clear-checksums，那等于把校验关掉）。所以新增本 changeset，
-- 「新库 = 001..006 顺序执行」与「存量库 = 只执行 005/006」得到同一份结构。
--
-- 【可裁剪段】下面两条 DROP COLUMN 意味着本脚本**不适用于**
-- 「复用存量表且存量模块仍在跑」的场景（ifmap.table-prefix=bankint_ 的共存期路径 A）：
-- 那种场景请注释掉这两条 DROP —— 旧列留着不影响 ifmap（它只读 result_judge）,
-- 但会让两套模块都能读写同一张表。
--
-- 【不自动回填】历史行的 result_flag/success_value 给不出**正确**的 result_judge 规格
-- （{flag 路径, 成功值} 与 {条件组} 不是一一映射：多值 / 大小写 / 兜底语义都会走样），
-- 而任何占位值（例如 {"op":"always"}）都等于**静默放行所有失败** —— 比报错危险得多。
-- 所以本脚本不写任何默认值，由宿主显式处置：见 docs/10-迁移指南.md。
--
-- 【非空表】严格模式（MySQL 5.7+ STRICT_TRANS_TABLES）下，给非空表加
-- `text NOT NULL` 且无默认值的列会报 1364/1138。处置顺序（同样不补占位值）：
--   1) 先按可空列加：ALTER TABLE `${tablePrefix}config` ADD COLUMN `result_judge` text;
--   2) 逐行补出真实规格（或按接口号批量生成）；
--   3) 再收紧：ALTER TABLE `${tablePrefix}config` MODIFY COLUMN `result_judge` text NOT NULL;
-- =====================================================================================

--changeset caijun:005-config-result-judge
ALTER TABLE `${tablePrefix}config` ADD COLUMN `result_judge` text NOT NULL COMMENT '结果判定规格（JSON；必填，无默认值 ⇒ 未配置即判定失败，不静默放行）';

--changeset caijun:005-config-judge-name
ALTER TABLE `${tablePrefix}config` ADD COLUMN `judge_name` varchar(64) NOT NULL DEFAULT '' COMMENT '判定器标识（空=默认 ruleSpec；宿主可注册自定义 ResultJudge）';

--changeset caijun:005-config-drop-result-flag
ALTER TABLE `${tablePrefix}config` DROP COLUMN `result_flag`;

--changeset caijun:005-config-drop-success-value
ALTER TABLE `${tablePrefix}config` DROP COLUMN `success_value`;
