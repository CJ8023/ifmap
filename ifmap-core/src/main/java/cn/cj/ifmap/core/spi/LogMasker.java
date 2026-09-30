package cn.cj.ifmap.core.spi;

/**
 * 日志脱敏 SPI（P1-12）。
 *
 * <p>入库前调用一次；实现须保证<b>幂等</b>（重复脱敏结果不变）与<b>不抛异常</b>
 * （脱敏失败不允许影响业务，失败时应原样返回并自行记 WARN）。</p>
 *
 * @author caijun
 */
public interface LogMasker {

    /** 返回脱敏后的报文；入参为 null 时返回 null。 */
    String mask(String json);
}
