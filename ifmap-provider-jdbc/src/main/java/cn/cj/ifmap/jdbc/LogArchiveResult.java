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
package cn.cj.ifmap.jdbc;

import java.io.Serializable;

/**
 * 一次执行日志归档的结果。
 *
 * <p>{@code archived} 与 {@code removed} 分开计数是有意的：归档器是**可重入**的
 * （先 INSERT 后 DELETE，中途失败下次重跑），重跑时可能出现"这条已经在归档表里了"——
 * 此时 {@code archived} 不涨而 {@code removed} 照涨，业务上仍然是"已搬到冷表"。</p>
 *
 * @author caijun
 */
public class LogArchiveResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long archived;
    private final long removed;
    private final int batches;

    public LogArchiveResult(long archived, long removed, int batches) {
        this.archived = archived;
        this.removed = removed;
        this.batches = batches;
    }

    /** 本次写入归档表的行数。 */
    public long getArchived() {
        return archived;
    }

    /** 本次从热表删除的行数（等于"真正搬到冷表"的行数）。 */
    public long getRemoved() {
        return removed;
    }

    /** 本次执行了多少批。 */
    public int getBatches() {
        return batches;
    }

    /** 本次什么事都没做（没有过期数据）。 */
    public boolean isEmpty() {
        return removed == 0L;
    }

    @Override
    public String toString() {
        return "LogArchiveResult{archived=" + archived + ", removed=" + removed + ", batches=" + batches + '}';
    }
}
