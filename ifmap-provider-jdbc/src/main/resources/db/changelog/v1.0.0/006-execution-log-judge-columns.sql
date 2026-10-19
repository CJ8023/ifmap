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
-- 执行日志补 4 列：判定结论要能归因（传输？业务？抽取？分类？），而不是只有 SUCCESS/FAIL。
--
-- 全部可空或有默认值 ⇒ 对存量表零风险（不改既有列、不加唯一键、不要求回填）：
--   http_status  传输层状态码（null = 宿主未上报）；
--   fail_type    失败类型（JudgeFailType 名；成功为空串）；
--   error_code   对方业务错误码（从判定命中的路径抽出，便于按码统计）；
--   track_id     对方链路 ID（便于找对方查单）。
-- =====================================================================================

--changeset caijun:006-execution-log-http-status
ALTER TABLE `${tablePrefix}execution_log` ADD COLUMN `http_status` smallint DEFAULT NULL COMMENT 'HTTP 状态码（宿主未上报时为空）';

--changeset caijun:006-execution-log-fail-type
ALTER TABLE `${tablePrefix}execution_log` ADD COLUMN `fail_type` varchar(32) NOT NULL DEFAULT '' COMMENT '失败类型（TIMEOUT/TRANSPORT/HTTP_5XX/... ；成功为空）';

--changeset caijun:006-execution-log-error-code
ALTER TABLE `${tablePrefix}execution_log` ADD COLUMN `error_code` varchar(64) NOT NULL DEFAULT '' COMMENT '对方业务错误码（从判定路径抽出）';

--changeset caijun:006-execution-log-track-id
ALTER TABLE `${tablePrefix}execution_log` ADD COLUMN `track_id` varchar(128) NOT NULL DEFAULT '' COMMENT '对方链路ID';
