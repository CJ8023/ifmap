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
-- 步骤 5/6：迁移后核对（只读）
-- 执行时机：① 03 之后先跑第 1~4 节（结构核对，此时表名可能还是 bankint_*）；
--           ② 04 改名之后再跑一遍全量（把下面所有 `bankint_` 视作 `ifmap_`，
--              或直接执行本文件的 ifmap_ 版本 —— 改名后统一用 ifmap_ 前缀即可）。
--
-- 核对原则：**不接受"看起来差不多"**。只有"与 00-precheck.sql 的留档完全一致"才算通过。
-- =============================================================================

SET SESSION time_zone = '+08:00';

-- ---------------------------------------------------------------------------
-- 1) 列数与关键列定义（期望列数：config 28 / logic_branch 17 / execution_log 17）
-- ---------------------------------------------------------------------------
SELECT TABLE_NAME, COUNT(*) AS col_cnt
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('ifmap_config', 'ifmap_logic_branch_config', 'ifmap_execution_log')
 GROUP BY TABLE_NAME ORDER BY TABLE_NAME;
-- 期望 28 / 17 / 17。少列说明 01/03 漏了；多列说明目标 DDL 改了而 kit 没同步。

SELECT TABLE_NAME, ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('ifmap_config', 'ifmap_logic_branch_config', 'ifmap_execution_log')
   AND COLUMN_NAME IN ('interface_order', 'busi_node', 'add_user_id', 'modify_user_id',
                       'add_time', 'modify_time', 'status', 'version', 'deleted_seq',
                       'logic_branch_order', 'logic_branch_flag', 'logic_branch_value',
                       'method_flag', 'request_param', 'response_param', 'execution_result', 'error_msg')
 ORDER BY TABLE_NAME, ORDINAL_POSITION;
-- 期望：interface_order smallint；busi_node varchar(32)；add_user_id varchar(64)；
--       add_time datetime(3)；status tinyint(1)；deleted_seq bigint；logic_branch_order smallint；
--       request_param json；response_param mediumtext；execution_result varchar(16)；error_msg varchar(1024)。
-- ⚠️ 已知可接受差异：`key_id` / `tenant_id` 可能仍是 `bigint(19)`（显示宽度）。
--     显示宽度不影响功能（8.0.19+ 已弃用），本项目未强制对齐；
--     若要完全一致，追加 `MODIFY COLUMN key_id bigint NOT NULL`（`execution_log.key_id`
--     若带 AUTO_INCREMENT 必须保留该属性），COPY 一次即可。

-- ---------------------------------------------------------------------------
-- 2) 索引（期望 9 条 ifmap_* 索引）
-- ---------------------------------------------------------------------------
SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('ifmap_config', 'ifmap_logic_branch_config', 'ifmap_execution_log')
   AND INDEX_NAME LIKE '%ifmap%'
 GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE ORDER BY TABLE_NAME, INDEX_NAME;
-- 期望：config 4（uk_ifmap_config_biz / idx_ifmap_config_list / _front / _code）
--       logic_branch 2（uk_ifmap_logic_branch / idx_ifmap_logic_branch_query）
--       execution_log 3（idx_ifmap_log_biz / _iface / _time）

-- ---------------------------------------------------------------------------
-- 3) 回填完整性（**期望全 0**）
-- ---------------------------------------------------------------------------
SELECT 'config 软删行未回填' AS chk, COUNT(*) AS bad FROM ifmap_config
 WHERE del_status = 1 AND deleted_seq = 0
UNION ALL
SELECT 'branch 软删行未回填', COUNT(*) FROM ifmap_logic_branch_config
 WHERE del_status = 1 AND deleted_seq = 0
UNION ALL
SELECT 'branch 兜底分支超过 1 条', COUNT(*) FROM (
         SELECT tenant_id, interface_no FROM ifmap_logic_branch_config
          WHERE del_status = 0 AND (logic_branch_flag IS NULL OR logic_branch_flag = '')
          GROUP BY tenant_id, interface_no HAVING COUNT(*) > 1) t
UNION ALL
SELECT 'config status 异常', COUNT(*) FROM ifmap_config WHERE status IS NULL OR status NOT IN (0, 1);

-- ---------------------------------------------------------------------------
-- 4) 唯一键效果自检（**期望 0 行**：同一组不允许出现 > 1 条未删除行）
-- ---------------------------------------------------------------------------
SELECT tenant_id, interface_no, busi_node, interface_order, COUNT(*) AS cnt
  FROM ifmap_config WHERE del_status = 0
 GROUP BY tenant_id, interface_no, busi_node, interface_order HAVING COUNT(*) > 1;

SELECT tenant_id, interface_no, COALESCE(method_flag, '<NULL>') AS method_flag,
       logic_branch_name, COUNT(*) AS cnt
  FROM ifmap_logic_branch_config WHERE del_status = 0
 GROUP BY tenant_id, interface_no, method_flag, logic_branch_name HAVING COUNT(*) > 1;

-- ---------------------------------------------------------------------------
-- 5) 行数与主键指纹（与 00-precheck 第 5 步的留档逐项对比，必须完全一致）
--    说明：不用 MD5(GROUP_CONCAT(...)) 之类的"内容校验和"——它会被
--    group_concat_max_len 静默截断，可能给出假绿。COUNT + SUM + MIN/MAX 反而更可靠。
-- ---------------------------------------------------------------------------
SELECT 'config' AS tbl, COUNT(*) AS rows_cnt, COALESCE(SUM(key_id), 0) AS sum_key_id,
       COALESCE(MIN(key_id), 0) AS min_key_id, COALESCE(MAX(key_id), 0) AS max_key_id
  FROM ifmap_config
UNION ALL
SELECT 'logic_branch', COUNT(*), COALESCE(SUM(key_id), 0), COALESCE(MIN(key_id), 0), COALESCE(MAX(key_id), 0)
  FROM ifmap_logic_branch_config;

SELECT 'execution_log' AS tbl, COUNT(*) AS rows_cnt,
       COALESCE(MIN(key_id), 0) AS min_key_id, COALESCE(MAX(key_id), 0) AS max_key_id
  FROM ifmap_execution_log;

-- ---------------------------------------------------------------------------
-- 6) 时间一致性抽样（与 00-precheck 第 9.3 步的 20 行**逐行**比对，必须一模一样）
-- ---------------------------------------------------------------------------
SELECT key_id, add_time, modify_time FROM ifmap_config ORDER BY key_id LIMIT 20;

-- ---------------------------------------------------------------------------
-- 7) 分支顺序人工复核（兜底分支必须在最后）
-- ---------------------------------------------------------------------------
SELECT tenant_id, interface_no, key_id, logic_branch_order,
       CASE WHEN logic_branch_flag IS NULL OR logic_branch_flag = '' THEN '兜底' ELSE '常规' END AS kind,
       logic_branch_flag, logic_branch_value, method_flag
  FROM ifmap_logic_branch_config
 WHERE del_status = 0
 ORDER BY tenant_id, interface_no, logic_branch_order, key_id;

-- ---------------------------------------------------------------------------
-- 8) 引擎查询冒烟（与 JdbcConfigRepository 的 SQL 语义一致：按顺序取首个命中）
--    把 <你的接口编号> / <你的租户ID> 换成真实值。
-- ---------------------------------------------------------------------------
SELECT key_id, interface_no, busi_node, interface_order, status, del_status
  FROM ifmap_config
 WHERE tenant_id = -1 AND del_status = 0
 ORDER BY interface_order ASC, key_id ASC LIMIT 5;

SELECT key_id, interface_no, method_flag, logic_branch_flag, logic_branch_value, logic_branch_order
  FROM ifmap_logic_branch_config
 WHERE tenant_id = -1 AND interface_no = '<你的接口编号>' AND del_status = 0
 ORDER BY logic_branch_order ASC, key_id ASC;

-- ---------------------------------------------------------------------------
-- 9) 历史表（ifmap 新增，存量没有）：由 ifmap 启动时建（`ifmap.ddl.auto=true`）或手工执行
--    db/changelog/v1.0.0/004-create-config-history.sql
-- ---------------------------------------------------------------------------
SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ifmap_config_history';
-- 期望 1 行（11 列）。没有这行不影响配置读写，但 admin 端的历史查询会不可用。

-- ---------------------------------------------------------------------------
-- 全部通过后：启动 ifmap 应用 → 观察执行日志（成功/失败率、耗时）→ 观察 1~2 个业务日
-- → 确认无异常后再清理/归档存量备份。
-- ---------------------------------------------------------------------------
