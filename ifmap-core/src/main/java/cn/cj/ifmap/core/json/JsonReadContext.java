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
 * 一次解析、多次取数的读取上下文。
 *
 * <p>{@link #read(String, boolean)} 必须支持完整 JsonPath 语义，
 * 包括通配符 {@code *}、递归下降 {@code ..}、过滤器 {@code [?(@.x == 'y')]}、
 * 切片 {@code [0:2]} 等，以保持与存量引擎的路径语义一致。</p>
 *
 * @author caijun
 */
public interface JsonReadContext {

    /**
     * 按路径取值。
     *
     * @param path       JsonPath 表达式
     * @param leafToNull 通配路径中缺失的叶子是否补 null（保证列表等长、下标对齐）
     * @return JDK 原生类型；路径不存在时返回 null
     */
    Object read(String path, boolean leafToNull);

    /**
     * 按路径取值（叶子缺失不补 null）。
     */
    Object read(String path);
}
