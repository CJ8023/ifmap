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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 配置审计报告（设计 §8.2 {@code GET /audit}）：把"启动期契约自检"扩展到"运行期全量巡检"。
 *
 * @author caijun
 */
public class AuditReport {

    private final List<String> errors = new ArrayList<String>();
    private final List<String> warnings = new ArrayList<String>();
    private int configCount;
    private int branchCount;
    private boolean truncated;

    public boolean isClean() {
        return errors.isEmpty();
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public int getConfigCount() {
        return configCount;
    }

    public void setConfigCount(int configCount) {
        this.configCount = configCount;
    }

    public int getBranchCount() {
        return branchCount;
    }

    public void setBranchCount(int branchCount) {
        this.branchCount = branchCount;
    }

    /** 配置数超过扫描上限时为 true（报告不完整）。 */
    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public void error(String message) {
        errors.add(message);
    }

    public void warning(String message) {
        warnings.add(message);
    }

    public void addAll(ValidationResult result) {
        if (result == null) {
            return;
        }
        errors.addAll(result.getErrors());
        warnings.addAll(result.getWarnings());
    }

    /** Markdown 摘要（可直接贴发布单 / 工单）。 */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ifmap 配置审计\n\n");
        sb.append("- 配置数：").append(configCount).append('\n');
        sb.append("- 分支数：").append(branchCount).append('\n');
        sb.append("- 结论：").append(isClean() ? "通过（0 error）" : errors.size() + " 个 error").append('\n');
        if (truncated) {
            sb.append("- 注意：配置数超过扫描上限，报告不完整\n");
        }
        sb.append("\n## errors\n\n");
        appendList(sb, errors);
        sb.append("\n## warnings\n\n");
        appendList(sb, warnings);
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, List<String> items) {
        if (items.isEmpty()) {
            sb.append("（无）\n");
            return;
        }
        for (String item : items) {
            sb.append("- ").append(item.replace("|", "\\|")).append('\n');
        }
    }

    @Override
    public String toString() {
        return "AuditReport{clean=" + isClean() + ", configCount=" + configCount
                + ", branchCount=" + branchCount + ", errors=" + errors.size() + ", warnings=" + warnings.size() + '}';
    }
}
