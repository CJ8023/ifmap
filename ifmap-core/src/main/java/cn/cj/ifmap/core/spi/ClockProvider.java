package cn.cj.ifmap.core.spi;

/**
 * 时间源 SPI：便于测试与多实例时钟对齐（TS-15 的配套）。
 *
 * @author caijun
 */
public interface ClockProvider {

    /** 当前时间戳（毫秒）。 */
    long currentTimeMillis();
}
