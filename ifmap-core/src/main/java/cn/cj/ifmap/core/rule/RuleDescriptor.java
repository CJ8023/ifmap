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
package cn.cj.ifmap.core.rule;

/**
 * 规则元信息：用于启动期校验、规则清单文档、管理端展示。
 *
 * @author caijun
 */
public final class RuleDescriptor {

    private final String name;
    private final String owner;
    private final String signature;
    private final String desc;
    private final String example;
    private final boolean override;
    private final boolean allowNullArgs;

    RuleDescriptor(String name, String owner, String signature, String desc, String example,
                   boolean override, boolean allowNullArgs) {
        this.name = name;
        this.owner = owner;
        this.signature = signature;
        this.desc = desc;
        this.example = example;
        this.override = override;
        this.allowNullArgs = allowNullArgs;
    }

    /** 规则名。 */
    public String getName() {
        return name;
    }

    /** 声明类全名。 */
    public String getOwner() {
        return owner;
    }

    /** 方法签名（含参数类型），用于同名校验与文档。 */
    public String getSignature() {
        return signature;
    }

    public String getDesc() {
        return desc;
    }

    public String getExample() {
        return example;
    }

    /** 是否为覆盖注册。 */
    public boolean isOverride() {
        return override;
    }

    /** 是否允许实际参数为 null。 */
    public boolean allowsNullArgs() {
        return allowNullArgs;
    }

    @Override
    public String toString() {
        return name + " -> " + owner + "#" + signature;
    }
}
