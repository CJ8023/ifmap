--liquibase formatted sql

--changeset caijun:001-create-ifmap-config
CREATE TABLE `${tablePrefix}config` (
  `key_id`                    bigint       NOT NULL                     COMMENT '主键（默认雪花ID）',
  `tenant_id`                 bigint       NOT NULL DEFAULT -1          COMMENT '租户ID（单租户固定 -1）',
  `interface_no`              varchar(64)  NOT NULL                    COMMENT '接口编号（业务唯一键）',
  `interface_code`            varchar(64)  NOT NULL                    COMMENT '接口编码（对接方接口编码）',
  `project_code`              varchar(64)           DEFAULT NULL       COMMENT '项目编号',
  `interface_name`            varchar(128) NOT NULL                    COMMENT '接口名称',
  `busi_node`                 varchar(32)  NOT NULL                    COMMENT '业务节点（取值由宿主机注册）',
  `bank_code`                 varchar(32)  NOT NULL                    COMMENT '资方编码',
  `bank_name`                 varchar(128)          DEFAULT NULL       COMMENT '资方名称',
  `financing_mode`            varchar(32)           DEFAULT NULL       COMMENT '融资模式',
  `front_interface_no`        varchar(64)           DEFAULT NULL       COMMENT '前置接口编号（空=无前置）',
  `interface_order`           smallint     NOT NULL DEFAULT 0           COMMENT '接口执行顺序，升序；同值按 key_id 兜底',
  `request_param_template`    text                                     COMMENT '请求参数模板（DSL），可为空',
  `response_param_template`   longtext                                 COMMENT '响应参数模板（DSL），可为空',
  `result_flag`               varchar(512) NOT NULL DEFAULT ''          COMMENT '执行结果标志（JsonPath）',
  `success_value`             varchar(512) NOT NULL DEFAULT ''          COMMENT '成功判断值，多值以 | 分隔（大小写不敏感）',
  `strategy_name`             varchar(128) NOT NULL DEFAULT ''          COMMENT '特殊处理策略标识（=Spring bean 名）',
  `status`                    tinyint(1)   NOT NULL DEFAULT 1           COMMENT '状态：1启用 0停用',
  `version`                   int          NOT NULL DEFAULT 0           COMMENT '乐观锁版本',
  `remark`                    varchar(512) NOT NULL DEFAULT ''          COMMENT '备注',
  `del_status`                tinyint(1)   NOT NULL DEFAULT 0           COMMENT '删除标识;0:未删除1:已删除',
  `deleted_seq`               bigint       NOT NULL DEFAULT 0           COMMENT '软删除唯一化：未删除=0，删除时=key_id',
  `add_user_id`               varchar(64)  NOT NULL DEFAULT ''          COMMENT '添加人',
  `add_time`                  datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '添加时间',
  `add_request_id`            varchar(40)  NOT NULL DEFAULT ''          COMMENT '创建请求ID',
  `modify_user_id`            varchar(64)  NOT NULL DEFAULT ''          COMMENT '更新人',
  `modify_time`               datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间（由写入方统一设置，不用 ON UPDATE）',
  `modify_request_id`         varchar(40)  NOT NULL DEFAULT ''          COMMENT '修改请求ID',
  PRIMARY KEY (`key_id`),
  UNIQUE KEY `uk_${tablePrefix}config_biz`   (`tenant_id`,`interface_no`,`busi_node`,`interface_order`,`deleted_seq`),
  KEY `idx_${tablePrefix}config_list`  (`tenant_id`,`del_status`,`busi_node`,`bank_code`),
  KEY `idx_${tablePrefix}config_front` (`tenant_id`,`del_status`,`front_interface_no`),
  KEY `idx_${tablePrefix}config_code`  (`tenant_id`,`del_status`,`interface_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC
  COMMENT='资方接口配置';
