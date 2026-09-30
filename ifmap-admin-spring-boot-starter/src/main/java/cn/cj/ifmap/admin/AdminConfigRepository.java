package cn.cj.ifmap.admin;

import cn.cj.ifmap.jdbc.JdbcConfigRepository;

/**
 * 管理端专用仓储句柄：持有一个<b>未被缓存包装、不被引擎过滤</b>的 {@link JdbcConfigRepository}。
 *
 * <p>为什么要有这个"壳"：{@code JdbcConfigRepository} 本身是 {@code ConfigRepository} 的实现，
 * 若直接把它注册成 bean，宿主机里"按 {@code ConfigRepository} 类型注入"的地方（如执行日志落库）
 * 就会看到两个候选而抛 {@code NoUniqueBeanDefinitionException}。让管理端仓储以一个不同
 * 类型的 bean 存在，既避免歧义，又明确表达"这是管理端视角的库表直读"。</p>
 *
 * <p>管理端需要它而不是引擎的 {@code ConfigRepository}：后者可能被 {@code CachingConfigRepository}
 * 包装过（读的是缓存、且只返回启用未删除的行），而管理端必须看到库里的真相（含停用/已删除）。</p>
 *
 * @author caijun
 */
public final class AdminConfigRepository {

    private final JdbcConfigRepository delegate;

    public AdminConfigRepository(JdbcConfigRepository delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为 null");
        }
        this.delegate = delegate;
    }

    /** 取底层仓储（未缓存、未过滤）。 */
    public JdbcConfigRepository get() {
        return delegate;
    }

    @Override
    public String toString() {
        return "AdminConfigRepository[" + delegate.tables().configTable() + "]";
    }
}
