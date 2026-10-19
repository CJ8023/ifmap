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
package cn.cj.ifmap.core.orchestrator.judgement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 单条判定条件（不可变）。
 *
 * @author caijun
 */
public final class JudgeCondition {

    private final String path;
    private final JudgeOp op;
    private final String value;
    private final boolean ignoreCase;
    private final List<String> values;
    private Pattern pattern;

    public JudgeCondition(String path, JudgeOp op, String value, boolean ignoreCase, List<String> values) {
        this.path = path;
        this.op = op;
        this.value = value;
        this.ignoreCase = ignoreCase;
        this.values = values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public String getPath() {
        return path;
    }

    public JudgeOp getOp() {
        return op;
    }

    public String getValue() {
        return value;
    }

    public boolean isIgnoreCase() {
        return ignoreCase;
    }

    public List<String> getValues() {
        return values;
    }

    /** 正则懒编译（规格校验期已保证可编译，这里不会再抛）。 */
    Pattern compiledPattern() {
        Pattern p = pattern;
        if (p == null) {
            p = Pattern.compile(value == null ? "" : value);
            pattern = p;
        }
        return p;
    }

    /** 人读摘要（写日志/契约自检报告用）。 */
    public String describe() {
        if (op.requiresValueList()) {
            return path + " " + op.name().toLowerCase() + " " + values;
        }
        if (op.requiresValue()) {
            return path + " " + op.name().toLowerCase() + " " + value + (ignoreCase ? " (ignoreCase)" : "");
        }
        return path + " " + op.name().toLowerCase();
    }

    @Override
    public String toString() {
        return describe();
    }
}
