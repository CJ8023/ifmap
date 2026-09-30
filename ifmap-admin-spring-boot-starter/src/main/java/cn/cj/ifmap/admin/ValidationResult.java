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
package cn.cj.ifmap.admin;

import cn.cj.ifmap.core.validate.ContractReport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 保存前校验结果（设计 §8.3）。{@code errors} 非空即不允许保存；{@code warnings} 只提示。
 *
 * @author caijun
 */
public class ValidationResult {

    private final List<String> errors;
    private final List<String> warnings;
    private ContractReport contract;
    private boolean failOnContractViolations;

    public ValidationResult() {
        this(new ArrayList<String>(), new ArrayList<String>());
    }

    public ValidationResult(List<String> errors, List<String> warnings) {
        this.errors = errors == null ? new ArrayList<String>() : errors;
        this.warnings = warnings == null ? new ArrayList<String>() : warnings;
    }

    /** 通过（无 error；warning 不影响结论）。 */
    public boolean isPassed() {
        return errors.isEmpty();
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public ContractReport getContract() {
        return contract;
    }

    public ValidationResult error(String message) {
        if (message != null && !message.trim().isEmpty()) {
            errors.add(message);
        }
        return this;
    }

    public ValidationResult warning(String message) {
        if (message != null && !message.trim().isEmpty()) {
            warnings.add(message);
        }
        return this;
    }

    /** 合并另一份结果（用于"配置 + 分支"一起校验）。 */
    public ValidationResult merge(ValidationResult other) {
        if (other == null) {
            return this;
        }
        this.errors.addAll(other.errors);
        this.warnings.addAll(other.warnings);
        if (other.contract != null) {
            this.contract = other.contract;
        }
        return this;
    }

    public ValidationResult contract(ContractReport report) {
        this.contract = report;
        return this;
    }

    /** 把契约违规也计成 error（保存前校验要求"模板引用的规则都注册了"）。 */
    public ValidationResult failOnContractViolations() {
        this.failOnContractViolations = true;
        if (contract != null && !contract.isClean()) {
            for (ContractReport.Violation violation : contract.getViolations()) {
                errors.add("模板契约违规：" + violation);
            }
        }
        return this;
    }

    /** 是否已把模板契约违规计入 error（调用过 {@link #failOnContractViolations()}）。 */
    public boolean isFailOnContractViolations() {
        return failOnContractViolations;
    }

    @Override
    public String toString() {
        return "ValidationResult{passed=" + isPassed() + ", errors=" + errors + ", warnings=" + warnings + '}';
    }
}
