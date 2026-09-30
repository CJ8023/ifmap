package cn.cj.ifmap.core.json;

/**
 * 一次解析、多次取数的读取上下文。
 *
 * <p>{@link #read(String, boolean)} 必须支持完整 JsonPath 语义，
 * 包括通配符 {@code *}、递归下降 {@code ..}、过滤器 {@code [?(@.x == 'y')]}、
 * 切片 {@code [0:2]} 等，以保持与存量引擎的路径语义一致。</p>
 *
 * @author caijun
 */
public interface JsonReadContext {

    /**
     * 按路径取值。
     *
     * @param path       JsonPath 表达式
     * @param leafToNull 通配路径中缺失的叶子是否补 null（保证列表等长、下标对齐）
     * @return JDK 原生类型；路径不存在时返回 null
     */
    Object read(String path, boolean leafToNull);

    /**
     * 按路径取值（叶子缺失不补 null）。
     */
    Object read(String path);
}
