/*
 * Copyright 2026 caijun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.cj.ifmap.admin;

/**
 * 管理端约定的审计请求头：操作人 / 请求号（与 ifmap 执行日志口径一致，便于串起"谁改的"）。
 *
 * @author caijun
 */
public final class IfmapAdminHeaders {

    /** 操作人（落库到 {@code add_user_id} / {@code modify_user_id}）。 */
    public static final String OPERATOR_ID = "X-Operator-Id";

    /** 请求号（落库到 {@code add_request_id} / {@code modify_request_id}）。 */
    public static final String REQUEST_ID = "X-Request-Id";

    private IfmapAdminHeaders() {
    }
}
