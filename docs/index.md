# ifmap

**Interface Mapping Engine** —— 配置驱动的接口报文映射引擎：用「模板 DSL + 可插拔规则」把
「一个接口的请求怎么组、响应怎么解、什么算成功、不同结果走什么动作」变成**配置**，
而不是每家资方接口重写一遍 Java 类。

```text
调用方 ──IfmapRequest──▶ ifmap 引擎 ──BankCall──▶ 宿主网关（HTTP/SDK/FTP…） ──▶ 资方
                            │
                            ├─ 模板渲染（request_param_template）
                            ├─ 出网前的特殊处理策略（加签/加密/换字段名）
                            ├─ 响应解析 + 规则链（@FUN 内置 18 个规则）
                            ├─ 成败判定（result_flag / success_value）
                            ├─ 逻辑分支动作（@IfmapAction）
                            └─ 执行日志（脱敏 + 截断 + 保留期清理）
```

## 它解决什么

| 传统做法 | 用 ifmap |
| --- | --- |
| 每家资方的组包/解包各写一个类，字段改一次要发一次版 | 改配置行（管理端 REST 或 SQL），不动代码 |
| 失败原因记在日志文本里，排查靠 grep | 执行日志结构化落库（请求/响应/命中分支/耗时），可按业务号追溯 |
| 报文里的姓名/手机号/卡号直接进日志 | 内置脱敏（值形态 + 字段名），可自定义 `LogMasker` |
| 上线靠"改完先手工点一遍" | 保存前校验（模板契约/JsonPath/策略名/前置链/唯一键）+ 试跑 + 历史回滚 |

## 特点

- **零侵入**：核心模块不依赖 Spring、不依赖任何 JSON 库（`JsonOps` SPI 可换 Jackson/Gson/fastjson）。
- **可独立使用**：纯 Java 也能跑（`ifmap-demo-pure-java`），不是只能当 Spring Boot starter 用。
- **装完即用**：starter 自带建表（MySQL 5.7/8.0 与 H2 都能跑同一份 DDL），执行日志默认保留 90 天并按批清理。
- **双 JDK 支持**：core 字节码目标 Java 8（`release=8`），Spring Boot 3 相关模块走 JDK 17 剖面。
- **可审计**：每次配置变更自动写快照（改前/改后 + 差异），回滚就是再存一版。

## 60 秒上手

```xml
<dependency>
  <groupId>cn.cj</groupId>
  <artifactId>ifmap-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
ifmap:
  tenant-id: 1                 # 可选默认租户
  table-prefix: ifmap_         # 复用存量表时改成你的前缀（如 bankint_）
  log:
    retention-days: 90         # 执行日志保留天数
```

```java
@RestController
public class DemoController {

    private final IfmapOrchestrator orchestrator;   // starter 自动装配

    public DemoController(IfmapOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/apply")
    public IfmapResult apply(@RequestBody Map<String, Object> body) {
        // 接口号 + 业务节点 定位配置，走完"组包 → 出网 → 解析 → 判定 → 分支动作"
        return orchestrator.dispatch("API_APPLY", "apply", IfmapRequest.of(body));
    }
}
```

配置怎么配、DSL 怎么写：见 **[快速开始](01-快速开始.md)** 与 **[模板 DSL 语法](02-模板DSL语法.md)**。

## 模块

| 模块 | JDK | 说明 |
| --- | --- | --- |
| `ifmap-core` | 8 | 引擎、模板 DSL、18 个内置规则、SPI（零第三方运行时依赖，只有 slf4j-api） |
| `ifmap-json-jackson` | 8 | `JsonOps` 的 Jackson 实现 |
| `ifmap-provider-jdbc` | 8 | 建表脚本（Liquibase 格式 SQL）、JDBC 仓储、执行日志清理 |
| `ifmap-spring-boot-starter` | 17 | Spring Boot 3 自动装配（引擎/仓储/策略收集/建表/日志清理） |
| `ifmap-admin-spring-boot-starter` | 17 | 管理端 REST（配置 CRUD/校验/试跑/历史回滚/巡检），默认关闭 |
| `ifmap-demo-pure-java` | 8 | 纯 Java 用法示例 |
| `ifmap-demo-spring-boot3` | 17 | Spring Boot 3 用法示例（可 `java -jar` 直接跑） |

## 文档

| 文档 | 内容 |
| --- | --- |
| [快速开始](01-快速开始.md) | 最小可运行示例、依赖、建表、第一条配置 |
| [模板 DSL 语法](02-模板DSL语法.md) | 模板取值、`@FUN` 规则调用、`@array@`、空值策略 |
| [规则清单与扩展](03-规则清单与扩展.md) | 18 个内置规则参数与示例、自定义 `@IfmapRule`（方法式） |
| [接入与建表](04-接入与建表.md) | 表结构、前缀替换、MySQL 5.7/8.0 差异、分区方案、迁移 |
| [Spring Boot 集成](05-SpringBoot集成.md) | 配置项、自动装配条件、关闭/覆盖 bean、宿主扩展点 |
| [执行编排与策略扩展](06-执行编排与策略扩展.md) | 前置接口链、特殊处理策略、逻辑分支动作、回调 |
| [管理端 REST](07-管理端REST.md) | 端点、状态码契约、乐观锁、历史与回滚、鉴权建议 |
| [日志与合规](08-日志与合规.md) | 脱敏规则、截断、写日志降级、保留期清理、合规自查清单 |
| [参与贡献](09-参与贡献.md) | 开发环境、双 JDK 构建、测试与质量门禁、提交检查表 |

## 许可

[Apache License 2.0](https://github.com/CJ8023/ifmap/blob/main/LICENSE) —— 详见
[NOTICE](https://github.com/CJ8023/ifmap/blob/main/NOTICE)。

## 状态

当前进度：核心 → 仓储 → Starter → 编排/策略 → 管理端 → 日志合规 **已交付**；
迁移预热（存量项目迁移脚本、`JsonOps` TCK、影子跑）在计划中。
每个模块的测试数与验证命令见仓库根目录 `README.md` 与 `CHANGELOG.md`。
