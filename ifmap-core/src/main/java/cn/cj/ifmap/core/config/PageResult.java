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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果（管理端列表接口统一返回体）。
 *
 * @param <T> 行类型
 * @author caijun
 */
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long total;
    private final int page;
    private final int size;
    private final List<T> rows;

    public PageResult(long total, int page, int size, List<T> rows) {
        this.total = total;
        this.page = page;
        this.size = size;
        // 拷一份 + 不可变视图：分页结果是对外 DTO，不能被调用方顺手改到（与 IfmapResult 一致）
        this.rows = rows == null ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(rows));
    }

    public long getTotal() {
        return total;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public List<T> getRows() {
        return rows;
    }

    /** 总页数（页大小为 0 时返回 0）。 */
    public int getPages() {
        return size <= 0 ? 0 : (int) ((total + size - 1) / size);
    }

    @Override
    public String toString() {
        return "PageResult{total=" + total + ", page=" + page + ", size=" + size + ", rows=" + rows.size() + '}';
    }
}
