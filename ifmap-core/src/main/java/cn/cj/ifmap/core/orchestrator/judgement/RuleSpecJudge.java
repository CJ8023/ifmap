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

    /** 判定器名：配置里 {@code judge_name} 写 {@code ruleSpec} 即选中本实现。 */
    @Override
    public String name() {
        return NAME;
    }

    /**
     * 按 {@code result_judge} 规格求值。三类失败都在这里消化成 {@link Judgement}（不抛给调用方）：
     * 规格非法 → SPEC_INVALID、响应不是 JSON → NOT_JSON、条件未命中 → FIELD_MISSING / VALUE_MISMATCH。
     */
    @Override
    public Judgement judge(ResultJudgeContext context) {
        IfmapConfig config = context.getConfig();
        String spec = config.getResultJudge();
        // 先用 validate 把规格整体校验一遍（null 路径、未知运算符等），parse 阶段就不再防御
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
        // 空响应直接判 NOT_JSON：既不是"命中"，也不是"取不到字段"，归因要分开
        String response = context.getResponseJson();
        if (response == null || response.trim().isEmpty()) {
            return Judgement.failure(JudgeFailType.NOT_JSON,
                    Judgement.Stage.BUSINESS, "响应为空，无法按规格判定");
        }
        ResultJudgeSpec.Evaluation evaluation;
        try {
            evaluation = parsed.evaluate(jsonOps.readContext(response));
        // 解析异常（非 JSON、路径语法不支持）也算 NOT_JSON：判定过程本身不该把异常抛给编排器
        } catch (RuntimeException e) {
            return Judgement.failure(JudgeFailType.NOT_JSON,
                    Judgement.Stage.BUSINESS, "响应解析失败：" + e.getMessage());
        }
        if (evaluation.isMatched()) {
            return Judgement.success(evaluation.getActual(), parsed.describe(),
                    "命中判定条件");
        }
        // 未命中分两种：路径根本不存在（FIELD_MISSING，多配错/对方改字段）vs 取到值但不符（VALUE_MISMATCH）
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
