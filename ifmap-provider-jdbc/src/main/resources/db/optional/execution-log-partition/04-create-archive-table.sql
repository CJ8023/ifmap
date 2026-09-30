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
-- 步骤 4/6：创建执行日志**归档冷表**（冷热分离的落点，只建一次）
--
-- 表前缀：本套脚本以 `ifmap_` 为例，存量部署请先整体替换：
--         sed -i 's/ifmap_/bankint_/g' *.sql
--
-- 执行时机：启用冷热分离（`JdbcExecutionLogArchiver`）**之前**。
-- 本文件在 ifmap 里是"可选运维脚本"，不进 Liquibase changelog —— 也就是说
-- **升级 jar 不会自动建这张表**，因为它属于运维决策（要不要留冷数据、留多久）。
-- 归档器在跑之前会自检本表存在且列齐，缺表时给出的报错就是指向本文件。
--
-- 为什么列定义与热表**逐列写死**、而不用 `CREATE TABLE ... LIKE`：
--   1. 本文件是可评审的（评审单里能直接看到冷表结构），`LIKE` 会随热表漂移；
--   2. 冷表要能独立演进（例如 archive_time、分区策略），将来还要按合规要求加列；
--   3. 归档器会核对热表列与自身内置清单是否**完全一致**，因此靠 `LIKE` 换取的一致性
--      并不额外带来安全 —— 少列/多列照样会在第一次归档时直接失败。
-- 若热表列有新增，请同步改三处：热表 DDL、JdbcExecutionLogArchiver.ARCHIVED_COLUMNS、本文件。
--
-- ⚠️ 不要给冷表加外键、触发器，也不要让业务代码写它：冷表只由归档器 INSERT、由合规流程查询。
-- ⚠️ 冷表数据量按"5 年"量级估算（每行几百字节 ~ 1KB）。上亿行时启用文件末的分区变体，
--    否则将来清理只能靠 DELETE（慢且产生大量 undo/binlog）。
-- =============================================================================

CREATE TABLE `ifmap_execution_log_archive` (
  `key_id`            bigint       NOT NULL                   COMMENT '主键（与热表同一雪花ID，跨表唯一 → 归档可重入）',
  `tenant_id`         bigint       NOT NULL DEFAULT -1         COMMENT '租户ID',
  `interface_no`      varchar(64)  NOT NULL                   COMMENT '接口编号',
  `biz_id`            varchar(64)  NOT NULL                   COMMENT '业务ID',
  `request_param`     json                   DEFAULT NULL       COMMENT '请求参数（已脱敏；超长按配置截断）',
  `response_param`    mediumtext             DEFAULT NULL       COMMENT '响应参数（已脱敏；超长按配置截断）',
  `execution_time`    bigint       NOT NULL DEFAULT 0          COMMENT '执行耗时(ms)',
  `execution_result`  varchar(16)            DEFAULT NULL       COMMENT '执行结果：SUCCESS/FAIL/SKIP/TIMEOUT',
  `error_msg`         varchar(1024)          DEFAULT NULL       COMMENT '失败原因（截断）',
  `remark`            varchar(512) NOT NULL DEFAULT ''         COMMENT '备注',
  `del_status`        tinyint(1)   NOT NULL DEFAULT 0          COMMENT '删除标识;0:未删除1:已删除',
  `add_user_id`       varchar(64)  NOT NULL DEFAULT ''         COMMENT '添加人',
  `add_time`          datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '原执行时间（保留原值，不改成归档时间）',
  `add_request_id`    varchar(40)  NOT NULL DEFAULT ''         COMMENT '创建请求ID',
  `modify_user_id`    varchar(64)  NOT NULL DEFAULT ''         COMMENT '更新人',
  `modify_time`       datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `modify_request_id` varchar(40)  NOT NULL DEFAULT ''         COMMENT '修改请求ID',
  `archive_time`      datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '归档时间（冷表独有列；热表的 add_time 才是业务时间）',
  PRIMARY KEY (`key_id`),
  KEY `idx_ifmap_archive_biz`   (`tenant_id`,`biz_id`,`add_time`),
  KEY `idx_ifmap_archive_iface` (`tenant_id`,`interface_no`,`add_time`),
  KEY `idx_ifmap_archive_time`  (`add_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC
  COMMENT='资方接口执行日志归档（冷表）';

-- -----------------------------------------------------------------------------
-- 【可选变体 A】冷表也按月分区（数据量大时强烈建议；MySQL 8.0）
--   好处：留存到期用 `ALTER TABLE ... DROP PARTITION` 秒级回收（不产生删除 binlog），
--         查询最近某月只扫对应分区。
--   代价：分区列必须进主键 → PRIMARY KEY (key_id, add_time)（key_id 不再单独唯一，
--         但雪花 ID 本身全局唯一，归档器的"是否已归档"判断仍然正确）。
--   用法：把上面的 CREATE TABLE 换成下面这段（去掉注释），或对已建好的表执行
--   `ALTER TABLE ifmap_execution_log_archive PARTITION BY RANGE (TO_DAYS(add_time)) (...)`。
--
-- CREATE TABLE `ifmap_execution_log_archive` (
--   ... 同上 18 列 ...
--   PRIMARY KEY (`key_id`,`add_time`),
--   ...
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC
--   COMMENT='资方接口执行日志归档（冷表）'
--   PARTITION BY RANGE (TO_DAYS(`add_time`))
--   (PARTITION p202601 VALUES LESS THAN (TO_DAYS('2026-02-01')),
--    PARTITION p202602 VALUES LESS THAN (TO_DAYS('2026-03-01')),
--    PARTITION p202603 VALUES LESS THAN (TO_DAYS('2026-04-01')),
--    PARTITION pmax    VALUES LESS THAN MAXVALUE);   -- 兜底分区：不设它，插入越界数据会报 1526
--
-- 【可选变体 B】冷表放到独立的归档库/实例（真正意义上的冷热分离）
--   同一实例内的冷表只解决"热表变小"，不解决"磁盘变贵"。跨库搬迁把本文件执行到归档库，
--   归档器换成该库的 DataSource 即可（JdbcExecutionLogArchiver 只认一个 JdbcTemplate）：
--     new JdbcExecutionLogArchiver(archiveDataSource, "ifmap_")
--   注意：跨库就无法用裸 SQL 一把 INSERT ... SELECT 完成搬迁了，需要"读一批 → 批量写归档库 →
--   删热表"的循环（本类里的三步流程正是为此跑通两个 DataSource 的形态预留的）。
-- -----------------------------------------------------------------------------
