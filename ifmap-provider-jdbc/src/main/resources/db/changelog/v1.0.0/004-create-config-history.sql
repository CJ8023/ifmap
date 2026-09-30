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

--changeset caijun:004-create-config-history
CREATE TABLE `${tablePrefix}config_history` (
  `key_id`         bigint       NOT NULL                   COMMENT '主键',
  `config_key_id`  bigint       NOT NULL                   COMMENT '原配置主键',
  `tenant_id`      bigint       NOT NULL DEFAULT -1         COMMENT '租户ID',
  `interface_no`   varchar(64)  NOT NULL                   COMMENT '接口编号（冗余，便于按接口查历史）',
  `change_type`    varchar(16)  NOT NULL                   COMMENT 'CREATE/UPDATE/DELETE/ENABLE/DISABLE',
  `change_reason`  varchar(255) NOT NULL DEFAULT ''         COMMENT '变更原因',
  `snapshot`       json         NOT NULL                   COMMENT '变更后的配置快照（DELETE 时存删除前快照）',
  `diff`           json                    DEFAULT NULL     COMMENT '与上一版的字段差异（可选）',
  `add_user_id`    varchar(64)  NOT NULL DEFAULT ''         COMMENT '操作人',
  `add_time`       datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '变更时间',
  `add_request_id` varchar(40)  NOT NULL DEFAULT ''         COMMENT '请求ID',
  PRIMARY KEY (`key_id`),
  KEY `idx_${tablePrefix}history_config` (`config_key_id`,`add_time`),
  KEY `idx_${tablePrefix}history_iface`  (`tenant_id`,`interface_no`,`add_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC
  COMMENT='资方接口配置变更历史';
