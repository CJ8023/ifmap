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
package cn.cj.ifmap.core.config;

import cn.cj.ifmap.core.exception.IfmapConfigException;

import java.util.Locale;

/**
 * 调用方向（对应列 {@code config.call_direction}）。
 *
 * <p>只有两个取值：{@link #OUTBOUND} 是<b>我方调对方</b>（默认，原行为），
 * {@link #INBOUND} 是<b>对方调我方</b>（被调接口）。</p>
 *
 * <p>INBOUND 在引擎里只改执行路径 —— 不出网、不跑前序策略、回参取请求原文；
 * 结果判定、逻辑分支、应答模板三者的语义与 OUTBOUND 完全一致。所以「方向」不是一个开关矩阵，
 * 只是「这条配置的报文从哪来、回参是什么」。</p>
 *
 * <p><b>非法值 fail fast</b>：写错字（如 {@code INBOUD}）不能让引擎静默按 OUTBOUND 出行网调用，
 * 那会把「对方调我」变成「我调对方」。空值等价 OUTBOUND（与 DDL 默认值同口径）。</p>
 *
 * @author caijun
 */
public enum CallDirection {

    /** 我方调对方（默认）：渲染请求 → 出网 → 判定回参。 */
    OUTBOUND,

    /** 对方调我方：回参即请求原文，不出网；判定用于「报文可受理」，逻辑分支用于业务分流。 */
    INBOUND;

    /**
     * 按名字解析（大小写不敏感、容忍首尾空白）。
     *
     * @param name 列值 / JSON 字段值，可为 null
     * @return 空值返回 {@link #OUTBOUND}
     * @throws IfmapConfigException 取值不在枚举内（写错方向比写错模板更危险，必须立刻失败）
     */
    public static CallDirection fromName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return OUTBOUND;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        for (CallDirection direction : values()) {
            if (direction.name().equals(normalized)) {
                return direction;
            }
        }
        throw new IfmapConfigException("非法的 call_direction：" + name
                + "（可选 " + OUTBOUND + " / " + INBOUND + "）");
    }
}
