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
package cn.cj.ifmap.core.json;

/**
 * JSON 能力 SPI：ifmap 的公开 API <b>不出现任何 JSON 库类型</b>，全部经本接口。
 *
 * <p>实现方（如 {@code ifmap-json-jackson}）负责把 JSON 文本解析为 JDK 原生类型：
 * {@code Map} / {@code List} / {@code String} / {@code Boolean} / {@code Number} / {@code null}。</p>
 *
 * <p>宿主可自行实现本接口以替换底层 JSON 库（Fastjson / Gson / Jackson 均可），
 * 也可通过 {@link JsonOpsHolder#set(JsonOps)} 手动注入。</p>
 *
 * @author caijun
 */
public interface JsonOps {

    /** SPI 实现名，用于日志与诊断（例如 {@code jackson}）。 */
    String name();

    /**
     * 解析 JSON 文本为 JDK 原生结构。
     *
     * @param json JSON 文本，允许为 null/空（返回 null）
     * @return {@code Map} / {@code List} / 标量 / null
     */
    Object parse(String json);

    /** 序列化为 JSON 文本。 */
    String toJson(Object value);

    /**
     * 建立一次解析、多次取数的读取上下文。
     *
     * <p>相对存量引擎的改进：存量实现每条 JsonPath 都重新解析一遍源 JSON，
     * ifmap 改为解析一次后复用。</p>
     */
    JsonReadContext readContext(String json);

    /** 判断文本是否为合法 JSON。 */
    boolean isJson(String text);

    /**
     * 判断 JsonPath 表达式语法是否合法（管理端"保存前校验"用，设计 §8.3）。
     *
     * <p>默认返回 {@code true}（实现方可以不支持该能力，校验会退化为"不校验路径"），
     * 支持 JsonPath 的实现应返回真实结果。</p>
     */
    default boolean isValidPath(String path) {
        return true;
    }
}
