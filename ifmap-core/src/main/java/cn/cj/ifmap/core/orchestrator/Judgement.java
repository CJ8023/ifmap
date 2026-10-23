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
package cn.cj.ifmap.core.orchestrator;

import cn.cj.ifmap.core.orchestrator.judgement.JudgeFailType;

/**
 * 结果判定结论。
 *
 * <p>{@code stage} / {@code failType} / {@code errorCode} / {@code httpStatus} / {@code trackId}
 * 都是<b>记录用</b>信息：判定结论只看 {@link #isSuccess()}，这些字段不驱动重试或分支选择。</p>
 *
 * <p>{@code failType} 成功时为 {@code null}（落库时归一化成空串，见 {@code writeLog}）。</p>
 *
 * @author caijun
 */
public final class Judgement {

    /**
     * 判定所处的段（设计 §3.3）：传输 / 业务规格 / 抽取 / 分支选择；成功时为 {@code null}。
     *
     * <p>{@link #BRANCH} 只在<b>分支判定规格非法</b>时出现：结果判定已成功、但拿不出合法的分支规格，
     * 这一步按 fail-closed 判失败（{@code SPEC_INVALID}），不落到兜底分支。</p>
     */
    public enum Stage {
        TRANSPORT, BUSINESS, EXTRACT, BRANCH, NONE
    }

    private final boolean success;
    private final Stage stage;
    private final String failType;
    private final String actualValue;
    private final String expectedValue;
    private final String errorCode;
    private final Integer httpStatus;
    private final String trackId;
    private final String message;
    private final String reason;

    private Judgement(boolean success, Stage stage, String failType, String actualValue, String expectedValue,
                      String errorCode, Integer httpStatus, String trackId, String message, String reason) {
        this.success = success;
        this.stage = stage;
        this.failType = failType;
        this.actualValue = actualValue;
        this.expectedValue = expectedValue;
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.trackId = trackId;
        this.message = message;
        this.reason = reason;
    }

    /** 判定成功。 */
    public static Judgement success(String actualValue, String expectedValue, String reason) {
        return new Judgement(true, null, null, actualValue, expectedValue, null, null, null, null, reason);
    }

    /** 判定失败（带判定段与失败类型）。 */
    public static Judgement failure(JudgeFailType failType, Stage stage, String actualValue, String expectedValue,
                                    String reason) {
        return new Judgement(false, stage, failType == null ? null : failType.name(), actualValue, expectedValue,
                null, null, null, null, reason);
    }

    /** 判定失败（无实际值/期望值，传输层与规格类失败用）。 */
    public static Judgement failure(JudgeFailType failType, Stage stage, String reason) {
        return failure(failType, stage, null, null, reason);
    }

    /**
     * 兼容旧工厂（{@code result_flag} + {@code success_value} 时代）：不带段与失败类型。
     *
     * @deprecated 新代码用 {@link #success(String, String, String)} / {@link #failure(JudgeFailType, Stage, String)}
     */
    @Deprecated
    public static Judgement of(boolean success, String actualValue, String expectedValue, String reason) {
        return new Judgement(success, null, null, actualValue, expectedValue, null, null, null, null, reason);
    }

    public boolean isSuccess() {
        return success;
    }

    public Stage getStage() {
        return stage;
    }

    /** 失败类型（{@link JudgeFailType} 的枚举名）；成功时为 {@code null}。 */
    public String getFailType() {
        return failType;
    }

    /** 响应报文里判定字段的实际值（字符串形态）。 */
    public String getActualValue() {
        return actualValue;
    }

    /** 规格的字符串化摘要（原 {@code success_value} 位置）。 */
    public String getExpectedValue() {
        return expectedValue;
    }

    /** 失败时取到的实际错误码（不写死 {@code "1"}）。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 宿主上报的 HTTP 状态码；未上报为 {@code null}。 */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /** ③ 段按固定候选路径抽取的链路 ID。 */
    public String getTrackId() {
        return trackId;
    }

    /** ③ 段按固定候选路径抽取的对方业务消息。 */
    public String getMessage() {
        return message;
    }

    /** 判定说明（写日志/排错用）。 */
    public String getReason() {
        return reason;
    }

    /** 复制一份只改 httpStatus（Judgement 不可变，便于在管线各段间安全传阅）。 */
    public Judgement withHttpStatus(Integer newHttpStatus) {
        return new Judgement(success, stage, failType, actualValue, expectedValue, errorCode, newHttpStatus,
                trackId, message, reason);
    }

    /** 复制一份只改 trackId（③ 段抽到对方流水号后补进来）。 */
    public Judgement withTrackId(String newTrackId) {
        return new Judgement(success, stage, failType, actualValue, expectedValue, errorCode, httpStatus,
                newTrackId, message, reason);
    }

    /** 复制一份只改 errorCode（判定器给出的业务错误码，如 VALUE_MISMATCH 时的实际值）。 */
    public Judgement withErrorCode(String newErrorCode) {
        return new Judgement(success, stage, failType, actualValue, expectedValue, newErrorCode, httpStatus,
                trackId, message, reason);
    }

    /**
     * 分支判定规格非法：把"结果判定成功"翻成失败（fail-closed）。
     *
     * <p>保留原 {@code actualValue} / 失败上下文，只改结论：日志里既能看到对方回参判过的值，
     * 也能看到失败原因是分支规格。</p>
     */
    public Judgement asBranchFailure(String branchReason) {
        return new Judgement(false, Stage.BRANCH, JudgeFailType.SPEC_INVALID.name(), actualValue, expectedValue,
                errorCode, httpStatus, trackId, message, branchReason);
    }

    /** ③ 段抽取结果：只补信息，不改 {@code success} 与 {@code failType}。 */
    public Judgement withExtraction(String newMessage, String newTrackId) {
        return new Judgement(success, stage, failType, actualValue, expectedValue, errorCode, httpStatus,
                newTrackId, newMessage, reason);
    }

    @Override
    public String toString() {
        return "Judgement{success=" + success + ", stage=" + stage + ", failType=" + failType
                + ", actual=" + actualValue + ", expected=" + expectedValue + ", errorCode=" + errorCode
                + ", httpStatus=" + httpStatus + ", trackId=" + trackId + ", message=" + message
                + ", reason=" + reason + '}';
    }
}
