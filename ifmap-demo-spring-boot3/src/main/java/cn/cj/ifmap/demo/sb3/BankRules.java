package cn.cj.ifmap.demo.sb3;

import cn.cj.ifmap.core.rule.IfmapRule;
import org.springframework.stereotype.Component;

/**
 * 宿主机自定义规则：声明成 Spring bean 即可，starter 会自动收集进 {@code RuleRegistry}
 * （不需要手动 {@code registry.register(...)}）。
 *
 * @author caijun
 */
@Component
public class BankRules {

    /** 资方要求的机构号补零：左补 0 到 6 位。 */
    @IfmapRule(value = "bankOrgNo", desc = "机构号左补零到 6 位", example = "12 -> 000012")
    public String bankOrgNo(String value) {
        if (value == null || value.trim().isEmpty()) {
            return value;
        }
        String trimmed = value.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = trimmed.length(); i < 6; i++) {
            sb.append('0');
        }
        return sb.append(trimmed).toString();
    }
}
