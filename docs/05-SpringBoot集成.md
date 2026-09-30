# 05 · Spring Boot 3 集成（`ifmap-spring-boot-starter`）

> 适用版本：`0.1.0-SNAPSHOT`　作者：caijun　要求：JDK 17+ / Spring Boot 3.x

引入一个依赖、写几行 yml，就完成：**自动建表 → 装配仓储（带缓存）→ 装配引擎 → 自动收集宿主机规则**。
以下内容与仓库里的 `ifmap-demo-spring-boot3` 示例工程一一对应，可直接对照运行。

---

## 1. 30 秒接入

```xml
<dependency>
  <groupId>cn.cj</groupId>
  <artifactId>ifmap-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/ifmap?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: ifmap
    password: ******
    driver-class-name: com.mysql.cj.jdbc.Driver

ifmap:
  table-prefix: ifmap_     # 复用存量表时改成 bankint_
  ddl:
    auto: true             # 启动自动建表（表已存在则跳过）
```

```java
@Service
public class ApplyService {

    private final IfmapEngine engine;
    private final ConfigRepository repository;

    public ApplyService(IfmapEngine engine, ConfigRepository repository) {
        this.engine = engine;
        this.repository = repository;
    }

    public String buildRequest(String tenantId, String interfaceNo, String busiNode, String sourceJson) {
        IfmapConfig config = repository.queryConfigs(tenantId, interfaceNo, busiNode).get(0);
        // 渲染：null 走策略，规则名写错在启动期就被拦下
        return engine.render(config.getRequestParamTemplate(), sourceJson);
    }
}
```

就这些。建表、仓储、引擎、规则注册全部由 starter 完成，无需 `@EnableXxx`、无需手写 `@Configuration`。

### 1.1 可运行的最小示例

```bash
mvn -pl ifmap-demo-spring-boot3 -am -DskipTests package
java -jar ifmap-demo-spring-boot3/target/ifmap-demo-spring-boot3-0.1.0-SNAPSHOT.jar
```

示例用 **H2 内存库**，无需任何外部依赖就能跑通全链路，实测输出（日志截取）：

```
IfmapSchemaInitializer : ifmap 检测到非 MySQL 数据库（H2），建表时将剥掉表尾 MySQL 表选项
IfmapSchemaInitializer : ifmap 已建表：[ifmap_config]（脚本 db/changelog/v1.0.0/001-create-ifmap-config.sql）
IfmapSchemaInitializer : ifmap 已建表：[ifmap_logic_branch_config]（脚本 .../002-create-logic-branch.sql）
IfmapSchemaInitializer : ifmap 已建表：[ifmap_execution_log]（脚本 .../003-create-execution-log.sql）
IfmapSchemaInitializer : ifmap 已建表：[ifmap_config_history]（脚本 .../004-create-config-history.sql）
DemoRunner             : 已写入演示配置 keyId=893079111570821120
DemoRunner             : 从库里查到 1 条配置（表已由 starter 自动建好）
DemoRunner             : 渲染结果：{"orgNo":"000012","applyNo":"AP20250101001","applyDate":"20250308","acctName":"张*"}
```

其中 `orgNo` 来自示例里的**宿主机自定义规则** `@IfmapRule("bankOrgNo")`，`applyDate` / `acctName` 来自内置规则。

---

## 2. 配置项

前缀 `ifmap`，全部有默认值，**不配也能跑**。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `ifmap.enabled` | `true` | 总开关。`false` 时 starter 一个 bean 都不装配（宿主完全接管） |
| `ifmap.table-prefix` | `ifmap_` | 表名前缀。只允许 `[A-Za-z0-9_]`、≤32 字符、首字符为字母或下划线，否则启动即失败 |
| `ifmap.id-strategy` | `snowflake` | `snowflake`（应用生成）或 `auto-increment`（交给数据库自增） |
| `ifmap.worker-id` | 未设 | `snowflake` 下建议显式指定。未设时按主机名派生，见 `docs/04` §7.1 |
| `ifmap.null-policy` | `skip-field` | `skip-field` / `empty-string` / `fail` |
| `ifmap.ddl.auto` | `true` | 启动建表。生产由 DBA 建表时置 `false` |
| `ifmap.cache.enabled` | `true` | 逻辑分支查询缓存。`false` 时每次查库 |
| `ifmap.cache.maximum-size` | `1000` | 缓存条目上限（超出按 LRU 淘汰） |
| `ifmap.cache.ttl` | `60s` | 条目存活时间，`0` 表示永不过期 |
| `ifmap.tenant-header` | `X-Tenant-Id` | 取租户的请求头名（上下文显式给了 `tenantId` 时不看头） |
| `ifmap.default-tenant-id` | `-1` | 上下文与请求头都取不到时的兜底租户 |
| `ifmap.orchestrator.stop-on-failure` | `true` | 多条配置中一条判定失败即停止后续执行 |
| `ifmap.orchestrator.fail-on-missing-strategy` | `true` | `strategy_name` 找不到策略时快速失败（`false` 则 WARN 跳过） |
| `ifmap.orchestrator.fail-on-missing-action` | `true` | `method_flag` 找不到动作时快速失败（`false` 则 WARN 跳过） |
| `ifmap.log.enabled` | `true` | 是否装配执行日志落库（`ExecutionLogSink`） |
| `ifmap.log.truncate-threshold` | `65536` | 单条报文落库最大长度，超出尾部替换为 `...truncated` |
| `ifmap.log.mask-fields` | 空（= 内置姓名类字段） | 按**字段名**脱敏的字段列表 |
| `ifmap.log.exclude-fields` | 空 | 这些字段**整体**替换为 `***` |

`duration` 支持 `60s` / `5m` / `1h` / `PT30S` 等 Spring 写法。

> `ifmap.log.mask-fields` 留空 = 用内置默认（`acctName` / `accountName` / `certName` / `userName` /
> `realName` / `legalName` / `legalPerson` / `contactName`）。想**完全关闭**按字段名脱敏请自定义 `LogMasker` bean
> —— 留空不代表关闭，否则姓名会原样落库。

---

## 3. 自动装配了什么

| Bean | 类型 | 来源 |
| --- | --- | --- |
| `ifmapTableNameResolver` | `TableNameResolver` | 前缀校验 + 四张表名 |
| `ifmapConfigRepository` | `ConfigRepository` | `JdbcConfigRepository`，缓存开启时包一层 `CachingConfigRepository` |
| `ifmapConfigWriter` | `JdbcConfigWriter` | 管理端写：新增 / 乐观锁更新 / 软删除 |
| `ifmapEngine` | `IfmapEngine` | 内置规则 18 个 + `JacksonJsonOps` + 空值策略 |
| `ifmapIdGenerator` | `IdGenerator` | 雪花（默认）或数据库自增 |
| `ifmapRuleRegistry` | `RuleRegistry` | 预注册内置规则，再自动收集宿主机 `@IfmapRule` |
| `ifmapRuleRegistrar` | `BeanPostProcessor` | 扫描宿主机 bean，把 `@IfmapRule` 方法注册进注册表 |
| `ifmapSchemaInitializer` | `IfmapSchemaInitializer` | `ifmap.ddl.auto=true` 时装配，读生产 DDL 建表 |
| `ifmapTenantResolver` | `TenantResolver` | `HeaderTenantResolver`（读 `ifmap.tenant-header`） |
| `ifmapLogMasker` | `LogMasker` | `DefaultLogMasker`（值形态 + 字段名两种脱敏） |
| `ifmapClockProvider` | `ClockProvider` | `SystemClockProvider`（测试可换假时钟） |
| `ifmapSpecialDealStrategyRegistry` | `SpecialDealStrategyRegistry` | 特殊处理策略注册表（key = bean 名） |
| `ifmapFullParamStrategyRegistry` | `FullParamStrategyRegistry` | 组包策略注册表（key = `bank|busi`） |
| `ifmapLogicBranchStrategyRegistry` | `LogicBranchStrategyRegistry` | 分支条件策略注册表（key = 分支 flag） |
| `ifmapActionRegistry` | `ActionRegistry` | 分支动作注册表（key = `method_flag`） |
| `ifmapCallbackRegistry` | `CallbackRegistry` | 回调处理器注册表（key = 接口号） |
| `ifmapContractValidator` | `ContractValidator` | 部署前契约自检（模板 JSON + `@FUN` 规则名/参数） |
| `ifmapStrategyRegistrar` | `BeanPostProcessor` | 扫描宿主机 bean，自动注册 5 类策略（缺注解即启动失败） |
| `ifmapExecutionLogSink` | `ExecutionLogSink` | 执行日志落库（`ifmap.log.enabled=true` 时装配） |
| `ifmapOrchestrator` | `IfmapOrchestrator` | 编排器。**需要 `ConfigRepository`**，无 `DataSource` 时不装配 |

**装配顺序**：`IfmapAutoConfiguration` 在 `DataSourceAutoConfiguration` 之后执行；`IfmapSchemaInitializer` 是
`InitializingBean`，仓储 bean 通过 `ObjectProvider<IfmapSchemaInitializer>` 强制其先生成，保证「建表 → 用表」。

### 3.1 全部可覆盖

starter 里**每一个 bean 都带 `@ConditionalOnMissingBean`**：宿主声明同类型 bean 即完全接管。

```java
@Configuration
public class MyIfmapConfig {

    // 用自研的 JsonOps 实现（例如 fastjson2），starter 不再装配 Jackson 实现
    @Bean
    public JsonOps jsonOps() {
        return new Fastjson2JsonOps();
    }

    // 完全接管引擎：自己挑规则、自己配空值策略
    @Bean
    public IfmapEngine ifmapEngine(RuleRegistry registry, JsonOps jsonOps) {
        return IfmapEngine.builder()
                .builtins(false)
                .registry(registry)
                .jsonOps(jsonOps)
                .nullPolicy(NullPolicy.FAIL)
                .build();
    }

    // 接管仓储：从配置中心读（不落库）
    @Bean
    public ConfigRepository configRepository(MyConfigCenter center) {
        return new ConfigCenterRepository(center);
    }
}
```

### 3.2 缓存实现：Caffeine 可选

| 条件 | 使用的实现 | 位置 |
| --- | --- | --- |
| classpath 上有 Caffeine | `CaffeineConfigCache` | starter（`caffeine` 是 `optional` 依赖） |
| 没有 Caffeine | `InMemoryConfigCache`（LRU + 惰性 TTL） | `ifmap-core`（零第三方依赖） |
| `ifmap.cache.enabled=false` | 不装配缓存，仓储直连数据库 | — |

两种实现都遵循同一语义：**loader 返回 null 不缓存、抛异常不缓存**；缓存 key 含 `tenantId`，避免跨租户串配置。

---

### 3.3 编排器与策略：写个 bean 就行（W4）

```java
// 1) 特殊处理策略：key = bean 名，配置里 strategy_name 直接写 czbApplyStrategy
@Component
public class CzbApplyStrategy implements SpecialDealStrategy {
    @Override
    public Map<String, Object> apply(StrategyContext context) {
        return Collections.singletonMap("channelCode", "ECC");
    }
}

// 2) 组包策略：注解声明 (bankCode, busiNode)，支持 * 通配
@Component
@FullParam(bankCode = "CMB", busiNode = "*")
public class CmbFullParam implements FullParamStrategy {
    @Override
    public Map<String, Object> assemble(StrategyContext context) {
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("bankCode", "CMB");
        return full;
    }
}

// 3) 分支动作：注解值 = ifmap_logic_branch_config.method_flag
@Component
@IfmapAction("submit")
public class SubmitAction implements IfmapActionHandler {
    @Override
    public void execute(StrategyContext context) { /* 写业务表 / 发消息 */ }
}
```

然后注入编排器直接调用：

```java
@Autowired
private IfmapOrchestrator orchestrator;

public IfmapResult call(IfmapRequest request) {
    return orchestrator.execute(request, "BIZ_APPLY", "apply");
}
```

| 要点 | 说明 |
| --- | --- |
| 自动收集 | 5 类策略 bean（`SpecialDealStrategy` / `FullParamStrategy` / `LogicBranchStrategy` / `IfmapActionHandler` / `IfmapCallbackHandler`）由 `ifmapStrategyRegistrar` 启动期注册 |
| 启动期快速失败 | 实现了策略接口但**缺注解**（`@FullParam` / `@LogicBranch` / `@IfmapAction` / `@IfmapCallback`）→ 启动即报错并指出 bean 名 + 类名 |
| 代理安全 | 注解查找会沿父类链与接口链进行，AOP 代理过的 bean 同样识别 |
| 编排器不装配？ | 编排器依赖 `ConfigRepository`，而仓储依赖 `DataSource`。**没有 `DataSource` 时编排器不装配**，但注册表/解析器/脱敏器照常 |
| 失败行为可调 | `ifmap.orchestrator.*` 三个开关控制「失败即停」与「缺失策略/动作是否快速失败」 |
| 日志与脱敏 | `ifmap.log.*` 控制落库、截断阈值、脱敏字段（详见 [`docs/06`](06-执行编排与策略扩展.md) §6） |

编排链路（前置接口递归 + 拓扑排序 + 判定 + 分支 + 回调）、`IfmapRequest`/`IfmapResult` 用法、
dry-run 试跑、契约自检见 [`docs/06-执行编排与策略扩展.md`](06-执行编排与策略扩展.md)。

---

## 4. 宿主机自定义规则

方法上加 `@IfmapRule`，类交给 Spring 就行——不需要手动 `registry.register(...)`：

```java
@Component
public class BankRules {

    /** 资方要求的机构号补零：左补 0 到 6 位。 */
    @IfmapRule(value = "bankOrgNo", desc = "机构号左补零到 6 位", example = "12 -> 000012")
    public String bankOrgNo(String value) {
        if (value == null || value.trim().isEmpty()) {
            return value;
        }
        String trimmed = value.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = trimmed.length(); i < 6; i++) {
            sb.append('0');
        }
        return sb.append(trimmed).toString();
    }
}
```

模板里直接 `"orgNo":"@FUN(bankOrgNo,$.orgCode)"`。

细节：

- 扫描实现是 `BeanPostProcessor`，因此**任何方式注册的 bean 都能收集**（`@Component` / `@Bean` / `@Import` 皆可）；
- CGLIB 代理会被归一化为用户类（`ClassUtils.getUserClass`），不会漏扫也不会重复扫；
- 同一个类只扫一次（按类缓存扫描结果）；
- `RuleRegistry` / `IfmapEngine` 自身不参与扫描；
- 规则名与内置规则冲突时按 `@IfmapRule(override = ...)` 的既有语义处理，详见 [`docs/03-规则清单与扩展.md`](03-规则清单与扩展.md)。

---

## 5. 建表行为（`ifmap.ddl.auto`）

`IfmapSchemaInitializer` 执行的是 **`ifmap-provider-jdbc` 里那份生产 DDL 本身**（`classpath:db/changelog/v1.0.0/00X-*.sql`，
与 Liquibase changelog 完全同一份文件），不是另写一份测试 DDL：

1. 逐个文件按 `${tablePrefix}` 替换表名；
2. 表已存在 → 跳过（`SELECT 1 FROM <table> WHERE 1 = 0`）；
3. 不存在 → 执行 `CREATE TABLE`；
4. 多实例并发启动撞车 → 捕获异常后复查一次，表存在即视为成功。

### 5.1 方言自适应

| 数据库 | 处理 |
| --- | --- |
| MySQL / MariaDB | 脚本**原文执行**（`ENGINE=InnoDB ... COMMENT='...'` 全部保留） |
| 其它（H2、PG 测试库等） | 剥掉表尾表选项后再执行；列定义、主键、唯一键、索引、列注释完整保留 |

剥离是**断言式**的：脚本结构与预期不符（没匹配到 `) ENGINE=...`）会直接抛 `IfmapConfigException`，
不会"静默建半张表"。日志会打印：

```
ifmap 检测到非 MySQL 数据库（H2），建表时将剥掉表尾 MySQL 表选项
```

> **H2 必须带 `MODE=MySQL`**：脚本用了 `KEY idx_xxx (...)`、`tinyint(1)`、`datetime(3)` 等 MySQL 写法。
> 例：`jdbc:h2:mem:demo;MODE=MySQL;DB_CLOSE_DELAY=-1`。
>
> **生产建议**：由 DBA 执行 SQL（`docs/04` §1）后把 `ifmap.ddl.auto` 置为 `false`；
> 已用 Liquibase 管理数据库时同理（避免两套机制同时改结构）。

---

## 6. 与手工装配的关系

| 场景 | 推荐方式 |
| --- | --- |
| Spring Boot 3 新项目 | 直接引 starter（本文） |
| 已有 Spring 5 / 非 Boot 项目 | 按 `docs/04` §2.1/§2.3 手工声明 `JdbcConfigRepository` |
| 纯 Java（无 Spring） | 按 `docs/01` 用 `IfmapEngine.createDefault()` + `docs/04` §2.2 |
| 灰度共存（存量 `bankint_*` 表） | `ifmap.table-prefix: bankint_`，见 `docs/04` §1.3 |

starter 与手工装配**可以混用**：宿主想要哪个 bean 就自己声明，starter 自动退让。

---

## 7. 排错

| 现象 | 原因与处理 |
| --- | --- |
| `NoSuchMethodError` 指向 `JdbcTemplate` | `spring-jdbc` 被解析成了旧版本（5.3 缺了 6.x 的 API）。`ifmap-provider-jdbc` 的 `spring-jdbc` 版本是**就地声明**的（父 POM 不托管），且 starter 把 `spring-boot-starter-jdbc` 声明在它之前，Boot 3 项目因此解析到 6.2.x；宿主若在 `dependencyManagement` 里强制降级，请取消 |
| 建表报 `Syntax error ... collate[*]` | 目标库不是 MySQL 且未走自适应分支（自定义了 `DataSource` 包装导致方言误判），或手工执行了生产脚本 |
| 启动报 `ifmap 表前缀非法` | `ifmap.table-prefix` 含非法字符 / 超长 / 数字开头 |
| 表建了但查不到配置 | 检查 `del_status=0` 与 `status=1`（仓储只返回这两类），见 `docs/04` §3 |
| 自定义规则不生效 | 规则类的 bean 没被 Spring 管到；日志会打印 `ifmap 已注册宿主机规则 bean：[...]，类 [...]`，看不到即未扫到 |
| 启动报策略 bean 缺注解 | 实现了 `SpecialDealStrategy`/`FullParamStrategy`/`LogicBranchStrategy`/`IfmapActionHandler`/`IfmapCallbackHandler` 就必须带对应注解（`SpecialDealStrategy` 例外，它按 bean 名注册），报错信息里有 bean 名与类名 |
| 启动期契约自检有违规 | 用 `ContractValidator.validate(configs).toMarkdown()` 打印明细；常见原因是模板不是合法 JSON、`@FUN` 规则名拼错（如 `farmatDate`） |
| `IfmapOrchestrator` 注入不到 | 编排器需要 `ConfigRepository`（即需要 `DataSource`）。无数据源时它不装配；也可自行 `IfmapOrchestrator.builder()` 声明 bean |
| 想看装配了什么 | `--debug` 启动，或看 `IfmapRuleRegistrar` / `IfmapStrategyRegistrar` / `IfmapSchemaInitializer` 的 INFO 日志 |

---

## 8. 自测

```bash
# starter 单测（含 H2 上真实执行生产 DDL、策略自动收集、编排器端到端）
mvn -pl ifmap-spring-boot-starter -am test        # 29 个测试

# 示例工程端到端（Spring 上下文 + H2 + 真实建表 + 真实仓储 + 真实引擎 + 真实编排）
mvn -pl ifmap-demo-spring-boot3 -am test          # 4 个测试

# 示例工程实跑（会打印「仅渲染」与「编排结果」两行日志）
mvn -pl ifmap-demo-spring-boot3 -am -DskipTests package
java -jar ifmap-demo-spring-boot3/target/ifmap-demo-spring-boot3-0.1.0-SNAPSHOT.jar
```

> Spring Boot 3 模块要求 JDK 17+。父 POM 用 `<jdk>[17,)</jdk>` 剖面自动装卸这两个模块，
> 因此在 JDK 8/11 上执行 `mvn test` 不会失败（CI 矩阵见 `.github/workflows/ci.yml`）。
