package cn.cj.ifmap.admin;

/**
 * 管理端约定的审计请求头：操作人 / 请求号（与 ifmap 执行日志口径一致，便于串起"谁改的"）。
 *
 * @author caijun
 */
public final class IfmapAdminHeaders {

    /** 操作人（落库到 {@code add_user_id} / {@code modify_user_id}）。 */
    public static final String OPERATOR_ID = "X-Operator-Id";

    /** 请求号（落库到 {@code add_request_id} / {@code modify_request_id}）。 */
    public static final String REQUEST_ID = "X-Request-Id";

    private IfmapAdminHeaders() {
    }
}
