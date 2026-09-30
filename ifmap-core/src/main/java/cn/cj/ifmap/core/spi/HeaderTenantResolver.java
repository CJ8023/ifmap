package cn.cj.ifmap.core.spi;

import cn.cj.ifmap.core.model.IfmapRequest;

/**
 * 默认租户解析：先从请求上下文取 {@code tenantId}，再取请求头 {@code X-Tenant-Id}，最后回落默认值。
 *
 * <p>纯 Java 场景调用方直接在 {@code IfmapRequest.tenantId} 传入即可。</p>
 *
 * @author caijun
 */
public class HeaderTenantResolver implements TenantResolver {

    /** 默认请求头名。 */
    public static final String DEFAULT_HEADER = "X-Tenant-Id";

    /** 默认租户（与存量表 {@code tenant_id} 默认值一致）。 */
    public static final String DEFAULT_TENANT_ID = "-1";

    private final String headerName;
    private final String defaultTenantId;

    public HeaderTenantResolver() {
        this(DEFAULT_HEADER, DEFAULT_TENANT_ID);
    }

    public HeaderTenantResolver(String headerName) {
        this(headerName, DEFAULT_TENANT_ID);
    }

    public HeaderTenantResolver(String headerName, String defaultTenantId) {
        this.headerName = (headerName == null || headerName.isEmpty()) ? DEFAULT_HEADER : headerName;
        this.defaultTenantId = (defaultTenantId == null || defaultTenantId.isEmpty())
                ? DEFAULT_TENANT_ID : defaultTenantId;
    }

    @Override
    public String resolve(IfmapRequest request) {
        if (request != null) {
            String fromContext = trimToNull(request.getTenantId());
            if (fromContext != null) {
                return fromContext;
            }
            String fromHeader = trimToNull(request.getHeader(headerName));
            if (fromHeader != null) {
                return fromHeader;
            }
        }
        return defaultTenantId;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getDefaultTenantId() {
        return defaultTenantId;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
