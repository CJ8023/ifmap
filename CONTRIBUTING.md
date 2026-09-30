# 贡献指南

本仓库的贡献约定（开发环境、双 JDK 构建、TDD 流程、代码/测试/文档约定、开源合规、
质量门禁与 PR 检查表）全部写在 **[docs/09-参与贡献.md](docs/09-参与贡献.md)**，
在线版本见 <https://github.com/CJ8023/ifmap> 文档站。

三条最容易踩的底线，先写在这里：

1. **JDK 8 与 JDK 17 都要能构建**：`ifmap-core` 的字节码目标是 Java 8（`release=8`），
   Spring Boot 3 相关模块走 `<jdk>[17,)</jdk>` 剖面。
2. **先写测试、先看它红**：修 bug 必须附一条在旧代码上真的会失败的测试。
3. **内部文档不进公开仓**：`docs/` 下含客户/银行细节的设计与审计文档已被 `.gitignore` 排除，
   不要 `git add -f`。

许可：本项目以 [Apache License 2.0](LICENSE) 发布。提交 PR 即表示你同意以同一许可分发你的贡献。
