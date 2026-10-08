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
-- 步骤 3/6：调整列名 + 列类型/长度/可空 + 建唯一键与索引
-- 前置：01、02 已完成；02 的"人工复核"（分支顺序）已确认。
--
-- ★★ 算法与锁（MySQL 5.7 官方 "Online DDL Operations" 表格，本脚本的核心依据）：
--    · 改变列数据类型 = In Place 否 / 重建表 是 / 允许并发 DML **否**
--      → 只能 `ALGORITHM=COPY`，且 COPY 下 **`LOCK=NONE` 不被支持**：
--        官方对 LOCK 的说明是 "If supported, permit concurrent reads and writes.
--        Otherwise, an error occurs."，非默认值还会 "halt the operation if the
--        requested degree of locking is not available" →
--        写 LOCK=NONE 会直接报错（错误文本：LOCK=NONE is not supported. ... Try LOCK=SHARED）。
--        所以类型变更一律 `ALGORITHM=COPY, LOCK=SHARED`（允许读、阻塞写）。
--    · 加列 / 加索引 / 仅把列改成可空 = In Place 是 / 允许并发 DML 是
--      → 这类操作用 `ALGORITHM=INPLACE, LOCK=NONE`（见下面 B-3 段）。
--
-- ★ 为什么配置表把"改类型"和"加唯一键/索引"合并成一条 ALTER：
--   COPY 本来就要整表重建一次，顺手把索引建出来总代价最小（5.7 铁律：同表变更合并成一条）。
--   COPY 失败时原表不变（新表未提交），所以这条语句是"要么全成、要么没动"的安全点。
--
-- ★ 为什么带 ROW_FORMAT=DYNAMIC：逻辑分支唯一键
--   (tenant_id 64B, interface_no 64B, method_flag 64B, logic_branch_name 128B → utf8mb4 下约 1280B)
--   超过旧行格式 COMPACT 的 767 字节前缀上限（错误 1071 Specified key was too long）；
--   DYNAMIC 下上限 3072 字节（需 innodb_large_prefix=ON，5.7 默认开启）。
--
-- ★ 执行窗口：A 段（配置表）秒级~分钟级，随时可执行；B 段看日志表体量，见 README 第 5 节。
-- =============================================================================

SET SESSION lock_wait_timeout = 10;
SET SESSION time_zone = '+08:00';

-- ===========================================================================
-- A. 接口配置表（数据量小：低峰直接 COPY）
-- ===========================================================================
ALTER TABLE `bankint_config`
  -- 存量主键/租户列的 bigint(19) 只是"显示宽度"，与目标 DDL 的 bigint(20) 不是两种类型；
  -- 写在这里是为了让迁移后的结构与目标**逐列一致**（否则 05-verify 按列名比对时会一直报差异）。
  -- 同一条 COPY 语句里顺带改掉，不额外重建表。
  MODIFY COLUMN `key_id`                  bigint(20)   NOT NULL              COMMENT '主键（默认雪花ID）',
  MODIFY COLUMN `tenant_id`               bigint(20)   NOT NULL DEFAULT -1   COMMENT '租户ID（单租户固定 -1）',
  MODIFY COLUMN `interface_no`            varchar(64)  NOT NULL              COMMENT '接口编号（业务唯一键）',
  MODIFY COLUMN `interface_code`          varchar(64)  NOT NULL              COMMENT '接口编码（对接方接口编码）',
  MODIFY COLUMN `project_code`            varchar(64)           DEFAULT NULL COMMENT '项目编号',
  MODIFY COLUMN `interface_name`          varchar(128) NOT NULL              COMMENT '接口名称',
  MODIFY COLUMN `busi_node`               varchar(32)  NOT NULL              COMMENT '业务节点（取值由宿主机注册）',
  -- ↓ 2 处改名：bank_code/bank_name → partner_code/partner_name（标识符不再锁死在"银行"，
  --   同一列可承载银行/保理/信托/小贷/保险等各类对手方；名字变了、列位置与数据都不动）
  CHANGE COLUMN `bank_code`               `partner_code` varchar(32)  NOT NULL                     COMMENT '合作机构编码',
  CHANGE COLUMN `bank_name`               `partner_name` varchar(128) DEFAULT NULL                 COMMENT '合作机构名称',
  -- ★ 条件列：存量有它 → 归一到 varchar(32)；存量没有 → 由 01-add-columns.sql 的 1.1 先 ADD
  --   （实测两套真实存量都没有这一列，见 README §9.4；少这一列 ifmap 运行期读写配置必 1054）
  MODIFY COLUMN `financing_mode`          varchar(32)           DEFAULT NULL COMMENT '融资模式',
  MODIFY COLUMN `front_interface_no`      varchar(64)           DEFAULT NULL COMMENT '前置接口编号（空=无前置）',
  MODIFY COLUMN `interface_order`         smallint     NOT NULL DEFAULT 0    COMMENT '接口执行顺序，升序；同值按 key_id 兜底',
  MODIFY COLUMN `request_param_template`  text                               COMMENT '请求参数模板（DSL），可为空',
  MODIFY COLUMN `response_param_template` longtext                           COMMENT '响应参数模板（DSL），可为空',
  MODIFY COLUMN `result_flag`             varchar(512) NOT NULL DEFAULT ''   COMMENT '执行结果标志（JsonPath）',
  MODIFY COLUMN `success_value`           varchar(512) NOT NULL DEFAULT ''   COMMENT '成功判断值，多值以 , 或 ; 分隔（大小写不敏感）',
  MODIFY COLUMN `strategy_name`           varchar(128) NOT NULL DEFAULT ''   COMMENT '特殊处理策略标识（=Spring bean 名）',
  MODIFY COLUMN `remark`                  varchar(512) NOT NULL DEFAULT ''   COMMENT '备注',
  MODIFY COLUMN `add_user_id`             varchar(64)  NOT NULL DEFAULT ''   COMMENT '添加人',
  MODIFY COLUMN `add_time`                datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '添加时间',
  MODIFY COLUMN `modify_user_id`          varchar(64)  NOT NULL DEFAULT ''   COMMENT '更新人',
  MODIFY COLUMN `modify_time`             datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间（由写入方统一设置，不用 ON UPDATE）',
  ADD UNIQUE KEY `uk_ifmap_config_biz`   (`tenant_id`,`interface_no`,`busi_node`,`interface_order`,`deleted_seq`),
  ADD KEY `idx_ifmap_config_list`  (`tenant_id`,`del_status`,`busi_node`,`partner_code`),
  ADD KEY `idx_ifmap_config_front` (`tenant_id`,`del_status`,`front_interface_no`),
  ADD KEY `idx_ifmap_config_code`  (`tenant_id`,`del_status`,`interface_code`),
  ROW_FORMAT=DYNAMIC,
  COMMENT='资方接口配置',
  ALGORITHM=COPY, LOCK=SHARED;
-- 说明：
--   · `add_time` / `modify_time`：timestamp → datetime(3)。源列若带 `ON UPDATE CURRENT_TIMESTAMP`，
--     这里不写 ON UPDATE 就是**有意去掉**它（目标契约：更新时间由写入方显式维护）。
--   · `request_param_template` / `response_param_template`：去掉 NOT NULL（目标允许为空）。
--   · `add_user_id` / `modify_user_id`：bigint → varchar(64)（存的是用户名/工号，不是数值）。
--   · 改名：`bank_code`→`partner_code`、`bank_name`→`partner_name`。想回退执行
--     `ALTER TABLE ... CHANGE COLUMN \`partner_code\` \`bank_code\` varchar(32) NOT NULL, ... ALGORITHM=COPY, LOCK=SHARED`。
--   · 唯一键含 `deleted_seq`：未删除恒 0 → 「同租户 + 同接口 + 同节点 + 同顺序」只允许一条；
--     软删除时写入 key_id → 同一组可以保留多条历史删除行。

-- ===========================================================================
-- B. 逻辑分支表（数据量小：低峰直接 COPY）
-- ===========================================================================
ALTER TABLE `bankint_logic_branch_config`
  -- 存量主键/租户列的 bigint(19) 只是"显示宽度"，与目标 DDL 的 bigint(20) 不是两种类型；
  -- 写在这里是为了让迁移后的结构与目标**逐列一致**（否则 05-verify 按列名比对时会一直报差异）。
  -- 同一条 COPY 语句里顺带改掉，不额外重建表。
  MODIFY COLUMN `key_id`                  bigint(20)   NOT NULL              COMMENT '主键',
  MODIFY COLUMN `tenant_id`               bigint(20)   NOT NULL DEFAULT -1   COMMENT '租户ID',
  MODIFY COLUMN `interface_no`       varchar(64)  NOT NULL              COMMENT '接口编号',
  MODIFY COLUMN `method_flag`        varchar(64)           DEFAULT NULL COMMENT '动作标识(Action Key)：分支命中后执行的动作，由宿主机 ActionRegistry 注册；空=该分支不执行动作',
  MODIFY COLUMN `logic_branch_name`  varchar(128) NOT NULL              COMMENT '逻辑分支名称',
  MODIFY COLUMN `logic_branch_flag`  varchar(512) NOT NULL DEFAULT ''   COMMENT '逻辑分支标志（JsonPath 表达式，支持 $.a.b 与裸字段名）；空=兜底分支',
  MODIFY COLUMN `logic_branch_value` varchar(512) NOT NULL DEFAULT ''   COMMENT '逻辑分支判断值，多值以 | 分隔',
  MODIFY COLUMN `remark`             varchar(512) NOT NULL DEFAULT ''   COMMENT '备注',
  MODIFY COLUMN `add_user_id`        varchar(64)  NOT NULL DEFAULT ''   COMMENT '添加人',
  MODIFY COLUMN `add_time`           datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '添加时间',
  MODIFY COLUMN `modify_user_id`     varchar(64)  NOT NULL DEFAULT ''   COMMENT '更新人',
  MODIFY COLUMN `modify_time`        datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  ADD UNIQUE KEY `uk_ifmap_logic_branch` (`tenant_id`,`interface_no`,`method_flag`,`logic_branch_name`,`deleted_seq`),
  ADD KEY `idx_ifmap_logic_branch_query` (`tenant_id`,`interface_no`,`del_status`,`logic_branch_order`),
  ROW_FORMAT=DYNAMIC,
  COMMENT='资方接口逻辑分支配置',
  ALGORITHM=COPY, LOCK=SHARED;
-- 说明：
--   · `logic_branch_flag` / `logic_branch_value` 的 NULL 会被归一成 ''（DEFAULT ''），
--     语义不变：引擎把"空串"与"NULL"同等对待 —— 空 flag 就是**兜底分支**。
--   · 唯一键里 `method_flag` 可空 → MySQL 唯一键不约束 NULL，因此"同一个兜底动作名"
--     可以有多条；真正的兜底分支唯一性由"logic_branch_flag 空"表达，admin 端会拦住第 2 条。

-- ===========================================================================
-- C. 执行日志表（可能很大：先看体量再选路径）
-- ===========================================================================
-- C-1 体量（大表请低峰执行；也可直接用 00-precheck 第 2 步的 TABLE_ROWS 估算）
-- SELECT COUNT(*) AS log_rows FROM `bankint_execution_log`;
--   · < 50 万行   → 执行 C-2（COPY，写阻塞：秒级~分钟级）
--   · >= 50 万行  → **跳过 C-2**，改用 README 第 5 节的 gh-ost 命令做同样的 MODIFY，
--                    然后回来执行 C-3（在线加索引）
--
-- C-2 类型/长度/可空调整（一条 ALTER：COPY 一共只重建一次表）
--     前置：磁盘余量 >= 表大小（COPY 期间新旧两份表并存）；低峰执行。
ALTER TABLE `bankint_execution_log`
  -- 存量主键/租户列的 bigint(19) 只是"显示宽度"，与目标 DDL 的 bigint(20) 不是两种类型；
  -- 写在这里是为了让迁移后的结构与目标**逐列一致**（否则 05-verify 按列名比对时会一直报差异）。
  -- 同一条 COPY 语句里顺带改掉，不额外重建表。
  MODIFY COLUMN `key_id`                  bigint(20)   NOT NULL              COMMENT '主键',
  MODIFY COLUMN `tenant_id`               bigint(20)   NOT NULL DEFAULT -1   COMMENT '租户ID',
  -- ★ 这三列在真实存量里也不齐：`interface_no` 实测两套存量都是 varchar(50)（目标是 64）；
  --   `biz_id` 在 yfl_bill 变体里是 bigint（目标是 varchar(64)，ifmap 写日志时传的是字符串 →
  --   不改会在严格模式下 1366 丢日志行）；`execution_time` 目标要求 NOT NULL DEFAULT 0（耗时 ms）。
  --   本语句已经是 ALGORITHM=COPY，多这几条 MODIFY 不会多一次表重建。
  MODIFY COLUMN `interface_no`     varchar(64) NOT NULL              COMMENT '接口编号',
  MODIFY COLUMN `biz_id`           varchar(64) NOT NULL              COMMENT '业务ID',
  MODIFY COLUMN `execution_time`   bigint(20)  NOT NULL DEFAULT 0    COMMENT '执行耗时(ms)',
  MODIFY COLUMN `request_param`    mediumtext  DEFAULT NULL COMMENT '请求参数（已脱敏；超长按配置截断；文本列，不要求是合法 JSON）',
  MODIFY COLUMN `response_param`   mediumtext  DEFAULT NULL COMMENT '响应参数（已脱敏；超长按配置截断）',
  MODIFY COLUMN `execution_result` varchar(16) DEFAULT NULL COMMENT '执行结果：SUCCESS/FAIL/SKIP/TIMEOUT',
  MODIFY COLUMN `remark`           varchar(512) NOT NULL DEFAULT '' COMMENT '备注',
  MODIFY COLUMN `add_user_id`      varchar(64)  NOT NULL DEFAULT '' COMMENT '添加人',
  MODIFY COLUMN `add_time`         datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '执行时间',
  MODIFY COLUMN `modify_user_id`   varchar(64)  NOT NULL DEFAULT '' COMMENT '更新人',
  MODIFY COLUMN `modify_time`      datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  ROW_FORMAT=DYNAMIC,
  ALGORITHM=COPY, LOCK=SHARED;
-- 注意：
--   · `request_param` 统一改成 mediumtext（与 `response_param` 对齐）：审计日志要"一定写得进去 + 内容忠实"，
--     json 列会拒收超长截断后的文本（3140 → 整条日志丢行），还会把小数规范化（10.00 → 10）。
--     若现状已是 json 列，改类型同样是 COPY（见上方在线 DDL 说明）。
--     但为了与其余列共用一条语句（只重建一次），这里统一走 COPY。
--   · `response_param` longtext → mediumtext（4GB → 16MB）：超长会报 1406，
--     迁移前用 00-precheck 第 8.1 条核对最大值；若确实存在 > 16MB 的报文，
--     请保留 longtext 并同步修改 db/changelog/v1.0.0/003-create-execution-log.sql。
--   · 大表更稳妥的做法是把 C-2 整体交给 gh-ost/pt-osc（见 README 第 5 节）。

-- C-3 索引（纯 ADD KEY = In Place + 允许并发 DML，不重建表；大表也可放心用）
ALTER TABLE `bankint_execution_log`
  ADD KEY `idx_ifmap_log_biz`   (`tenant_id`,`biz_id`,`add_time`),
  ADD KEY `idx_ifmap_log_iface` (`tenant_id`,`interface_no`,`add_time`),
  ADD KEY `idx_ifmap_log_time`  (`add_time`),
  ALGORITHM=INPLACE, LOCK=NONE;

-- ===========================================================================
-- D. 自检（列类型 + 索引；详细核对见 05-verify.sql）
-- ===========================================================================
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND (   (TABLE_NAME = 'bankint_config'              AND COLUMN_NAME IN ('interface_order','add_user_id','add_time','deleted_seq','status'))
        OR (TABLE_NAME = 'bankint_logic_branch_config' AND COLUMN_NAME IN ('logic_branch_order','deleted_seq'))
        OR (TABLE_NAME = 'bankint_execution_log'       AND COLUMN_NAME IN ('request_param','response_param','execution_result','error_msg')))
 ORDER BY TABLE_NAME, COLUMN_NAME;

SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('bankint_config', 'bankint_logic_branch_config', 'bankint_execution_log')
   AND INDEX_NAME LIKE '%ifmap%'
 GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE
 ORDER BY TABLE_NAME, INDEX_NAME;
-- **期望 9 条**：config 4（uk_config_biz / idx_config_list / _front / _code）
--              + logic_branch 2（uk_logic_branch / idx_logic_branch_query）
--              + execution_log 3（idx_log_biz / _iface / _time）
-- 返回 0 条 = 本步骤没执行成功，不要继续。

-- ---------------------------------------------------------------------------
-- 回滚（如需；每一步都必须"先 DROP 唯一键再改回类型"）：
--   ALTER TABLE `bankint_config` DROP INDEX `uk_ifmap_config_biz`;
--   ALTER TABLE `bankint_config` MODIFY COLUMN `interface_order` char(2) NULL,
--     MODIFY COLUMN `add_user_id` bigint(19) NULL, ..., ALGORITHM=COPY, LOCK=SHARED;
-- （完整反向定义请从 00-precheck 第 3 步留档的 COLUMN_TYPE 复制；不要凭记忆手写。）
-- ---------------------------------------------------------------------------
