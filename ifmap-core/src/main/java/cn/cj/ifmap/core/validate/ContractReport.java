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
package cn.cj.ifmap.core.validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 契约自检报告：扫描"配置里的模板"与"已注册规则"的一致性（设计 §5.4 的启动期自检）。
 *
 * @author caijun
 */
public final class ContractReport {

    private final List<Violation> violations;

    public ContractReport(List<Violation> violations) {
        this.violations = Collections.unmodifiableList(new ArrayList<Violation>(violations));
    }

    /** 是否零违规（W4 的验收口径）。 */
    public boolean isClean() {
        return violations.isEmpty();
    }

    public List<Violation> getViolations() {
        return violations;
    }

    public int size() {
        return violations.size();
    }

    /** 生成 Markdown 报告（可贴到发布单/管理端页面）。 */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("| # | interfaceNo | order | 模板 | 问题 |\n");
        sb.append("|---|---|---|---|---|\n");
        int index = 1;
        for (Violation violation : violations) {
            sb.append("| ").append(index++).append(" | ").append(nullToDash(violation.getInterfaceNo()))
                    .append(" | ").append(violation.getInterfaceOrder() == null ? "-" : violation.getInterfaceOrder())
                    .append(" | ").append(violation.getTemplateKind())
                    .append(" | ").append(nullToDash(violation.getMessage())).append(" |\n");
        }
        if (violations.isEmpty()) {
            sb.append("\n零违规：配置模板引用的规则全部已注册。\n");
        }
        return sb.toString();
    }

    private static String nullToDash(String value) {
        return value == null ? "-" : value.replace("|", "\\|");
    }

    @Override
    public String toString() {
        return "ContractReport{violations=" + violations.size() + '}';
    }

    /** 一条违规。 */
    public static final class Violation {

        /** 模板类型：request / response。 */
        public static final String KIND_REQUEST = "request";
        /** 模板类型：response。 */
        public static final String KIND_RESPONSE = "response";

        private final String interfaceNo;
        private final Integer interfaceOrder;
        private final String templateKind;
        private final String message;

        public Violation(String interfaceNo, Integer interfaceOrder, String templateKind, String message) {
            this.interfaceNo = interfaceNo;
            this.interfaceOrder = interfaceOrder;
            this.templateKind = templateKind;
            this.message = message;
        }

        public String getInterfaceNo() {
            return interfaceNo;
        }

        public Integer getInterfaceOrder() {
            return interfaceOrder;
        }

        public String getTemplateKind() {
            return templateKind;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return interfaceNo + "#" + interfaceOrder + "[" + templateKind + "] " + message;
        }
    }
}
