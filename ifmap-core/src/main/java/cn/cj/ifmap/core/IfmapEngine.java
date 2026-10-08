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
package cn.cj.ifmap.core;

import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.exception.StartupValidationException;
import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonOpsHolder;
import cn.cj.ifmap.core.rule.RuleContext;
import cn.cj.ifmap.core.rule.RuleDescriptor;
import cn.cj.ifmap.core.rule.RuleRegistry;
import cn.cj.ifmap.core.rule.StrictTypes;
import cn.cj.ifmap.core.rule.builtin.BuiltinRules;
import cn.cj.ifmap.core.template.NullPolicy;
import cn.cj.ifmap.core.template.TemplateEngine;
import cn.cj.ifmap.core.template.TemplateScanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            BuiltinRules.registerTo(this.registry, builder.strictTypes);
        }
        if (builder.additionalBeans != null) {
            for (Object bean : builder.additionalBeans) {
                this.registry.register(bean);
            }
        }
        this.templateEngine = new TemplateEngine(this.jsonOps, this.registry, builder.nullPolicy,
                builder.strictTypes);
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

    /**
     * 启动期校验单个模板：引用到未注册规则、或表达式是错语法，都抛 {@code StartupValidationException}。
     *
     * <p>表达式错语法不受 {@code strict-types} 控制：{@code @sum@$.a,$.b} 这类写法在运行期
     * 只会静默求和成 0，{@code @FUN(} 未闭合则会把整串原样写进报文 —— 错的东西不该能存进去。</p>
     */
    public void validateTemplate(String templateId, String templateJson) {
        Map<String, Set<String>> missing = Collections.emptyMap();
        try {
            registry.assertRulesExist(Collections.singletonMap(templateId, templateJson));
        } catch (StartupValidationException e) {
            missing = e.getMissingRules();
        }
        List<String> problems = TemplateScanner.expressionProblems(templateJson);
        if (missing.isEmpty() && problems.isEmpty()) {
            return;
        }
        throw new StartupValidationException(describe(templateId, missing, problems), missing, problems);
    }

    /** 启动期校验一批模板。 */
    public void validateTemplates(Map<String, String> templates) {
        Map<String, Set<String>> missing = Collections.emptyMap();
        try {
            registry.assertRulesExist(templates);
        } catch (StartupValidationException e) {
            missing = e.getMissingRules();
        }
        List<String> problems = new ArrayList<String>();
        if (templates != null) {
            for (Map.Entry<String, String> entry : templates.entrySet()) {
                for (String problem : TemplateScanner.expressionProblems(entry.getValue())) {
                    problems.add(entry.getKey() + "：" + problem);
                }
            }
        }
        if (missing.isEmpty() && problems.isEmpty()) {
            return;
        }
        throw new StartupValidationException("模板校验未通过：" + describeBlock(missing, problems), missing, problems);
    }

    private static String describe(String templateId, Map<String, Set<String>> missing, List<String> problems) {
        return "模板 [" + templateId + "] 校验未通过：" + describeBlock(missing, problems);
    }

    private static String describeBlock(Map<String, Set<String>> missing, List<String> problems) {
        StringBuilder sb = new StringBuilder();
        if (!missing.isEmpty()) {
            sb.append("引用了未注册的规则 ").append(missing);
        }
        if (!problems.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("表达式错语法：").append(problems);
        }
        return sb.toString();
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
        private StrictTypes strictTypes = StrictTypes.WARN;
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

        /**
         * 静默错误的处置口径，默认 {@link StrictTypes#WARN}（保持存量输出 + ERROR 日志）。
         *
         * <p>下一版默认值会切到 {@code FAIL}，存量模板请先用 {@code warn} 跑一轮日志再切。</p>
         */
        public Builder strictTypes(StrictTypes strictTypes) {
            this.strictTypes = strictTypes;
            return this;
        }

        public IfmapEngine build() {
            return new IfmapEngine(this);
        }
    }
}
