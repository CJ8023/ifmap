/*
 * Copyright 2026 caijun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.cj.ifmap.core.rule.builtin;

import cn.cj.ifmap.core.exception.RuleArgumentException;
import cn.cj.ifmap.core.rule.IfmapRule;
import cn.cj.ifmap.core.util.Text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.List;

/**
 * 内置数值/金额规则。
 *
 * <p>约定：所有金额规则统一返回 {@code String}（报文里的金额就是字符串），
 * 且都必须能显式指定舍入方式，避免各家机构各自约定不一致。</p>
 *
 * @author caijun
 */
public class NumberRules {

    /** 四舍五入到指定小数位（默认 HALF_UP）。 */
    @IfmapRule(value = "numRound",
            desc = "按小数位舍入，默认 HALF_UP",
            example = "@FUN(numRound,$.amount,2)")
    public String numRound(String value, Integer scale) {
        return numRound(value, scale, "HALF_UP");
    }

    /** 按指定舍入方式舍入。 */
    @IfmapRule(value = "numRound",
            desc = "按小数位与舍入方式舍入，rounding 取 HALF_UP / UP / DOWN / HALF_EVEN / CEILING / FLOOR",
            example = "@FUN(numRound,$.amount,2,HALF_UP)")
    public String numRound(String value, Integer scale, String rounding) {
        if (Text.isBlank(value)) {
            return null;
        }
        int s = scale == null ? 2 : scale;
        return Text.toDecimal(value, "numRound 入参").setScale(s, parseRounding(rounding)).toPlainString();
    }

    /** 列表求和。 */
    @IfmapRule(value = "numSum",
            desc = "列表按数值求和",
            example = "@FUN(numSum,$.amounts)")
    public String numSum(List<?> values) {
        BigDecimal sum = BigDecimal.ZERO;
        if (values != null) {
            for (Object item : values) {
                if (item == null) {
                    continue;
                }
                sum = sum.add(Text.toDecimal(item, "numSum 元素"));
            }
        }
        return stripZeros(sum);
    }

    /** 列表求和并舍入。 */
    @IfmapRule(value = "numSum",
            desc = "列表求和并按小数位舍入",
            example = "@FUN(numSum,$.amounts,2,HALF_UP)")
    public String numSum(List<?> values, Integer scale, String rounding) {
        BigDecimal sum = BigDecimal.ZERO;
        if (values != null) {
            for (Object item : values) {
                if (item == null) {
                    continue;
                }
                sum = sum.add(Text.toDecimal(item, "numSum 元素"));
            }
        }
        int s = scale == null ? 2 : scale;
        return sum.setScale(s, parseRounding(rounding)).toPlainString();
    }

    /** 数值加减整数（常用于分页/序号偏移）。 */
    @IfmapRule(value = "numOffset",
            desc = "数值加减整数",
            example = "@FUN(numOffset,$.index,1)")
    public String numOffset(String value, Integer delta) {
        if (Text.isBlank(value)) {
            return null;
        }
        BigDecimal base = Text.toDecimal(value, "numOffset 入参");
        return stripZeros(base.add(BigDecimal.valueOf(delta == null ? 0 : delta)));
    }

    /** 金额格式转换。 */
    @IfmapRule(value = "numFormat",
            desc = "金额格式转换，style 取 PLAIN / STRIP_ZERO / THOUSAND / CENT_TO_YUAN / YUAN_TO_CENT",
            example = "@FUN(numFormat,$.amount,CENT_TO_YUAN)")
    public String numFormat(String value, String style) {
        if (Text.isBlank(value)) {
            return null;
        }
        String s = Text.isBlank(style) ? "PLAIN" : style.trim().toUpperCase();
        BigDecimal decimal = Text.toDecimal(value, "numFormat 入参");
        if ("PLAIN".equals(s)) {
            return decimal.toPlainString();
        }
        if ("STRIP_ZERO".equals(s)) {
            return stripZeros(decimal);
        }
        if ("THOUSAND".equals(s)) {
            DecimalFormat format = new DecimalFormat("#,##0.00");
            format.setRoundingMode(RoundingMode.HALF_UP);
            return format.format(decimal);
        }
        if ("CENT_TO_YUAN".equals(s)) {
            return decimal.movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        if ("YUAN_TO_CENT".equals(s)) {
            return decimal.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString();
        }
        throw new RuleArgumentException("numFormat 的 style [" + style + "] 不支持，"
                + "可用 PLAIN / STRIP_ZERO / THOUSAND / CENT_TO_YUAN / YUAN_TO_CENT");
    }

    private static RoundingMode parseRounding(String rounding) {
        if (Text.isBlank(rounding)) {
            return RoundingMode.HALF_UP;
        }
        try {
            return RoundingMode.valueOf(rounding.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuleArgumentException("舍入方式 [" + rounding + "] 不支持，"
                    + "可用 HALF_UP / UP / DOWN / HALF_EVEN / CEILING / FLOOR");
        }
    }

    private static String stripZeros(BigDecimal decimal) {
        BigDecimal stripped = decimal.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        return stripped.toPlainString();
    }
}
