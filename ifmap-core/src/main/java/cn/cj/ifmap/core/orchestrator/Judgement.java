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

/**
 * 结果判定结论（{@code result_flag} + {@code success_value}）。
 *
 * @author caijun
 */
public final class Judgement {

    private final boolean success;
    private final String actualValue;
    private final String expectedValue;
    private final String reason;

    private Judgement(boolean success, String actualValue, String expectedValue, String reason) {
        this.success = success;
        this.actualValue = actualValue;
        this.expectedValue = expectedValue;
        this.reason = reason;
    }

    public static Judgement of(boolean success, String actualValue, String expectedValue, String reason) {
        return new Judgement(success, actualValue, expectedValue, reason);
    }

    public boolean isSuccess() {
        return success;
    }

    /** 响应报文里判定字段的实际值（字符串形态）。 */
    public String getActualValue() {
        return actualValue;
    }

    /** 配置里声明的成功值（原样）。 */
    public String getExpectedValue() {
        return expectedValue;
    }

    /** 判定说明（写日志/排错用）。 */
    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "Judgement{success=" + success + ", actual=" + actualValue + ", expected=" + expectedValue
                + ", reason=" + reason + '}';
    }
}
