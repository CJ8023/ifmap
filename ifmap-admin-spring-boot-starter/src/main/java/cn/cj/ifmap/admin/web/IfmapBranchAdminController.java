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
package cn.cj.ifmap.admin.web;

import cn.cj.ifmap.admin.ConfigAdminService;
import cn.cj.ifmap.admin.IfmapAdminHeaders;
import cn.cj.ifmap.core.config.LogicBranchConfig;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 逻辑分支管理端（{@code /branches}）。
 *
 * <p>分支表没有 {@code version} 列（设计 §6.4），因此这里不做乐观锁；唯一键由数据库兜底。</p>
 *
 * @author caijun
 */
@RestController
@RequestMapping("/branches")
public class IfmapBranchAdminController {

    private final ConfigAdminService service;

    public IfmapBranchAdminController(ConfigAdminService service) {
        this.service = service;
    }

    /** 查询某接口的分支。 */
    @GetMapping
    public List<LogicBranchConfig> list(@RequestParam(value = "tenantId", required = false) String tenantId,
                                        @RequestParam("interfaceNo") String interfaceNo,
                                        @RequestParam(value = "includeDeleted", required = false) Boolean includeDeleted) {
        return service.branches(tenantId, interfaceNo, includeDeleted != null && includeDeleted);
    }

    /** 新增分支。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody LogicBranchConfig branch,
                                      @RequestHeader(value = IfmapAdminHeaders.OPERATOR_ID, required = false)
                                      String operatorId) {
        if (branch == null) {
            throw new IfmapConfigException("分支不能为空");
        }
        return Map.of("ok", true, "keyId", service.createBranch(branch, operatorId));
    }

    /** 修改分支。 */
    @PutMapping("/{keyId}")
    public Map<String, Object> update(@PathVariable("keyId") long keyId, @RequestBody LogicBranchConfig branch,
                                      @RequestHeader(value = IfmapAdminHeaders.OPERATOR_ID, required = false)
                                      String operatorId) {
        if (branch == null) {
            throw new IfmapConfigException("分支不能为空");
        }
        branch.setKeyId(keyId);
        return Map.of("ok", service.updateBranch(branch, operatorId));
    }

    /** 逻辑删除分支。 */
    @DeleteMapping("/{keyId}")
    public Map<String, Object> delete(@PathVariable("keyId") long keyId,
                                      @RequestHeader(value = IfmapAdminHeaders.OPERATOR_ID, required = false)
                                      String operatorId) {
        return Map.of("ok", service.deleteBranch(keyId, operatorId));
    }
}
