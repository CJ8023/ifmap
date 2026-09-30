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

/**
 * 管理端配置查询条件（分页 + 筛选），设计 §8.2 `GET /configs`。
 *
 * <p>所有筛选条件都是"有值才参与过滤"，{@code tenantId} 为空时按 {@code -1} 处理。</p>
 *
 * @author caijun
 */
public class ConfigQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 最大页大小，防止一次拉全表。 */
    public static final int MAX_SIZE = 200;

    private String tenantId;
    private String interfaceNo;
    private String busiNode;
    private String bankCode;
    private Integer status;
    /** 为 true 时连已删除的配置一起返回（管理端"回收站"视图）。 */
    private boolean includeDeleted;
    /**
     * 为 true 时<b>不</b>按 {@code tenant_id} 过滤（跨租户全量巡检用）。
     *
     * <p>默认 false：普通查询必须落在某个租户内（空值按 {@code -1}），避免一次误调用变成全表扫描。</p>
     */
    private boolean allTenants;
    private int page = 1;
    private int size = 20;

    public String getTenantId() {
        return tenantId;
    }

    public ConfigQuery setTenantId(String tenantId) {
        this.tenantId = tenantId;
        return this;
    }

    public String getInterfaceNo() {
        return interfaceNo;
    }

    public ConfigQuery setInterfaceNo(String interfaceNo) {
        this.interfaceNo = interfaceNo;
        return this;
    }

    public String getBusiNode() {
        return busiNode;
    }

    public ConfigQuery setBusiNode(String busiNode) {
        this.busiNode = busiNode;
        return this;
    }

    public String getBankCode() {
        return bankCode;
    }

    public ConfigQuery setBankCode(String bankCode) {
        this.bankCode = bankCode;
        return this;
    }

    public Integer getStatus() {
        return status;
    }

    public ConfigQuery setStatus(Integer status) {
        this.status = status;
        return this;
    }

    public boolean isIncludeDeleted() {
        return includeDeleted;
    }

    public ConfigQuery setIncludeDeleted(boolean includeDeleted) {
        this.includeDeleted = includeDeleted;
        return this;
    }

    public boolean isAllTenants() {
        return allTenants;
    }

    /** 跨租户查询开关；由 {@code GET /audit} 这类"全量体检"接口显式打开。 */
    public ConfigQuery setAllTenants(boolean allTenants) {
        this.allTenants = allTenants;
        return this;
    }

    public int getPage() {
        return page;
    }

    /** 页码从 1 开始，&lt;1 视为 1。 */
    public ConfigQuery setPage(int page) {
        this.page = page < 1 ? 1 : page;
        return this;
    }

    public int getSize() {
        return size;
    }

    /** 页大小，越界时收敛到 `[1, MAX_SIZE]`。 */
    public ConfigQuery setSize(int size) {
        if (size < 1) {
            this.size = 20;
        } else {
            this.size = Math.min(size, MAX_SIZE);
        }
        return this;
    }

    /** SQL OFFSET。 */
    public int offset() {
        return (page - 1) * size;
    }
}
