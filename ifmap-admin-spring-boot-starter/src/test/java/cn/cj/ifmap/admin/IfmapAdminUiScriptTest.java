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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可视化页面的"静态契约"测试：页面是手写 JS，编译期没有类型检查，所以把最容易静默出错的三件事变成断言。
 *
 * <ol>
 *   <li><b>路径白名单</b>：JS 里出现的每个接口路径都必须是真实存在的端点（写错一个字母就是空白页面）；</li>
 *   <li><b>不硬编码前缀</b>：前缀可配（{@code ifmap.admin.base-path}），写死 {@code /ifmap/admin} 会让改前缀的部署整页 404；</li>
 *   <li><b>id 对得上</b>：{@code el('xxx')} 引用的元素必须在 index.html 里存在（否则交互静默失效）；</li>
 *   <li><b>语法合法</b>：有 node 时跑一次 {@code node --check}（本机/CI 通常都有；没有就跳过，不让构建挂）。</li>
 * </ol>
 *
 * @author caijun
 */
class IfmapAdminUiScriptTest {

    private static final String HTML = "META-INF/ifmap-admin-ui/index.html";
    private static final String JS = "META-INF/ifmap-admin-ui/app.js";

    /** 管理端真实存在的端点根（与 §8.2 的 API 一览一一对应）。 */
    private static final Set<String> ALLOWED_ROOTS = new LinkedHashSet<String>(Arrays.asList(
            "/configs", "/branches", "/rules", "/actions", "/strategies", "/audit", "/enums", "/ui"));

    /**
     * 允许出现在 JS 里的"路径片段"：它们是拼在 {@code keyId} 后面的尾巴（{@code '/configs/' + id + '/history'}），
     * 不是接口根路径，所以不参与白名单校验。
     */
    private static final Set<String> PATH_FRAGMENTS = new LinkedHashSet<String>(Arrays.asList(
            "/", "/history", "/rollback", "/status"));

    /** 由 JS 渲染 HTML 时生成的元素 id（不写在 index.html 里）。 */
    private static final List<String> GENERATED_PREFIXES = Arrays.asList("ed-", "br-");

    private static final List<String> GENERATED_IDS = Arrays.asList("btn-page-prev", "btn-page-next");

    @Test
    @DisplayName("app.js 里的接口路径都在端点白名单内（写错路径 = 空白页面，编译期发现不了）")
    void onlyKnownEndpointsAreCalled() {
        Set<String> paths = stringLiterals(stripComments(text(JS))).stream()
                .filter(literal -> literal.startsWith("/"))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertTrue(paths.size() >= 5, "没抓到路径，说明提取逻辑失效了：" + paths);

        List<String> unknown = new ArrayList<String>();
        for (String path : paths) {
            String normalized = path.split("\\?")[0];
            boolean known = PATH_FRAGMENTS.contains(normalized) || ALLOWED_ROOTS.stream().anyMatch(root ->
                    normalized.equals(root) || normalized.startsWith(root + "/"));
            if (!known) {
                unknown.add(path);
            }
        }
        assertEquals(new ArrayList<String>(), unknown,
                "app.js 调用了未在 docs/07-管理端REST.md 登记的路径：" + unknown);
    }

    @Test
    @DisplayName("不硬编码 /ifmap/admin：前缀是从当前页面 URL 反推的")
    void basePathIsNotHardcoded() {
        String script = text(JS);
        // 注释里可以提到默认前缀（讲清楚为什么不能写死），代码里的字符串字面量一个都不许有
        Set<String> literals = stringLiterals(stripComments(script));

        assertTrue(script.contains("location.pathname"), "必须从 location.pathname 反推前缀");
        assertEquals(new ArrayList<String>(), literals.stream()
                .filter(literal -> literal.startsWith("/ifmap"))
                .collect(Collectors.toList()), "禁止硬编码默认前缀（改 ifmap.admin.base-path 会整页 404）");
    }

    @Test
    @DisplayName("el('id') 引用的元素都存在于 index.html（或明确是动态生成的）")
    void referencedIdsExist() {
        Set<String> referenced = new LinkedHashSet<String>();
        Matcher matcher = Pattern.compile("el\\(\\s*'([A-Za-z0-9_-]+)'\\s*\\)").matcher(text(JS));
        while (matcher.find()) {
            referenced.add(matcher.group(1));
        }
        Set<String> declared = new LinkedHashSet<String>();
        Matcher ids = Pattern.compile("id=\"([^\"]+)\"").matcher(text(HTML));
        while (ids.find()) {
            declared.add(ids.group(1));
        }

        assertTrue(referenced.size() >= 20, "没抓到元素引用：" + referenced);

        List<String> missing = referenced.stream()
                .filter(id -> !declared.contains(id))
                .filter(id -> !GENERATED_IDS.contains(id))
                .filter(id -> GENERATED_PREFIXES.stream().noneMatch(id::startsWith))
                .collect(Collectors.toList());
        assertEquals(new ArrayList<String>(), missing,
                "app.js 引用了 index.html 里不存在的元素 id（交互会静默失效）：" + missing);
    }

    @Test
    @DisplayName("index.html 引用的两个静态资源都真实存在，且没有外链（内网离线可用）")
    void htmlIsSelfContained() {
        String html = text(HTML);

        assertTrue(html.contains("app.js") && html.contains("app.css"));
        assertTrue(!Pattern.compile("(?:src|href)\\s*=\\s*[\"'](?:https?:)?//").matcher(html).find(),
                "页面不允许引用外部 CDN：内网/离线环境必须能打开");
        assertTrue(Files.exists(uiDir().resolve("app.js")) && Files.exists(uiDir().resolve("app.css")));
    }

    @Test
    @DisplayName("有 node 就跑一次 node --check（手写 JS 最大的风险是语法错）")
    void scriptSyntaxIsValid(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "当前环境没有 node，跳过语法检查");

        Path script = tempDir.resolve("app.js");
        Files.write(script, text(JS).getBytes(StandardCharsets.UTF_8));

        Process process = new ProcessBuilder("node", "--check", script.toString())
                .redirectErrorStream(true).start();
        String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        int code = process.waitFor();

        assertEquals(0, code, "node --check 失败：" + output);
    }

    // ------------------------------------------------------------------ 工具

    /** 去掉块注释与行注释：注释里的示例路径不该被当成"调用了接口"（也避免正则误判）。 */
    private static String stripComments(String script) {
        return script.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** 抓出单引号 / 双引号字符串字面量（够用即可：只用于白名单与硬编码检查）。 */
    private static Set<String> stringLiterals(String script) {
        Set<String> literals = new LinkedHashSet<String>();
        Matcher matcher = Pattern.compile("'([^'\\\\\\n]*)'|\"([^\"\\\\\\n]*)\"").matcher(script);
        while (matcher.find()) {
            literals.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        return literals;
    }

    /** 静态资源在构建产物里的目录（用 classpath 定位，避免依赖 Maven 的工作目录约定）。 */
    private static Path uiDir() {
        try {
            java.net.URL url = IfmapAdminUiScriptTest.class.getClassLoader().getResource(JS);
            if (url == null) {
                throw new IllegalStateException("classpath 里找不到 " + JS);
            }
            return Path.of(url.toURI()).getParent();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(String classpathResource) {
        try (InputStream in = IfmapAdminUiScriptTest.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("classpath 里找不到 " + classpathResource);
            }
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            process.getInputStream().close();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
