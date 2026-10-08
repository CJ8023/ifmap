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
import cn.cj.ifmap.core.rule.StrictTypes;
import cn.cj.ifmap.core.util.Text;
import cn.cj.ifmap.core.util.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;

/**
 * 内置字符串规则。
 *
 * @author caijun
 */
public class StringRules {

    private static final Logger log = LoggerFactory.getLogger(StringRules.class);

    private final StrictTypes strictTypes;

    /** 默认 {@code WARN}：宿主直接 {@code new StringRules()} 时行为不变。 */
    public StringRules() {
        this(StrictTypes.WARN);
    }

    public StringRules(StrictTypes strictTypes) {
        this.strictTypes = strictTypes == null ? StrictTypes.WARN : strictTypes;
    }

    /**
     * 多值拼接：null 按空串处理。
     *
     * <p>形参是 {@code Object...}，所以 {@code Coercions.canCoerce} 那道「容器不能当字符串」的防线
     * 在这里不起作用（{@code canCoerce(t, Object.class)} 恒为 true），只能在本方法里自己查：
     * 否则 {@code @FUN(concat,$.list)} 会把数组串成 {@code "[01, 02]"} 直接进报文。</p>
     */
    @IfmapRule(value = "concat", allowNullArgs = true,
            desc = "多值拼接（null 按空串）",
            example = "@FUN(concat,$.province,$.city,$.district)")
    public String concat(Object... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            Object part = parts[i];
            if (part instanceof Map || part instanceof Collection || part instanceof Object[]) {
                rejectContainer(part, i + 1);
                sb.append(part);
            } else if (part != null) {
                sb.append(Values.stringify(part));
            }
        }
        return sb.toString();
    }

    /**
     * 容器实参处置（设计 §4.4 U4-A）。
     *
     * <p>规则内拿不到字段路径（{@code RuleContext} 只有租户/接口号），所以日志里给的是
     * 规则名 + 实参下标 + 实际类型；要定位到具体字段，请用 {@code fail} 模式让异常带上调用栈，
     * 或临时打开 DEBUG。</p>
     */
    private void rejectContainer(Object part, int index) {
        String message = "规则 [concat] 第 " + index + " 个实参收到数组/对象（"
                + part.getClass().getName() + "），会被 List/Map.toString() 串成 [01, 02] / {a=1} 直接进报文；"
                + "数组请改用 listJoin 规则或 key 上的 @array 列转行，对象请先取到具体字段";
        if (strictTypes.failFast()) {
            throw new RuleArgumentException(message);
        }
        log.error("{}（strict-types=warn 保持旧输出 {}）", message, part);
    }

    /** 空值兜底：null 或空串时返回默认值。 */
    @IfmapRule(value = "strDefault", allowNullArgs = true,
            desc = "空值兜底（null 或空串时返回默认值）",
            example = "@FUN(strDefault,$.remark,无)")
    public String strDefault(String value, String defaultValue) {
        return Text.isEmpty(value) ? defaultValue : value;
    }

    /** 截断：超长直接截断。 */
    @IfmapRule(value = "strTruncate",
            desc = "按最大长度截断",
            example = "@FUN(strTruncate,$.remark,50)")
    public String strTruncate(String value, Integer maxLength) {
        return strTruncate(value, maxLength, "");
    }

    /** 截断：超长截断并追加后缀（如省略号）。 */
    @IfmapRule(value = "strTruncate",
            desc = "按最大长度截断并追加后缀",
            example = "@FUN(strTruncate,$.remark,50,...)")
    public String strTruncate(String value, Integer maxLength, String suffix) {
        if (value == null) {
            return null;
        }
        int max = maxLength == null || maxLength <= 0 ? value.length() : maxLength;
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + (suffix == null ? "" : suffix);
    }

    /** 脱敏：按类型选择内置规则。 */
    @IfmapRule(value = "strMask",
            desc = "脱敏，type 取 ID_CARD / MOBILE / BANK_CARD / NAME / EMAIL",
            example = "@FUN(strMask,$.idCard,ID_CARD)")
    public String strMask(String value, String type) {
        if (Text.isEmpty(value) || Text.isEmpty(type)) {
            return value;
        }
        String t = type.trim().toUpperCase();
        if ("ID_CARD".equals(t)) {
            return strMask(value, t, 4, 2);
        }
        if ("MOBILE".equals(t)) {
            return strMask(value, t, 3, 2);
        }
        if ("BANK_CARD".equals(t)) {
            return strMask(value, t, 4, 3);
        }
        if ("NAME".equals(t)) {
            return strMask(value, t, 1, 0);
        }
        if ("EMAIL".equals(t)) {
            int at = value.indexOf('@');
            if (at <= 1) {
                return value;
            }
            return value.substring(0, 1) + repeat('*', at - 1) + value.substring(at);
        }
        return value;
    }

    /** 脱敏：保留头尾、中间打星。 */
    @IfmapRule(value = "strMask",
            desc = "脱敏，保留头 keepPrefix 位与尾 keepSuffix 位",
            example = "@FUN(strMask,$.bankCard,BANK_CARD,4,3)")
    public String strMask(String value, String type, Integer keepPrefix, Integer keepSuffix) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 1) {
            return value;
        }
        int prefix = keepPrefix == null || keepPrefix < 0 ? 0 : keepPrefix;
        int suffix = keepSuffix == null || keepSuffix < 0 ? 0 : keepSuffix;
        if (prefix + suffix >= value.length()) {
            return value;
        }
        return value.substring(0, prefix)
                + repeat('*', value.length() - prefix - suffix)
                + value.substring(value.length() - suffix);
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
