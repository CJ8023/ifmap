package cn.cj.ifmap.core.util;

/**
 * 日志文本工具：截断（合规项之一）。
 *
 * @author caijun
 */
public final class Logs {

    /** 截断标记。 */
    public static final String TRUNCATED = "...truncated";

    private Logs() {
    }

    /**
     * 超过阈值则截断并追加 {@link #TRUNCATED} 标记。
     *
     * @param text      原文，null 原样返回
     * @param threshold 阈值（&le;0 表示不限制）
     */
    public static String truncate(String text, int threshold) {
        if (text == null || threshold <= 0 || text.length() <= threshold) {
            return text;
        }
        return text.substring(0, threshold) + TRUNCATED;
    }
}
