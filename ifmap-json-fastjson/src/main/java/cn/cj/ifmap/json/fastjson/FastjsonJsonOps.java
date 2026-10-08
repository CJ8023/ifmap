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
package cn.cj.ifmap.json.fastjson;

import cn.cj.ifmap.core.json.JsonOps;
import cn.cj.ifmap.core.json.JsonReadContext;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;

/**
 * 基于 fastjson 的 {@link JsonOps} <b>兼容实现</b>（存量迁移过渡用，新项目请用
 * {@code ifmap-json-jackson}）。
 *
 * <p>为什么要有它：存量 ECC 引擎的取值/模板全走 fastjson 的 {@code JSONPath}，与 jayway 的
 * {@code json-path} 是**两套方言**。迁移期要让老配置在 ifmap 上「字节级一致」地跑起来，
 * 最省事、也最可验证的做法不是去翻译 216 个调用点，而是把 fastjson 原样装回 {@code JsonOps} SPI。
 * 但它只解决「能跑」；长期（安全、可维护、与 Spring Boot 生态同栈）仍应切到 Jackson 实现。</p>
 *
 * <p><b>安全须知</b>：fastjson 1.2.68~1.2.83 在默认配置下存在可被利用的 AutoType 反序列化 RCE
 * （CVE-2026-16723），本模块固定依赖 <b>1.2.84</b>（官方 backport 的加固版）。此外：</p>
 * <ul>
 *   <li>本实现只调用 {@code JSON.parse(String)} / {@code JSON.toJSONString} / {@code JSONPath}，
 *       <b>不使用</b> {@code parseObject(Class)}、{@code TypeReference} 等会触发 AutoType 的 API；</li>
 *   <li><b>不修改</b> {@code ParserConfig.getGlobalInstance()}（库改全局配置是隐性副作用，
 *       会污染宿主的其它 fastjson 用法）；确需 {@code safeMode} 的宿主请自行设置；</li>
 *   <li>遇到 {@code @type} 报文时 fastjson 1.2.84 会「静默解析成 null」，本实现把它转成显式异常
 *       （见 {@link FastjsonValues#parseDocument(String)}），避免报文被悄悄当成空对象。</li>
 * </ul>
 *
 * <p>与 Jackson 实现的已知差异见 {@code docs/05-SpringBoot集成.md} 的「换 JSON 实现」一节。</p>
 *
 * @author caijun
 */
public final class FastjsonJsonOps implements JsonOps {

    /** SPI 实现名（日志与诊断用）。 */
    public static final String NAME = "fastjson";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Object parse(String json) {
        if (FastjsonValues.isBlank(json)) {
            return null;
        }
        Object value;
        try {
            value = FastjsonValues.parseDocument(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON 解析失败：" + e.getMessage(), e);
        }
        return FastjsonValues.toJdk(value);
    }

    @Override
    public String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            // WriteMapNullValue：显式 null 值的键必须留在 JSON 里（默认会被 fastjson 丢掉，
            // 而 ifmap 的模板渲染/快照往返依赖「键存在 + 值为 null」这一区别）
            // WriteBigDecimalAsPlain：小数契约 —— BigDecimal 必须按 toPlainString() 输出，
            // 否则 1.0E+10 这种源文字会原样喷进报文（见 JsonOps 小数契约）
            return JSON.toJSONString(value, SerializerFeature.WriteMapNullValue,
                    SerializerFeature.WriteBigDecimalAsPlain);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON 序列化失败：" + e.getMessage(), e);
        }
    }

    @Override
    public JsonReadContext readContext(String json) {
        return new FastjsonReadContext(json);
    }

    @Override
    public boolean isJson(String text) {
        if (FastjsonValues.isBlank(text)) {
            return false;
        }
        try {
            JSON.parse(text, Feature.OrderedField);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean isValidPath(String path) {
        return FastjsonPaths.isValid(path);
    }
}
