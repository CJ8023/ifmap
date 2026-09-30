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

/**
 * 一处影子运行差异。
 *
 * @author caijun
 */
public final class ShadowFieldDiff {

    /** 差异类型。 */
    public enum Kind {

        /** 新引擎执行异常（最需要人工介入的一类）。 */
        SHADOW_ERROR,

        /** 成败判定不一致。 */
        SUCCESS_MISMATCH,

        /** 判定标识（{@code result_flag} 命中值）不一致。 */
        RESULT_FLAG_MISMATCH,

        /** 存量链路有、新引擎没有该字段。 */
        MISSING_IN_SHADOW,

        /** 新引擎有、存量链路没有该字段。 */
        MISSING_IN_LEGACY,

        /** 两侧都有但取值不同。 */
        VALUE_MISMATCH
    }

    /** 成败差异、判定标识差异使用的伪字段名。 */
    public static final String SUCCESS_KEY = "success";
    public static final String RESULT_FLAG_KEY = "resultFlag";

    private final Kind kind;
    private final String key;
    private final Object legacyValue;
    private final Object shadowValue;

    private ShadowFieldDiff(Kind kind, String key, Object legacyValue, Object shadowValue) {
        this.kind = kind;
        this.key = key;
        this.legacyValue = legacyValue;
        this.shadowValue = shadowValue;
    }

    public static ShadowFieldDiff valueMismatch(String key, Object legacyValue, Object shadowValue) {
        return new ShadowFieldDiff(Kind.VALUE_MISMATCH, key, legacyValue, shadowValue);
    }

    public static ShadowFieldDiff missingInShadow(String key, Object legacyValue) {
        return new ShadowFieldDiff(Kind.MISSING_IN_SHADOW, key, legacyValue, null);
    }

    public static ShadowFieldDiff missingInLegacy(String key, Object shadowValue) {
        return new ShadowFieldDiff(Kind.MISSING_IN_LEGACY, key, null, shadowValue);
    }

    public static ShadowFieldDiff successMismatch(boolean legacySuccess, boolean shadowSuccess) {
        return new ShadowFieldDiff(Kind.SUCCESS_MISMATCH, SUCCESS_KEY,
                Boolean.valueOf(legacySuccess), Boolean.valueOf(shadowSuccess));
    }

    public static ShadowFieldDiff resultFlagMismatch(String legacyFlag, String shadowFlag) {
        return new ShadowFieldDiff(Kind.RESULT_FLAG_MISMATCH, RESULT_FLAG_KEY, legacyFlag, shadowFlag);
    }

    public static ShadowFieldDiff shadowError(String message) {
        return new ShadowFieldDiff(Kind.SHADOW_ERROR, "shadow", null, message);
    }

    public Kind getKind() {
        return kind;
    }

    public String getKey() {
        return key;
    }

    public Object getLegacyValue() {
        return legacyValue;
    }

    public Object getShadowValue() {
        return shadowValue;
    }

    @Override
    public String toString() {
        return kind + " " + key + ": legacy=" + legacyValue + ", shadow=" + shadowValue;
    }
}
