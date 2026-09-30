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

--changeset caijun:002-create-logic-branch
CREATE TABLE `${tablePrefix}logic_branch_config` (
  `key_id`             bigint       NOT NULL                   COMMENT '主键',
  `tenant_id`          bigint       NOT NULL DEFAULT -1         COMMENT '租户ID',
  `interface_no`       varchar(64)  NOT NULL                   COMMENT '接口编号',
  `method_flag`        varchar(64)           DEFAULT NULL       COMMENT '动作标识(Action Key)：分支命中后执行的动作，由宿主机 ActionRegistry 注册；空=默认兜底分支',
  `logic_branch_name`  varchar(128) NOT NULL                   COMMENT '逻辑分支名称',
  `logic_branch_flag`  varchar(512) NOT NULL DEFAULT ''         COMMENT '逻辑分支标志（JsonPath 表达式，支持 $.a.b 与裸字段名）',
  `logic_branch_value` varchar(512) NOT NULL DEFAULT ''         COMMENT '逻辑分支判断值，多值以 | 分隔',
  `logic_branch_order` smallint     NOT NULL DEFAULT 0          COMMENT '分支匹配顺序，升序，先命中先生效；默认兜底分支应设为最大',
  `remark`             varchar(512) NOT NULL DEFAULT ''         COMMENT '备注',
  `del_status`         tinyint(1)   NOT NULL DEFAULT 0          COMMENT '删除标识;0:未删除1:已删除',
  `deleted_seq`        bigint       NOT NULL DEFAULT 0          COMMENT '软删除唯一化：未删除=0，删除时=key_id',
  `add_user_id`        varchar(64)  NOT NULL DEFAULT ''         COMMENT '添加人',
  `add_time`           datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '添加时间',
  `add_request_id`     varchar(40)  NOT NULL DEFAULT ''         COMMENT '创建请求ID',
  `modify_user_id`     varchar(64)  NOT NULL DEFAULT ''         COMMENT '更新人',
  `modify_time`        datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `modify_request_id`  varchar(40)  NOT NULL DEFAULT ''         COMMENT '修改请求ID',
  PRIMARY KEY (`key_id`),
  UNIQUE KEY `uk_${tablePrefix}logic_branch` (`tenant_id`,`interface_no`,`method_flag`,`logic_branch_name`,`deleted_seq`),
  KEY `idx_${tablePrefix}logic_branch_query` (`tenant_id`,`interface_no`,`del_status`,`logic_branch_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC
  COMMENT='资方接口逻辑分支配置';
