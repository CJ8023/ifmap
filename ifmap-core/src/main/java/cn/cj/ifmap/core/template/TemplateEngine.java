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
package cn.cj.ifmap.core.template;

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonReadContext;
import cn.cj.ifmap.core.rule.RuleContext;
import cn.cj.ifmap.core.rule.RuleRegistry;
import cn.cj.ifmap.core.rule.StrictTypes;
import cn.cj.ifmap.core.util.Text;
import cn.cj.ifmap.core.util.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模板 DSL 引擎：把「模板 + 源报文」渲染成「目标报文」。
 *
 * <p>支持的语法（与存量引擎对齐）：</p>
 * <ul>
 *   <li>{@code $.path}：JsonPath 取值（数字会统一转成字符串，保持与存量行为一致）</li>
 *   <li>{@code @FUN(规则名, 参数...)}：调用已注册规则，参数本身也可以是表达式</li>
 *   <li>{@code @and@} / {@code @or@} / {@code @concat@} / {@code @append@} / {@code @sum@}：多路取值</li>
 *   <li>key 后缀 {@code @array}：值统一包装成数组；若值是对象且字段都是数组，则<b>按列转行</b>
 *       （{@code {"A":["x","y"],"B":["a","b"]}} -> {@code [{"A":"x","B":"a"},{"A":"y","B":"b"}]}）</li>
 *   <li>key 里的 {@code @array@$.path}：按源数组逐元素展开，<b>每个元素成为子模板的源报文</b></li>
 *   <li>{@code $.seqNo}：流水号</li>
 * </ul>
 *
 * <p>相对存量引擎修正的语义：</p>
 * <ol>
 *   <li>取不到值时按 {@link NullPolicy} 处理，<b>永不</b>回写模板原文；</li>
 *   <li>{@code @or@} 全部落空返回 {@code null}（存量会回落成模板原文）；</li>
 *   <li>规则参数按顶层逗号切分，支持参数里嵌套 {@code @FUN(...)}；</li>
 *   <li>源报文只解析一次。</li>
 * </ol>
 *
 * @author caijun
 */
public final class TemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(TemplateEngine.class);

    private static final DateTimeFormatter SEQ_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final JsonOps jsonOps;
    private final RuleRegistry registry;
    private final NullPolicy nullPolicy;
    private final StrictTypes strictTypes;

    /** 保留 3 参构造（宿主源码兼容），等价于 {@link StrictTypes#WARN}。 */
    public TemplateEngine(JsonOps jsonOps, RuleRegistry registry, NullPolicy nullPolicy) {
        this(jsonOps, registry, nullPolicy, StrictTypes.WARN);
    }

    public TemplateEngine(JsonOps jsonOps, RuleRegistry registry, NullPolicy nullPolicy, StrictTypes strictTypes) {
        if (jsonOps == null) {
            throw new IfmapConfigException("jsonOps 不能为空");
        }
        if (registry == null) {
            throw new IfmapConfigException("ruleRegistry 不能为空");
        }
        this.jsonOps = jsonOps;
        this.registry = registry;
        this.nullPolicy = nullPolicy == null ? NullPolicy.SKIP_FIELD : nullPolicy;
        this.strictTypes = strictTypes == null ? StrictTypes.WARN : strictTypes;
    }

    public StrictTypes getStrictTypes() {
        return strictTypes;
    }

    public JsonOps getJsonOps() {
        return jsonOps;
    }

    public RuleRegistry getRegistry() {
        return registry;
    }

    public NullPolicy getNullPolicy() {
        return nullPolicy;
    }

    /** 渲染：模板 JSON 文本 + 源报文 JSON 文本 -> JSON 文本。 */
    public String renderToJson(String templateJson, String sourceJson, RuleContext context) {
        Object result = render(templateJson, sourceJson, context);
        return jsonOps.toJson(result);
    }

    /** 渲染：模板 JSON 文本 + 源报文 JSON 文本 -> JDK 结构。 */
    public Object render(String templateJson, String sourceJson, RuleContext context) {
        TemplateScanner.requireNonEmpty(templateJson, "模板");
        Object template = jsonOps.parse(templateJson);
        if (template == null) {
            throw new IfmapConfigException("模板解析结果为空，请检查模板 JSON 是否合法");
        }
        JsonReadContext source = jsonOps.readContext(sourceJson);
        return render(template, source, context);
    }

    /** 渲染：已解析模板 + 已建立的读取上下文。 */
    public Object render(Object template, JsonReadContext source, RuleContext context) {
        RuleContext ctx = context == null ? RuleContext.empty() : context;
        return build(template, source, ctx, "$");
    }

    @SuppressWarnings("unchecked")
    private Object build(Object node, JsonReadContext source, RuleContext ctx, String path) {
        if (node instanceof Map) {
            return buildObject((Map<String, Object>) node, source, ctx, path);
        }
        if (node instanceof List) {
            return buildList((List<Object>) node, source, ctx, path);
        }
        if (node instanceof String) {
            return resolve((String) node, source, ctx, path);
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildObject(Map<String, Object> template, JsonReadContext source,
                                           RuleContext ctx, String path) {
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String fieldPath = path + "." + key;
            int arraysIdx = key.indexOf(TemplateConstants.KEY_ARRAYS);
            if (arraysIdx >= 0) {
                String resultKey = key.substring(0, arraysIdx);
                String sourcePath = key.substring(arraysIdx + TemplateConstants.KEY_ARRAYS.length()).trim();
                res.put(resultKey, expandArray(value, sourcePath, source, ctx, fieldPath));
            } else if (value instanceof Map) {
                Map<String, Object> sub = buildObject((Map<String, Object>) value, source, ctx, fieldPath);
                putWithArraySuffix(key, sub, res, fieldPath);
            } else if (value instanceof List) {
                List<Object> sub = buildList((List<Object>) value, source, ctx, fieldPath);
                putWithArraySuffix(key, sub, res, fieldPath);
            } else if (value instanceof String) {
                String valueKey = (String) value;
                if (isKey(valueKey)) {
                    Object resolved = resolve(valueKey, source, ctx, fieldPath);
                    if (resolved == null) {
                        applyNullPolicy(key, valueKey, res, fieldPath);
                    } else {
                        putWithArraySuffix(key, resolved, res, fieldPath);
                    }
                } else {
                    // 模板里的字面量（常量字段）：原样输出
                    putWithArraySuffix(key, valueKey, res, fieldPath);
                }
            } else if (value == null) {
                applyNullPolicy(key, "null", res, fieldPath);
            } else {
                putWithArraySuffix(key, value, res, fieldPath);
            }
        }
        return res;
    }

    @SuppressWarnings("unchecked")
    private List<Object> buildList(List<Object> template, JsonReadContext source, RuleContext ctx, String path) {
        List<Object> res = new ArrayList<Object>(template.size());
        int i = 0;
        for (Object item : template) {
            String itemPath = path + "[" + i + "]";
            if (item instanceof Map) {
                res.add(buildObject((Map<String, Object>) item, source, ctx, itemPath));
            } else if (item instanceof String) {
                String valueKey = (String) item;
                if (isKey(valueKey)) {
                    Object resolved = resolve(valueKey, source, ctx, itemPath);
                    if (resolved != null) {
                        res.add(resolved);
                    } else if (nullPolicy == NullPolicy.FAIL) {
                        throw new IfmapConfigException("模板字段 [" + itemPath + "] 取值为空（表达式：" + valueKey + "）");
                    } else {
                        log.warn("模板字段 [{}] 表达式 [{}] 取值为空，按 {} 跳过该数组元素", itemPath, valueKey, nullPolicy);
                    }
                } else {
                    res.add(valueKey);
                }
            } else {
                res.add(item);
            }
            i++;
        }
        return res;
    }

    /**
     * key 以 {@code @array} 结尾时：去掉后缀并把值统一包装成数组。
     *
     * <p>对象值按列转行（与存量引擎对齐）：字段值若是数组，按下标逐行取值；
     * 非数组字段作为常量复制到每一行；取不到的元素（通配缺失、数组偏短）跳过该字段。</p>
     */
    private void putWithArraySuffix(String key, Object value, Map<String, Object> res, String fieldPath) {
        if (!key.endsWith(TemplateConstants.KEY_ARRAY)) {
            res.put(key, value);
            return;
        }
        String resultKey = key.substring(0, key.length() - TemplateConstants.KEY_ARRAY.length());
        res.put(resultKey, toArray(value, fieldPath));
    }

    /** 值转数组：List 原样、Map 列转行、其他包成单元素数组。 */
    @SuppressWarnings("unchecked")
    private List<Object> toArray(Object value, String fieldPath) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        List<Object> out = new ArrayList<Object>();
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            int length = 1;
            for (Object item : map.values()) {
                if (item instanceof List && ((List<?>) item).size() > length) {
                    length = ((List<?>) item).size();
                }
            }
            for (int i = 0; i < length; i++) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    Object item = entry.getValue();
                    if (item instanceof List) {
                        List<?> list = (List<?>) item;
                        if (list.size() > i && list.get(i) != null) {
                            row.put(entry.getKey(), list.get(i));
                        }
                    } else if (item != null) {
                        row.put(entry.getKey(), item);
                    }
                }
                if (!row.isEmpty()) {
                    out.add(row);
                }
            }
            if (out.isEmpty()) {
                log.warn("模板字段 [{}] 的 @array 对象值没有任何可用元素，输出空数组", fieldPath);
            }
            return out;
        }
        out.add(value);
        return out;
    }

    /** {@code @array@$.path}：按源数组逐元素渲染子模板，每个元素成为子模板的源报文。 */
    @SuppressWarnings("unchecked")
    private List<Object> expandArray(Object templateValue, String sourcePath, JsonReadContext source,
                                     RuleContext ctx, String fieldPath) {
        List<Object> out = new ArrayList<Object>();
        Object raw = Text.isEmpty(sourcePath) ? null : source.read(sourcePath, false);
        if (raw == null) {
            log.warn("模板字段 [{}] 的 @array@ 数据源 [{}] 不存在，输出空数组", fieldPath, sourcePath);
            return out;
        }
        List<Object> items = Text.asList(raw);
        int i = 0;
        for (Object item : items) {
            String itemPath = fieldPath + "[" + i + "]";
            JsonReadContext itemSource = jsonOps.readContext(item == null ? "{}" : jsonOps.toJson(item));
            if (templateValue instanceof Map) {
                out.add(buildObject((Map<String, Object>) templateValue, itemSource, ctx, itemPath));
            } else if (templateValue instanceof String) {
                out.add(resolve((String) templateValue, itemSource, ctx, itemPath));
            } else {
                out.add(templateValue);
            }
            i++;
        }
        return out;
    }

    private void applyNullPolicy(String key, String valueKey, Map<String, Object> res, String fieldPath) {
        switch (nullPolicy) {
            case EMPTY_STRING:
                putWithArraySuffix(key, "", res, fieldPath);
                return;
            case FAIL:
                throw new IfmapConfigException("模板字段 [" + fieldPath + "] 取值为空（表达式：" + valueKey + "）");
            case SKIP_FIELD:
            default:
                log.warn("模板字段 [{}] 表达式 [{}] 取值为空，按 SKIP_FIELD 省略该字段", fieldPath, valueKey);
        }
    }

    /** 是否为模板变量（而非字面量）。 */
    public static boolean isKey(String valueKey) {
        return DslExpressions.isExpression(valueKey);
    }

    private static boolean isFun(String valueKey) {
        return DslExpressions.isFun(valueKey);
    }

    /** 解析单个取值表达式。 */
    public Object resolve(String valueKey, JsonReadContext source, RuleContext ctx, String fieldPath) {
        if (isFun(valueKey)) {
            return invokeFun(valueKey, source, ctx, fieldPath);
        }
        if (DslExpressions.hasInfix(valueKey, TemplateConstants.SEPARATOR_AND)) {
            List<Object> merged = new ArrayList<Object>();
            for (String part : DslExpressions.infixOperands(valueKey, TemplateConstants.SEPARATOR_AND)) {
                Object value = resolve(part, source, ctx, fieldPath);
                if (value instanceof List) {
                    merged.addAll((List<?>) value);
                } else if (value != null) {
                    merged.add(value);
                }
            }
            return merged;
        }
        if (DslExpressions.hasInfix(valueKey, TemplateConstants.SEPARATOR_CONCAT)) {
            return joinResolved(valueKey, TemplateConstants.SEPARATOR_CONCAT, source, ctx, fieldPath, ",");
        }
        if (DslExpressions.hasInfix(valueKey, TemplateConstants.SEPARATOR_APPEND)) {
            return joinResolved(valueKey, TemplateConstants.SEPARATOR_APPEND, source, ctx, fieldPath, "");
        }
        if (DslExpressions.hasInfix(valueKey, TemplateConstants.SEPARATOR_OR)) {
            for (String part : DslExpressions.infixOperands(valueKey, TemplateConstants.SEPARATOR_OR)) {
                Object value = resolve(part, source, ctx, fieldPath);
                if (value != null) {
                    return value;
                }
            }
            // 与存量引擎的差异：全部落空返回 null（存量会回落成模板原文），交由 NullPolicy 处理
            log.warn("模板字段 [{}] 的 @or@ 表达式 [{}] 全部分支取值为空", fieldPath, valueKey);
            return null;
        }
        if (DslExpressions.hasInfix(valueKey, TemplateConstants.SEPARATOR_SUM)) {
            BigDecimal sum = BigDecimal.ZERO;
            for (String part : DslExpressions.infixOperands(valueKey, TemplateConstants.SEPARATOR_SUM)) {
                Object value = resolve(part, source, ctx, fieldPath);
                if (value instanceof List) {
                    for (Object item : (List<?>) value) {
                        sum = sum.add(toDecimal(item, fieldPath));
                    }
                } else {
                    sum = sum.add(toDecimal(value, fieldPath));
                }
            }
            // 金额统一返回字符串（与 numSum / NumberRules 口径一致）：BigDecimal 直接进报文
            // 会是 JSON number，而源文字的位数（10.00 的两位）只有 String 能携带
            return Values.stringify(sum);
        }
        if (TemplateConstants.PATH_SEQ_NO.equals(valueKey)) {
            return seqNo();
        }
        if (valueKey.startsWith(TemplateConstants.PATH_PREFIX)) {
            return normalize(source.read(valueKey, valueKey.indexOf('*') >= 0));
        }
        return valueKey;
    }

    private Object joinResolved(String valueKey, String separator, JsonReadContext source,
                                RuleContext ctx, String fieldPath, String joiner) {
        List<Object> values = new ArrayList<Object>();
        for (String part : DslExpressions.infixOperands(valueKey, separator)) {
            Object value = resolve(part, source, ctx, fieldPath);
            if (value instanceof List) {
                values.addAll((List<?>) value);
            } else if (value != null) {
                values.add(value);
            }
        }
        return Text.join(values, joiner);
    }

    /** 调用规则。 */
    private Object invokeFun(String valueKey, JsonReadContext source, RuleContext ctx, String fieldPath) {
        String inner = DslExpressions.inner(valueKey);
        if (Text.isBlank(inner)) {
            log.warn("模板字段 [{}] 出现空函数调用 [{}]，按取值为空处理", fieldPath, valueKey);
            return null;
        }
        String ruleName = DslExpressions.funName(valueKey);
        if (Text.isEmpty(ruleName)) {
            log.warn("模板字段 [{}] 的函数名称为空 [{}]，按取值为空处理", fieldPath, valueKey);
            return null;
        }
        // 规则不存在 -> 立刻抛 RuleNotFoundException，不做静默降级
        boolean allowNullArgs = registry.descriptor(ruleName).allowsNullArgs();
        List<String> args = DslExpressions.funArgs(valueKey);
        Object[] resolved = new Object[args.size()];
        for (int i = 0; i < args.size(); i++) {
            String expr = args.get(i);
            Object value = expr.length() == 0 ? "" : resolve(expr, source, ctx, fieldPath);
            if (value == null && !allowNullArgs) {
                log.warn("模板字段 [{}] 调用规则 [{}] 时第 {} 个参数 [{}] 取值为空，按 {} 处理",
                        fieldPath, ruleName, i + 1, expr, nullPolicy);
                return null;
            }
            resolved[i] = value;
        }
        return registry.invoke(ruleName, ctx, resolved);
    }

    /** 按顶层逗号切分函数参数（支持参数里嵌套 @FUN(...)）。 */
    static List<String> splitTopLevel(String inner) {
        return DslExpressions.splitTopLevel(inner);
    }

    /**
     * 取到的值归一化为「可进报文」的形态：
     * 标量 -> 文本（{@link Values#stringify}，小数保尾零、不出科学计数法）；
     * 数组 / 对象 -> 递归规整但<b>保持 JSON 层类型</b>（数组还是数组）。
     *
     * <p>null 必须原样返回 null：上游靠它区分「取不到值」并走 {@link NullPolicy}，
     * 归一成空串会让 SKIP_FIELD 失效。</p>
     */
    private Object normalize(Object value) {
        if (value instanceof Map || value instanceof List || value instanceof Object[]) {
            return Values.plainify(value);
        }
        if (value == null) {
            return null;
        }
        return Values.stringify(value);
    }

    /**
     * {@code @sum@} 的操作数转数值。
     *
     * <p>两条返回 0 的路径语义完全不同，不能混为一谈（设计 §4.4 U4-B）：</p>
     * <ul>
     *   <li><b>缺失</b>（null / 空串，如 {@code $.notExist}）：静默 0，两种模式都不报 ——
     *       「缺失即 0」是求和表达式的常规用法，FAIL 下报错会打断正常业务；</li>
     *   <li><b>非数值</b>（{@code "abc"}、数组、对象）：WARN 保持 0 并把日志升为 ERROR，
     *       FAIL 直接抛异常（这是本来就该报的错，只是之前只打了 WARN）。</li>
     * </ul>
     */
    private BigDecimal toDecimal(Object value, String fieldPath) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return Values.toPlainDecimal((Number) value);
        }
        if (value instanceof Map || value instanceof Collection || value instanceof Object[]) {
            return nonNumeric(value, fieldPath);
        }
        String s = String.valueOf(value).trim();
        if (s.length() == 0) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return nonNumeric(value, fieldPath);
        }
    }

    /** 非数值操作数的处置：WARN 保持 0（ERROR 日志），FAIL 抛异常。 */
    private BigDecimal nonNumeric(Object value, String fieldPath) {
        String message = "模板字段 [" + fieldPath + "] 的 @sum@ 操作数不是数值：" + value
                + "（" + value.getClass().getName() + "）；请确认路径取到的是数字字段，"
                + "或者用 @FUN(numSum,...) 显式处理集合";
        if (strictTypes.failFast()) {
            throw new IfmapConfigException(message);
        }
        log.error("{}（strict-types=warn 按 0 处理）", message);
        return BigDecimal.ZERO;
    }

    /** 流水号：yyyyMMddHHmmssSSS + 3 位随机数（与存量实现一致）。 */
    public static String seqNo() {
        return SEQ_NO_FORMATTER.format(LocalDateTime.now())
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }
}
