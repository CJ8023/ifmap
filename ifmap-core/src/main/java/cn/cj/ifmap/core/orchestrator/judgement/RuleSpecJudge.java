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
import cn.cj.ifmap.core.spi.ResultJudge;
import cn.cj.ifmap.core.spi.ResultJudgeContext;

import java.util.List;

/**
 * 默认判定器 {@code ruleSpec}：读 {@code result_judge} 规格求值，<b>fail-closed</b>。
 *
 * <p>规格为空/非法 → {@code SPEC_INVALID}；响应非 JSON → {@code NOT_JSON}；
 * 路径取不到 → {@code FIELD_MISSING}；取到值未命中 → {@code VALUE_MISMATCH}。
 * 任何情况都<b>不抛异常</b>（判定不是业务异常）。</p>
 *
 * @author caijun
 */
public final class RuleSpecJudge implements ResultJudge {

    /** 注册名（配置列 {@code judge_name} 的默认值）。 */
    public static final String NAME = "ruleSpec";

    private static final String ALWAYS_SUMMARY = "always";

    private final JsonOps jsonOps;

    public RuleSpecJudge(JsonOps jsonOps) {
        if (jsonOps == null) {
            throw new IllegalArgumentException("jsonOps 不能为 null");
        }
        this.jsonOps = jsonOps;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Judgement judge(ResultJudgeContext context) {
        IfmapConfig config = context.getConfig();
        String spec = config.getResultJudge();
        List<String> errors = ResultJudgeSpec.validate(spec, jsonOps);
        if (!errors.isEmpty()) {
            return Judgement.failure(JudgeFailType.SPEC_INVALID,
                    Judgement.Stage.BUSINESS, errors.get(0));
        }
        ResultJudgeSpec parsed = ResultJudgeSpec.parse(spec, jsonOps);
        if (parsed.isAlways()) {
            return Judgement.success(null, ALWAYS_SUMMARY,
                    "always：该接口不做结果判定");
        }
        String response = context.getResponseJson();
        if (response == null || response.trim().isEmpty()) {
            return Judgement.failure(JudgeFailType.NOT_JSON,
                    Judgement.Stage.BUSINESS, "响应为空，无法按规格判定");
        }
        ResultJudgeSpec.Evaluation evaluation;
        try {
            evaluation = parsed.evaluate(jsonOps.readContext(response));
        } catch (RuntimeException e) {
            return Judgement.failure(JudgeFailType.NOT_JSON,
                    Judgement.Stage.BUSINESS, "响应解析失败：" + e.getMessage());
        }
        if (evaluation.isMatched()) {
            return Judgement.success(evaluation.getActual(), parsed.describe(),
                    "命中判定条件");
        }
        if (evaluation.getMissedPath() != null) {
            return Judgement.failure(JudgeFailType.FIELD_MISSING,
                    Judgement.Stage.BUSINESS, evaluation.getActual(),
                    parsed.describe(), "取不到判定字段 " + evaluation.getMissedPath());
        }
        return Judgement.failure(JudgeFailType.VALUE_MISMATCH,
                Judgement.Stage.BUSINESS, evaluation.getActual(),
                parsed.describe(), "未命中：" + parsed.describe()).withErrorCode(evaluation.getActual());
    }
}
