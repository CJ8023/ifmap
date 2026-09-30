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
package cn.cj.ifmap.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开源合规自检：LICENSE / NOTICE 存在，且每个源文件都带 Apache-2.0 许可头。
 *
 * <p>用测试而不是 license-maven-plugin 的理由：本仓库要求"零新增构建期依赖也能跑通校验"，
 * 而且这样在 {@code mvn test}（含 JDK 8 矩阵）里就会拦住漏加许可头的提交 ——
 * 靠人肉 review 一定会漏。</p>
 *
 * @author caijun
 */
class LicenseHeaderTest {

    /** 与 LICENSE 一致的版权行（Apache 规范：Copyright [年份] [版权人]）。 */
    static final String COPYRIGHT = "Copyright 2026 caijun";

    /** 许可头里的关键信息（两条足以区分"真许可头"与"随便一段注释"）。 */
    private static final String MARK_LICENSE = "Licensed under the Apache License, Version 2.0";
    private static final String MARK_URL = "http://www.apache.org/licenses/LICENSE-2.0";

    /** 扫描时要跳过的目录（构建产物、IDE、Git 内部）。 */
    private static final List<String> SKIP_DIRS = Arrays.asList(
            "target", ".git", ".idea", ".mvn", "node_modules", "site");

    @Test
    @DisplayName("仓库根目录的 LICENSE 与 NOTICE 都存在且非空")
    void licenseAndNoticeExist() throws IOException {
        Path root = repoRoot();

        Path license = root.resolve("LICENSE");
        assertTrue(Files.isRegularFile(license), "缺少 LICENSE 文件：" + license);
        String licenseText = new String(Files.readAllBytes(license), StandardCharsets.UTF_8);
        assertTrue(licenseText.contains("Apache License"), "LICENSE 必须是 Apache-2.0 全文");
        assertTrue(licenseText.contains("Version 2.0"), "LICENSE 里应出现 Version 2.0");

        Path notice = root.resolve("NOTICE");
        assertTrue(Files.isRegularFile(notice), "缺少 NOTICE 文件：" + notice);
        String noticeText = new String(Files.readAllBytes(notice), StandardCharsets.UTF_8);
        assertTrue(noticeText.contains("ifmap"), "NOTICE 应写明产品名");
        assertTrue(noticeText.contains(COPYRIGHT), "NOTICE 的版权行应与源码许可头一致");
    }

    @Test
    @DisplayName("每个 .java 源文件都以 Apache-2.0 许可头开始")
    void everyJavaFileHasLicenseHeader() throws IOException {
        List<Path> javaFiles = collectSources(repoRoot(), ".java");
        assertTrue(javaFiles.size() > 100, "源文件数量异常（" + javaFiles.size() + "），"
                + "扫描根目录可能找错了");

        List<String> offenders = new ArrayList<String>();
        for (Path file : javaFiles) {
            String head = readHead(file, 4096);
            if (!head.startsWith("/*") || !head.contains(COPYRIGHT)
                    || !head.contains(MARK_LICENSE) || !head.contains(MARK_URL)) {
                offenders.add(relative(file));
            }
        }
        assertEquals(new ArrayList<String>(), offenders, describe(offenders, ".java 文件缺少许可头"));
    }

    @Test
    @DisplayName("SQL 脚本也带许可头（Liquibase changelog 的标记行必须仍在首行）")
    void sqlFilesHaveLicenseHeader() throws IOException {
        List<Path> sqlFiles = collectSources(repoRoot(), ".sql");
        assertTrue(sqlFiles.size() >= 5, "SQL 脚本数量异常（" + sqlFiles.size() + "）");

        List<String> offenders = new ArrayList<String>();
        List<String> badMarker = new ArrayList<String>();
        for (Path file : sqlFiles) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String head = headLines(text, 30);
            if (!head.contains(COPYRIGHT) || !head.contains(MARK_LICENSE)) {
                offenders.add(relative(file));
            }
            // Liquibase 要求 `--liquibase formatted sql` 出现在任何 changeset 之前
            if (file.getFileName().toString().matches("^\\d{3}-.*\\.sql$")
                    && !text.startsWith("--liquibase formatted sql")) {
                badMarker.add(relative(file));
            }
        }
        assertEquals(new ArrayList<String>(), offenders, describe(offenders, "SQL 脚本缺少许可头"));
        assertEquals(new ArrayList<String>(), badMarker,
                describe(badMarker, "Liquibase changelog 的首行必须仍是 --liquibase formatted sql"));
    }

    // ------------------------------------------------------------------ 工具

    /** 从当前工作目录向上找仓库根（含 ifmap-parent 的 pom.xml 且有 ifmap-core 子目录）。 */
    static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom) && Files.isDirectory(dir.resolve("ifmap-core"))
                    && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录（从 " + Paths.get("").toAbsolutePath() + " 向上查找失败）");
    }

    /** 收集根目录下所有指定后缀的源文件（跳过构建产物与 IDE 目录）。 */
    private static List<Path> collectSources(Path root, String suffix) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .filter(p -> !skip(p, root))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static boolean skip(Path file, Path root) {
        Path relative = root.relativize(file);
        for (Path part : relative) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String readHead(Path file, int maxChars) {
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return text.length() > maxChars ? text.substring(0, maxChars) : text;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String headLines(String text, int lines) {
        String[] parts = text.split("\n", -1);
        int end = Math.min(lines, parts.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            sb.append(parts[i]).append('\n');
        }
        return sb.toString();
    }

    private static String relative(Path file) {
        return repoRoot().relativize(file).toString().replace('\\', '/');
    }

    private static String describe(List<String> offenders, String what) {
        if (offenders.isEmpty()) {
            return "";
        }
        List<String> shown = offenders.size() > 10 ? offenders.subList(0, 10) : offenders;
        return what + "：共 " + offenders.size() + " 个，前若干：" + shown;
    }
}
