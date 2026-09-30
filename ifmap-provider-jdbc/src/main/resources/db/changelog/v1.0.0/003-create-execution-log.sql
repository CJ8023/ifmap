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

--changeset caijun:003-create-execution-log
CREATE TABLE `${tablePrefix}execution_log` (
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
  PRIMARY KEY (`key_id`),
  KEY `idx_${tablePrefix}log_biz`   (`tenant_id`,`biz_id`,`add_time`),
  KEY `idx_${tablePrefix}log_iface` (`tenant_id`,`interface_no`,`add_time`),
  KEY `idx_${tablePrefix}log_time`  (`add_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC
  COMMENT='资方接口执行日志';
