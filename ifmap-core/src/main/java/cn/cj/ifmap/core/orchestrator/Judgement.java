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
