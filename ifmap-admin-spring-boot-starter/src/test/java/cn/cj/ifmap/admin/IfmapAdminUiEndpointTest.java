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

import cn.cj.ifmap.ittest.AdminTestApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可视化页面的 HTTP 契约：静态资源挂得上、{@code /ui} 会跳转、{@code /enums} 可选 SPI 生效。
 *
 * <p>为什么必须有这层测试：页面是打包在 jar 里的静态资源，Maven 里少一行 resources 配置、
 * 或者资源处理器路径写错，编译与单测都不会报错 —— 只有真起 Tomcat 发一次 HTTP 才会暴露。</p>
 *
 * @author caijun
 */
@ActiveProfiles("test")
@SpringBootTest(classes = AdminTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ifmap.admin.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:ifmap_admin_ui;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
class IfmapAdminUiEndpointTest {

    private static final String BASE = "/ifmap/admin";

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("GET /ui → 302 到 index.html（手敲不带 .html 的地址不能 404）")
    void uiRedirects() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://localhost:" + port + BASE + "/ui").openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        try {
            assertEquals(HttpStatus.FOUND.value(), connection.getResponseCode());
            assertEquals(BASE + "/ui/index.html", connection.getHeaderField("Location"));
        } finally {
            connection.disconnect();
        }

        // 带尾斜杠同样要跳
        HttpURLConnection slash = (HttpURLConnection) new URL(
                "http://localhost:" + port + BASE + "/ui/").openConnection();
        slash.setInstanceFollowRedirects(false);
        try {
            assertEquals(HttpStatus.FOUND.value(), slash.getResponseCode());
        } finally {
            slash.disconnect();
        }
    }

    @Test
    @DisplayName("静态资源真的打进 jar 了：index.html / app.js / app.css 都能取到")
    void staticAssetsAreServed() {
        ResponseEntity<byte[]> html = rest.getForEntity(BASE + "/ui/index.html", byte[].class);
        assertEquals(HttpStatus.OK, html.getStatusCode());
        assertTrue(String.valueOf(html.getHeaders().getContentType()).startsWith("text/html"),
                String.valueOf(html.getHeaders().getContentType()));
        assertTrue(body(html).contains("app.js"), "页面必须引用 app.js");
        assertTrue(body(html).contains("app.css"), "页面必须引用 app.css");

        ResponseEntity<byte[]> js = rest.getForEntity(BASE + "/ui/app.js", byte[].class);
        assertEquals(HttpStatus.OK, js.getStatusCode());
        assertTrue(body(js).contains("X-Operator-Id"), "页面写操作必须带审计请求头");
        assertTrue(body(js).length() > 2000, "app.js 内容异常（" + body(js).length() + " 字符）");

        ResponseEntity<byte[]> css = rest.getForEntity(BASE + "/ui/app.css", byte[].class);
        assertEquals(HttpStatus.OK, css.getStatusCode());
        assertTrue(body(css).contains("--accent"), "app.css 内容异常");
    }

    /** 一律按 UTF-8 自己解码：避免依赖 HTTP 客户端对 {@code text/*} 默认字符集的猜测。 */
    private static String body(ResponseEntity<byte[]> response) {
        byte[] bytes = response.getBody();
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("页面取不到 jar 外的路径（静态资源只挂 /ui/**，不能把 classpath 敞出去）")
    void onlyUiPrefixIsMapped() {
        assertEquals(HttpStatus.NOT_FOUND,
                rest.getForEntity(BASE + "/ui/../application.properties", byte[].class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                rest.getForEntity(BASE + "/META-INF/ifmap-admin-ui/app.js", byte[].class).getStatusCode());
    }

    @Test
    @DisplayName("GET /enums：宿主机注册了 IfmapEnumProvider 就有内容（页面拿它做下拉）")
    void enumsComeFromHostProvider() {
        ResponseEntity<byte[]> response = rest.getForEntity(BASE + "/enums", byte[].class);
        String json = body(response);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(json.contains("partnerCode"), json);
        assertTrue(json.contains("招商银行"), json);
        assertTrue(json.contains("CMB"), json);
    }
}
