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

import cn.cj.ifmap.core.config.IfmapConfig;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.orchestrator.Judgement;
import cn.cj.ifmap.core.spi.PartnerOutcome;
import cn.cj.ifmap.core.spi.ResultJudge;
import cn.cj.ifmap.core.spi.ResultJudgeContext;

/**
 * 四段判定管线：<b>传输 → 业务 → 抽取 → 分类</b>。
 *
 * <p>四段是逻辑分段（三个私有动作 + 枚举），不各建一个类：</p>
 * <ol>
 *   <li><b>传输层是硬门槛</b>：超时 / 非 2xx 直接判失败，<b>不进规格求值</b>；</li>
 *   <li><b>业务层</b>：按 {@code judge_name} 取判定器（默认 {@code ruleSpec}），取不到 fail-closed；</li>
 *   <li><b>抽取层</b>：固定候选路径补 {@code message} / {@code trackId}，<b>不改结论</b>；</li>
 *   <li><b>分类</b>：失败类型由枚举承担，成功为 {@code null}。</li>
 * </ol>
 *
 * @author caijun
 */
public final class JudgementPipeline {

    private final ResultJudgeRegistry judges;
    private final JsonOps jsonOps;

    public JudgementPipeline(ResultJudgeRegistry judges, JsonOps jsonOps) {
        if (judges == null) {
            throw new IllegalArgumentException("judges 不能为 null");
        }
        if (jsonOps == null) {
            throw new IllegalArgumentException("jsonOps 不能为 null");
        }
        this.judges = judges;
        this.jsonOps = jsonOps;
    }

    /**
     * 判定一次调用。
     *
     * @param config       接口配置（{@code result_judge} / {@code judge_name}）
     * @param responseJson 响应原文；{@code outcome} 非空时以 {@code outcome.getBody()} 为准
     * @param outcome      宿主上报的传输层结果，可为 {@code null}（老实现走 default，等价于今天的行为）
     */
    public Judgement judge(IfmapConfig config, String responseJson, PartnerOutcome outcome) {
        Integer httpStatus = null;
        if (outcome != null) {
            if (outcome.isTimedOut()) {
                return Judgement.failure(JudgeFailType.TIMEOUT, Judgement.Stage.TRANSPORT, outcome.getMessage())
                        .withHttpStatus(outcome.getStatusCode());
            }
            if (outcome.isFailure()) {
                return Judgement.failure(JudgeFailType.TRANSPORT, Judgement.Stage.TRANSPORT, outcome.getMessage());
            }
            httpStatus = outcome.getStatusCode();
            if (httpStatus != null) {
                JudgeFailType httpFail = JudgeFailType.forHttpStatus(httpStatus.intValue());
                if (httpFail != null) {
                    return Judgement.failure(httpFail, Judgement.Stage.TRANSPORT,
                            "HTTP " + httpStatus + (outcome.getMessage() == null ? "" : "：" + outcome.getMessage()))
                            .withHttpStatus(httpStatus)
                            .withExtraction(ResponseExtractor.message(jsonOps, outcome.getBody()),
                                    ResponseExtractor.trackId(jsonOps, outcome.getBody()));
                }
            }
            responseJson = outcome.getBody();
        }

        ResultJudge resultJudge = judges.lookup(trimToNull(config.getJudgeName()));
        if (resultJudge == null) {
            return Judgement.failure(JudgeFailType.SPEC_INVALID, Judgement.Stage.BUSINESS,
                    "判定器未注册：" + config.getJudgeName()).withHttpStatus(httpStatus);
        }
        Judgement judgement = resultJudge.judge(new ResultJudgeContext(config, responseJson, jsonOps));
        // ③ 抽取段：只补信息，不改 success / failType / errorCode
        return judgement
                .withExtraction(ResponseExtractor.message(jsonOps, responseJson),
                        ResponseExtractor.trackId(jsonOps, responseJson))
                .withHttpStatus(httpStatus);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
