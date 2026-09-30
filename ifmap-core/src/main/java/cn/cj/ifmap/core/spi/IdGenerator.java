package cn.cj.ifmap.core.spi;

/**
 * 主键生成 SPI。
 *
 * <p>starter 必须自带生成能力，否则"自带建表"无法闭环（原实现依赖外部服务生成 ID）。
 * 默认实现为 {@link SnowflakeIdGenerator}；若建表时使用 {@code AUTO_INCREMENT}，
 * 可提供返回 {@code null} 的实现让数据库生成。</p>
 *
 * @author caijun
 */
public interface IdGenerator {

    /** 生成下一个主键；返回 {@code null} 表示交给数据库生成。 */
    Long nextId();

    /** 库自增策略：不生成 ID，由数据库负责。 */
    IdGenerator AUTO_INCREMENT = () -> null;
}
