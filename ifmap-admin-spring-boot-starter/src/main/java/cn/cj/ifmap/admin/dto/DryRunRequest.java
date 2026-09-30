package cn.cj.ifmap.admin.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 试跑请求体（{@code POST /configs/dry-run}）：用当前库里的配置渲染报文但<b>不出网</b>。
 *
 * <p>{@code mockResponse} 是伪造的对方应答（引擎据此走判定与分支）；留空时用 {@code {}}，
 * 保证试跑绝不产生真实外呼。</p>
 *
 * @author caijun
 */
public class DryRunRequest {

    private String tenantId;
    private String interfaceNo;
    private String busiNode;
    private String bizId;
    private Map<String, Object> params = new LinkedHashMap<String, Object>();
    private String mockResponse;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getInterfaceNo() {
        return interfaceNo;
    }

    public void setInterfaceNo(String interfaceNo) {
        this.interfaceNo = interfaceNo;
    }

    public String getBusiNode() {
        return busiNode;
    }

    public void setBusiNode(String busiNode) {
        this.busiNode = busiNode;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public String getMockResponse() {
        return mockResponse;
    }

    public void setMockResponse(String mockResponse) {
        this.mockResponse = mockResponse;
    }
}
