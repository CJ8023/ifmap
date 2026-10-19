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
    private final int totalConfigs;
    private final int alwaysCount;

    public ContractReport(List<Violation> violations) {
        this(violations, 0, 0);
    }

    /**
     * @param totalConfigs 本次扫描的接口数（判定形态统计的分母）
     * @param alwaysCount  其中「不做判定」（{@code {"op":"always"}}）的接口数 —— 它是**合法配置**，
     *                     单独统计而不是记违规：很多接口确实只看通不通，报成违规会让报告失去信噪比
     */
    public ContractReport(List<Violation> violations, int totalConfigs, int alwaysCount) {
        this.violations = Collections.unmodifiableList(new ArrayList<Violation>(violations));
        this.totalConfigs = totalConfigs;
        this.alwaysCount = alwaysCount;
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

    /** 只取判定规格相关的违规（模板违规与判定违规混在一张表里时，排查需要分开看）。 */
    public List<Violation> judgeSpecViolations() {
        List<Violation> result = new ArrayList<Violation>();
        for (Violation violation : violations) {
            if (Violation.KIND_JUDGE.equals(violation.getTemplateKind())) {
                result.add(violation);
            }
        }
        return result;
    }

    /** 本次扫描的接口数。 */
    public int totalConfigs() {
        return totalConfigs;
    }

    /** 「不做判定」的接口数（{@code always}）。 */
    public int alwaysCount() {
        return alwaysCount;
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
        if (totalConfigs > 0) {
            sb.append("\n### 判定形态统计\n\n");
            sb.append("- 接口总数：").append(totalConfigs).append('\n');
            sb.append("- 不判定（always）：").append(alwaysCount).append('\n');
            sb.append("- 条件判定：").append(totalConfigs - alwaysCount).append('\n');
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
        /** 校验对象：结果判定规格（不是模板，但同样属于"部署前该拦住"的契约问题）。 */
        public static final String KIND_JUDGE = "judge";

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
