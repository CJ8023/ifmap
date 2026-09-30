# ifmap

[![ci](https://github.com/CJ8023/ifmap/actions/workflows/ci.yml/badge.svg)](https://github.com/CJ8023/ifmap/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-8%20%7C%2011%20%7C%2017%20%7C%2021-orange.svg)](#3-模块)
[![docs](https://img.shields.io/badge/docs-mkdocs--material-4b8bbe.svg)](docs/index.md)

> **I**nterface **F**ield **MAP**ping —— 面向银行/资金方接口报文的**声明式字段映射引擎**（Spring Boot Starter + 纯 Java 库）。

用「一份 JSON 模板」描述「源报文 → 目标报文」的映射关系：取值、拼装、码值翻译、日期与金额处理全部在模板里声明，
业务代码不再为每家银行写一个转换类。

```json
{
  "bizNode": "GP81",
  "orderNo": "$.orderNo",
  "dueDate": "@FUN(dateFormat,$.dueDate,yyyyMMdd)",
  "totalAmount": "@FUN(numSum,$.amounts,2,HALF_UP)",
  "invoiceTypeName": "@FUN(dictVal,$.invoiceType,01:增值税专用发票;02:增值税普通发票,其他)",
  "items@array@$.items": {
    "sku": "$.sku",
    "amount": "@FUN(numRound,$.price,2)"
  }
}
```

---

## 1. 当前状态（W1 骨架 + W2 配置仓储 + W3 Starter + W4 编排与策略 + W5 管理端 REST + W6 日志与合规 + W7 开源工程化 + W8 迁移预热）

| 项 | 状态 |
| --- | --- |
| 版本 | `0.1.0-SNAPSHOT`（里程碑 1，未发布到中央仓库） |
| 已落地模块 | `ifmap-core`、`ifmap-json-jackson`、`ifmap-json-tck`、`ifmap-provider-jdbc`、`ifmap-spring-boot-starter`、`ifmap-admin-spring-boot-starter`、`ifmap-demo-pure-java`、`ifmap-demo-spring-boot3` |
| 编译验证 | JDK **8** 与 JDK **17** 均 `BUILD SUCCESS`（core/json/provider 字节码目标 Java 8，`major version: 52`；starter 为 Java 17，`major version: 61`） |
| 测试 | **321 个**单元/端到端测试全绿（core 155 + json-jackson 46 + provider-jdbc 33 + starter 41 + admin 42 + demo-sb3 4；JDK 8 侧 234 个，Spring Boot 3 模块按剖面跳过） |
| 静态分析 | SpotBugs（`effort=max` / `threshold=medium`，绑定 `verify`，JDK 11+ 启用）：**0 缺陷**；排除清单逐条写明理由（`spotbugs-exclude.xml`） |
| 开源合规 | Apache-2.0 `LICENSE` + `NOTICE` + 全量源文件许可头（`LicenseHeaderTest` 在 `mvn test` 里自动拦漏加）；`CONTRIBUTING.md` + 文档站 |
| 文档站 | MkDocs + Material（`mkdocs.yml`，`--strict` 全绿：11 页），CI 独立 job 构建 |
| 迁移预热 | 存量表迁移 SQL kit（加列/回填/改类型/改表名，含体检与核对脚本）、`JsonOps` 一致性 TCK、影子运行框架（双跑比对、不出网、只记录）、[`docs/10-迁移指南.md`](docs/10-迁移指南.md) |
| 建表 | MySQL 5.7 / 8.0 兼容 DDL ×4 张表 + Liquibase changelog；starter 可启动期自动建表（`ifmap.ddl.auto`） |
| Spring Boot 3 | 引一个依赖 + 几行 yml 即用：自动建表、装配仓储（带缓存）、装配引擎、自动收集宿主机 `@IfmapRule` 与 5 类策略 bean |
| 执行编排 | 编排器：租户解析 → 前置接口**递归**加载 → 拓扑排序 + 环检测 → 渲染 → 出网 → 判定 → 分支动作 → 脱敏落日志；支持 dry-run 试跑与部署前契约自检 |
| 管理端 REST | 配置 CRUD + 保存前校验（422 带明细）+ 乐观锁 + 变更历史快照/差异 + 一键回滚 + 逻辑分支维护 + 全量巡检（Markdown 报告）；**默认关闭**，需自行加鉴权 |
| 日志与合规 | 执行日志脱敏（值形态 + 字段名）+ 超长截断 + 写日志失败降级 + **保留期清理**（默认 90 天，分批删除防主从延迟；cron 可配，守护线程调度、不依赖 `@EnableScheduling`） |
| License | Apache-2.0（`LICENSE` + `NOTICE`；贡献约定见 `CONTRIBUTING.md`） |

**尚未落地**（见 `docs/` 与整体设计文档 W8 计划）：Fastjson 实现、Feign 数据源、管理端可视化、分区/冷热分离。

> 迁移预热（W8）已交付：存量表迁移 SQL kit（`db/migration/ecc-to-ifmap/`，随 jar 发布、需手工执行）、
> `JsonOps` TCK 一致性套件（`ifmap-json-tck`）、影子运行框架（`cn.cj.ifmap.core.shadow`）、
> 公开迁移指南 [`docs/10-迁移指南.md`](docs/10-迁移指南.md)。

---

## 2. 解决什么问题

如果你们现在的接口映射是「每家银行一个 `XxxConvertRule` 类 + 模板里写 `@FUN(...)` 反射调用」，通常会有下面这些坑；
ifmap 逐条对齐修正：

| 存量常见问题 | ifmap 的做法 |
| --- | --- |
| 规则名拼错（如 `farmatDate`）运行期**静默返回 null**，或把 `@FUN(...)` **原文写进报文** | 启动期模板校验，规则不存在直接**失败并列出模板标识 + 规则名** |
| 每家银行一份转换代码，几百个类 | 模板声明式配置，新增资方 = 新增一份模板数据 |
| 码值映射硬编码在 Java 里（30+ 处） | `dictVal` / `dictValArray` 表达式配置化，含默认值与回退 |
| 金额舍入方式各家不一致 | 所有金额规则**必须显式指定**小数位与舍入方式 |
| 客户数据（统一社会信用代码等）硬编码在规则里 | 内置规则只保留**通用**能力，客户业务规则通过 `@IfmapRule` 外挂 |
| 每条 JsonPath 都重新解析一遍源报文 | `JsonReadContext` **解析一次、多次取值** |
| 公开 API 泄漏 JSON 库类型（fastjson 到处流转） | `JsonOps` SPI 隔离，公开 API **只有 JDK 类型** |

---

## 3. 模块

| 模块 | 说明 | Java | 依赖 |
| --- | --- | --- | --- |
| `ifmap-parent` | 父 POM，统一版本与插件版本 | — | — |
| `ifmap-core` | 模板 DSL、规则注册表、内置规则、SPI。**零 Spring、零 JSON 库** | 8+ | 仅 `slf4j-api` |
| `ifmap-json-jackson` | `JsonOps` 的 Jackson + JsonPath 实现（默认） | 8+ | jackson-databind、json-path |
| `ifmap-provider-jdbc` | `ConfigRepository` 的 JDBC 实现 + 4 张表建表脚本（Liquibase） | 8+ | spring-jdbc |
| `ifmap-spring-boot-starter` | Spring Boot 3 自动配置：建表、仓储（可选缓存）、引擎、规则自动收集 | 17+ | starter-jdbc、provider-jdbc、json-jackson、caffeine(可选) |
| `ifmap-admin-spring-boot-starter` | 管理端 REST（配置 CRUD、校验、历史与回滚、分支维护、巡检）；`ifmap.admin.enabled=true` 才装配 | 17+ | starter、provider-jdbc、starter-web、autoconfigure |
| `ifmap-demo-pure-java` | 纯 Java（非 Spring）可运行示例 | 8+ | core + json-jackson |
| `ifmap-demo-spring-boot3` | Spring Boot 3 可运行示例（H2 内存库，`java -jar` 即跑通全链路） | 17+ | starter |

依赖方向严格单向：`demo → json-jackson → core`、`provider-jdbc → core`，**core 不反向依赖任何实现**。

`ifmap-provider-jdbc` 的 `spring-jdbc` 就地声明为 **5.3.x**（JDK 8 + Spring 5 兼容），只使用 Spring 5.3 / 6.2 共有的 `JdbcTemplate` API；
Spring Boot 3 项目引 `ifmap-spring-boot-starter` 时会解析到 Boot 传递来的 **6.2.x**（starter 把 `spring-boot-starter-jdbc` 声明在 `ifmap-provider-jdbc` 之前）。

---

## 4. 30 秒上手

```xml
<dependency>
  <groupId>cn.cj</groupId>
  <artifactId>ifmap-json-jackson</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
// 1. 建引擎（自动发现 JsonOps 实现 + 注册 18 个内置规则）
IfmapEngine engine = IfmapEngine.createDefault();

// 2. 启动期校验模板：规则名写错在这里就失败（而不是运行期静默返回 null）
engine.validateTemplate("czb-apply-01", templateJson);

// 3. 渲染
String target = engine.render(templateJson, sourceJson);

// 3'. 需要租户/接口号/工作日历等环境信息时
RuleContext ctx = RuleContext.builder().tenantId("T001").interfaceNo("IF001").build();
String target2 = engine.render(templateJson, sourceJson, ctx);
```

运行示例（含「规则名拼错被拦下」「数组列转行」等 5 段演示）：

```bash
mvn -q -pl ifmap-demo-pure-java exec:java            # 或直接运行 cn.cj.ifmap.demo.IfmapQuickStart
```

### Spring Boot 3 项目（更省事）

```xml
<dependency>
  <groupId>cn.cj</groupId>
  <artifactId>ifmap-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
ifmap:
  table-prefix: ifmap_      # 复用存量表时改成 bankint_
  ddl:
    auto: true              # 启动自动建表（表已存在则跳过）
```

之后直接 `@Autowired IfmapEngine engine;` / `@Autowired ConfigRepository repository;` / `@Autowired IfmapOrchestrator orchestrator;` 即可：

- 自定义规则只要是个 Spring bean（方法带 `@IfmapRule`）就会被自动收集；
- 自定义策略只要是个 Spring bean（`SpecialDealStrategy` / `@FullParam` 的 `FullParamStrategy` / `@LogicBranch` / `@IfmapAction`）就会被自动注册，**缺注解启动即失败**。

```java
IfmapResult result = orchestrator.execute(
        IfmapRequest.builder().tenantId("1001").bizId("B-1").put("orgCode", "12").build(),
        "BIZ_APPLY", "apply");
```

详见 [`docs/05-SpringBoot集成.md`](docs/05-SpringBoot集成.md) 与 [`docs/06-执行编排与策略扩展.md`](docs/06-执行编排与策略扩展.md)。

---

## 5. 模板 DSL 速览

| 写法 | 位置 | 语义 |
| --- | --- | --- |
| `$.a.b` | value | JsonPath 取值（通配 `*`、递归 `..`、过滤 `[?(@.x=='y')]`、切片 `[0:2]`、函数 `length()` 全支持） |
| `GP81` | value | 不以 `$.`/`@` 开头的字符串 = **字面量常量** |
| `@FUN(name,arg1,arg2)` | value | 调用规则；参数支持嵌套 `@FUN`、`$.` 路径、字面量 |
| `$.seqNo` | value | 内置流水号 `yyyyMMddHHmmssSSS` + 3 位随机数 |
| `a@b@c` 形式的 `@and@` | value | 多路取值**合并为数组**（List 会被展平） |
| `@or@` | value | 多路取值取**第一个非空**；全空则按空值策略处理 |
| `@concat@` / `@append@` | value | 多路取值用半角逗号 / 无分隔符拼接 |
| `@sum@` | value | 多路取值按数值求和（非数值按 0） |
| `key@array` | key | 值统一变数组：List 原样；**Map 做「列转行」**；标量包成单元素数组 |
| `name@array@$.items` | key | 按源数组**逐元素展开子模板**（每个元素成为子模板的源报文） |

完整语法与语义细则见 [`docs/02-模板DSL语法.md`](docs/02-模板DSL语法.md)。

---

## 6. 内置规则（18 个规则名 / 30 个规则方法）

`concat`、`strDefault`、`strTruncate`、`strMask`、`dictVal`、`dictValArray`、
`dateFormat`、`dateConvert`、`dateAdd`、`dateDiff`、`dateEndOfMonth`、`workdayAdd`、
`numRound`、`numSum`、`numOffset`、`numFormat`、`listJoin`、`listOp`

- 口径：**规则名 18 个、规则方法（含重载）30 个**；复现 `mvn -q -pl ifmap-demo-pure-java exec:java` 或 `engine.describeRules()`（输出可直接贴文档的 Markdown 表格）
- 扩展方式：方法上加 `@IfmapRule("name")`，类交给 `RuleRegistry.register(bean)`
- 上下文注入：规则方法**第一个参数**声明为 `RuleContext` 即自动注入，模板里不用传
- 逐元素自动映射：形参是 `String`、实参是 `List` 时自动逐元素调用并收集（深度上限 3）

详见 [`docs/03-规则清单与扩展.md`](docs/03-规则清单与扩展.md)。

---

## 7. 空值策略

| 策略 | 行为 |
| --- | --- |
| `SKIP_FIELD`（默认） | 字段整条不出现在目标报文里 |
| `EMPTY_STRING` | 输出空字符串 |
| `FAIL` | 抛 `IfmapConfigException`，并指出出问题的模板字段 |

**任何情况下都不会把 `@FUN(...)` 或 `$.path` 原文回写进报文**（存量引擎会）。

```java
IfmapEngine engine = IfmapEngine.builder()
        .nullPolicy(NullPolicy.FAIL)
        .register(new MyBankRules())   // 追加自定义规则
        .build();
```

---

## 8. 构建与测试

```bash
# JDK 8 / 11 / 17 / 21 均可构建（core 目标字节码 Java 8）
mvn clean test
mvn -pl ifmap-core test                 # 只跑 core（不依赖 JSON 库）

# Spring Boot 3 模块（starter + demo-sb3）要求 JDK 17+：
# 父 POM 用 <jdk>[17,)</jdk> 剖面自动装卸，JDK 8/11 上执行 mvn test 不会失败
mvn -pl ifmap-spring-boot-starter -am test
mvn -pl ifmap-admin-spring-boot-starter -am test   # 管理端 REST（含 H2 + 真 Tomcat 端到端）
```

Windows 下手动指定 JDK 与本地仓库：

```bat
set JAVA_HOME=D:\cj\softwares\dev\JDK\jdk17\jdk-17.0.20+8&& D:\cj\softwares\dev\apache-maven-3.9.16\bin\mvn.cmd -Dmaven.repo.local=D:\sz\mvnrep -f D:\cj\code\ifmap\pom.xml clean test
```

> 示例工程未绑定 SLF4J 实现，运行时出现 `No SLF4J providers were found` 属正常现象，日志实现由宿主提供。

---

## 9. 文档

**公开文档（随仓库发布）**

| 文档 | 内容 |
| --- | --- |
| [`docs/01-快速开始.md`](docs/01-快速开始.md) | 环境要求、三种接入方式、启动期校验、上下文与工作日历、常见问题 |
| [`docs/02-模板DSL语法.md`](docs/02-模板DSL语法.md) | DSL 全量语法、语义细则、与存量引擎的差异对照 |
| [`docs/03-规则清单与扩展.md`](docs/03-规则清单与扩展.md) | 18 个内置规则详解、自定义规则、覆盖与重载规则 |
| [`docs/04-接入与建表.md`](docs/04-接入与建表.md) | 建表脚本与 Liquibase、JDBC 仓储接入、软删除与乐观锁语义、ID 策略、排错 |
| [`docs/05-SpringBoot集成.md`](docs/05-SpringBoot集成.md) | Spring Boot 3 starter：配置项、自动装配、覆盖机制、缓存、自定义规则、策略自动收集、建表与方言自适应、排错 |
| [`docs/06-执行编排与策略扩展.md`](docs/06-执行编排与策略扩展.md) | 编排器执行顺序、三类策略 SPI、动作与回调、判定、脱敏与截断、契约自检、异常体系 |
| [`docs/07-管理端REST.md`](docs/07-管理端REST.md) | 管理端：启用与鉴权、API 一览、状态码语义、保存前校验清单、历史与回滚、试跑、巡检、排错 |
| [`docs/08-日志与合规.md`](docs/08-日志与合规.md) | 日志与合规：落什么/不落什么、脱敏、截断、失败降级、保留期清理（含分区方案对比）、异常体系、合规自查清单 |
| [`docs/09-参与贡献.md`](docs/09-参与贡献.md) | 参与贡献：开发环境、双 JDK 构建、TDD 流程、代码/测试/文档约定、开源合规、质量门禁与 PR 检查表 |
| [`docs/10-迁移指南.md`](docs/10-迁移指南.md) | 存量迁移指南（M1 表结构 / M2 规则策略对齐 / M3 影子运行 / M4 切换回滚）、共存方案、行为对齐清单、排错 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 贡献指南入口（三条底线 + 指向 `docs/09`） |

**内部文档（仅本地，已在 `.gitignore` 中排除）**

| 文档 | 内容 |
| --- | --- |
| `docs/ifmap-整体设计_v1.md` | 整体设计（分层、SPI、表结构、分期计划、ADR） |
| `docs/ifmap-内置规则审计与优化_v1.md` | 存量规则审计、P0 缺陷实证、core 规则收敛清单 |
| `docs/bankintconfig模块优化方案_v1.md` | 迁移前的现状分析与优化方案（前身文档） |
| `docs/tools/` | 规则清单/审计脚本 |

> 内部文档含客户工商数据与银行合作细节，**推送公开仓前请确认未被纳入版本控制**（`git status` 应看不到它们）。

## 10. 文档站

```bash
py -m pip install -r docs-requirements.txt   # Windows（Linux/macOS 用 python3 -m pip）
py -m mkdocs serve                            # 本地预览 http://127.0.0.1:8000
py -m mkdocs build --strict                   # 与 CI 相同的严格构建（警告即失败）
```

## 11. License

[Apache License 2.0](LICENSE) —— 版权与第三方声明见 [`NOTICE`](NOTICE)。
提交 PR 即表示同意以同一许可分发你的贡献（详见 [`CONTRIBUTING.md`](CONTRIBUTING.md)）。
