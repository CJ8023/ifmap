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
-- 步骤 1/6：把执行日志热表改造成**按月 RANGE 分区**（设计 §6.4 方案 B）
--
-- 表前缀：本套脚本以 `ifmap_` 为例，存量部署请先整体替换：
--         sed -i 's/ifmap_/bankint_/g' *.sql
--
-- ⚠️⚠️ 这是本套脚本里唯一"重"的一步：改分区 = 重建表 = 全表 COPY（MySQL 5.7/8.0 均无法
--      在线改分区列/主键，`ALGORITHM=INPLACE` 不适用于改主键与加分区），
--      期间原表可读可写但会长时间持锁，且会写满磁盘（新旧表共存）。
--      请**先跑 00-precheck.sql**，按输出决定走哪条路：
--
--        路线 A（表小 / 允许停写 5~30 分钟）——用本文件的手工流程：
--            停写 → 建影子表 → 分批搬数据 → 原子 RENAME → 校验 → DROP 旧表 → 恢复写入
--        路线 B（表大 / 不能停写）——用在线工具 pt-online-schema-change：
--            pt-online-schema-change --alter "DROP PRIMARY KEY, ADD PRIMARY KEY (key_id, add_time),
--              PARTITION BY RANGE (TO_DAYS(add_time)) (...)" D=库,t=ifmap_execution_log ...
--            注意：工具默认沿用原主键，**必须显式写 `DROP PRIMARY KEY, ADD PRIMARY KEY (key_id, add_time)`**
--            以及分区子句，否则会因为"唯一键未包含分区列"直接失败（1503）。
--            gh-ost 对分区变更的支持随版本而异，用前先核对你们版本的文档。
--        无论哪条路线，都必须保留**原表名**（RENAME 而不是新建同义表名），
--        这样 ifmap 侧的表名前缀配置与归档器/清理器都不用改。
--
-- 为什么可以把主键从 (key_id) 改成 (key_id, add_time)：
--   ① 分区表硬约束：每个唯一键都必须包含所有分区列；
--   ② add_time 是 NOT NULL DEFAULT CURRENT_TIMESTAMP(3)，不会出现"分区键为 NULL"的行；
--   ③ ifmap 只按 key_id 建索引查配置表，**从不按 key_id 单独查执行日志**
--      （只有 `recentLogs(tenantId, bizId)` 与按 add_time 的清理/归档），
--      所以"key_id 不再单独唯一"对业务语义没有影响；
--   ④ key_id 本身是雪花 ID，全局唯一性仍由生成器保证，归档器的幂等判断依然成立。
--
-- 分区带来的收益：
--   * 保留期到期时用 `ALTER TABLE ... DROP PARTITION` 秒级回收（不产生删除 binlog，不像 DELETE 那样
--     产生大量 undo/redo，也不会让主从延迟）；见 05-archive-and-drop.sql
--   * 按时间范围查日志只扫对应分区（EXPLAIN 里能看到 partitions 列被裁剪）
-- 代价：
--   * 主键变宽（+8 字节/行索引）、分区表不能用外键、每个分区一个表空间文件
--   * 分区方案一旦写定，杂查询（例如只用 tenant_id 过滤）不会变快 —— 分区不替代索引
-- =============================================================================

SET SESSION time_zone = '+08:00';
SET SESSION lock_wait_timeout = 10;

-- -----------------------------------------------------------------------------
-- 1.0 【写操作开始】先停写：让应用侧关闭日志写入（或用只读窗口/维护页），
--     再确认已经没有任何事务碰这张表（注意：5.7 与 8.0 查锁的表不一样，下面两个都列出来）
-- -----------------------------------------------------------------------------
SELECT trx_id, trx_mysql_thread_id AS thread_id, trx_started,
       TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS running_seconds, trx_state, trx_rows_locked
  FROM information_schema.innodb_trx
 ORDER BY trx_started;

-- MySQL 8.0（performance_schema，需 performance_schema = ON）：
-- SELECT ENGINE_TRANSACTION_ID, THREAD_ID, OBJECT_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS
--   FROM performance_schema.data_locks WHERE OBJECT_NAME = 'ifmap_execution_log';
-- MySQL 5.7：
-- SELECT * FROM information_schema.innodb_locks WHERE locked_table LIKE '%ifmap_execution_log%';

-- 两条查询都必须"查不到任何碰这张表的事务"再往下走。
-- 等待期间也不要跑长 SELECT（会挡住 DDL 拿元数据锁）。

-- -----------------------------------------------------------------------------
-- 1.1 建影子表：结构与热表一致，只改主键并加分区
--     分区边界用 TO_DAYS('YYYY-MM-01')（当月第一天）表达，取 00-precheck.sql 的月度输出逐月列出。
--     最后必须有 `pmax VALUES LESS THAN MAXVALUE`：没有兜底分区时，插入超出边界的时间会报 1526
--     （"Table has no partition for value"），日志写入会直接失败 → 这是生产事故级别的问题。
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS `ifmap_execution_log_new`;   -- 只允许删这张临时表，别把参数改错！
CREATE TABLE `ifmap_execution_log_new` (
  `key_id`            bigint       NOT NULL                   COMMENT '主键',
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
  `add_time`          datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '执行时间',
  `add_request_id`    varchar(40)  NOT NULL DEFAULT ''         COMMENT '创建请求ID',
  `modify_user_id`    varchar(64)  NOT NULL DEFAULT ''         COMMENT '更新人',
  `modify_time`       datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `modify_request_id` varchar(40)  NOT NULL DEFAULT ''         COMMENT '修改请求ID',
  PRIMARY KEY (`key_id`,`add_time`),                          -- ← 唯一改动：分区列必须进主键
  KEY `idx_ifmap_log_biz`   (`tenant_id`,`biz_id`,`add_time`),
  KEY `idx_ifmap_log_iface` (`tenant_id`,`interface_no`,`add_time`),
  KEY `idx_ifmap_log_time`  (`add_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC
  COMMENT='资方接口执行日志'
  PARTITION BY RANGE (TO_DAYS(`add_time`)) (
    PARTITION p202601 VALUES LESS THAN (TO_DAYS('2026-02-01')),
    PARTITION p202602 VALUES LESS THAN (TO_DAYS('2026-03-01')),
    PARTITION p202603 VALUES LESS THAN (TO_DAYS('2026-04-01')),
    -- …… 按 00-precheck.sql 的月度输出把余下月份补齐 ……
    PARTITION pmax    VALUES LESS THAN MAXVALUE              -- 兜底：务必保留（见上方 1526 说明）
  );

-- -----------------------------------------------------------------------------
-- 1.2 分批搬数据：**按月搬**（一条 INSERT 就是一个事务，别指望一条语句搬完千万行）。
--     先搬老月份、再搬新月份，这样"重新打开写入"前写入的窗口最小。
--     ⚠️ 停写窗口内搬完最稳；若无法停写，请走路线 B（在线工具），不要边写边搬。
-- -----------------------------------------------------------------------------
INSERT INTO `ifmap_execution_log_new`
      (`key_id`,`tenant_id`,`interface_no`,`biz_id`,`request_param`,`response_param`,`execution_time`,
       `execution_result`,`error_msg`,`remark`,`del_status`,`add_user_id`,`add_time`,`add_request_id`,
       `modify_user_id`,`modify_time`,`modify_request_id`)
SELECT `key_id`,`tenant_id`,`interface_no`,`biz_id`,`request_param`,`response_param`,`execution_time`,
       `execution_result`,`error_msg`,`remark`,`del_status`,`add_user_id`,`add_time`,`add_request_id`,
       `modify_user_id`,`modify_time`,`modify_request_id`
  FROM `ifmap_execution_log`
 WHERE `add_time` >= '2026-01-01 00:00:00.000' AND `add_time` < '2026-02-01 00:00:00.000';
-- …… 其余月份照抄，只改 WHERE 区间 ……

-- 搬完立刻对账（**必须相等**，不相等就不要往下走）。
-- 停写窗口内 COUNT(*) 是精确的；若表极大且无法接受两次全表计数，
-- 改成逐月计数（`WHERE add_time >= ? AND add_time < ?`，能走分区/索引裁剪）再逐月比对。
SELECT (SELECT COUNT(*) FROM `ifmap_execution_log`)     AS hot_rows,
       (SELECT COUNT(*) FROM `ifmap_execution_log_new`) AS new_rows;

-- -----------------------------------------------------------------------------
-- 1.3 原子换名：一条语句同时改两张表的名字，对应用来说"表名没变、数据没丢"。
--     应用无需改配置、无需重启（下次查询自然命中新表）。
-- -----------------------------------------------------------------------------
RENAME TABLE `ifmap_execution_log`     TO `ifmap_execution_log_old`,
             `ifmap_execution_log_new` TO `ifmap_execution_log`;

-- -----------------------------------------------------------------------------
-- 1.4 恢复写入，并做一轮业务冒烟（跑一笔接口、看日志表有没有新行、分区裁剪是否生效）。
--     确认无误后，**先别急着 DROP 旧表**：留 1~3 天作为回滚窗口（万一要回滚，
--     再 RENAME 回来即可 —— 期间的增量需要从 _old 与当前表合并，所以窗口越短越好）。
-- -----------------------------------------------------------------------------
-- 1.5 回滚窗口结束后删旧表（大表 DROP 会占用 IO，建议低峰期；
--     只有 innodb_file_per_table = 1 时磁盘空间才会在 DROP 后真正释放）
-- DROP TABLE `ifmap_execution_log_old`;

-- -----------------------------------------------------------------------------
-- 1.6 别忘了：给后续每个月加分区（02-add-monthly-partition.sql）！
--     没有新分区时，新数据会全落进 pmax 兜底分区 —— pmax 一旦有数据，
--     拆分它就要搬数据（REORGANIZE 会 COPY），所以要在月初之前加好。
-- =============================================================================
