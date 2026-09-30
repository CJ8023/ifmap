package cn.cj.ifmap.core.spi;

/**
 * 默认时间源：系统时钟。
 *
 * @author caijun
 */
public class SystemClockProvider implements ClockProvider {

    /** 共享实例（无状态，可安全复用）。 */
    public static final SystemClockProvider INSTANCE = new SystemClockProvider();

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
