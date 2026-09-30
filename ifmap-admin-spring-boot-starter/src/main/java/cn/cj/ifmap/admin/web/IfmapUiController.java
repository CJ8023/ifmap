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

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.URI;

/**
 * 可视化页面的入口：{@code GET {base-path}/ui} → 302 到 {@code {base-path}/ui/index.html}。
 *
 * <p>为什么要这个跳转：资源处理器只挂得上 {@code /ui/**}，而 {@code /ui} 本身不会命中它
 * （{@code /ui/**} 不匹配 {@code /ui}）—— 没有这个 Controller，运维在浏览器里手敲
 * {@code /ifmap/admin/ui} 会得到 404，还以为页面没部署成功。</p>
 *
 * <p>本类会被 {@code IfmapAdminWebConfigurer} 统一加上前缀，所以这里写的是相对路径。</p>
 *
 * @author caijun
 */
@Controller
public class IfmapUiController {

    private final String basePath;

    public IfmapUiController(String basePath) {
        this.basePath = basePath == null ? "" : basePath;
    }

    /** 带不带尾斜杠都跳到静态页。 */
    @GetMapping({"/ui", "/ui/"})
    public ResponseEntity<Void> index() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(basePath + "/ui/index.html"))
                .build();
    }
}
