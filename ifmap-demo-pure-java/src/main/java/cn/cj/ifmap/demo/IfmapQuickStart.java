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
package cn.cj.ifmap.demo;

import cn.cj.ifmap.core.IfmapEngine;
import cn.cj.ifmap.core.exception.StartupValidationException;
import cn.cj.ifmap.core.rule.RuleContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * ifmap 纯 Java（非 Spring）快速上手示例。
 *
 * <p>运行：{@code mvn -q -pl ifmap-demo-pure-java exec:java} 或用 IDE 直接运行 main。</p>
 *
 * @author caijun
 */
public final class IfmapQuickStart {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    public static void main(String[] args) throws IOException {
        banner("1. 构建引擎（自动发现 JsonOps + 注册 18 个内置规则）");
        IfmapEngine engine = IfmapEngine.createDefault();
        System.out.println("JsonOps 实现 : " + engine.getJsonOps().name());
        System.out.println("已注册规则数 : " + engine.getRegistry().getRuleNames().size());

        String templateJson = readResource("/demo/template.json");
        String sourceJson = readResource("/demo/source.json");

        banner("2. 启动期校验模板（规则不存在会在这里失败）");
        engine.validateTemplate("demo-transfer", templateJson);
        System.out.println("模板校验通过：引用的规则全部已注册");

        banner("3. 渲染报文");
        RuleContext context = RuleContext.builder()
                .tenantId("T001")
                .interfaceNo("IF_DEMO_0001")
                .build();
        String target = engine.render(templateJson, sourceJson, context);
        System.out.println(pretty(target));

        banner("4. 存量引擎的典型坑：规则名拼写错误 @FUN(farmatDate,...)");
        try {
            engine.validateTemplate("demo-typo", "{\"dueDate\":\"@FUN(farmatDate,$.dueDate,yyyy-MM-dd)\"}");
            System.out.println("!! 本行不应被打印：拼错的规则名没有被拦下");
        } catch (StartupValidationException e) {
            // 存量引擎在这里只会静默返回 null（或回写 @FUN 原文），ifmap 直接阻断启动
            System.out.println("[预期失败] 启动期已拦下：" + e.getMessage());
            System.out.println("解析后的缺失规则：templateId -> ruleNames = " + e.getMissingRules());
        }

        banner("5. 规则清单（可直接用于文档/管理端）");
        System.out.println(engine.describeRules());
    }

    private static void banner(String title) {
        System.out.println();
        System.out.println("==================== " + title + " ====================");
    }

    /** 极简 JSON 美化（只做缩进，不引第三方依赖）。 */
    private static String pretty(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 64);
        int indent = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString && (c == '{' || c == '[')) {
                sb.append(c).append('\n').append(spaces(++indent));
                continue;
            }
            if (!inString && (c == '}' || c == ']')) {
                sb.append('\n').append(spaces(--indent)).append(c);
                continue;
            }
            if (!inString && c == ',') {
                sb.append(c).append('\n').append(spaces(indent));
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String spaces(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    private static String readResource(String path) throws IOException {
        InputStream in = IfmapQuickStart.class.getResourceAsStream(path);
        if (in == null) {
            throw new IOException("资源不存在：" + path);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), UTF_8);
        } finally {
            in.close();
        }
    }
}
