package cn.cj.ifmap.demo.sb3;

import cn.cj.ifmap.core.model.BankCall;
import cn.cj.ifmap.core.spi.BankServiceGateway;
import org.springframework.stereotype.Component;

/**
 * 演示用"资方网关"：真实项目里这里换成 HTTP / SDK 调用。
 *
 * <p>编排器把渲染好的请求报文交给它，它返回原始响应报文（字符串），
 * 判定与出参映射由编排器按配置完成。</p>
 *
 * @author caijun
 */
@Component
public class DemoBankGateway implements BankServiceGateway {

    @Override
    public String exchange(BankCall call) {
        // 演示：回一个"成功 + 业务流水号"的响应
        return "{\"resultCode\":\"0000\",\"resultMsg\":\"成功\",\"data\":{\"applyNo\":\"AP20250101001\"}}";
    }
}
