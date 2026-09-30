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

import cn.cj.ifmap.admin.dto.ApiError;
import cn.cj.ifmap.admin.web.IfmapConfigAdminController;
import cn.cj.ifmap.core.exception.IfmapConfigException;
import cn.cj.ifmap.core.exception.IfmapRemoteException;
import cn.cj.ifmap.core.exception.IfmapStrategyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 管理端异常 → HTTP 状态码映射。
 *
 * <p>只作用于 ifmap 管理端包（{@code basePackageClasses}），<b>不会</b>影响宿主机的异常处理。</p>
 *
 * @author caijun
 */
@RestControllerAdvice(basePackageClasses = IfmapConfigAdminController.class)
public class IfmapAdminExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IfmapAdminExceptionHandler.class);

    /** 校验未通过：422 + 完整违规清单。 */
    @ExceptionHandler(IfmapValidationException.class)
    public ResponseEntity<ApiError> handleValidation(IfmapValidationException e) {
        ValidationResult result = e.getResult();
        ApiError body = new ApiError(e.getMessage(), e.getClass().getSimpleName(),
                result == null ? null : result.getErrors(),
                result == null ? null : result.getWarnings());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /** 配置/参数错误：400。 */
    @ExceptionHandler({IfmapConfigException.class, IfmapStrategyException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage(), e.getClass().getSimpleName()));
    }

    /** 外呼异常：502（试跑一般不出网，真出网失败也能定位）。 */
    @ExceptionHandler(IfmapRemoteException.class)
    public ResponseEntity<ApiError> handleRemote(IfmapRemoteException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError(e.getMessage(), e.getClass().getSimpleName()));
    }

    /** 兜底：500，同时打日志（管理端不应该出现未知异常）。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception e) {
        log.error("ifmap 管理端未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("管理端内部错误：" + e.getMessage(), e.getClass().getSimpleName()));
    }
}
