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
package cn.cj.ifmap.testkit;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JUnit 会话结束时的真库测试表清理（通过 {@code META-INF/services} 自动装配，使用方无需写任何配置）。
 *
 * <p>为什么需要它：真库档每跑一个测试类都会建一组 {@code itt_*} 表，正常退出路径靠 JVM shutdown hook 清理，
 * 但 surefire 的 fork 进程可能以 {@code Runtime.halt()} 结束 —— <b>halt 不执行 shutdown hook</b>，
 * 于是表会留在库里（实测残留）。监听器挂在 JUnit 自己的会话生命周期上，与 JVM 怎么退无关。</p>
 *
 * <p>只清本 JVM 登记过的前缀；万一监听器与测试类不在同一个类加载器（静态状态不共享），
 * 还有 {@link TestDatabases#sweepStaleTestTables(int)} 在下次运行时兜底。</p>
 *
 * @author caijun
 */
public final class TestDataCleanupListener implements LauncherSessionListener {

    private static final Logger LOGGER = Logger.getLogger("cn.cj.ifmap.testkit");

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        try {
            TestDatabases.cleanupAll();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "测试表清理失败（不影响测试结论，可手工清理 itt_* 表）：{0}", e.getMessage());
        }
    }
}
