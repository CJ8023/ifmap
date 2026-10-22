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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调用方向解析：空值按 OUTBOUND（与列默认值同口径），写错字必须 fail fast。
 *
 * <p>为什么「写错字不能静默降级」要单独立一条：方向决定了报文从哪来、回参是什么。
 * 把 {@code INBOUND} 写成 {@code INBOUD} 若被当成 OUTBOUND，就变成「引擎去调对方」，
 * 而实际场景是「对方调我」—— 表现是运行期真的发出一次外呼，比抛异常危险得多。</p>
 *
 * @author caijun
 */
class CallDirectionTest {

    @Test
    @DisplayName("空值 = OUTBOUND（与 config.call_direction 的 DEFAULT 同口径，存量行零回填）")
    void blankMeansOutbound() {
        assertEquals(CallDirection.OUTBOUND, CallDirection.fromName(null));
        assertEquals(CallDirection.OUTBOUND, CallDirection.fromName(""));
        assertEquals(CallDirection.OUTBOUND, CallDirection.fromName("   "));
    }

    @Test
    @DisplayName("大小写与首尾空白容忍（列值 / JSON 字段可能来自人工录入）")
    void caseAndSpaceInsensitive() {
        assertEquals(CallDirection.INBOUND, CallDirection.fromName("inbound"));
        assertEquals(CallDirection.INBOUND, CallDirection.fromName(" InBound "));
        assertEquals(CallDirection.OUTBOUND, CallDirection.fromName("outbound"));
    }

    @Test
    @DisplayName("非法值 fail fast，并在消息里给出可选值")
    void unknownValueFailsFast() {
        IfmapConfigException error = assertThrows(IfmapConfigException.class,
                () -> CallDirection.fromName("INBOUD"));
        assertTrue(error.getMessage().contains("call_direction"), error.getMessage());
        assertTrue(error.getMessage().contains("INBOUND"), error.getMessage());
    }

    @Test
    @DisplayName("IfmapConfig.inbound()：null 方向按 OUTBOUND（false），不 NPE")
    void configInboundFlag() {
        IfmapConfig config = new IfmapConfig();
        assertFalse(config.inbound());
        config.setCallDirection(CallDirection.INBOUND);
        assertTrue(config.inbound());
        config.setCallDirection(CallDirection.OUTBOUND);
        assertFalse(config.inbound());
    }
}
