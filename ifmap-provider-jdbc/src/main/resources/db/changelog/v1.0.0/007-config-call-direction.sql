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
-- 被调接口支持：给配置加一个「调用方向」列。
--
-- 为什么单开 007 而不改 001：Liquibase 按 changeset 的 checksum 判定是否需要重跑，
-- 改 001 会让所有已部署库重跑建表而失败（与 005/006 同一处理方式）。
--
-- 默认值 'OUTBOUND' ⇒ 存量行零回填、零风险：老行为（我方调对方）不变。
-- INBOUND 只改变引擎的执行路径（不出网、不跑前序策略、回参取请求原文），
-- 判定 / 逻辑分支 / 应答模板的语义全部复用。
-- =====================================================================================

--changeset caijun:007-config-call-direction
ALTER TABLE `${tablePrefix}config` ADD COLUMN `call_direction` varchar(16) NOT NULL DEFAULT 'OUTBOUND' COMMENT '调用方向：OUTBOUND=我方调对方，INBOUND=对方调我方';
