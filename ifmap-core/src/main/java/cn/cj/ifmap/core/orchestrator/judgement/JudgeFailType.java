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
package cn.cj.ifmap.core.orchestrator.judgement;

/**
 * 失败类型（落 {@code ifmap_execution_log.fail_type}）：<b>闭合枚举</b>，成功时为空。
 *
 * <p>只用于记录与排障，<b>不驱动</b>重试/熔断（设计 §3.5）。</p>
 *
 * @author caijun
 */
public enum JudgeFailType {

    TRANSPORT, TIMEOUT, HTTP_4XX, HTTP_5XX, SPEC_INVALID, NOT_JSON, FIELD_MISSING, VALUE_MISMATCH, EXTRACT_FAILED;

    /** HTTP 状态码 → 失败类型（2xx 返回 null）。 */
    public static JudgeFailType forHttpStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return null;
        }
        return statusCode >= 500 ? HTTP_5XX : HTTP_4XX;
    }
}
