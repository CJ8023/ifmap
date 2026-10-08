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
-- 步骤 0/6：迁移前体检（只读，不修改任何数据）
-- 执行顺序与每一步的算法/锁语义见同目录 README.md。
--
-- 表前缀：本套脚本以 `bankint_` 为例（ifmap 前身工程的存量前缀）。
--         其它存量部署请先替换前缀：sed -i 's/bankint_/yourprefix_/g' *.sql
--
-- ⚠️ 存量的建表 DDL 不在 ifmap 仓库里（配置表由存量服务创建），
--    因此本脚本一律以 information_schema 里的**实际**定义为准做体检，
--    而不是照抄设计文档里的假设。第 3 步的输出请贴到变更评审单，
--    以它为基线去核对 03-modify-and-index.sql 里的列宽/类型是否与现状一致。
-- =============================================================================

SET SESSION time_zone = '+08:00';   -- 时间列按会话时区解释；后续每一步都要保持一致
SET SESSION lock_wait_timeout = 10; -- 后面几步的 DDL 等锁上限（默认 1 年，遇长事务会一直挂着）

-- ---------------------------------------------------------------------------
-- 1) 环境与关键参数
-- ---------------------------------------------------------------------------
SELECT VERSION() AS mysql_version, DATABASE() AS db_name,
       @@time_zone AS session_tz, @@sql_mode AS sql_mode,
       @@lock_wait_timeout AS lock_wait_timeout;

SHOW VARIABLES LIKE 'innodb_large_prefix';            -- 仅 5.7 有该变量；8.0 已移除（返回空行属正常）
SHOW VARIABLES LIKE 'innodb_default_row_format';      -- DYNAMIC = 索引前缀上限 3072 字节
SHOW VARIABLES LIKE 'innodb_online_alter_log_max_size';

-- ---------------------------------------------------------------------------
-- 2) 表是否存在、体量、行格式（决定后面走"低峰 COPY"还是"gh-ost"）
-- ---------------------------------------------------------------------------
SELECT TABLE_NAME, ENGINE, ROW_FORMAT, TABLE_ROWS, AVG_ROW_LENGTH,
       ROUND(DATA_LENGTH / 1024 / 1024, 1) AS data_mb,
       ROUND(INDEX_LENGTH / 1024 / 1024, 1) AS index_mb
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('bankint_config', 'bankint_logic_branch_config', 'bankint_execution_log')
 ORDER BY TABLE_NAME;
-- 期望 3 行。0 行 = 库名/前缀不对，先确认再继续。
-- ROW_FORMAT=COMPACT 的老表请留意第 4 条的 1071 风险（03 脚本里已带 ROW_FORMAT=DYNAMIC 兜底）。

-- ---------------------------------------------------------------------------
-- 3) 列定义全量清单（迁移基线，务必留档）
-- ---------------------------------------------------------------------------
SELECT TABLE_NAME, ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE,
       COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('bankint_config', 'bankint_logic_branch_config', 'bankint_execution_log')
 ORDER BY TABLE_NAME, ORDINAL_POSITION;
-- 与目标 DDL 的列数对比：config 25 → 28（+3）、logic_branch 15 → 17（+2）、execution_log 16 → 17（+1）
-- ⚠️ 「25 → 28」这个对比是**按 kit 的参考环境**写的：实测有的存量环境 config 是 22 列
--    （没有 interface_code / project_code / interface_name，却多一个 interface_url，见 README §9.4），
--    列数对不上属正常 —— 以第 3.1 步的「列齐备性」清单为准。

-- ---------------------------------------------------------------------------
-- 3.1) ★ 列齐备性预检：把 03 里那句 「Unknown column 'xxx'（1054）」提前成一张待办清单
--      （实测价值：kit 按参考环境写死了 `financing_mode` / `interface_code` 等列，
--        而真实存量环境未必有 —— 本步一跑就知道差哪几列，不必等到 03 才 1054。）
--
--      处置规则：
--        · 缺的是「目标 DDL 有、存量没有」的列（如 `financing_mode`）→ 它是**新增列**，
--          01-add-columns.sql 里已备好对应的 ADD COLUMN（注释里标 ★）；
--        · 缺的是「kit 引用、但目标 DDL 也没有」的列 → 把 03 里对应的 MODIFY 行删掉即可。
--      本步输出的是**待办清单，不是门禁**（各环境存量结构不同，README §9.4 有实测对照）。
-- ---------------------------------------------------------------------------
SELECT r.tbl AS table_name, r.col AS missing_column
  FROM ( SELECT 'bankint_config' AS tbl, 'key_id' AS col
         UNION ALL SELECT 'bankint_config', 'tenant_id'
         UNION ALL SELECT 'bankint_config', 'interface_no'
         UNION ALL SELECT 'bankint_config', 'interface_code'
         UNION ALL SELECT 'bankint_config', 'project_code'
         UNION ALL SELECT 'bankint_config', 'interface_name'
         UNION ALL SELECT 'bankint_config', 'busi_node'
         UNION ALL SELECT 'bankint_config', 'bank_code'
         UNION ALL SELECT 'bankint_config', 'bank_name'
         UNION ALL SELECT 'bankint_config', 'financing_mode'
         UNION ALL SELECT 'bankint_config', 'front_interface_no'
         UNION ALL SELECT 'bankint_config', 'interface_order'
         UNION ALL SELECT 'bankint_config', 'request_param_template'
         UNION ALL SELECT 'bankint_config', 'response_param_template'
         UNION ALL SELECT 'bankint_config', 'result_flag'
         UNION ALL SELECT 'bankint_config', 'success_value'
         UNION ALL SELECT 'bankint_config', 'strategy_name'
         UNION ALL SELECT 'bankint_config', 'remark'
         UNION ALL SELECT 'bankint_config', 'del_status'
         UNION ALL SELECT 'bankint_config', 'add_user_id'
         UNION ALL SELECT 'bankint_config', 'add_time'
         UNION ALL SELECT 'bankint_config', 'modify_user_id'
         UNION ALL SELECT 'bankint_config', 'modify_time'
         UNION ALL SELECT 'bankint_logic_branch_config', 'key_id'
         UNION ALL SELECT 'bankint_logic_branch_config', 'tenant_id'
         UNION ALL SELECT 'bankint_logic_branch_config', 'interface_no'
         UNION ALL SELECT 'bankint_logic_branch_config', 'method_flag'
         UNION ALL SELECT 'bankint_logic_branch_config', 'logic_branch_name'
         UNION ALL SELECT 'bankint_logic_branch_config', 'logic_branch_flag'
         UNION ALL SELECT 'bankint_logic_branch_config', 'logic_branch_value'
         UNION ALL SELECT 'bankint_logic_branch_config', 'remark'
         UNION ALL SELECT 'bankint_logic_branch_config', 'del_status'
         UNION ALL SELECT 'bankint_logic_branch_config', 'add_user_id'
         UNION ALL SELECT 'bankint_logic_branch_config', 'add_time'
         UNION ALL SELECT 'bankint_logic_branch_config', 'modify_user_id'
         UNION ALL SELECT 'bankint_logic_branch_config', 'modify_time'
         UNION ALL SELECT 'bankint_execution_log', 'key_id'
         UNION ALL SELECT 'bankint_execution_log', 'tenant_id'
         UNION ALL SELECT 'bankint_execution_log', 'interface_no'
         UNION ALL SELECT 'bankint_execution_log', 'biz_id'
         UNION ALL SELECT 'bankint_execution_log', 'request_param'
         UNION ALL SELECT 'bankint_execution_log', 'response_param'
         UNION ALL SELECT 'bankint_execution_log', 'execution_result'
         UNION ALL SELECT 'bankint_execution_log', 'remark'
         UNION ALL SELECT 'bankint_execution_log', 'del_status'
         UNION ALL SELECT 'bankint_execution_log', 'add_user_id'
         UNION ALL SELECT 'bankint_execution_log', 'add_time'
         UNION ALL SELECT 'bankint_execution_log', 'modify_user_id'
         UNION ALL SELECT 'bankint_execution_log', 'modify_time'
       ) r
  LEFT JOIN information_schema.COLUMNS c
    ON c.TABLE_SCHEMA = DATABASE()
   AND c.TABLE_NAME   = r.tbl
   AND c.COLUMN_NAME  = r.col
 WHERE c.COLUMN_NAME IS NULL
 ORDER BY r.tbl, r.col;
-- 本清单只列 kit 在 01/02/03 里**引用到**的列。目标 DDL 还有 `status` / `version` / `deleted_seq` /
-- `logic_branch_order`（这四列由 01 新增，不在此列）、`error_msg`（由 01 新增）
-- 以及 `execution_log.execution_time`（kit 不改它，但目标 DDL 必须有；缺了要自行 ADD COLUMN，05-verify 会核对）。

-- ---------------------------------------------------------------------------
-- 4) 现有索引（确认没有同名 uk_ifmap_* / idx_ifmap_* 冲突）
-- ---------------------------------------------------------------------------
SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('bankint_config', 'bankint_logic_branch_config', 'bankint_execution_log')
 ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;

-- ---------------------------------------------------------------------------
-- 5) 基线快照（迁移后用 05-verify.sql 复核，两处必须完全一致）
--    配置表小：直接 COUNT + SUM 精确核对；
--    日志表可能很大：用 COUNT(主键) + MIN/MAX（走索引），别在高峰期做全表 SUM。
-- ---------------------------------------------------------------------------
SELECT 'config' AS tbl, COUNT(*) AS rows_cnt, COALESCE(SUM(key_id), 0) AS sum_key_id,
       COALESCE(MIN(key_id), 0) AS min_key_id, COALESCE(MAX(key_id), 0) AS max_key_id
  FROM bankint_config
UNION ALL
SELECT 'logic_branch', COUNT(*), COALESCE(SUM(key_id), 0), COALESCE(MIN(key_id), 0), COALESCE(MAX(key_id), 0)
  FROM bankint_logic_branch_config;

SELECT 'execution_log' AS tbl, COUNT(*) AS rows_cnt,
       COALESCE(MIN(key_id), 0) AS min_key_id, COALESCE(MAX(key_id), 0) AS max_key_id
  FROM bankint_execution_log;

-- ---------------------------------------------------------------------------
-- 6) ★ P0 唯一键冲突预检（不通过就不要往下走，否则 03 加唯一键会直接 1062 失败）
--
--    目标唯一键：
--      config       (tenant_id, interface_no, busi_node, interface_order, deleted_seq)
--      logic_branch (tenant_id, interface_no, method_flag, logic_branch_name, deleted_seq)
--
--    ⚠️ interface_order 会从 char(2) 变成 smallint：'02' 与 '2' 迁移后**是同一个值**，
--       所以这里必须按 CAST(... AS UNSIGNED) 分组，按字符串分组会漏掉这处冲突。
--    ⚠️ deleted_seq 回填规则是「未删除=0，已删除=key_id」，所以只有"同一组里有 ≥2 条
--       del_status=0"才会真的撞唯一键；已删除行各自唯一，不用处理。
-- ---------------------------------------------------------------------------

-- 6.1 config：未删除行冲突（**期望 0 行**）
SELECT tenant_id, interface_no, busi_node, CAST(interface_order AS UNSIGNED) AS order_num,
       COUNT(*) AS dup_cnt,
       GROUP_CONCAT(key_id ORDER BY key_id) AS key_ids,
       GROUP_CONCAT(interface_order ORDER BY interface_order) AS raw_order_values
  FROM bankint_config
 WHERE del_status = 0
 GROUP BY tenant_id, interface_no, busi_node, CAST(interface_order AS UNSIGNED)
HAVING COUNT(*) > 1;

-- 6.2 config：已删除行（理论上不挡唯一键，用于发现"删了又建"的历史；可仅记录）
SELECT tenant_id, interface_no, busi_node, CAST(interface_order AS UNSIGNED) AS order_num,
       COUNT(*) AS dup_cnt, GROUP_CONCAT(key_id ORDER BY key_id) AS key_ids
  FROM bankint_config
 WHERE del_status = 1
 GROUP BY tenant_id, interface_no, busi_node, CAST(interface_order AS UNSIGNED)
HAVING COUNT(*) > 1;

-- 6.3 logic_branch：未删除行冲突（**期望 0 行**）
SELECT tenant_id, interface_no, COALESCE(method_flag, '<NULL>') AS method_flag,
       logic_branch_name, COUNT(*) AS dup_cnt,
       GROUP_CONCAT(key_id ORDER BY key_id) AS key_ids
  FROM bankint_logic_branch_config
 WHERE del_status = 0
 GROUP BY tenant_id, interface_no, method_flag, logic_branch_name
HAVING COUNT(*) > 1;
-- 注意 method_flag 为 NULL 的组：MySQL 唯一键不约束 NULL，理论上可放行；
-- 但要确认这些接口的兜底分支不超过 1 条（见第 10.3 条）。

-- 6.4 处理建议（由人工决定，脚本不自动改数据）：
--     · 同组多条 → 改 interface_order（例如 10 / 20 / 30）分批拉开；
--     · 确认多余的配置无效 → del_status=1（同时把 deleted_seq 置为 key_id，见 02-backfill.sql）；
--     · 处理完重跑 6.1 / 6.3，直到返回 0 行。

-- ---------------------------------------------------------------------------
-- 7) interface_order 能否安全转 smallint（P0）
-- ---------------------------------------------------------------------------
-- 7.1 格式体检：必须是纯数字且长度 ≤ 4（smallint 上限 32767）→ **期望 0 行**
SELECT key_id, CONCAT('[', interface_order, ']') AS raw_value,
       CHAR_LENGTH(TRIM(interface_order)) AS trimmed_len
  FROM bankint_config
 WHERE TRIM(interface_order) NOT REGEXP '^[0-9]+$'
    OR CHAR_LENGTH(TRIM(interface_order)) > 4
 ORDER BY key_id;
-- 非数字 / 空值 → 按 0 处理（执行：UPDATE bankint_config SET interface_order = 0 WHERE ...）；
-- 长度 > 4 → 人工确认（顺序号不该有 5 位）。

-- 7.2 值分布：人工过一眼有没有 '2' 与 '02' 并存（迁移后会合并成同一个值）
SELECT CONCAT('[', interface_order, ']') AS raw_value, COUNT(*) AS cnt
  FROM bankint_config GROUP BY interface_order ORDER BY interface_order;

-- ---------------------------------------------------------------------------
-- 8) 列长度与 NULL（P0：超长 → 1406；目标 NOT NULL 但有 NULL → 1048/1138）
-- ---------------------------------------------------------------------------
-- 8.1 长度体检（每张表只扫一次；列名后缀 = 该列的迁移目标上限，逐个比对）
-- ★ 列清单是**动态生成**的：只体检"当前库里真实存在"的列 —— 存量变体缺列时这条不会 1054
--   （缺了哪几列看第 3.1 步的清单）。GROUP_CONCAT 故意不写 DISTINCT：MySQL 5.7 下
--   DISTINCT + ORDER BY 非选列表达式会报 3029。
-- config（小表）
SET @s8a1 := (SELECT CONCAT('SELECT ',
                      GROUP_CONCAT(CONCAT('MAX(CHAR_LENGTH(`', COLUMN_NAME, '`)) AS `', COLUMN_NAME, '`')
                                   ORDER BY COLUMN_NAME SEPARATOR ','),
                      ' FROM `bankint_config`')
                FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bankint_config'
                 AND COLUMN_NAME IN ('interface_name','bank_name','bank_code','interface_code','project_code',
                                     'front_interface_no','financing_mode','busi_node','strategy_name',
                                     'result_flag','success_value','remark'));
SET @s8a1 := IFNULL(@s8a1, 'SELECT 1 AS config_variant_columns_absent');
PREPARE q8a1 FROM @s8a1; EXECUTE q8a1; DEALLOCATE PREPARE q8a1;
-- logic_branch（小表）
SET @s8a2 := (SELECT CONCAT('SELECT ',
                      GROUP_CONCAT(CONCAT('MAX(CHAR_LENGTH(`', COLUMN_NAME, '`)) AS `', COLUMN_NAME, '`')
                                   ORDER BY COLUMN_NAME SEPARATOR ','),
                      ' FROM `bankint_logic_branch_config`')
                FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bankint_logic_branch_config'
                 AND COLUMN_NAME IN ('logic_branch_name','logic_branch_flag','logic_branch_value',
                                     'method_flag','remark'));
SET @s8a2 := IFNULL(@s8a2, 'SELECT 1 AS logic_branch_variant_columns_absent');
PREPARE q8a2 FROM @s8a2; EXECUTE q8a2; DEALLOCATE PREPARE q8a2;
-- execution_log（大表：低峰执行）
-- `interface_no` / `biz_id` 也在这里量：目标都是 varchar(64)，存量实测是 varchar(50) / bigint，
-- 超长会在 03 的 MODIFY 时报 1406（见 README §9.4）。
SET @s8a3 := (SELECT CONCAT('SELECT ',
                      GROUP_CONCAT(CONCAT('MAX(CHAR_LENGTH(`', COLUMN_NAME, '`)) AS `', COLUMN_NAME, '`')
                                   ORDER BY COLUMN_NAME SEPARATOR ','),
                      ' FROM `bankint_execution_log`')
                FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bankint_execution_log'
                 AND COLUMN_NAME IN ('interface_no','biz_id','response_param','execution_result','remark'));
SET @s8a3 := IFNULL(@s8a3, 'SELECT 1 AS execution_log_variant_columns_absent');
PREPARE q8a3 FROM @s8a3; EXECUTE q8a3; DEALLOCATE PREPARE q8a3;
-- 超过上限时：先清理/截断数据，或放宽目标列宽（需同步改 db/changelog/v1.0.0/*.sql 与目标一致性检查）。

-- 8.2 NULL 体检：目标为 NOT NULL 的列（**期望全 0**；每张表只扫一次）
-- ★ 同样动态生成列清单：存量没有的列不参与（缺列看第 3.1 步）。
-- config
SET @s8b1 := (SELECT CONCAT('SELECT ',
                      GROUP_CONCAT(CONCAT('SUM(`', COLUMN_NAME, '` IS NULL) AS `', COLUMN_NAME, '`')
                                   ORDER BY COLUMN_NAME SEPARATOR ','),
                      ' FROM `bankint_config`')
                FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bankint_config'
                 AND COLUMN_NAME IN ('interface_name','interface_code','busi_node','bank_code','interface_order',
                                     'result_flag','success_value','strategy_name','remark','add_user_id',
                                     'modify_user_id','add_request_id','modify_request_id','add_time','modify_time'));
SET @s8b1 := IFNULL(@s8b1, 'SELECT 1 AS config_variant_columns_absent');
PREPARE q8b1 FROM @s8b1; EXECUTE q8b1; DEALLOCATE PREPARE q8b1;
-- logic_branch
SET @s8b2 := (SELECT CONCAT('SELECT ',
                      GROUP_CONCAT(CONCAT('SUM(`', COLUMN_NAME, '` IS NULL) AS `', COLUMN_NAME, '`')
                                   ORDER BY COLUMN_NAME SEPARATOR ','),
                      ' FROM `bankint_logic_branch_config`')
                FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bankint_logic_branch_config'
                 AND COLUMN_NAME IN ('interface_no','logic_branch_name','del_status','add_time','modify_time'));
SET @s8b2 := IFNULL(@s8b2, 'SELECT 1 AS logic_branch_variant_columns_absent');
PREPARE q8b2 FROM @s8b2; EXECUTE q8b2; DEALLOCATE PREPARE q8b2;
-- execution_log
SET @s8b3 := (SELECT CONCAT('SELECT ',
                      GROUP_CONCAT(CONCAT('SUM(`', COLUMN_NAME, '` IS NULL) AS `', COLUMN_NAME, '`')
                                   ORDER BY COLUMN_NAME SEPARATOR ','),
                      ' FROM `bankint_execution_log`')
                FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bankint_execution_log'
                 AND COLUMN_NAME IN ('interface_no','biz_id','execution_time','remark','add_time','modify_time'));
SET @s8b3 := IFNULL(@s8b3, 'SELECT 1 AS execution_log_variant_columns_absent');
PREPARE q8b3 FROM @s8b3; EXECUTE q8b3; DEALLOCATE PREPARE q8b3;
-- 处理：按业务语义补默认值（字符串 → ''、时间 → '1970-01-01 00:00:00'、数字 → 0）。
-- 不用处理的列：logic_branch_flag / logic_branch_value —— 它们为 NULL 恰恰表达"兜底分支"，
-- 03 的 MODIFY（NOT NULL DEFAULT ''）会把 NULL 归一成空串，语义不变（引擎把空串与 NULL 同等对待），
-- 详见第 10.3 条。

-- ---------------------------------------------------------------------------
-- 10) 数据形态体检（不阻塞迁移，但强烈建议看一眼）
-- ---------------------------------------------------------------------------
-- 10.1 busi_node 尾随空格：char(10) → varchar(32) 转换会**自动去掉尾随空格**，
--      值"看起来变了"；比较语义不变（目标排序规则 utf8mb4_general_ci 是 PAD SPACE）。
SELECT DISTINCT CONCAT('[', busi_node, ']') AS padded_value
  FROM bankint_config WHERE busi_node <> TRIM(busi_node);

-- 10.2 模板必须能通过 ifmap 的契约校验（非法 JSON 会被 admin 端拒绝保存）
SELECT key_id, interface_no, 'request_param_template' AS col FROM bankint_config
 WHERE request_param_template IS NOT NULL AND JSON_VALID(request_param_template) = 0
UNION ALL
SELECT key_id, interface_no, 'response_param_template' FROM bankint_config
 WHERE response_param_template IS NOT NULL AND JSON_VALID(response_param_template) = 0;
-- 期望 0 行。有行 = 存量脏数据，迁移后在 admin 端保存同一接口会被拒（需人工修 JSON）。

-- 10.3 ★ 兜底分支：有几个、分别属于哪个接口（**每个接口期望 ≤ 1 条**）
SELECT tenant_id, interface_no, COUNT(*) AS fallback_cnt,
       GROUP_CONCAT(key_id ORDER BY key_id) AS key_ids
  FROM bankint_logic_branch_config
 WHERE del_status = 0
   AND (logic_branch_flag IS NULL OR logic_branch_flag = '')
   AND (logic_branch_value IS NULL OR logic_branch_value = '')
 GROUP BY tenant_id, interface_no
 ORDER BY fallback_cnt DESC, interface_no;
-- 语义对齐（重要）：ifmap 引擎与存量一致 ——
--   · `logic_branch_flag` 为空 = **兜底分支**（不参与常规匹配，仅当所有常规分支都未命中时生效）；
--   · `method_flag` 为空 = **该分支不执行任何动作**（与"兜底"是两件事）。
-- 迁移后兜底分支的优先级由 logic_branch_order 决定（02-backfill.sql 会给它一个大值）。

-- 10.4 execution_result 取值分布（char(2) → varchar(16)，值本身不变）
SELECT execution_result, COUNT(*) AS cnt
  FROM bankint_execution_log GROUP BY execution_result ORDER BY cnt DESC;

-- 10.5 日志表 request_param 的 JSON 合法性（**信息性统计，不是门禁**）
-- 目标 schema 里 request_param 是 mediumtext（与 response_param 对齐，见 03-modify-and-index.sql）：
-- 审计日志要求"一定写得进去 + 内容忠实"，所以历史上有非法 JSON（多为超长截断的报文）也不影响迁移。
SELECT COUNT(*) AS non_json_cnt
  FROM bankint_execution_log
 WHERE request_param IS NOT NULL AND JSON_VALID(request_param) = 0;

-- ---------------------------------------------------------------------------
-- 体检结论：上面所有标注「期望 0」/「期望 ≤ 1」的查询都通过后，才可以执行
-- 01-add-columns.sql。任何一条不通过都必须先处理数据，不要"先跑起来再说"。
-- ---------------------------------------------------------------------------
