package cn.cj.ifmap.core.config;

import java.io.Serializable;
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
        this.rows = rows == null ? Collections.<T>emptyList() : rows;
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
