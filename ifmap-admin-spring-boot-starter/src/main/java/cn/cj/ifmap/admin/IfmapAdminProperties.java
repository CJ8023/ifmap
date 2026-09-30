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
package cn.cj.ifmap.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ifmap.admin.*} 配置项（设计 §8.1）。
 *
 * <pre>{@code
 * ifmap:
 *   admin:
 *     enabled: true                 # 默认 false：不显式打开就没有任何管理端端点
 *     base-path: /ifmap/admin       # 所有端点的统一前缀
 *     history-limit: 50             # 单次查询返回的历史条数上限
 * }</pre>
 *
 * <p>默认关闭是刻意的：管理端能改线上配置，必须由部署方显式打开（并自行加鉴权）。</p>
 *
 * @author caijun
 */
@ConfigurationProperties(prefix = "ifmap.admin")
public class IfmapAdminProperties {

    /** 是否启用管理端 REST。 */
    private boolean enabled = false;

    /** 统一路径前缀（必须以 {@code /} 开头，不能以 {@code /} 结尾）。 */
    private String basePath = "/ifmap/admin";

    /** 历史查询默认/最大条数。 */
    private int historyLimit = 50;

    /** 单次审计扫描的最大配置条数（防止管理端把大表全量拉进内存）。 */
    private int auditMaxConfigs = 5000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    public int getAuditMaxConfigs() {
        return auditMaxConfigs;
    }

    public void setAuditMaxConfigs(int auditMaxConfigs) {
        this.auditMaxConfigs = auditMaxConfigs;
    }

    /** 归一化后的路径前缀：去掉尾部斜杠、缺省补前导斜杠。 */
    public String normalizedBasePath() {
        String path = basePath == null ? "" : basePath.trim();
        if (path.isEmpty()) {
            return "";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
