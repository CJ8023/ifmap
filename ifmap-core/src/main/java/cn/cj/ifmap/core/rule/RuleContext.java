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

import cn.cj.ifmap.core.spi.HolidayCalendar;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则调用上下文：承载与报文无关的环境信息（租户、接口号、工作日历等）。
 *
 * <p>规则方法把本类声明为第一个参数即可拿到，模板里无需传入。</p>
 *
 * @author caijun
 */
public final class RuleContext {

    /** 属性名：工作日历。 */
    public static final String ATTR_HOLIDAY_CALENDAR = "holidayCalendar";

    private static final RuleContext EMPTY = builder().build();

    private final String tenantId;
    private final String interfaceNo;
    private final Map<String, Object> attributes;

    private RuleContext(Builder builder) {
        this.tenantId = builder.tenantId;
        this.interfaceNo = builder.interfaceNo;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.attributes));
    }

    public static RuleContext empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 基于当前上下文派生一份并追加属性。 */
    public RuleContext with(String key, Object value) {
        Builder b = builder().tenantId(tenantId).interfaceNo(interfaceNo).attributes(attributes);
        b.attribute(key, value);
        return b.build();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getInterfaceNo() {
        return interfaceNo;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /** 工作日历，未配置返回 null。 */
    public HolidayCalendar getHolidayCalendar() {
        Object v = attributes.get(ATTR_HOLIDAY_CALENDAR);
        return v instanceof HolidayCalendar ? (HolidayCalendar) v : null;
    }

    @Override
    public String toString() {
        return "RuleContext{tenantId='" + tenantId + "', interfaceNo='" + interfaceNo + "', attributes=" + attributes + '}';
    }

    /** 构造器。 */
    public static final class Builder {

        private String tenantId;
        private String interfaceNo;
        private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder interfaceNo(String interfaceNo) {
            this.interfaceNo = interfaceNo;
            return this;
        }

        public Builder attribute(String key, Object value) {
            if (key != null) {
                this.attributes.put(key, value);
            }
            return this;
        }

        public Builder attributes(Map<String, Object> values) {
            if (values != null) {
                this.attributes.putAll(values);
            }
            return this;
        }

        public RuleContext build() {
            return new RuleContext(this);
        }
    }
}
