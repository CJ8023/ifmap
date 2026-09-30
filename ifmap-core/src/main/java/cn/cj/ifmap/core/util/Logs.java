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
package cn.cj.ifmap.core.util;

/**
 * 日志文本工具：截断（合规项之一）。
 *
 * @author caijun
 */
public final class Logs {

    /** 截断标记。 */
    public static final String TRUNCATED = "...truncated";

    private Logs() {
    }

    /**
     * 超过阈值则截断并追加 {@link #TRUNCATED} 标记。
     *
     * @param text      原文，null 原样返回
     * @param threshold 阈值（&le;0 表示不限制）
     */
    public static String truncate(String text, int threshold) {
        if (text == null || threshold <= 0 || text.length() <= threshold) {
            return text;
        }
        return text.substring(0, threshold) + TRUNCATED;
    }
}
