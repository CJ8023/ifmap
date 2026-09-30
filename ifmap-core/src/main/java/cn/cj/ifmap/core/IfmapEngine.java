package cn.cj.ifmap.core;

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonOpsHolder;
import cn.cj.ifmap.core.rule.RuleContext;
import cn.cj.ifmap.core.rule.RuleDescriptor;
import cn.cj.ifmap.core.rule.RuleRegistry;
import cn.cj.ifmap.core.rule.builtin.BuiltinRules;
import cn.cj.ifmap.core.template.NullPolicy;
import cn.cj.ifmap.core.template.TemplateEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * ifmap 门面：宿主只需要这一个类。
 *
 * <pre>{@code
 * IfmapEngine engine = IfmapEngine.createDefault();
 * String target = engine.render(templateJson, sourceJson);
 * }</pre>
 *
 * @author caijun
 */
public final class IfmapEngine {

    private final JsonOps jsonOps;
    private final RuleRegistry registry;
    private final TemplateEngine templateEngine;

    private IfmapEngine(Builder builder) {
        this.jsonOps = builder.jsonOps == null ? JsonOpsHolder.get() : builder.jsonOps;
        this.registry = builder.registry == null ? new RuleRegistry() : builder.registry;
        if (builder.builtins) {
            BuiltinRules.registerTo(this.registry);
        }
        if (builder.additionalBeans != null) {
            for (Object bean : builder.additionalBeans) {
                this.registry.register(bean);
            }
        }
        this.templateEngine = new TemplateEngine(this.jsonOps, this.registry, builder.nullPolicy);
    }

    /** 默认引擎：自动发现的 JsonOps + 内置规则 + {@link NullPolicy#SKIP_FIELD}。 */
    public static IfmapEngine createDefault() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 渲染为 JSON 文本（默认上下文）。 */
    public String render(String templateJson, String sourceJson) {
        return render(templateJson, sourceJson, RuleContext.empty());
    }

    /** 渲染为 JSON 文本。 */
    public String render(String templateJson, String sourceJson, RuleContext context) {
        return templateEngine.renderToJson(templateJson, sourceJson, context);
    }

    /** 渲染为 JDK 结构。 */
    public Object renderToObject(String templateJson, String sourceJson, RuleContext context) {
        return templateEngine.render(templateJson, sourceJson, context);
    }

    /** 渲染为 Map（模板根必须是对象）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> renderToMap(String templateJson, String sourceJson, RuleContext context) {
        Object result = renderToObject(templateJson, sourceJson, context);
        if (!(result instanceof Map)) {
            throw new IfmapConfigException("模板根节点必须是 JSON 对象，实际为 "
                    + (result == null ? "null" : result.getClass().getName()));
        }
        return (Map<String, Object>) result;
    }

    /** 启动期校验单个模板：引用到未注册规则即抛 {@code StartupValidationException}。 */
    public void validateTemplate(String templateId, String templateJson) {
        registry.assertRulesExist(Collections.singletonMap(templateId, templateJson));
    }

    /** 启动期校验一批模板。 */
    public void validateTemplates(Map<String, String> templates) {
        registry.assertRulesExist(templates);
    }

    /** 生成规则清单（Markdown 表格），可直接贴进文档。 */
    public String describeRules() {
        List<RuleDescriptor> descriptors = new ArrayList<RuleDescriptor>(registry.getDescriptors());
        Collections.sort(descriptors, new Comparator<RuleDescriptor>() {
            @Override
            public int compare(RuleDescriptor a, RuleDescriptor b) {
                int c = a.getName().compareTo(b.getName());
                return c != 0 ? c : a.getSignature().compareTo(b.getSignature());
            }
        });
        StringBuilder sb = new StringBuilder();
        sb.append("| 规则名 | 签名 | 允许 null 入参 | 说明 | 示例 |\n");
        sb.append("| --- | --- | --- | --- | --- |\n");
        for (RuleDescriptor d : descriptors) {
            sb.append("| `").append(d.getName()).append("` | `").append(d.getSignature())
                    .append("` | ").append(d.allowsNullArgs() ? "是" : "否")
                    .append(" | ").append(escape(d.getDesc()))
                    .append(" | `").append(escape(d.getExample())).append("` |\n");
        }
        return sb.toString();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("|", "\\|");
    }

    public JsonOps getJsonOps() {
        return jsonOps;
    }

    public RuleRegistry getRegistry() {
        return registry;
    }

    public TemplateEngine getTemplateEngine() {
        return templateEngine;
    }

    /** 引擎构造器。 */
    public static final class Builder {

        private JsonOps jsonOps;
        private RuleRegistry registry;
        private NullPolicy nullPolicy = NullPolicy.SKIP_FIELD;
        private boolean builtins = true;
        private Object[] additionalBeans;

        /** 指定 JsonOps；不指定则用 SPI 自动发现。 */
        public Builder jsonOps(JsonOps jsonOps) {
            this.jsonOps = jsonOps;
            return this;
        }

        /** 指定规则注册表；不指定则新建。 */
        public Builder registry(RuleRegistry registry) {
            this.registry = registry;
            return this;
        }

        /** 是否注册内置规则，默认 true。 */
        public Builder builtins(boolean enabled) {
            this.builtins = enabled;
            return this;
        }

        /** 追加注册业务规则实例（在注册表已有的基础上追加，重载将被允许）。 */
        public Builder register(Object... beans) {
            this.additionalBeans = beans;
            return this;
        }

        public Builder nullPolicy(NullPolicy nullPolicy) {
            this.nullPolicy = nullPolicy;
            return this;
        }

        public IfmapEngine build() {
            return new IfmapEngine(this);
        }
    }
}
