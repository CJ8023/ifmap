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
package cn.cj.ifmap.core.shadow;

import cn.cj.ifmap.core.model.IfmapResult;
import cn.cj.ifmap.core.spi.ClockProvider;
import cn.cj.ifmap.core.model.IfmapRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 影子运行框架测试：验证「新老双跑 + 差异比对」的核心契约。
 *
 * <p>最关键的一条契约：**影子链路（新引擎）出现任何异常，都不能改变主链路（存量链路）的结果**。</p>
 *
 * @author caijun
 */
class ShadowRunnerTest {

    private final List<ShadowFieldDiff> collected = new ArrayList<ShadowFieldDiff>();
    private final ShadowDiffSink collectingSink = new ShadowDiffSink() {
        @Override
        public void onDiff(ShadowFieldDiff diff) {
            collected.add(diff);
        }
    };

    // ------------------------------------------------------------ 主链路契约

    @Test
    @DisplayName("主链路异常原样抛出，且影子根本不执行（不为一次注定失败的业务多跑一遍）")
    void legacyExceptionPropagatesAndShadowIsNotRun() {
        AtomicInteger shadowCalls = new AtomicInteger();
        ShadowRunner runner = runner(request -> {
            throw new IllegalStateException("存量链路炸了");
        }, r -> {
            shadowCalls.incrementAndGet();
            return ifmapSuccess(mapOf("a", "1"));
        }, ShadowOptions.defaults());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> runner.run(request()));
        assertEquals("存量链路炸了", error.getMessage());
        assertEquals(0, shadowCalls.get());
    }

    @Test
    @DisplayName("影子链路抛异常：只记录，不外泄，主链路结果照常返回")
    void shadowExceptionIsRecordedNotThrown() {
        ShadowRunner runner = runner(
                request -> ShadowOutcome.of(legacyFields("AP1")),
                r -> {
                    throw new IllegalStateException("新引擎炸了");
                },
                ShadowOptions.defaults());

        ShadowRunResult result = runner.run(request());

        assertTrue(result.isRan());
        assertNotNull(result.getLegacy(), "主链路结果必须原样带回");
        assertNull(result.getShadow(), "影子抛异常时没有结果");
        assertTrue(result.getShadowError().contains("新引擎炸了"), result.getShadowError());
        assertFalse(result.isMatched(), "影子异常必须算「未对齐」，否则会静默放过问题");
        assertEquals(1, result.getDiffs().size());
        assertEquals(ShadowFieldDiff.Kind.SHADOW_ERROR, result.getDiffs().get(0).getKind());
        assertEquals(1, collected.size(), "差异必须通知 sink");
    }

    // ------------------------------------------------------------ 比对语义

    @Test
    @DisplayName("两侧一致 → matched=true 且零差异；sink 不被调用")
    void matchedWhenBothSidesAgree() {
        ShadowRunner runner = runner(
                request -> ShadowOutcome.of(legacyFields("AP1")),
                r -> ifmapSuccess(mapOf("applyNo", "AP1", "applyStatus", "S")),
                ShadowOptions.defaults());

        ShadowRunResult result = runner.run(request());

        assertTrue(result.isRan());
        assertTrue(result.isMatched(), describe(result));
        assertTrue(result.getDiffs().isEmpty());
        assertTrue(collected.isEmpty());
        assertFalse(result.isTruncated());
    }

    @Test
    @DisplayName("字段值不一致 → VALUE_MISMATCH，且两侧取值都带上（便于定位）")
    void reportsValueMismatch() {
        ShadowRunner runner = runner(
                request -> ShadowOutcome.of(legacyFields("AP1")),
                r -> ifmapSuccess(mapOf("applyNo", "AP2", "applyStatus", "S")),
                ShadowOptions.defaults());

        ShadowRunResult result = runner.run(request());

        assertFalse(result.isMatched());
        assertEquals(1, result.getDiffs().size());
        ShadowFieldDiff diff = result.getDiffs().get(0);
        assertEquals(ShadowFieldDiff.Kind.VALUE_MISMATCH, diff.getKind());
        assertEquals("applyNo", diff.getKey());
        assertEquals("AP1", diff.getLegacyValue());
        assertEquals("AP2", diff.getShadowValue());
        assertTrue(diff.toString().contains("applyNo"), diff.toString());
    }

    @Test
    @DisplayName("单侧缺字段 → MISSING_IN_SHADOW / MISSING_IN_LEGACY 分别报出（方向不能混）")
    void reportsMissingKeysOnBothSides() {
        ShadowRunner runner = runner(
                request -> ShadowOutcome.of(mapOf("onlyLegacy", "1", "shared", "x")),
                r -> ifmapSuccess(mapOf("onlyShadow", "2", "shared", "x")),
                ShadowOptions.defaults());

        ShadowRunResult result = runner.run(request());

        assertEquals(2, result.getDiffs().size());
        assertEquals(ShadowFieldDiff.Kind.MISSING_IN_SHADOW, result.getDiffs().get(0).getKind());
        assertEquals("onlyLegacy", result.getDiffs().get(0).getKey());
        assertEquals("1", result.getDiffs().get(0).getLegacyValue());
        assertNull(result.getDiffs().get(0).getShadowValue());
        assertEquals(ShadowFieldDiff.Kind.MISSING_IN_LEGACY, result.getDiffs().get(1).getKind());
        assertEquals("onlyShadow", result.getDiffs().get(1).getKey());
        assertNull(result.getDiffs().get(1).getLegacyValue());
        assertEquals("2", result.getDiffs().get(1).getShadowValue());
    }

    @Test
    @DisplayName("成败判定不一致 → SUCCESS_MISMATCH（影子期最严重的差异，优先排在最前）")
    void reportsSuccessMismatch() {
        ShadowRunner runner = runner(
                request -> ShadowOutcome.of(true, "0000", legacyFields("AP1")),
                r -> IfmapResult.builder().success(false).matchedBranch("0000").errorMessage("失败").build(),
                ShadowOptions.defaults());

        ShadowRunResult result = runner.run(request());

        // 判定标识两侧一致 => 差异只剩「成败」这一维 + 影子失败时缺的两个字段
        assertEquals(3, result.getDiffs().size());
        ShadowFieldDiff first = result.getDiffs().get(0);
        assertEquals(ShadowFieldDiff.Kind.SUCCESS_MISMATCH, first.getKind());
        assertEquals("success", first.getKey());
        assertEquals(Boolean.TRUE, first.getLegacyValue());
        assertEquals(Boolean.FALSE, first.getShadowValue());
        assertEquals(ShadowFieldDiff.Kind.MISSING_IN_SHADOW, result.getDiffs().get(1).getKind(),
                "影子失败时没有字段，应报「影子缺字段」而不是把字段差异全抹掉");
        assertEquals("applyNo", result.getDiffs().get(1).getKey());
    }

    @Test
    @DisplayName("判定标识不一致 → RESULT_FLAG_MISMATCH")
    void reportsResultFlagMismatch() {
        ShadowRunner runner = runner(
                request -> ShadowOutcome.of(true, "0000", legacyFields("AP1")),
                r -> IfmapResult.builder().success(true).matchedBranch("9999").data(legacyFields("AP1")).build(),
                ShadowOptions.defaults());

        ShadowRunResult result = runner.run(request());

        assertEquals(1, result.getDiffs().size());
        assertEquals(ShadowFieldDiff.Kind.RESULT_FLAG_MISMATCH, result.getDiffs().get(0).getKind());
        assertEquals("0000", result.getDiffs().get(0).getLegacyValue());
        assertEquals("9999", result.getDiffs().get(0).getShadowValue());
    }

    // ------------------------------------------------------------ 选项

    @Test
    @DisplayName("开关关闭 / 采样为 0 → 不跑影子，主链路照常返回")
    void disabledOrZeroSampleSkipsShadow() {
        AtomicInteger shadowCalls = new AtomicInteger();
        ShadowEngine shadow = r -> {
            shadowCalls.incrementAndGet();
            return ifmapSuccess(legacyFields("AP1"));
        };

        ShadowRunResult disabled = runner(r -> ShadowOutcome.of(legacyFields("AP1")), shadow,
                ShadowOptions.builder().enabled(false).build()).run(request());
        assertFalse(disabled.isRan());
        assertTrue(disabled.getDiffs().isEmpty());
        assertNotNull(disabled.getLegacy());

        ShadowRunResult zero = runner(r -> ShadowOutcome.of(legacyFields("AP1")), shadow,
                ShadowOptions.builder().sampleRate(0.0d).build()).run(request());
        assertFalse(zero.isRan());

        assertEquals(0, shadowCalls.get(), "未采样时影子引擎不应被调用");
    }

    @Test
    @DisplayName("采样率 0.5：跑一部分、跳过一部分（固定种子，结果可复现）")
    void samplesPartOfTraffic() {
        AtomicInteger shadowCalls = new AtomicInteger();
        ShadowRunner runner = runner(
                r -> ShadowOutcome.of(legacyFields("AP1")),
                r -> {
                    shadowCalls.incrementAndGet();
                    return ifmapSuccess(legacyFields("AP1"));
                },
                ShadowOptions.builder().sampleRate(0.5d).build());

        int ran = 0;
        for (int i = 0; i < 400; i++) {
            if (runner.run(request()).isRan()) {
                ran++;
            }
        }

        assertTrue(ran > 0, "采样率 0.5 不可能一次都不跑");
        assertTrue(ran < 400, "采样率 0.5 不可能每次都跑");
        assertEquals(ran, shadowCalls.get());
    }

    @Test
    @DisplayName("ignoreKeys 声明的字段不参与比对（traceId / 时间戳这类天然不同的字段）")
    void ignoreKeysAreExcluded() {
        ShadowRunner runner = runner(
                request -> ShadowOutcome.of(mapOf("traceId", "L-1", "applyNo", "AP1")),
                r -> ifmapSuccess(mapOf("traceId", "S-9", "applyNo", "AP1")),
                ShadowOptions.builder().ignoreKeys("traceId").build());

        assertTrue(runner.run(request()).isMatched());
    }

    @Test
    @DisplayName("数字宽容比对：1 与 1.0 视为相同；关掉宽容即视为不同")
    void numberTolerance() {
        ShadowOutcome legacy = ShadowOutcome.of(mapOf("amount", Integer.valueOf(1)));
        ShadowOutcome shadow = ShadowOutcome.of(mapOf("amount", Double.valueOf(1.0d)));

        assertTrue(new ShadowComparer(ShadowOptions.defaults()).compare(legacy, shadow).isEmpty(),
                "默认按数值比较，1 与 1.0 视为相同（避免因序列化差异产生海量假差异）");
        assertEquals(1, new ShadowComparer(ShadowOptions.builder().numberTolerant(false).build())
                .compare(legacy, shadow).size());
    }

    @Test
    @DisplayName("字符串去空格后比对；关掉即视为不同")
    void trimStrings() {
        ShadowOutcome legacy = ShadowOutcome.of(mapOf("name", "张三"));
        ShadowOutcome shadow = ShadowOutcome.of(mapOf("name", " 张三 "));

        assertTrue(new ShadowComparer(ShadowOptions.defaults()).compare(legacy, shadow).isEmpty());
        assertEquals(1, new ShadowComparer(ShadowOptions.builder().trimStrings(false).build())
                .compare(legacy, shadow).size());
    }

    @Test
    @DisplayName("嵌套 Map / List 里的数字同样按宽容比对")
    void nestedStructuresCompareTolerantly() {
        Map<String, Object> legacy = new LinkedHashMap<String, Object>();
        legacy.put("fee", Integer.valueOf(100));
        List<Object> legacyList = new ArrayList<Object>();
        legacyList.add(Integer.valueOf(1));
        legacyList.add("x");
        Map<String, Object> legacyItem = new LinkedHashMap<String, Object>();
        legacyItem.put("qty", Integer.valueOf(2));
        legacyList.add(legacyItem);
        legacy.put("items", legacyList);

        Map<String, Object> shadow = new LinkedHashMap<String, Object>();
        shadow.put("fee", Double.valueOf(100.0d));
        List<Object> shadowList = new ArrayList<Object>();
        shadowList.add(Double.valueOf(1.0d));
        shadowList.add("x");
        Map<String, Object> shadowItem = new LinkedHashMap<String, Object>();
        shadowItem.put("qty", Double.valueOf(2.0d));
        shadowList.add(shadowItem);
        shadow.put("items", shadowList);

        assertTrue(new ShadowComparer(ShadowOptions.defaults())
                .compare(ShadowOutcome.of(legacy), ShadowOutcome.of(shadow)).isEmpty());
    }

    @Test
    @DisplayName("差异条数上限：超出即截断并置 truncated=true（防止差异列表爆炸）")
    void maxDiffsTruncates() {
        Map<String, Object> legacy = new LinkedHashMap<String, Object>();
        Map<String, Object> shadow = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 5; i++) {
            legacy.put("k" + i, "L" + i);
            shadow.put("k" + i, "S" + i);
        }

        ShadowRunner runner = runner(
                request -> ShadowOutcome.of(legacy),
                r -> ifmapSuccess(shadow),
                ShadowOptions.builder().maxDiffs(2).build());

        ShadowRunResult result = runner.run(request());

        assertEquals(2, result.getDiffs().size());
        assertTrue(result.isTruncated());
    }

    @Test
    @DisplayName("差异顺序稳定：先成败/判定，再按字段名升序")
    void diffsAreDeterministic() {
        Map<String, Object> legacy = new LinkedHashMap<String, Object>();
        legacy.put("zeta", "1");
        legacy.put("alpha", "2");
        Map<String, Object> shadow = new LinkedHashMap<String, Object>();
        shadow.put("zeta", "9");
        shadow.put("alpha", "8");

        ShadowRunResult result = runner(
                request -> ShadowOutcome.of(legacy),
                r -> ifmapSuccess(shadow),
                ShadowOptions.defaults()).run(request());

        assertEquals(Arrays.asList("alpha", "zeta"), keys(result));
    }

    // ------------------------------------------------------------ sink

    @Test
    @DisplayName("sink 抛异常不影响结果（影子期连日志组件故障都不能打断主链路）")
    void sinkFailureDoesNotBreak() {
        ShadowRunner runner = new ShadowRunner(
                r -> ifmapSuccess(legacyFields("AP2")),
                request -> ShadowOutcome.of(legacyFields("AP1")),
                ShadowExtractor.defaults(),
                diff -> {
                    throw new IllegalStateException("sink 炸了");
                },
                ShadowOptions.defaults());

        ShadowRunResult result = runner.run(request());

        assertEquals(1, result.getDiffs().size());
        assertFalse(result.isMatched());
    }

    @Test
    @DisplayName("noop sink 可用；logging sink 可用（默认实现不抛异常）")
    void builtInSinksAreUsable() {
        ShadowDiffSink.noop().onDiff(ShadowFieldDiff.valueMismatch("k", "a", "b"));
        ShadowDiffSink.logging().onDiff(ShadowFieldDiff.valueMismatch("k", "a", "b"));
    }

    // ------------------------------------------------------------ 请求与耗时

    @Test
    @DisplayName("ShadowRequest → IfmapRequest：租户/业务号/参数原样透传；缺省字段不炸")
    void requestMapsToIfmapRequest() {
        IfmapRequest ifmapRequest = ShadowRequest.builder()
                .tenantId("T001").bizId("B1").requestId("R1").operatorId("u1")
                .interfaceNo("IF_A").busiNode("GP81")
                .params(mapOf("orgCode", "12"))
                .build()
                .toIfmapRequest();

        assertEquals("T001", ifmapRequest.getTenantId());
        assertEquals("B1", ifmapRequest.getBizId());
        assertEquals("R1", ifmapRequest.getRequestId());
        assertEquals("u1", ifmapRequest.getOperatorId());
        assertEquals("12", ifmapRequest.getPayload().get("orgCode"));

        IfmapRequest empty = ShadowRequest.builder().interfaceNo("IF_A").build().toIfmapRequest();
        assertNotNull(empty.getPayload());
        assertTrue(empty.getPayload().isEmpty());
    }

    @Test
    @DisplayName("影子耗时被记录（用于评估切换后性能影响）")
    void shadowElapsedIsRecorded() {
        long[] now = {1_000L};
        ShadowRunner runner = new ShadowRunner(
                r -> {
                    now[0] += 37L;
                    return ifmapSuccess(legacyFields("AP1"));
                },
                request -> ShadowOutcome.of(legacyFields("AP1")),
                ShadowExtractor.defaults(),
                ShadowDiffSink.noop(),
                ShadowOptions.defaults(),
                new ClockProvider() {
                    @Override
                    public long currentTimeMillis() {
                        return now[0];
                    }
                });

        assertEquals(37L, runner.run(request()).getShadowElapsedMs());
    }

    // ------------------------------------------------------------ 默认抽取器

    @Test
    @DisplayName("默认抽取器：IfmapResult 的 success / matchedBranch / data / errorMessage 全部映射到比对字段")
    void defaultExtractorMapsIfmapResult() {
        ShadowOutcome outcome = ShadowExtractor.defaults().extract(IfmapResult.builder()
                .success(false).matchedBranch("0000").errorMessage("超时")
                .put("applyNo", "AP1").build());

        assertFalse(outcome.isSuccess());
        assertEquals("0000", outcome.getResultFlag());
        assertEquals("超时", outcome.getMessage());
        assertEquals("AP1", outcome.getFields().get("applyNo"));
    }

    @Test
    @DisplayName("ShadowOutcome：fields 必须是不可变副本（比对结果不能反过来影响宿主数据）")
    void outcomeFieldsAreImmutable() {
        Map<String, Object> source = mapOf("a", "1");
        ShadowOutcome outcome = ShadowOutcome.of(source);
        source.put("b", "2");

        assertEquals(1, outcome.getFields().size(), "应持有副本，外部改动不得影响结果");
        assertThrows(UnsupportedOperationException.class, () -> outcome.getFields().put("c", "3"));
    }

    @Test
    @DisplayName("ShadowRunResult：diffs 不可变")
    void runResultDiffsAreImmutable() {
        ShadowRunResult result = runner(
                request -> ShadowOutcome.of(legacyFields("AP1")),
                r -> ifmapSuccess(mapOf("applyNo", "AP2")),
                ShadowOptions.defaults()).run(request());

        assertThrows(UnsupportedOperationException.class,
                () -> result.getDiffs().add(ShadowFieldDiff.valueMismatch("x", "1", "2")));
    }

    // ------------------------------------------------------------ 工具

    private ShadowRunner runner(ShadowTarget legacy, ShadowEngine shadow, ShadowOptions options) {
        return new ShadowRunner(shadow, legacy, ShadowExtractor.defaults(), collectingSink, options);
    }

    private ShadowRequest request() {
        return ShadowRequest.builder()
                .tenantId("T001").bizId("B1").requestId("R1").operatorId("u1")
                .interfaceNo("IF_A").busiNode("GP81")
                .params(mapOf("orgCode", "12"))
                .build();
    }

    private static Map<String, Object> legacyFields(String applyNo) {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("applyNo", applyNo);
        fields.put("applyStatus", "S");
        return fields;
    }

    private static IfmapResult ifmapSuccess(Map<String, Object> data) {
        return IfmapResult.builder().success(true).data(data).build();
    }

    private static List<String> keys(ShadowRunResult result) {
        List<String> keys = new ArrayList<String>();
        for (ShadowFieldDiff diff : result.getDiffs()) {
            keys.add(diff.getKey());
        }
        return keys;
    }

    private static String describe(ShadowRunResult result) {
        return "差异：" + result.getDiffs() + " 影子异常：" + result.getShadowError();
    }

    private static Map<String, Object> mapOf(String k1, Object v1) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(k1, v1);
        return map;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = mapOf(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
