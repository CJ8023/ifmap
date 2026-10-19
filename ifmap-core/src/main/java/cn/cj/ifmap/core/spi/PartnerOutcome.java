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
package cn.cj.ifmap.core.spi;

/**
 * 传输层结果（可选上报）：状态码 / 超时 / 错误消息。
 *
 * <p>宿主实现 {@link PartnerServiceGateway#exchangeForOutcome} 时可顺带上报；
 * <b>不上报</b>（走默认实现）时只有响应体，判定行为与旧版完全一致。</p>
 *
 * <p>这些信息<b>只用于</b>判定与落库归因，不驱动重试或分支。</p>
 *
 * @author caijun
 */
public final class PartnerOutcome {

    private final String body;
    private final Integer statusCode;
    private final boolean timedOut;
    private final boolean failed;
    private final String message;

    private PartnerOutcome(String body, Integer statusCode, boolean timedOut, boolean failed, String message) {
        this.body = body;
        this.statusCode = statusCode;
        this.timedOut = timedOut;
        this.failed = failed;
        this.message = message;
    }

    /** 只有响应体（无状态码）：{@code exchange} 的默认回落形态。 */
    public static PartnerOutcome ofBody(String body) {
        return new PartnerOutcome(body, null, false, false, null);
    }

    /** 拿到 HTTP 状态码（含非 2xx）：body 由调用方另行传入或置空。 */
    public static PartnerOutcome of(String message, int statusCode) {
        return new PartnerOutcome(null, Integer.valueOf(statusCode), false, false, message);
    }

    /** 带状态码与响应体。 */
    public static PartnerOutcome of(String message, int statusCode, String body) {
        return new PartnerOutcome(body, Integer.valueOf(statusCode), false, false, message);
    }

    /** 读超时（与普通传输失败区分：{@code TIMEOUT} vs {@code TRANSPORT}）。 */
    public static PartnerOutcome timeout(String message) {
        return new PartnerOutcome(null, null, true, true, message);
    }

    /** 连接/IO 类传输失败（没有状态码）。 */
    public static PartnerOutcome failure(String message) {
        return new PartnerOutcome(null, null, false, true, message);
    }

    public String getBody() {
        return body;
    }

    /** HTTP 状态码；宿主未上报为 {@code null}。 */
    public Integer getStatusCode() {
        return statusCode;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    /** 是否发生了传输层失败（超时 / 连接 / IO），无状态码可判。 */
    public boolean isFailure() {
        return failed;
    }

    /** 传输层错误消息（写日志用）。 */
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "PartnerOutcome{status=" + statusCode + ", timedOut=" + timedOut + ", failed=" + failed
                + ", message=" + message + '}';
    }
}
