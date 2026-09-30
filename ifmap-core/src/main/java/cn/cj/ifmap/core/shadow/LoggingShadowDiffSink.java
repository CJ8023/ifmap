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
package cn.cj.ifmap.core.shadow;

import cn.cj.ifmap.core.spi.DefaultLogMasker;
import cn.cj.ifmap.core.spi.LogMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 差异打成 warn 日志的默认出口。
 *
 * <p>差异文本里可能带客户数据，所以出日志前统一走一遍 {@link LogMasker}：
 * 手机号 / 身份证 / 银行卡号这类<b>值形态</b>的敏感串会被打码
 * （{@link DefaultLogMasker} 的值形态正则与 JSON 形态无关，直接作用于文本即可）。</p>
 *
 * @author caijun
 */
public final class LoggingShadowDiffSink implements ShadowDiffSink {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingShadowDiffSink.class);

    private final LogMasker masker;

    public LoggingShadowDiffSink() {
        this(new DefaultLogMasker());
    }

    public LoggingShadowDiffSink(LogMasker masker) {
        this.masker = masker;
    }

    @Override
    public void onDiff(ShadowFieldDiff diff) {
        if (diff == null) {
            return;
        }
        String text = diff.toString();
        LOG.warn("ifmap 影子运行差异：{}", masker == null ? text : masker.mask(text));
    }
}
