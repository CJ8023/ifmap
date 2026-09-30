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
package cn.cj.ifmap.admin.spi;

/**
 * 一个下拉选项：{@code value} 是**落库值**，{@code label} 是给人看的显示名。
 *
 * <p>不可变值对象：管理端页面（含单元测试）会跨线程读它，改一个字段就改所有引用是最难查的
 * 那类 bug，所以这里没有 setter。</p>
 *
 * @author caijun
 */
public final class EnumOption {

    private final String value;
    private final String label;

    public EnumOption(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("枚举项的 value 不能为空");
        }
        this.value = value;
        this.label = label == null || label.isEmpty() ? value : label;
    }

    /** 静态工厂：{@code EnumOption.of("CMB", "招商银行")}。 */
    public static EnumOption of(String value, String label) {
        return new EnumOption(value, label);
    }

    public String getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EnumOption)) {
            return false;
        }
        EnumOption that = (EnumOption) other;
        return value.equals(that.value) && label.equals(that.label);
    }

    @Override
    public int hashCode() {
        return 31 * value.hashCode() + label.hashCode();
    }

    @Override
    public String toString() {
        return "EnumOption{" + value + "=" + label + "}";
    }
}
