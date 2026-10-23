# ifmap Starter · 整体架构设计（含表结构重设计）v1

> **作者**：caijun
> **日期**：2026-09-30
> **版本**：v1.0（命名与 Q1~Q10 均已定稿）
> **项目名**：`ifmap`（**I**nterface **Map**ping）— 模块 `ifmap-*`、包 `cn.cj.ifmap.*`、配置前缀 `ifmap.`、类名前缀 `Ifmap*`；**表名统一 `ifmap_*`**（`ifmap_config` / `ifmap_logic_branch_config` / `ifmap_execution_log` / `ifmap_config_history`，索引同步 `uk_ifmap_*` / `idx_ifmap_*`），与项目名一致（与 ECC 共存方案见 §6.11）。
> **命名检索结论**：与 TCG IF-MAP 协议的 Java 客户端库 `ifmapj`（2015 年后停止维护）在搜索结果上可能交叉，但 **Maven 坐标与包名完全隔离**（`cn.cj:ifmap-*` ≠ `ifmapj`），无实际冲突（详见 §9.1）。
> **定位**：把"配置驱动的银行报文映射引擎"从 `biz-svc-ecc` 中独立出来，做成**开源 Spring Boot Starter**（`cn.cj` 命名空间）
> **关联文档**：[`bankintconfig模块优化方案_v1.md`](./bankintconfig模块优化方案_v1.md)（现状缺陷清单与修复计划，本设计不重复其内容）
> **代码仓库**：GitHub [`CJ8023/ifmap`](https://github.com/CJ8023/ifmap.git)；本地开发路径 `D:\cj\code\ifmap`（D2 已定，见 §12.3）
> **配套交付**：[`tools/audit_bankint_config.py`](./tools/audit_bankint_config.py)（配置静态审计，审计对象是 ECC 存量 `bankint_*` 表，故文件名保留旧前缀）、[`tools/gen_rule_inventory.py`](./tools/gen_rule_inventory.py)（内置规则清单生成器）
> **规则审计**：[`ifmap-内置规则审计与优化_v1.md`](./ifmap-内置规则审计与优化_v1.md)（52 个内置规则的去留、core 精简至 18 个的方案）

> ⚠️ **历史稿提示（2026-10 更新）**：本文是 v1 定稿的**设计存档**，落地过程中有过若干次破坏性重构。
> 正文里凡涉及下面三处的描述，都以**现状文档为准**，本文只作设计沿革参考：
>
> 1. **`ifmap_config.front_interface_no` 已删除**（连同 `idx_ifmap_config_front`）：它与 `interface_order`
>    表达的是同一件事，现由 `interface_order`（业务节点内的步骤序号）单一定序 + 起点截断 + **跨步骤共享池**
>    表达；唯一键相应由一把五列拆成 `uk_ifmap_config_biz` + `uk_ifmap_config_order` 两把，
>    `ConfigRepository` 增加 `queryConfigsByBusiNode(tenantId, busiNode)`。
> 2. **`strategy_name` 已拆成 `pre_strategy_name` / `post_strategy_name`**：`IfmapStrategyHook` 分
>    `PRE`（出网前）/ `POST`（回参产物之后、判定之前）两个相位。
> 3. **`ConfigValidator` 的校验清单**已去掉「前置链成环」、新增「池引用只能指向本节点更早的步骤」。
> 4. **成败判定已换成 `result_judge` 单列**（`result_flag` / `success_value` 两列连同 `ResponseJudge` 一起删除）：
>    本文里凡写「`result_flag` + `success_value`」「判定三态」的段落都是当时的形态，
>    现状是「JSON 规格 + 四段判定管线 + `fail_type` 归因」，见
>    [`docs/06`](./06-执行编排与策略扩展.md) §7 与
>    [`判定管线设计`](./superpowers/specs/2026-10-14-判定管线-design.md)。**本文其余内容不改** ——
>    历史档留着，才能回答"当时为什么这么定"。
>
> 现状口径见 [`CHANGELOG.md`](../CHANGELOG.md) 的「Changed（编排定序、跨步骤共享池与前后序策略）」与
> [`docs/06-执行编排与策略扩展.md`](./06-执行编排与策略扩展.md)。

---

## 0. 阅读指引

| 你想知道 | 看哪节 |
|---|---|
| 需求是怎么定的、有哪些硬约束 | §1、§2 |
| 现在的表和引擎有什么问题 | §3 |
| 新架构长什么样、包怎么分 | §4、§5 |
| **新表结构到底怎么改** | **§6**（重点） |
| 怎么和 ECC 存量数据对接 | §6.7、§10 |
| 管理端怎么做 | §8 |
| 开源仓库怎么组织、CI 怎么配 | §9 |
| 什么时候做什么 | §11 |
| 还没定的问题 | §12 |

**术语**：本文中「引擎」= ifmap；「ECC」= `biz-svc-ecc`（存量服务，本次不改）；「宿主机应用」= 引入 starter 的业务服务。

---

## 1. 设计输入（需求确认结论）

### 1.1 P0 — 架构边界（已确认）

| # | 问题 | 结论 | 对设计的影响 |
|---|---|---|---|
| P0-1 | 使用方与用途 | **开源** | 不得出现公司内部依赖（ISF / 私服 jar / 中文内部约定）；注释、异常消息、文档需面向外部开发者；License / CI / 示例 / 文档站成为一等交付物 |
| P0-2 | Spring Boot / JDK 基线 | **支持 Spring Boot 3.x / jakarta** | 自动配置走 `AutoConfiguration.imports`；`jakarta.*` 命名空间；JDK 17 编译 |
| P0-3 | ISF 能否进公开 API | **不能** | 公开 API 不得出现 `ApiReq` / `BizException` / `BeanUtil`；必须自定义请求/响应/异常模型，由宿主机应用写适配器 |
| P0-4 | 配置数据归属 | **Starter 自带建表** | 交付 Liquibase changelog + 可执行 DDL；表名/前缀可配置；须提供配置管理端（与 P1-8 合流） |
| P0-5 | 改造方式 | **并行新建 starter，ECC 不动** | 本设计不改 ECC 任何代码；starter 是"另起一套"，通过 §10 的迁移方案承接存量配置 |

### 1.2 P1 / P2 — 设计细节（已确认）

| # | 问题 | 结论 | 落点 |
|---|---|---|---|
| P1-6 | 三张表 DDL | 已提供 `financing_scheme` 库中 `ifmap_config` / `ifmap_execution_log` / `ifmap_logic_branch_config` 定义 | §3.1 诊断 → §6 重设计 |
| P1-7 | 通用规则 vs 专属规则 | **通用规则内置于 starter；同时保留宿主机的覆盖（override）与新增能力** | §5.4 规则注册与覆盖机制 |
| P1-8 | 配置管理端 | **需要** | §8 |
| P1-9 | 多租户 `tenant_id` 来源 | **请求头** | §5.6、§7.2、§7.3 |
| P1-10 | Actuator / 指标 | **不需要** | 不引入 `spring-boot-starter-actuator`；可观测性靠日志 + 可选 Micrometer（列为 roadmap） |
| P1-11 | 是否还有其他服务读该表 | **没有** | 配置与运行时同库，无需独立配置服务 |
| P1-12 | 合规约束（脱敏 / 留痕 / 加密） | **有** | §7.5 日志脱敏 SPI + §6.4 日志保留策略 |
| P2-13 | 坐标 / 版本 / 非 Spring / 示例 | ① 命名空间 **`cn.cj`**（不用 `com.cdyfsz`）② **独立版本线** ③ **需支持纯 Java（非 Spring）调用** ④ **需要示例工程与文档站** | §9 |

---

## 2. 设计目标与硬约束

### 2.1 目标（按优先级）

| 优先级 | 目标 | 验收方式 |
|---|---|---|
| 1 | **引擎可独立运行**：不依赖任何内部 SDK、不依赖 Spring 也能跑 | `ifmap-core` 的 `mvn dependency:tree` 无 `club.newepoch` / `com.cdyfsz`；纯 Java demo 能跑通 |
| 2 | **能力可扩展**：宿主机可覆盖/新增规则、策略、动作 | §5.4 覆盖语义 + 单测 |
| 3 | **配置可管理**：自带建表 + 管理端 CRUD + 审计 | §8 + §6 |
| 4 | **行为可观测**：执行链路留痕、报文脱敏 | §7.5 |
| 5 | **开源可用**：文档、示例、CI、License 齐全 | §9 |

### 2.2 硬约束

| 编号 | 约束 | 设计对策 |
|---|---|---|
| C1 | 秘密性：公开 API 不许出现内部类型 | 自定义 `IfmapRequest` / `IfmapResult` / `IfmapException`（§5.1） |
| C2 | 兼容性：core 支持纯 Java（JDK 8+）；Spring 门面支持 SB3（JDK 17） | 模块分层，Spring 只在门面模块（§4.2） |
| C3 | 数据库：自带建表，且要能承接 ECC 存量数据 | 字段名**只增不映射** —— 唯一例外是 `bank_code`/`bank_name` → `partner_code`/`partner_name` **2 处改名**（§6.7、ADR-15）；其余是类型修正 + 新增列（§6.2） |
| C4 | 不侵入 ECC | 不改 ECC 一行代码；迁移通过 ETL + 适配器（§10） |
| C5 | 依赖可控：开源工程需避免引入有安全历史的库 | JSON 能力抽象为 SPI，默认 Jackson；fastjson 仅作可选兼容模块（§4.4） |
| C6 | 合规：报文脱敏、留痕 | `LogMasker` SPI + 默认脱敏规则（§7.5） |

### 2.3 明确【不做】的事

| 不做 | 原因 |
|---|---|
| 不把 ECC 的 17 个银行回调搬进 starter | 业务专属（§5.5），宿主机实现 |
| 不引入银行 SDK（cmbc / cfca / hxapi / hxb） | 闭源 / 客户专属 |
| 不引入 Actuator（P1-10） | 需求明确不要 |
| v1 不支持 PostgreSQL / Oracle | 方言差异（json 类型、分区），列入 roadmap |
| 不承诺兼容 ECC 的 `@FUN` 之外的自定义语法 | 以现有 DSL 语义为准（P0-5 语义不改） |

---

## 3. 现状诊断

> 引擎侧的代码级缺陷（P0-1 ~ P2-12）见 [`bankintconfig模块优化方案_v1.md`](./bankintconfig模块优化方案_v1.md) §3，本节只补充**与 starter 边界、与表结构相关**的问题。

### 3.1 表结构问题清单

对三张表逐字段核对后的结论。「影响」列标注的是**在新架构下**的后果（部分问题在 ECC 单库单服务场景尚不致命，但独立成产品后必然暴露）。

#### P0 — 正确性（必须修，否则新表不能用）

| 编号 | 表 | 现状 | 问题 | 影响 | 处置 |
|---|---|---|---|---|---|
| **TS-1** | `ifmap_config` | `interface_order char(2) NOT NULL` | ① `char` 存"顺序"会造成**字符串排序**（`'10' < '2'`）② **实测引擎里从未使用该字段**（全仓只有 PO 声明 `private String interfaceOrder`，无排序代码） | 「接口执行顺序」这一声明能力**完全失效**；同 `interface_no` 下多条配置的执行顺序取决于 DB 返回顺序（无 `ORDER BY`）→ **不确定** | 改 `smallint`；引擎实现显式排序；同值以 `key_id` 兜底（§6.2） |
| **TS-2** | `ifmap_logic_branch_config` | **无顺序字段** | 分支匹配用 `filter(...).findFirst()`（`IfmapConfigManager.java:356-358`）→ 顺序由 DB 返回顺序决定 | 多条分支同时命中时**结果不确定**；"默认兜底分支"靠"`logic_branch_flag` 为空"隐式表达，多条时同样不确定（对应方案 P1-18） | 新增 `logic_branch_order`（§6.3） |
| **TS-3** | 三张表 | **除主键外零索引** | `ifmap_execution_log` 按 `biz_id` / `interface_no` / 时间查询均全表扫 | 执行日志表随业务线性增长，问题定位查询退化；配置表在管理端分页也全表扫 | 补业务索引（§6.2~§6.4） |
| **TS-4** | `ifmap_config` | **无唯一约束** | 同一 `(tenant_id, interface_no, busi_node, interface_order)` 可重复插入 | 引擎取第一条，而顺序又不确定（TS-1）→ **静默错配**：配置重复但无人发现 | 加唯一键（§6.2 + §6.8） |
| **TS-5** | 三张表 | `del_status tinyint(1)` 软删除 | 软删除与唯一约束天然冲突（删掉后可再插，唯一键会撞历史行） | 直接加唯一键会导致"删除后重建同配置"失败 | `deleted_seq` 方案（§6.8） |

#### P1 — 可用性 / 一致性

| 编号 | 表 | 现状 | 问题 | 处置 |
|---|---|---|---|---|
| **TS-6** | `ifmap_logic_branch_config` | `method_flag varchar(50) DEFAULT NULL`，实际值 `discSuccess` / `discFail` | 该字段在 ECC 承载的是 **scfp 专属的业务动作名**（见 `NbtsDiscountResultLogicBranchStrategyImpl.java:66-78` 注释），语义绑死在业务上 | 保留列名，语义重定义为「**动作标识（Action Key）**」，由宿主机通过 `ActionRegistry` 注册；`method_flag` 为空 = 默认兜底分支（§5.4） |
| **TS-7** | `ifmap_config` | `result_flag varchar(255)` | 承载的是 **JsonPath 表达式**，复杂过滤表达式（含 `[?(@.x=='y')]`）易超 255 | 扩至 512 |
| **TS-8** | `ifmap_config` | `success_value varchar(255)` 单值 | 「执行成功判断值」在真实场景需要多值（如 `SUCCESS`/`S`/`0000`），现只能配一个 | 语义扩展为「多值，`,`/`;` 分隔」，**不改列名**（兼容） |
| **TS-9** | `ifmap_config` | 无启停字段 | 配置改错只能删，无法"停用观察"；灰度/暂停接口无手段 | 新增 `status`（§6.2） |
| **TS-10** | `ifmap_config` | 无乐观锁 | 管理端并发编辑后写覆盖（last-write-wins），改坏无法察觉 | 新增 `version`（§6.2） |
| **TS-11** | `ifmap_execution_log` | `request_param json NOT NULL`、`response_param longtext`，**无脱敏、无截断、无清理** | 报文含身份证/卡号/手机号 → 合规风险（P1-12）；大报文长text 使表膨胀；无清理策略 → 无限增长 | `LogMasker` SPI + 长度上限 + 保留期清理（§6.4、§7.5） |
| **TS-12** | `ifmap_execution_log` | `execution_result char(2)`，无字典；且 ECC 里 `setExecutionResult` 被注释掉（`IfmapConfigManager.java:339`） | 字段闲置且无取值约定 | 改 `varchar(16)` + 枚举 `SUCCESS/FAIL/SKIP/TIMEOUT`（§6.4、§6.6） |
| **TS-13** | — | 无配置变更历史表 | 配置改坏无法回滚；"谁在什么时候把模板改成这样"不可查 | 新增 `ifmap_config_history`（§6.5） |
| **TS-14** | 三张表 | `timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP` | ① `timestamp` **2038 年溢出** ② 受会话时区影响，跨时区部署会漂 ③ `modify_time` 无 `ON UPDATE`，靠代码写，漏写则不准 | 改 `datetime(3)`；`modify_time` 由引擎统一维护（§6.9） |
| **TS-15** | 三张表 | `key_id bigint(19) NOT NULL`，**无 `AUTO_INCREMENT`** | 主键由外部（管理端服务）生成，引擎自身无法建配置 | 引擎提供 `IdGenerator` SPI，默认雪花实现（§6.10） |
| **TS-16** | 三张表 | `utf8mb4` + `ROW_FORMAT=DYNAMIC`，未声明 `COLLATE` | 排序规则随实例默认变（5.7 常为 `utf8mb4_general_ci`，8.0 为 `utf8mb4_0900_ai_ci`），跨环境比较/唯一键行为不一致 | 显式声明 `COLLATE`；区分 5.7 / 8.0 DDL（§6.11） |

#### P2 — 规范与前瞻

| 编号 | 表 | 现状 | 问题 | 处置 |
|---|---|---|---|---|
| **TS-17** | `ifmap_config` | `busi_node char(10)` | 实际取值如 `GP85`/`GP98`/`GP99`（`BankFileNodeEnum`），`char` 定长会补空格、比较易踩坑；且节点集合应由宿主机定义 | 改 `varchar(32)` + 枚举由宿主机注册（§6.6） |
| **TS-18** | `ifmap_config` | `interface_no` / `interface_code` / `front_interface_no` 三个"编号"语义重叠 | 新人无法区分；`front_interface_no` 用 `varchar(255)` 存编号（过长） | 文档化三者语义（§6.6）；**`front_interface_no` 最终被整体删除**（改由 `interface_order` 单一定序 + 跨步骤共享池表达，见顶部「历史稿提示」） |
| **TS-19** | 三张表 | `tenant_id bigint NOT NULL DEFAULT -1` | ECC 场景实为单租户（`-1`） | 保留（开源产品多租户是加分项），默认单租户，见 §7.3 |
| **TS-20** | `ifmap_config` | `add_user_id bigint(19)` | 用户 ID 若不是数字（UUID/工号）则插不进去 | 改 `varchar(64)`（§6.2） |

### 3.2 引擎侧与 starter 边界相关的现状

| 现象 | 数据 | 对新架构的含义 |
|---|---|---|
| 公开 API 深度绑定 ISF | `ApiReq` 出现在几乎所有引擎入口；`BizException` 用于错误传播 | 必须全部替换为自定义模型（C1） |
| 配置来源是 feign 而非 DB | 引擎通过 `FinancingSchemeFeignRepository` 取配置 | **这是好事**：证明"配置来源"天然可替换 → 直接抽象为 `ConfigRepository` SPI（§5.2） |
| 策略实现与业务强绑定 | 38 个 `AbstractSpecialDealStrategy` 子类、17 个银行回调、`IfmapConfigManager:276` 民生硬编码 | 全部下沉为宿主机实现 + SPI 装配（§5.5） |
| 规则方法零 Java 调用 | `ConvertRule` 57 个声明（52 个唯一名）仅通过 DB 模板 `@FUN(...)` 反射调用 | 规则注册必须**显式化**（否则开源用户无法知道有哪些规则可用）→ §5.4 |
| `JsonParseUtils` 是跨包公共工具 | 216 个调用点 / 68 文件 / 3 个包 | 抽进 core 会牵动其他上下文 → **本设计不抽**（§10.4 说明理由与替代路径） |

---

## 4. 架构总览

### 4.1 分层与依赖方向

```
                 ┌───────────────────────────────────────────────┐
                 │   宿主机应用（ECC / 新业务 / 开源使用者）        │
                 │   · 实现 SPI（策略、动作、回调、脱敏…）          │
                 │   · 写适配器（ApiReq ⇄ IfmapRequest）          │
                 └───────────────┬───────────────────────────────┘
                                 │ depends on
   ┌─────────────────────────────┴──────────────────────────────┐
   │  ifmap-spring-boot-starter        ← 唯一入口       │
   │  · AutoConfiguration.imports                               │
   │  · 装配 core + provider，扫描宿主机 SPI 实现                │
   └───────────────┬────────────────────────────────────────────┘
                   │
   ┌───────────────┴────────────────────────────────────────────┐
   │  ifmap-core            ← 引擎本体（零 Spring）      │
   │  · DSL 解析与求值 / JsonPath 取数 / 规则注册表               │
   │  · 编排（Manager）/ 工厂 / 契约校验 / 缓存抽象               │
   │  · 全部 SPI 接口定义 / 异常体系 / 领域模型                   │
   └───────┬───────────────────────┬────────────────────┬───────┘
           │                       │                    │
   ┌───────┴────────┐   ┌──────────┴────────┐   ┌───────┴────────────┐
   │ provider-jdbc  │   │ json-jackson      │   │ provider-memory    │
   │ (JdbcTemplate) │   │ (默认)             │   │ (测试/纯 Java demo) │
   │ provider-remote│   │ json-fastjson      │   │                    │
   │ (远端 HTTP，W12)│   │ (兼容 ECC，可选)    │   │                    │
   └────────────────┘   └───────────────────┘   └────────────────────┘
           │
   ┌───────┴────────────────────────────────────────────────────┐
   │  ifmap-admin （可选，Spring MVC 配置管理端）        │
   └────────────────────────────────────────────────────────────┘
```

**依赖铁律**：箭头只能向下。`core` 不允许依赖任何 provider、任何 Spring、任何 JSON 具体实现。

### 4.2 模块清单

| 模块 | 定位 | JDK | 关键依赖 | 必需 |
|---|---|---|---|---|
| `ifmap-core` | 引擎本体 | 8 | `json-path`（Apache-2.0）、SLF4J API | ✅ |
| `ifmap-json-jackson` | JSON SPI 默认实现 | 8 | Jackson | ✅（默认） |
| `ifmap-json-fastjson` | 兼容实现（对齐 ECC 行为） | 8 | fastjson **1.2.84** | ✅ **W11 已交付**（可选模块） |
| `ifmap-provider-jdbc` | 配置仓储：`JdbcTemplate` + Liquibase changelog | 8 | spring-jdbc（可选依赖） | ✅（自带建表场景） |
| `ifmap-provider-remote` | 配置仓储：远端 HTTP（**不硬依赖 Feign**，改由 `ConfigFetcher` 函数式 SPI 注入宿主自己的 Feign / RestTemplate） | 8 | 无（只依赖 core） | 可选（**W12 已交付**，ECC 迁移期） |
| `ifmap-provider-memory` | 内存仓储（测试 / 纯 Java demo） | 8 | 无 | ✅ |
| `ifmap-spring-boot-starter` | SB3 自动配置门面（**落地名**：原设计名 `ifmap-spring-boot3-starter`） | 17 | spring-boot-autoconfigure 3.x、spring-boot-starter-jdbc | ✅ |
| `ifmap-spring-boot-2-starter` | SB2.4~2.7 兼容门面（Maven profile 按需构建） | 8 | spring-boot-autoconfigure 2.x | 可选（§12 Q3） |
| `ifmap-admin` | 配置管理端（REST + 校验 + 历史回滚） | 17 | spring-web | 可选 |
| `ifmap-demo-pure-java` | 纯 Java 示例（P2-13③） | 8 | 无 | ✅ |
| `ifmap-demo-spring-boot3` | SB3 示例（P2-13④） | 17 | SB3 + H2 | ✅ |

### 4.3 包结构（groupId `cn.cj`）

```
cn.cj.ifmap.core                     引擎本体
├── model            IfmapRequest / IfmapResult / IfmapConfig / LogicBranchConfig / ExecutionLog
├── dsl              模板解析、表达式求值、@FUN 调用协议
├── json             JsonOps（SPI）、JsonPath 封装、ReadContext（一次解析多次取数）
├── rule             ConvertRule（内置规则）、@IfmapRule、RuleRegistry
├── strategy         IfmapStrategyHook / FullParamStrategy / LogicBranchStrategy + 三个工厂
├── action           ActionRegistry（method_flag → 动作）
├── spi              全部扩展点接口（§5）
├── cache            ConfigCache（抽象）、默认 Caffeine/内存实现
├── exception        IfmapException / ConfigException / RuleException
└── engine           IfmapEngine（对外唯一门面类，纯 Java 可用）

cn.cj.ifmap.spring                   自动配置（SB2/SB3 共享源码）
├── IfmapAutoConfiguration
├── IfmapProperties
├── IfmapSpiRegistrar         扫描并注册宿主机 SPI 实现
└── IfmapTenantFilter         （admin 模块提供）请求头 → 租户上下文

cn.cj.ifmap.jdbc / cn.cj.ifmap.remote / （内存仓储在 core 的 testkit 里：cn.cj.ifmap.core.testkit）
cn.cj.ifmap.admin                   管理端
cn.cj.ifmap.demo.*                  示例
```

### 4.4 Spring Boot 支持范围（Q3 已决策：仅 SB3）

| 项 | 决策 |
|---|---|
| 支持版本 | **Spring Boot 3.x（`jakarta` 命名空间）+ JDK 17** |
| 不支持 | SB 2.x / `javax`（Q3 确认不需要） |
| 自动配置注册 | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| 设计红利 | 单一命名空间，无 `javax`/`jakarta` 双写问题；`ifmap-spring` 可直接依赖 SB3 autoconfigure |

**若未来真出现 SB2 需求**（补救路径已预留，当前零成本）：

1. 新增 `ifmap-spring-boot-2-starter` 模块，**只放** `META-INF/spring.factories`；
2. 自动配置类主体复制一份到 `javax` 分支（或 `maven-compiler-plugin` 双执行）；
3. **`core` 完全不用改** —— 这是「core 零 Spring」决策（ADR-1）的额外收益。

> 已删除原「SB2/SB3 双线共享源码」方案与其 `javax` 回避技巧（`InitializingBean` 替代 `@PostConstruct`）。既然只做 SB3，这些复杂度不再需要。

### 4.5 JSON 能力选型（Q5 已决策：Jackson）

**结论：Jackson 为默认实现（SB3 自带，零额外依赖）；fastjson 保留为可选兼容模块（仅供 ECC 迁移期）。**

> **W11 已交付**：`ifmap-json-fastjson` 用 **fastjson 1.2.84**（1.2.68~1.2.83 在默认配置下存在可被利用的 AutoType RCE，故不用设计初稿写的 1.2.83），
> 跑通与 Jackson 实现**同一套 24 条 `JsonOps` TCK 契约** + 同一批模板 DSL 端到端用例；两套 JsonPath 方言无法完全抹平的 6 处差异逐条列在
> [`docs/05-SpringBoot集成.md`](05-SpringBoot集成.md) §3.2，并被 `FastjsonJsonOpsTest` / `FastjsonPathsTest` 断言钉住。

| 候选 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **Jackson** | ① **SB3 自带**，不新增依赖 ② `json-path`(jayway) 的默认 `JsonProvider` **就是 Jackson**（双重契合）③ 生态最大、CVE 响应快、性能优、树模型/流式/注解齐全 | `ObjectMapper` 配置项多，需 starter 统一构造并暴露 | ✅ **默认** |
| Gson | 轻量、API 简单 | 性能弱于 Jackson；`json-path` 需额外 Provider；树模型与流式能力弱 | 备选 |
| fastjson 1.2.84 | 与 ECC 现网行为一致 | 1.x 有历史反序列化 RCE（1.2.68~1.2.83 默认配置下可被利用）；国际开源社区接受度低 | ⚠️ 仅兼容模块（**W11 已交付**） |
| fastjson2 | 性能强、国人熟悉 | 与 1.x 行为**不完全一致**（无法"零成本"承接 ECC 存量模板）；包名体系不同 | ❌ |
| JSON-java (`org.json`) | 体积极小 | 功能弱、性能差、API 陈旧 | ❌ |
| Jsoniter / DSL-JSON / Avaje JSONB | 快 | 维护活跃度或生态不足，SB3 集成少 | ❌ |
| Moshi | Kotlin 友好 | Java 生态弱 | ❌ |

#### 解耦铁律：`JsonOps` SPI 的方法签名**不得出现任何 JSON 库的类型**

```java
public interface JsonOps {
    Object             read(String json, String jsonPath);   // → Map/List/String/Number/Boolean/null
    String             readString(String json, String jsonPath);
    String             write(Object value);                  // Object → JSON 文本
    Map<String,Object> toMap(String json);                   // 空串/非对象 → 空 Map（不抛异常）
    boolean            isJson(String text);
}
```

| 为什么这样设计 | 说明 |
|---|---|
| 签名只用 JDK 类型 | 换实现**不需要改宿主机代码**；且 fastjson 的 `JSONObject implements Map`、`JSONArray implements List` → **天然兼容** |
| JsonPath 由 `JsonOps` 封装 | 现状 `JsonParseUtils` 直接用 fastjson 的 `JSONPath`；封装后 ECC 侧零感知 |
| 默认实现 | `JacksonJsonOps`：`ObjectMapper` + jayway `JacksonJsonProvider`（`json-path` 已依赖） |
| 兼容实现 | `FastjsonJsonOps`：`JSON.parse` + `JSONPath`；额外提供 `readJSONObject()` 供 ECC 侧保留原类型（避免 216 个调用点全改） |
| 一致性保障 | 交付**双实现一致性测试套件**：同一批真实模板（取自 `docs/邮储线上保理/002_设计/融资方案配置.sql`）+ 同一批响应报文，两个实现结果必须**逐字相等** |

> `json-path` 是 Apache-2.0，可安全进 core。若未来要替换 JsonPath 实现或支持数据库侧 JSON 查询，`JsonOps` 已是现成抽象点。

---

## 5. 核心抽象（公开 API 与 SPI）

### 5.1 公开模型（替换 ISF 类型，C1）

| 新类型 | 替换 | 说明 |
|---|---|---|
| `IfmapRequest` | `ApiReq` | 泛化请求上下文：`tenantId`、`operatorId`、`requestId`、`bizId`、`headers`、`payload(Map)`、`attributes(Map)` |
| `IfmapResult` | `ResponseParamEntity` + `ResponseParamVO` 的组合 | 引擎输出：`success`、`data`、`rawResponse`、`executedInterfaces`、`elapsedMs` |
| `IfmapException` | `BizException` | 引擎统一异常（含 `errorCode`、`interfaceNo`、`stage`）；**不继承任何内部类** |
| `IfmapContext` | 隐含在 `ApiReq` 里的东西 | 一次执行的上下文（不可变）：request + config + 解析后的响应 JSON + 缓存 `ReadContext` |
| `ExecutionLog` | `IfmapExecutionLogDTO` | 执行日志模型 |

**适配器约定**：宿主机写 `IfmapRequestAdapter`，把 `ApiReq` 转成 `IfmapRequest`（ECC 侧约 30 行）。

### 5.2 配置仓储 SPI

```java
public interface ConfigRepository {
    /** 按业务键查询接口配置（实现方须保证按 interfaceOrder 升序返回） */
    List<IfmapConfig> queryConfigs(String tenantId, String interfaceNo, String busiNode);
    /** 查询单条 */
    Optional<IfmapConfig> findConfig(String tenantId, String interfaceNo, String busiNode, int order);
    /** 查询逻辑分支（实现方须保证按 logicBranchOrder 升序返回） */
    List<LogicBranchConfig> queryLogicBranches(String tenantId, String interfaceNo);
    /** 写执行日志 */
    void saveExecutionLog(ExecutionLog log);
}
```

| 实现 | 模块 | 场景 |
|---|---|---|
| `JdbcConfigRepository` | `provider-jdbc` | starter 自带建表（P0-4） |
| `RemoteConfigRepository` | `provider-remote` | ECC 迁移期（配置仍在 financing-scheme，走 HTTP 接口读；**W12 已交付**） |
| `InMemoryConfigRepository` | `provider-memory` | 单测、纯 Java demo |

### 5.3 策略 SPI（保留 ECC 三类 + 工厂）

| SPI | ECC 现状 | 注册键 | 说明 |
|---|---|---|---|
| `IfmapStrategyHook` | 38 个 Impl，`strategy_name` = Spring bean 名（落地后**拆为 `pre_strategy_name` / `post_strategy_name` 两列**，分 `PRE` / `POST` 相位） | **bean 名 / 类简单名 / 宽松小写名**（三 key，解决 P0-7） | 策略钩子（补参数、文件上传…） |
| `FullParamStrategy` | 20 个 Impl，`@FullParam(partnerCode, busiNode)` | 注解值 | 主参数组包（如 961 行的 `FinancingFullParamStrategyImpl`） |
| `LogicBranchStrategy` | 12 个 Impl，`@LogicBranch` + 反射 `getDeclaredMethod` | 注解值 + **Action Key** | 分支动作（改走 ActionRegistry，不再反射方法名，见 TS-6） |
| ~~`IfmapCallbackHandler`~~ | `IfmapConfigApplication` 17 个回调 | ~~`interfaceNo`~~ | **v2 已移除**：被调场景改由「配置 + `call_direction=INBOUND`」表达（见 [`12-被调接口配置指南`](12-被调接口配置指南.md)），不再需要宿主逐接口写 handler |

**`IfmapStrategyHook` 三 key 注册**（直接解决 P0-7，且对宿主机零风险）：

```java
registry.register(beanName, strategy);            // Spring 真实 bean 名（权威值）
registry.register(simpleName, strategy);          // 类简单名（宽松兜底，命中时 WARN）
registry.register(decapitalizeLoose(simpleName), strategy); // "首字母小写"（兼容人工配置，命中时 WARN）
// 多个不同策略命中同一 key → 记 ambiguous 并保留首个，启动期 WARN + 审计报告
```

### 5.4 规则注册与覆盖机制（P1-7 的核心实现）

**现状问题**：`ConvertRule` 的 57 个方法**在 Java 侧零引用**，全靠 DB 模板里的 `@FUN(名字, ...)` 反射调用（方案 P0-5）。开源用户无法知道"有哪些规则可用"，也无法安全地新增。

**目标设计**：

```java
// 1. 内置规则：以注解声明，启动期扫描注册
@IfmapRule(value = "dateFormat", desc = "日期格式化", example = "@FUN(dateFormat,$.date,yyyy-MM-dd)")
public String dateFormat(String dateValue, String pattern) { ... }

// 2. 宿主机新增规则：在自己的包里写同样的注解即可被扫描
@IfmapRule(value = "cmbInvoiceType", desc = "民生票据类型转换")
public String cmbInvoiceType(String t) { ... }

// 3. 宿主机覆盖内置规则：显式声明 override
@IfmapRule(value = "dateFormat", override = true, desc = "覆写内置实现")
public String myDateFormat(String dateValue, String pattern) { ... }
```

| 能力 | 规则 |
|---|---|
| 新增（名字不冲突） | 直接注册 |
| **覆盖内置** | 必须 `override = true`；未声明则**启动失败**（防止无意的语义漂移） |
| 覆盖宿主机其他模块 | 同上；同一 key 出现 >1 个 `override=true` → 启动失败（歧义） |
| 同名重载（ECC 真实存在 5 个方法各 2 重载） | 注册表按**参数个数 + 参数类型**存多签名；`@FUN` 调用时按实参类型匹配，匹配歧义 → 抛 `RuleException`（不再静默） |
| 未注册的规则被调用 | 启动期契约自检报错（扫描 DB 模板）；运行期抛 `RuleNotFoundException`（当前是静默失败） |
| 规则清单可查 | `engine.describeRules()` → Markdown 清单（管理端"可用规则"页 + 契约自检报告） |

> **规则集清单与去留决策**：现有 52 个内置规则（`ConvertRule`）的**逐个判定**（core / 配置化 / 外移）、运行期静默失败的**生产实证**（`farmatDate` ×5、`cebFilePathJoin` ×1）、以及 core 收敛为 **18 个**的目标清单，见 [`ifmap-内置规则审计与优化_v1.md`](./ifmap-内置规则审计与优化_v1.md)。本节示例中的 `@IfmapRule(value = "...")` 即审计文档 §6.3 的**方法级注解**：注解写在方法上，`value` 即 `@FUN` 中的规则名，一个类可承载多个规则方法（存量 `ConvertRule` 的 57 个方法迁移时只需补注解）。

**兼容性**：ECC 现有 DB 模板中的 `@FUN(cmbInvoiceType, ...)` 等名字，只要宿主机把对应方法加 `@IfmapRule("cmbInvoiceType")` 即可零改动复用；**引擎不硬编码任何银行名**。

### 5.5 必须由宿主机实现的部分

| 类别 | 内容 | 是否需要 starter 提供接口 |
|---|---|---|
| 业务策略 | ECC 的 38 / 20 / 12 个 Impl | 提供接口 + 注册机制，实现归宿主机 |
| 银行回调 | 17 个回调方法 | **v2 已移除**：改由配置表达（`call_direction=INBOUND` + 判定 + 逻辑分支动作），宿主只需写动作 |
| 条件参数 | `IfmapConfigManager:276` 民生硬编码 | `IfmapRequest.attributes` → `RuleContext`（规则方法把 `RuleContext` 声明为第一个形参即可读）；原计划的 `ConditionValueResolver` SPI 因**无调用方**已删（见 CHANGELOG Removed） |
| 上下文 | 租户/操作人/请求 ID | `IfmapRequestAdapter` + `TenantResolver` |
| 脱敏 | 各银行报文敏感字段 | `LogMasker` SPI（默认实现覆盖通用证件号/卡号/手机号） |
| 文件/加解密 | cmbc cryptokit、cfca、hxapi | **不进 starter**；宿主机在策略实现里自行使用 |

### 5.6 其余 SPI 一览

| SPI | 职责 | 默认实现 | 必需 |
|---|---|---|---|
| `JsonOps` | JSON 读写（解耦具体 JSON 库） | Jackson | ✅ |
| `RuleRegistry` | 规则注册/查询/覆盖 | 内置 | ✅ |
| `ActionRegistry` | `method_flag` → 动作处理器（TS-6） | 内置（空表 + 启动告警） | ✅ |
| `ConfigCache` | 配置缓存 | Caffeine（`maximumSize` + `expireAfterWrite`，key 含 tenantId） | ✅ |
| `IdGenerator` | 主键生成（TS-15） | 雪花 | ✅ |
| `TenantResolver` | 请求头 → tenantId（P1-9） | `X-Tenant-Id` 请求头 | ✅ |
| `LogMasker` | 报文脱敏（P1-12） | 通用证件/卡号/手机号 | ✅ |
| `ExecutionLogSink` | 日志落库 | JDBC | ✅ |
| `ClockProvider` | 时间源（可测） | 系统时钟 | ✅ |

---

## 6. 表结构重设计（重点）

### 6.1 设计原则

| 原则 | 说明 |
|---|---|
| **P1 只增不映射**（含 2 处列改名） | ECC 在用字段名**要么原样保留、要么只改名不动数据**（改名 = `CHANGE COLUMN`，依旧是 `ALTER`，**不需要 ETL、不需要字段映射表**）→ 迁移始终是纯增量 `ALTER + UPDATE`。v1.0 定稿后唯一的改名是 `bank_code`/`bank_name` → `partner_code`/`partner_name`（去「银行」化，§6.7 / ADR-15）；其余字段只改类型 + 新增列 |
| **P2 显式优于隐式** | 顺序、状态、唯一性都**落到列上**，不靠"约定"（对比现状：顺序靠 DB 返回顺序） |
| **P3 面向开源** | 去掉 `financing_scheme` 库名硬编码；表名前缀可配；审计字段可整体关闭 |
| **P4 时区与年限** | `timestamp` → `datetime(3)`（避开 2038 + 时区漂移） |
| **P5 软删除可唯一** | `deleted_seq` 方案（§6.8） |
| **P6 索引跟查询走** | 只加真实查询用到的索引（管理端分页、运行期取配置、日志定位） |

### 6.2 `ifmap_config` 目标结构

```sql
CREATE TABLE `ifmap_config` (
  `key_id`                    bigint       NOT NULL                     COMMENT '主键（默认雪花ID）',
  `tenant_id`                 bigint       NOT NULL DEFAULT -1           COMMENT '租户ID（单租户固定 -1）',
  `interface_no`              varchar(64)  NOT NULL                     COMMENT '接口编号（业务唯一键）',
  `interface_code`            varchar(64)  NOT NULL                     COMMENT '接口编码（对接方接口编码）',
  `project_code`              varchar(64)           DEFAULT NULL        COMMENT '项目编号',
  `interface_name`            varchar(128) NOT NULL                     COMMENT '接口名称',
  `busi_node`                 varchar(32)  NOT NULL                     COMMENT '业务节点（枚举由宿主机注册）',
  `partner_code`                 varchar(32)  NOT NULL                     COMMENT '合作机构编码',
  `partner_name`                 varchar(128)          DEFAULT NULL        COMMENT '合作机构名称',
  `financing_mode`            varchar(32)           DEFAULT NULL        COMMENT '融资模式（见 §6.6）',
  `interface_order`           smallint     NOT NULL DEFAULT 0           COMMENT '【改】本业务节点内的步骤序号，升序；同值按 key_id 兜底',
  `request_param_template`    text                                  COMMENT '请求参数模板（DSL）',
  `response_param_template`   longtext                              COMMENT '响应参数模板（DSL）',
  `result_flag`               varchar(512) NOT NULL DEFAULT ''         COMMENT '【扩】执行结果标志（JsonPath）',
  `success_value`             varchar(512) NOT NULL DEFAULT ''         COMMENT '【扩】成功判断值，多值以 , 或 ; 分隔（大小写不敏感）',
  `pre_strategy_name`         varchar(128) NOT NULL DEFAULT ''         COMMENT '【扩】前序策略钩子标识（=Spring bean 名），出网前执行',
  `post_strategy_name`        varchar(128) NOT NULL DEFAULT ''         COMMENT '【扩】后序策略钩子标识（=Spring bean 名），回参产物之后执行',
  `status`                    tinyint(1)   NOT NULL DEFAULT 1           COMMENT '【新】状态：1启用 0停用',
  `version`                   int          NOT NULL DEFAULT 0           COMMENT '【新】乐观锁版本',
  `remark`                    varchar(512) NOT NULL DEFAULT ''         COMMENT '【扩】备注',
  `del_status`                tinyint(1)   NOT NULL DEFAULT 0           COMMENT '删除标识;0:未删除1:已删除',
  `deleted_seq`               bigint       NOT NULL DEFAULT 0           COMMENT '【新】软删除唯一化：未删除=0，删除时=key_id',
  `add_user_id`               varchar(64)  NOT NULL DEFAULT ''         COMMENT '【改类型】添加人',
  `add_time`                  datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '【改类型】添加时间',
  `add_request_id`            varchar(40)  NOT NULL DEFAULT ''         COMMENT '创建请求ID',
  `modify_user_id`            varchar(64)  NOT NULL DEFAULT ''         COMMENT '【改类型】更新人',
  `modify_time`               datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '【改类型】更新时间（引擎统一维护）',
  `modify_request_id`         varchar(40)  NOT NULL DEFAULT ''         COMMENT '修改请求ID',
  PRIMARY KEY (`key_id`),
  UNIQUE KEY `uk_ifmap_config_biz`   (`tenant_id`,`interface_no`,`deleted_seq`),
  UNIQUE KEY `uk_ifmap_config_order` (`tenant_id`,`busi_node`,`interface_order`,`deleted_seq`),
  KEY `idx_ifmap_config_list`  (`tenant_id`,`del_status`,`busi_node`,`partner_code`),
  KEY `idx_ifmap_config_code`  (`tenant_id`,`del_status`,`interface_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC
  COMMENT='资方接口配置';
```

**逐项说明**：

| 变更 | 原 | 新 | 理由 |
|---|---|---|---|
| `interface_order` | `char(2)` | `smallint` | TS-1：消除字符串排序；同时**引擎必须实现排序**（原字段从未被使用） |
| `deleted_seq` | — | `bigint DEFAULT 0` | TS-5：软删除下的唯一性（§6.8） |
| `status` | — | `tinyint DEFAULT 1` | TS-9 |
| `version` | — | `int DEFAULT 0` | TS-10 |
| `result_flag` | 255 | 512 | TS-7 |
| `success_value` | 255 | 512 | TS-8（语义扩为多值，**列名不变**） |
| ~~`strategy_name`~~ → `pre_strategy_name` / `post_strategy_name` | 50 | 128 | 类名 `BCMPushFileUploadDealStrategyImpl` 已 34 字符，留余量；落地后一列拆两列（见顶部「历史稿提示」） |
| `busi_node` | `char(10)` | `varchar(32)` | TS-17 |
| ~~`front_interface_no`~~ | 255 | — | TS-18；落地后该列**被整体删除**，不再收窄 |
| `interface_code` | 255 | 64 | TS-18（编码不会超 64；过长会挤压索引） |
| `financing_mode` | 50 | 32 | 枚举值短 |
| `remark` | 255 | 512 | 管理端需要写清变更原因 |
| `add_user_id`/`modify_user_id` | `bigint` | `varchar(64)` | TS-20 |
| `add_time`/`modify_time` | `timestamp` | `datetime(3)` | TS-14 |
| `request_param_template` / `response_param_template` | `text NOT NULL` / `longtext NOT NULL` | 去掉 `NOT NULL` | 存在「接口只有响应模板、无请求模板」的场景（纯回调接口），`NOT NULL` 会逼配置者填 `''` 甚至 `{}`，进而被引擎当作有效模板解析 |
| `key_id` | `bigint(19)` | `bigint` | 去掉无效显示宽度（8.0 弃用警告） |
| **改名（仅 2 处）** | `bank_code` → `partner_code`、`bank_name` → `partner_name` | — | 英文标识符不再锁死在「银行」：同一列要承载银行 / 保理 / 信托 / 小贷 / 保险等**各类合作机构**（ADR-15）。用 `CHANGE COLUMN`，**不动数据** |
| **未变** | `interface_no`/`project_code`/`interface_name`/`tenant_id`/`del_status`/`add_request_id`/`modify_request_id`/两个模板列列名 | | 列名原样保留 → 迁移脚本仍是纯增量的 `ALTER`（改名只有上面那 2 处） |

### 6.3 `ifmap_logic_branch_config` 目标结构

```sql
CREATE TABLE `ifmap_logic_branch_config` (
  `key_id`             bigint       NOT NULL                  COMMENT '主键',
  `tenant_id`          bigint       NOT NULL DEFAULT -1        COMMENT '租户ID',
  `interface_no`       varchar(64)  NOT NULL                  COMMENT '接口编号',
  `method_flag`        varchar(64)           DEFAULT NULL      COMMENT '【语义重定义】动作标识(Action Key)：分支命中后执行的动作，由宿主机 ActionRegistry 注册；空=默认兜底分支',
  `logic_branch_name`  varchar(128) NOT NULL                  COMMENT '逻辑分支名称',
  `logic_branch_flag`  varchar(512) NOT NULL DEFAULT ''        COMMENT '【扩】逻辑分支标志（JsonPath 表达式，支持 $.a.b 与裸字段名）',
  `logic_branch_value` varchar(512) NOT NULL DEFAULT ''        COMMENT '【扩】逻辑分支判断值，多值以 | 分隔',
  `logic_branch_order` smallint     NOT NULL DEFAULT 0         COMMENT '【新】分支匹配顺序，升序，先命中先生效；默认兜底分支应设为最大',
  `remark`             varchar(512) NOT NULL DEFAULT ''        COMMENT '备注',
  `del_status`         tinyint(1)   NOT NULL DEFAULT 0         COMMENT '删除标识;0:未删除1:已删除',
  `deleted_seq`        bigint       NOT NULL DEFAULT 0         COMMENT '【新】软删除唯一化',
  `add_user_id`        varchar(64)  NOT NULL DEFAULT ''        COMMENT '添加人',
  `add_time`           datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '添加时间',
  `add_request_id`     varchar(40)  NOT NULL DEFAULT ''        COMMENT '创建请求ID',
  `modify_user_id`     varchar(64)  NOT NULL DEFAULT ''        COMMENT '更新人',
  `modify_time`        datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `modify_request_id`  varchar(40)  NOT NULL DEFAULT ''        COMMENT '修改请求ID',
  PRIMARY KEY (`key_id`),
  UNIQUE KEY `uk_ifmap_logic_branch` (`tenant_id`,`interface_no`,`method_flag`,`logic_branch_name`,`deleted_seq`),
  KEY `idx_ifmap_logic_branch_query` (`tenant_id`,`interface_no`,`del_status`,`logic_branch_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC
  COMMENT='资方接口逻辑分支配置';
```

**关键点**：

1. **新增 `logic_branch_order`** 是本次表结构最重要的修复（TS-2）——它是 `findFirst()` 顺序不确定问题的**唯一根治手段**（应用层排序无法解决"DB 返回顺序不定"）。
2. **`method_flag` 语义变更**（TS-6）：ECC 里它是 scfp 专属动作名（`discSuccess`/`discFail`），开源版把它定义成**通用的动作标识**，取值由宿主机注册。迁移时 ECC 只需把 `discSuccess`/`discFail` 注册为两个 Action，**数据零改动**。
3. 唯一键含 `method_flag`：同一接口下同名分支可因动作不同而共存；`method_flag` 为 NULL 时 MySQL 唯一键**不约束 NULL**（多个 NULL 允许）→ 正好允许"多条默认兜底分支"存在，由 `logic_branch_order` 决定优先级（不再静默取第一条，而是**启动期告警**：默认分支 >1 条）。

> ⚠️ 注意：唯一键含 `method_flag` 而 MySQL 唯一索引允许多个 NULL，这是一个**有意利用**的特性，需在管理端加"默认分支唯一"的校验（§8.3）。

### 6.4 `ifmap_execution_log` 目标结构

```sql
CREATE TABLE `ifmap_execution_log` (
  `key_id`          bigint       NOT NULL                  COMMENT '主键',
  `tenant_id`       bigint       NOT NULL DEFAULT -1        COMMENT '租户ID',
  `interface_no`    varchar(64)  NOT NULL                  COMMENT '接口编号',
  `biz_id`          varchar(64)  NOT NULL                  COMMENT '业务ID',
  `request_param`   json                  DEFAULT NULL      COMMENT '【改】请求参数（已脱敏；超长按配置截断）',
  `response_param`  mediumtext            DEFAULT NULL      COMMENT '【改】响应参数（已脱敏；超长按配置截断）',
  `execution_time`  bigint       NOT NULL DEFAULT 0         COMMENT '执行耗时(ms)',
  `execution_result` varchar(16)          DEFAULT NULL      COMMENT '【改】执行结果：SUCCESS/FAIL/SKIP/TIMEOUT',
  `error_msg`       varchar(1024)         DEFAULT NULL      COMMENT '【新】失败原因（截断）',
  `remark`          varchar(512) NOT NULL DEFAULT ''        COMMENT '备注',
  `del_status`      tinyint(1)   NOT NULL DEFAULT 0         COMMENT '删除标识;0:未删除1:已删除',
  `add_user_id`     varchar(64)  NOT NULL DEFAULT ''        COMMENT '添加人',
  `add_time`        datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '【改类型】执行时间',
  `add_request_id`  varchar(40)  NOT NULL DEFAULT ''        COMMENT '创建请求ID',
  `modify_user_id`  varchar(64)  NOT NULL DEFAULT ''        COMMENT '更新人',
  `modify_time`     datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `modify_request_id` varchar(40) NOT NULL DEFAULT ''       COMMENT '修改请求ID',
  PRIMARY KEY (`key_id`),
  KEY `idx_ifmap_log_biz`     (`tenant_id`,`biz_id`,`add_time`),
  KEY `idx_ifmap_log_iface`   (`tenant_id`,`interface_no`,`add_time`),
  KEY `idx_ifmap_log_time`    (`add_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC
  COMMENT='资方接口执行日志';
```

| 变更 | 理由 |
|---|---|
| `request_param json NOT NULL` → **可 NULL** | 部分接口无请求体（如纯查询回调）；NOT NULL 会导致插入失败 |
| `response_param longtext` → `mediumtext` | 上限 16MB 已足够；`longtext` 会让行外存储页更易膨胀。**若宿主机需保留超大报文，配 `ifmap.log.truncate-threshold` 截断** |
| `execution_result char(2)` → `varchar(16)` + 枚举 | TS-12；`char(2)` 存不下 `SUCCESS` |
| 新增 `error_msg` | 失败原因可查（现在只能从 `remark` 猜） |
| 新增 3 个索引 | TS-3；`idx_ifmap_log_time` 同时服务于**清理任务**与按时间归档 |
| `execution_time` 语义澄清 | ECC 里初值为 `0`、语义为**耗时 ms**，保留 |

**归档 / 清理**（TS-11）：

| 方案 | 说明 | 默认 |
|---|---|---|
| A. 保留期清理 | 引擎内置 `LogCleaner`（定时按 `add_time < now - retentionDays` 分批删除），默认 **90 天**，批大小 1000、每批 sleep，配置 `ifmap.log.retention-days` / `ifmap.log.clean-batch-size` | ✅ 默认启用 |
| B. 按月分区 | `PARTITION BY RANGE (TO_DAYS(add_time))`，需要 `PRIMARY KEY (key_id, add_time)`；`DROP PARTITION` 秒级回收 | ✅ 提供 6 步运维手册，默认关闭（增加运维复杂度） |
| C. 冷热分离 | 归档到 `ifmap_execution_log_archive`（冷表） | ✅ 提供 `JdbcExecutionLogArchiver` + 建表脚本，默认关闭（不自动调度） |

> 选 A 的理由：开源产品要"装完就能用"，不应强制用户先建分区。B / C 作为**可选手册**给出
> （W10 落地）：`ifmap-provider-jdbc/src/main/resources/db/optional/execution-log-partition/`
> （体检 / 分区改造 / 每月加分区 / 校验 / 建冷表 / 归档回收 6 步 + README），
> 归档器 `JdbcExecutionLogArchiver` 可重入、分批、列清单自检；文档见 `docs/08-日志与合规.md` §5.2 / §5.3。

**A 的落地实现（W6）**：`JdbcExecutionLogCleaner`（provider-jdbc）+ `IfmapLogCleanTask`（任务）+ `IfmapLogCleanScheduler`（cron 驱动）。要点：

| 决策 | 取值 | 理由 |
|---|---|---|
| 默认开关 | **开**（`ifmap.log.clean-enabled=true`） | 装完即用；关掉后清理器 bean 仍在，可手工调用 |
| 删除算法 | 两步：`SELECT key_id ... LIMIT n` → `DELETE ... WHERE key_id IN (...)` | `DELETE ... LIMIT` 是 MySQL 方言；`DELETE ... IN (SELECT 同表)` 在 MySQL 报 1093 → 两步法 MySQL/H2 都合法（单测可用 H2 跑真实 DDL） |
| 安全阀 | `retentionDays &le; 0` 直接拒绝 | 配 0 会退化成"删全表" |
| 护栏 | `maxBatches` 封顶 + WARN | 一次跑太久会挤压业务；没删完的留给下个周期 |
| 防主从延迟 | 批大小 1000 + 批间 sleep 50ms（可配） | 一条 DELETE 一个事务，批太大 → 大 binlog 事件 + 从库串行 apply |
| 可中断 | 线程被中断即停止本轮并保留中断标记 | 应用停机/优雅关闭时不硬撑 |
| 失败处理 | 只 WARN（`cleanNow()` 返回 -1），不向上抛 | 后台任务异常不该影响业务，也不该被调度器判为"任务失败"停掉 |
| 调度方式 | 自带**守护线程**单线程调度器 + `CronExpression.parse`（**不用 `@EnableScheduling`**） | ① 自动配置不该打开宿主的全局调度设施；② Spring 无 TaskScheduler 时用**非守护**线程 → 非 Web/批处理应用 `main()` 返回后 JVM 不退出（**本仓库 demo 实测卡死，改守护线程后 exit=0**） |
| 表名前缀 | 走 `TableNameResolver` | 复用存量 `bankint_*` 表时清理的也是那张表 |

公开文档：`docs/08-日志与合规.md`（含 10 条合规自查清单与排错表）。

### 6.5 新增表 `ifmap_config_history`（TS-13）

```sql
CREATE TABLE `ifmap_config_history` (
  `key_id`         bigint       NOT NULL                  COMMENT '主键',
  `config_key_id`  bigint       NOT NULL                  COMMENT '原配置主键',
  `tenant_id`      bigint       NOT NULL DEFAULT -1        COMMENT '租户ID',
  `interface_no`   varchar(64)  NOT NULL                  COMMENT '接口编号（冗余，便于按接口查历史）',
  `change_type`    varchar(16)  NOT NULL                  COMMENT 'CREATE/UPDATE/DELETE/ENABLE/DISABLE',
  `change_reason`  varchar(255) NOT NULL DEFAULT ''        COMMENT '变更原因',
  `snapshot`       json         NOT NULL                  COMMENT '变更后的配置快照（DELETE 时存删除前快照）',
  `diff`           json                  DEFAULT NULL      COMMENT '与上一版的字段差异（可选，便于展示）',
  `add_user_id`    varchar(64)  NOT NULL DEFAULT ''        COMMENT '操作人',
  `add_time`       datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '变更时间',
  `add_request_id` varchar(40)  NOT NULL DEFAULT ''        COMMENT '请求ID',
  PRIMARY KEY (`key_id`),
  KEY `idx_ifmap_history_config` (`config_key_id`,`add_time`),
  KEY `idx_ifmap_history_iface`  (`tenant_id`,`interface_no`,`add_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC
  COMMENT='资方接口配置变更历史';
```

用途：管理端"版本对比 / 一键回滚"；审计"谁在什么时候把模板改坏了"。由 `admin` 模块写入（引擎运行期只读）。

### 6.6 数据字典

| 字段 | 取值 | 归属 |
|---|---|---|
| `busi_node` | 由宿主机注册（ECC 实例：`GP81`/`GP85`/`GP98`/`GP99`…，见 `BankFileNodeEnum`） | 宿主机 |
| `financing_mode` | `0` 直保理 / `1` 再保理 / `2` 转开银票 / `3` 提前结清 / `10` 合同模式 / `11` 订单模式 / `12` 到货模式 / `13` 发票模式 / `14` 信用模式 | 沿用原注释 |
| `execution_result` | `SUCCESS` / `FAIL` / `SKIP` / `TIMEOUT` | 引擎 |
| `change_type` | `CREATE` / `UPDATE` / `DELETE` / `ENABLE` / `DISABLE` | 管理端 |
| `status` | `1` 启用 / `0` 停用 | 引擎 |
| `method_flag` | 宿主机注册的动作标识（ECC 实例：`discSuccess` / `discFail`） | 宿主机 |

**三个"编号"的语义澄清（TS-18）**：

| 字段 | 语义 | 示例 |
|---|---|---|
| `interface_no` | **引擎内部的接口业务编号**，**租户内唯一**（`uk_ifmap_config_biz`），是配置查询与 `queryLogicBranches` 的键；一个接口号只属于一个 `busi_node` | `IF_PSBC_SIGNUP_001` |
| `interface_code` | **对接方（银行/金融机构服务）的接口编码**，用于出网调用时定位对方接口 | `PSBC_0001` |
| `busi_node` + `interface_order` | **步骤位置**：同一业务节点内唯一（`uk_ifmap_config_order`），决定执行次序；调用方传的 `interfaceNo` 是这段计划的**起点** | `apply` + `2` |

### 6.7 迁移映射（ECC 现有表 → 目标表）

由于"只增不映射"（改名只有 2 处、`CHANGE COLUMN` 即可，不需要 ETL），迁移脚本形式统一：

```sql
-- 示例：ifmap_config 存量迁移（在同一实例内新增列后回填）
ALTER TABLE `ifmap_config`
  CHANGE COLUMN `bank_code`        `partner_code` varchar(32)  NOT NULL                     COMMENT '合作机构编码',
  CHANGE COLUMN `bank_name`        `partner_name` varchar(128)          DEFAULT NULL        COMMENT '合作机构名称',
  MODIFY COLUMN `interface_order`  smallint     NOT NULL DEFAULT 0,
  MODIFY COLUMN `result_flag`      varchar(512) NOT NULL DEFAULT '',
  MODIFY COLUMN `success_value`    varchar(512) NOT NULL DEFAULT '',
  -- ↓ 旧 `strategy_name` 一列拆成两列（存量先 ADD COLUMN，值由人工按新口径重配）
  MODIFY COLUMN `pre_strategy_name`  varchar(128) NOT NULL DEFAULT '',
  MODIFY COLUMN `post_strategy_name` varchar(128) NOT NULL DEFAULT '',
  MODIFY COLUMN `busi_node`        varchar(32)  NOT NULL,
  MODIFY COLUMN `add_user_id`      varchar(64)  NOT NULL DEFAULT '',
  MODIFY COLUMN `modify_user_id`   varchar(64)  NOT NULL DEFAULT '',
  MODIFY COLUMN `add_time`         datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  MODIFY COLUMN `modify_time`      datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD COLUMN    `status`           tinyint(1)   NOT NULL DEFAULT 1  COMMENT '状态：1启用 0停用',
  ADD COLUMN    `version`          int          NOT NULL DEFAULT 0  COMMENT '乐观锁版本',
  ADD COLUMN    `deleted_seq`      bigint       NOT NULL DEFAULT 0  COMMENT '软删除唯一化',
  ADD UNIQUE KEY `uk_ifmap_config_biz` (`tenant_id`,`interface_no`,`busi_node`,`interface_order`,`deleted_seq`),
  ADD KEY `idx_ifmap_config_list` (`tenant_id`,`del_status`,`busi_node`,`partner_code`),
  ROW_FORMAT=DYNAMIC,
  ALGORITHM=COPY, LOCK=SHARED;
```

> ⚠️ **改名也在这一条 `ALTER` 里**：5.7 没有 `RENAME COLUMN`（8.0.28+ 才有），只能 `CHANGE COLUMN`；
> 它与上面的类型变更合并 → **不额外多一次重建**。注意官方在线 DDL 表里「Renaming a column」虽然标
> In Place / 不重建，但脚注限定 *"keep the same data type and only change the column name"* ——
> 本 kit 顺带改了列注释（`bank_name` 还收窄了类型），所以实际仍走 `COPY`，与类型变更同批。
> **但改名会让仍写旧列名的存量 SQL 立刻报 1054** —— 共存期怎么处置（同步改存量 SQL / 可更新兼容视图 /
> 把改名挪到 `04` 的停写窗口）见 `db/migration/ecc-to-ifmap/README.md` §2，这是本次改名**真正的新增风险**。

> ⚠️ **MySQL 5.7 铁律**：同表多变更**必须合并成一条 ALTER**（5.7 无 `ALGORITHM=INSTANT`，每条独立语句各重建一次表）；每条都要**显式写** `ALGORITHM=` 与 `LOCK=`，防静默降级成阻塞写入的 `COPY`。
>
> **算法与锁按操作类型区分（W8 核实官方在线 DDL 表后修正）**：
>
> | 操作 | 5.7 可用写法 | 说明 |
> |---|---|---|
> | 纯 `ADD COLUMN` / `ADD KEY` / 仅把列置为可空 | `ALGORITHM=INPLACE, LOCK=NONE` | 允许并发读写 |
> | **改变列数据类型**（上面这条 ALTER 就是：`char(2)→smallint`、`bigint→varchar(64)`、`timestamp→datetime(3)`…） | **`ALGORITHM=COPY, LOCK=SHARED`** | 只允许并发读，阻塞写；**`LOCK=NONE` 在这种语句上会直接报错** |
>
> 另：唯一键 `(tenant_id, interface_no, busi_node, interface_order, deleted_seq)` 约 1040 字节，超过旧行格式 `COMPACT` 的 767 字节索引前缀上限（报 1071）→ `COPY` 重建时一并写 `ROW_FORMAT=DYNAMIC`（上限 3072 字节）。详见 `SQL/DDL/大表ALTER防主从延迟规范与踩坑记录.md`。

| 源字段 | 目标字段 | 转换 |
|---|---|---|
| `interface_order char(2)` | `smallint` | 直接 `CAST`（值均为数字串） |
| `add_user_id bigint` | `varchar(64)` | 直接转 |
| `add_time timestamp` | `datetime(3)` | 注意**时区**：`timestamp` 存储已转 UTC，迁移会按会话时区解释 → 迁移前 `SET time_zone='+08:00'` |
| `del_status=1` 的历史行 | `deleted_seq` | `UPDATE ... SET deleted_seq = key_id WHERE del_status = 1`（否则唯一键冲突） |
| `method_flag='discSuccess'` | 不变 | ECC 侧注册两个 Action，**数据零改动** |
| 无 | `logic_branch_order` | 按 `key_id` 升序回填（保持现状顺序），兜底分支给大值 |
| 无 | `status` | 默认 `1` |

**迁移执行顺序**（详见 §10）：先建新列 → 回填 → 加唯一键/索引（分两步，避免一条 ALTER 里既改数据又建唯一键失败）。

### 6.8 软删除 + 唯一约束方案（TS-5）

**问题**：`del_status` 软删除 + 业务唯一键，如何共存？

| 方案 | 做法 | 优点 | 缺点 | 采用 |
|---|---|---|---|---|
| A. 唯一键含 `del_status` | `UNIQUE(..., del_status)` | 简单 | 只能删一次（第二次删同名配置会撞唯一键）；两次以上历史行冲突 | ❌ |
| B. **唯一键含 `deleted_seq`** | 未删除 `deleted_seq=0`；删除时 `deleted_seq=key_id` | 可无限次删除重建；未删除行仍受唯一约束保护 | 多一列 + 删除时要写两个字段 | ✅ |
| C. 物理删除 + 历史表 | 删除即 DELETE，快照进 `ifmap_config_history` | 表最干净，无唯一键困扰 | 运行期无法"停用观察"；误删需从历史恢复 | 备选（配置量小的场景更简洁） |
| D. 唯一键不含软删除 | `UNIQUE(..., del_status)` 用触发器 | — | MySQL 触发器难维护 | ❌ |

**采用 B**，并在引擎里把"删除"实现为原子操作：

```sql
UPDATE ifmap_config
   SET del_status = 1, deleted_seq = key_id, version = version + 1,
       modify_user_id = ?, modify_time = CURRENT_TIMESTAMP(3), modify_request_id = ?
 WHERE key_id = ? AND del_status = 0;
```

### 6.9 审计字段的维护责任

| 字段 | 谁维护 | 说明 |
|---|---|---|
| `add_time` / `add_user_id` / `add_request_id` | 管理端（INSERT 时） | 引擎运行期不写配置 |
| `modify_time` / `modify_user_id` / `modify_request_id` | 管理端（UPDATE 时统一设置） | **DDL 不加 `ON UPDATE CURRENT_TIMESTAMP`**：因为要同时写 `modify_user_id`，靠 DDK 自动更新会导致"时间变了但人没变"的错配 |
| `version` | 管理端（`UPDATE ... SET version = version + 1 WHERE key_id = ? AND version = ?`） | 乐观锁；影响行数 0 → 抛"配置已被他人修改" |

### 6.10 主键生成（TS-15）

| 方案 | 说明 | 默认 |
|---|---|---|
| 雪花 ID | `IdGenerator` SPI 默认实现（workerId 从配置/环境变量取，支持容器化部署） | ✅ |
| 数据库自增 | 生成 DDL 时把列改为 `bigint NOT NULL AUTO_INCREMENT`（由 `ifmap.ddl.id-strategy=auto-increment` 控制建表脚本） | 可选 |
| 自定义 | 宿主机实现 `IdGenerator` | 可选 |

> 原表用 `bigint(19) NOT NULL`（19 是无效宽度，实际 20），靠外部服务生成 ID。starter 必须自带生成能力，否则"自带建表"无法闭环。

### 6.11 MySQL 版本与建表脚本策略

| 关注点 | 5.7 | 8.0 | 对策 |
|---|---|---|---|
| 显示宽度 `bigint(20)` | 支持 | 8.0.19+ **弃用**（警告） | DDL 写 `bigint`（两版都对） |
| `json` 类型 | 5.7.8+ 支持 | 支持 | ✅ |
| `datetime(3)` | 支持 | 支持 | ✅ |
| 默认排序规则 | `utf8mb4_general_ci` | `utf8mb4_0900_ai_ci` | **显式写 `COLLATE=utf8mb4_general_ci`**（8.0 也认） |
| 表达式默认值 `DEFAULT (expr)` | ❌ | 8.0.13+ | 避免使用 |

> **D3 决策（已定）**：**自测兼容 MySQL 5.7、主推 8.0**。DDL 采用上表的双版本兼容写法（`bigint` 不带显示宽度、显式 `COLLATE=utf8mb4_general_ci`、`datetime(3)`、不用 `DEFAULT (expr)`）。
>
> **自测口径（W2 实测更正）**：W2 当前**没有可用的 Docker 环境**，`provider-jdbc` 的仓储测试改为
> **H2 `MODE=MySQL` 执行生产建表脚本**（`TestSchema` 只剥离表尾 `ENGINE/COLLATE/ROW_FORMAT` 与表级 `COMMENT`，
> 列定义/主键/唯一键/索引原样执行，且变换不匹配即失败）。**MySQL 5.7/8.0 的真库验证由发布侧执行**；
> `ci.yml` 里的 MySQL 集成 job 保持注释状态，待有 Docker 的 CI 环境再启用（Testcontainers）。
>
> **W3 起，H2 兼容不再依赖"测试专用改写"**：starter 的 `IfmapSchemaInitializer` 按数据库方言决定是否剥表尾表选项
> （MySQL/MariaDB 原文执行；其它库剥掉 `ENGINE= / CHARSET= / COLLATE= / ROW_FORMAT= / COMMENT=`，断言式），
> 因此 **H2 跑的就是生产 DDL 本身**；`provider-jdbc` 测试里的 `TestSchema` 也遵循同一规则。
> H2 连接串**必须带 `MODE=MySQL`**（脚本用了 `KEY idx_...` / `tinyint(1)` / `datetime(3)`）。

**建表脚本交付形态**（P0-4）：

```
ifmap-provider-jdbc/src/main/resources/
├── db/changelog/db.changelog-master.yaml            # Liquibase 主入口（${tablePrefix} 参数，默认 ifmap_）
├── db/changelog/v1.0.0/001-create-ifmap-config.sql        # 28 列
├── db/changelog/v1.0.0/002-create-logic-branch.sql        # 17 列
├── db/changelog/v1.0.0/003-create-execution-log.sql       # 17 列
├── db/changelog/v1.0.0/004-create-config-history.sql      # 11 列
├── db/optional/execution-log-partition/README.md    # 可选：分区 + 冷热分离 6 步运维手册（默认不启用）
├── db/optional/execution-log-partition/00-precheck.sql           # 体检（只读）
├── db/optional/execution-log-partition/01-partition-hot.sql      # 热表改按月分区（重建表）
├── db/optional/execution-log-partition/02-add-monthly-partition.sql  # 每月加分区
├── db/optional/execution-log-partition/03-verify.sql             # 校验（只读）
├── db/optional/execution-log-partition/04-create-archive-table.sql   # 建归档冷表
└── db/optional/execution-log-partition/05-archive-and-drop.sql   # 归档 + 保留期回收
```

> **与初稿的差异（W2 回填）**：初稿的 `005-create-indexes.sql` **未采用** —— 四张表的索引/唯一键直接写在各自
> `CREATE TABLE` 内（一次建表即完整，避免"表建好了但索引漏执行"的中间态）；`db/optional/` 只保留一份
> **可选**的运维手册（默认不启用；启用分区需先把执行日志主键改为 `(key_id, add_time)`，详见该目录 README）。
> 列数口径：``grep -cE '^  `[a-z_]+` +[a-z]' <file>``。

| 配置 | 默认 | 说明 |
|---|---|---|
| `ifmap.ddl.auto` | `true`（starter 场景）/ `false`（纯 Java） | 是否随启动建表 |
| `ifmap.table-prefix` | `ifmap_`（空 → `ifmap_config`；复用 ECC 存量表时设为 `bankint_`） | 表名前缀，便于同库多实例/多环境共存 |
| `ifmap.id-strategy` | `snowflake` | `snowflake` / `auto-increment` / `custom` |
| `ifmap.audit-fields.enabled` | `true` | 关掉后可精简审计列（生成 DDL 时） |
| `ifmap.multi-tenant.enabled` | `false` | 单租户固定 `-1` |

#### 表名：统一为 `ifmap_*`（2026-09-30 定稿，替代早期"保留 `bankint_*`"方案）

> **决策变更**：早期版本为了"承接 ECC 存量数据、迁移脚本纯增量"，曾决定表名保留 `bankint_*`。
> 按最新要求（**项目更名后 DDL 同步更名**），表名与项目名统一为 `ifmap_*`。
> 字段名**仍遵循 P1「只增不映射」**（§6.1）—— **表名**变（`RENAME TABLE`，元数据操作），
> 列名只有 **2 处**变（`bank_code`/`bank_name` → `partner_code`/`partner_name`，`CHANGE COLUMN`），
> 因此附录 C 的迁移脚本仍然是**纯增量**（`ALTER + UPDATE`，无 ETL）。

| 旧名（ECC 存量） | 新名（ifmap） | 索引 / 唯一键同步更名 |
|---|---|---|
| `bankint_config` | `ifmap_config` | `uk_ifmap_config_biz`、`idx_ifmap_config_list` / `_front` / `_code` |
| `bankint_logic_branch_config` | `ifmap_logic_branch_config` | `uk_ifmap_logic_branch`、`idx_ifmap_logic_branch_query` |
| `bankint_execution_log` | `ifmap_execution_log` | `idx_ifmap_log_biz` / `_iface` / `_time` |
| `bankint_config_history` | `ifmap_config_history` | `idx_ifmap_history_config` / `_iface` |

**与 ECC 共存的三条路径**（按推荐度排序）：

| 方案 | 做法 | 适用场景 | 代价 / 风险 |
|---|---|---|---|
| **A. 前缀指向**（共存期首选） | ifmap 侧配 `ifmap.table-prefix=bankint_`，**直接读写 ECC 现有表** | ECC 与 ifmap 需同时使用同一批配置（灰度切换期） | 0 数据迁移；表名非项目名风格 |
| **B. 一次 `RENAME TABLE`**（切换期首选） | `RENAME TABLE bankint_config TO ifmap_config, bankint_logic_branch_config TO ifmap_logic_branch_config, bankint_execution_log TO ifmap_execution_log, bankint_config_history TO ifmap_config_history;` | ECC 的 `bankintconfig` 模块**已停用下线** | 元数据操作、**秒级**（5.7 与 8.0 均支持）；⚠️ 执行后 ECC 旧代码（SQL 里写死 `bankint_config`）**立即失效**，故必须先下线旧模块 |
| **C. 新建 + `INSERT ... SELECT`**（最稳） | 新建 `ifmap_*` 表，配置数据搬一次 | 两套需并行验证、要求可随时回滚 | 一次数据搬迁（配置表数据量小，秒级~分钟级）；回滚容易 |

> ⚠️ **不要**用「建视图 `bankint_config` → `ifmap_config`」做兜底：视图不可写、且会掩盖真实迁移状态。
> ⚠️ **B 方案的安全顺序**：① 停 ECC 读配置的定时任务/入口 → ② 停 `bankintconfig` 模块 → ③ 执行 RENAME → ④ 起 ifmap → ⑤ 观察。任一步失败可 `RENAME` 回滚（秒级）。

---

## 7. 运行期设计

### 7.1 执行主流程（不变的部分）

引擎执行语义**保持与 ECC 一致**（P0-5 明确"不改语义"），只把入口模型从 ISF 类型换成自有类型：

```
IfmapRequest
  → [执行计划] 取该 busi_node 内的有序步骤（interface_order 升序），截断到起点 interfaceNo
  → [主参数组包] FullParamStrategy（按 partnerCode + busiNode）+ payload + 跨步骤共享池
  → [请求模板渲染] @FUN 规则 + JsonPath 取数（ReadContext 一次解析多次取数）
  → [前序策略钩子] IfmapStrategyHook（pre_strategy_name，相位 PRE）
  → [出网调用] 宿主机提供的 PartnerServiceGateway SPI
  → [响应模板解析] response_param_template → 本步骤产物
  → [后序策略钩子] IfmapStrategyHook（post_strategy_name，相位 POST）
  → [结果判定] result_flag + success_value（多值）；**成功才把产物写进共享池**
  → [逻辑分支] logic_branch_order 升序匹配 → ActionRegistry 执行
  → [执行日志] ExecutionLogSink（脱敏后落库）
IfmapResult（data = 展平后的池 + pool 键）
```

### 7.2 租户上下文（P1-9）

```java
public interface TenantResolver {
    String resolve(IfmapRequest request);   // 默认实现读请求头 X-Tenant-Id
}
```

- 默认从请求头 `X-Tenant-Id` 取；缺失时用 `ifmap.default-tenant-id`（默认 `-1`）
- **缓存 key 必须含 tenantId**（这是方案 v1 §7 风险表里的"高风险：跨租户串配置"）
- 纯 Java 场景由调用方在 `IfmapRequest.tenantId` 显式传入

### 7.3 配置缓存

```java
ConfigCacheKey = tenantId + ":" + interfaceNo + [":" + busiNode]
```

| 项 | 值 |
|---|---|
| 实现 | Caffeine（`maximumSize` 默认 1000，`expireAfterWrite` 默认 60s） |
| 失效 | 管理端保存/删除时主动 `invalidate(key)`；跨实例失效靠 TTL |
| 开关 | `ifmap.cache.enabled=true`，`ifmap.cache.ttl=60s` |
| 注意 | **只缓存 `interfaceNo` 维度的分支列表**，其余按需（与方案 v1 T22 一致） |

> 修正说明：方案 v1 基于"零新增依赖"选了 Hutool `TimedCache`；本设计的父 BOM 已管理 Caffeine 版本（`caffeine.version=2.9.2`），且开源工程更应选 Caffeine（有上限淘汰与统计），故**改为 Caffeine**。

### 7.4 执行顺序（TS-1 的落地）

```
queryConfigsByBusiNode(tenantId, busiNode)
  → 过滤 status = 1 且 del_status = 0
  → ORDER BY interface_order ASC, key_id ASC   ← 引擎侧兜底排序（即使 provider 没排序）
  → 起点 interfaceNo 非空时：定位它在计划中的位置并截断（取它及其之后）
  → 起点不在计划内（停用 / 已删除 / 编号写错）→ 抛 IfmapConfigException
```

### 7.5 合规：日志脱敏（P1-12）

```java
public interface LogMasker {
    String mask(String json);          // 返回脱敏后的 JSON 字符串
}
```

| 内置脱敏规则（默认开启） | 正则 |
|---|---|
| 手机号 | `1[3-9]\d{9}` → `138****8888` |
| 身份证 | 18 位 → 保留前 6 后 4 |
| 银行卡 | 13~19 位连续数字 → 保留前 6 后 4 |
| 名称类字段 | 按字段名配置（`acctName`/`certName` 等，配置化） |

| 其他合规项 | 设计 |
|---|---|
| 截断 | `ifmap.log.truncate-threshold=64KB`，超出截断并标记 `...truncated` |
| 保留期 | 默认 90 天（§6.4 方案 A，**W6 已落地**：`JdbcExecutionLogCleaner` + `IfmapLogCleanTask`，分批删除 + 批间 sleep + `maxBatches` 护栏 + `retentionDays<=0` 拒绝） |
| 加密 | **不做**（国密/SM 加解密属业务，宿主机在策略实现里做） |
| 敏感字段不落库 | 支持 `ifmap.log.exclude-fields` 配置 |

### 7.6 异常体系

| 异常 | 场景 | 是否可重试 |
|---|---|---|
| `IfmapConfigException` | 配置缺失/非法/成环/契约违规 | ❌ |
| `IfmapRuleException` | `@FUN` 规则不存在/参数不匹配 | ❌ |
| `IfmapStrategyException` | 策略/动作未注册 | ❌ |
| `IfmapRemoteException` | 出网调用失败 | ✅ |
| `IfmapException` | 基类 | — |

**与旧行为的关键差异**（对应方案 v1 的 P0/P1）：
- `finally` 里写日志失败**不再吞异常**，改为 `WARN` + 上报（可配）
- 分支/接口未匹配**不再静默**，改为 `WARN` + 可选 `fail-fast`
- 非引擎异常**不再强转**（ECC 现状 `:212` 会把非 `BizException` 转成 `ClassCastException`）

---

## 8. 配置管理端（P1-8）

### 8.1 形态

| 项 | 决策 |
|---|---|
| 交付方式 | 独立模块 `ifmap-admin`（可选依赖）。**v1.0 只交付 REST API**；可视化页面**延后至 v1.1**（Q6 结论），v1.0 仅附一个静态只读页（规则清单 + 配置列表） |
| 依赖 | `spring-web`（不引 Spring Security；鉴权交给宿主机） |
| 挂载 | `@ConditionalOnProperty(ifmap.admin.enabled=true)` |
| 路径 | `/ifmap/admin/**`（可配 `ifmap.admin.base-path`） |

### 8.2 API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/configs` | 分页查询（tenant/busiNode/partnerCode/interfaceNo 筛选） |
| GET | `/configs/{id}` | 详情 |
| POST | `/configs` | 新增（含校验） |
| PUT | `/configs/{id}` | 修改（乐观锁 `version`） |
| DELETE | `/configs/{id}` | 逻辑删除（§6.8 方案 B） |
| POST | `/configs/{id}/enable`、`/disable` | 启停（TS-9） |
| POST | `/configs/validate` | **保存前校验**（不落库）：全过 → 200 + 明细；有 error → **422 + `errors`/`warnings`** |
| POST | `/configs/dry-run` | **试跑**：用样例报文 + 假应答跑一遍完整编排链路，返回映射结果（`tenantId`/`interfaceNo`/`busiNode`/`params`/`mockResponse` 都在请求体里，不占路径） |
| GET | `/configs/{id}/history` | 变更历史 |
| POST | `/configs/{id}/rollback/{historyId}` | 回滚 |
| GET | `/branches` | 逻辑分支查询/维护（含 `logic_branch_order`） |
| GET | `/rules` | **可用规则清单**（`RuleRegistry.describeAll()`） |
| GET | `/actions` | 已注册动作清单 |
| GET | `/audit` | 全量配置审计（落地方案 v1 的 T23：`@FUN` 合法性、`pre_/post_strategy_name` 契约、池引用、死分支、重复默认分支）；`tenantId` 不传 = **跨租户全量**，`markdown=true` 返回可贴工单的报告 |

**状态码契约（W5 落地时定稿）**：`200` 成功，**也包括"业务上没做成"**（乐观锁冲突、目标已删除 → `ok=false`，前端按业务提示而不是弹通用错误）；`400` 用错接口 / 目标不存在 / 参数缺失；`422` 保存前校验未通过（带 `errors`）；`502` 外呼失败（试跑正常不出网）；`500` 兜底。详见公开文档 `docs/07-管理端REST.md`。

### 8.3 保存前校验清单（把"线上炸"提前到"保存时拒"）

| 校验 | 依据 |
|---|---|
| 模板是合法 JSON | 基础 |
| 所有 `@FUN(name,…)` 的 `name` 已在 `RuleRegistry` | 方案 v1 P0-5 |
| `@FUN` 参数个数/类型可匹配到某个重载 | 方案 v1 P1-4（含 5 组重载） |
| `pre_strategy_name` / `post_strategy_name` ∈ 已注册策略 key（**权威 bean 名**） | 方案 v1 P0-7（11/38 不一致）；两列拆自旧 `strategy_name` |
| 所有 JsonPath 表达式语法合法 | 新增能力 |
| 请求模板里的 `$.pool.<接口号>` 指向本节点内**更早**的步骤 | 落地后新增；取代原「`front_interface_no` 不构成环」（方案 v1 P0-2） |
| 默认兜底分支 ≤ 1 条；`logic_branch_order` 唯一 | 本次 TS-2 |
| `method_flag` ∈ 已注册动作 | 本次 TS-6 |
| 唯一键不冲突（**两个维度**）：`(tenant, interface_no)` 与 `(tenant, busi_node, interface_order)` | 本次 TS-4（DB 两把唯一键兜底 + 友好提示） |

---

## 9. 开源工程化

### 9.1 项目命名（✅ 已定稿：`ifmap`）

**为什么必须改**：`bankint` = **bank** + **interface**，把范围锁死在"银行"。但引擎的实际能力是「**配置驱动的对外接口报文映射与编排**」，适用对象包括银行、担保/保理公司、征信、税务、发票平台、物流、ERP……（P0-1 已定为开源）。**名字必须反映能力，而不是反映当前第一个使用方。**

#### 命名要求

| 编号 | 要求 |
|---|---|
| N1 | 不绑定 `bank`/`finance` 等具体行业 |
| N2 | 语义指向"接口对接 / 报文映射 / 编排"，新人看名字能猜出用途 |
| N3 | 短（≤ 10 字符），好拼、好念、**无歧义读音** |
| N4 | 可直接作 Java 包名（全小写、无连字符）与 artifactId 前缀 |
| N5 | Maven Central / GitHub / 搜索引擎**无同领域同名项目** |
| N6 | 文档站域名可用（`ifmap.io` / `ifmap.org` / `docs.ifmap.cn`） |

#### 候选清单（评分 1~5）

| 候选 | 含义 | 包名 | 语义 | 记忆 | 唯一性 | 综合 | 说明 |
|---|---|---|---|---|---|---|---|
| **`ifmap`** | **I**nterface **Map**ping | `cn.cj.ifmap` | ★★★★★ | ★★★★ | ★★★★ | **13** | **首选**：语义最准；`ifmap-core` / `ifmap-spring-boot-starter` 干净；`Interface` 天然涵盖银行/三方/文件接口（满足 N1） |
| **`xmap`** | e**X**change **MAP** | `cn.cj.xmap` | ★★★★ | ★★★★★ | ★★★ | **12** | 最短最有力，`x` 兼有"跨/交换"义；缺点：与 GIS 工具 XMap 同名（不同领域） |
| **`confmapper`** | **Conf**ig **Mapper** | `cn.cj.confmapper` | ★★★★★ | ★★★ | ★★★★ | **12** | 最精准表达"**配置驱动**映射"的机制本质（与 `MapStruct` 同命名族）；缺点略长 |
| **`patchbay`** | 配线架（按配置接通线路） | `cn.cj.patchbay` | ★★★★★ | ★★ | ★★★★★ | **12** | 隐喻最美：所有接口线路在配线架上按配置接通；缺点生僻 |
| **`linkflow`** | **Link** + **Flow** | `cn.cj.linkflow` | ★★★★ | ★★★★★ | ★★ | **11** | 品牌感最好、中文语境直白；缺点：国内已有 Linkflow（CDP 公司，不同领域） |
| **`mapex`** | **MAP** + **EX**change | `cn.cj.mapex` | ★★★★ | ★★★ | ★★★★ | **11** | 短、有辨识度；缺点：读音歧义（map-ex / ma-pex） |
| `stencil` | 模板/镂版 | `cn.cj.stencil` | ★★★ | ★★★★ | ★★ | 9 | 体现"模板驱动"，但 Stencil（Web Components 编译器）同名 |
| `handshake` | 握手 | `cn.cj.handshake` | ★★★ | ★★★★ | ★★ | 9 | 体现"双方对接"，但同名项目多 |
| `interlink` | 互连 | `cn.cj.interlink` | ★★★ | ★★★ | ★★★ | 9 | 偏泛 |
| ~~`loom`~~ | 织机 | — | — | — | — | — | **排除**：与 JDK Project Loom 强冲突 |
| ~~`conductor`~~ | 编排 | — | — | — | — | — | **排除**：Netflix Conductor 强冲突 |
| ~~`nexus`~~ | 枢纽 | — | — | — | — | — | **排除**：Sonatype Nexus 强冲突 |
| ~~`gateway`~~ | 网关 | — | — | — | — | — | **排除**：本引擎**不提供网络端点**（嵌入式引擎，被业务应用调用），叫 gateway 会误导使用者 |

#### 定稿：`ifmap`

| 理由 | 说明 |
|---|---|
| 1. 语义零歧义 | I-F-Map = Interface Mapping；`ifmap_config` 的本质就是「接口 → 模板」的映射表 |
| 2. 天然满足 N1 | `Interface` 不限定行业，未来的担保/征信/税务/物流接口都在语义内 |
| 3. 工程化友好 | 包名 `cn.cj.ifmap.core` / `cn.cj.ifmap.spring`；artifactId `ifmap-core`、`ifmap-spring-boot-starter`、`ifmap-admin` |
| 4. 重名风险低 | Maven Central 与 GitHub 无同领域 `ifmap` Java 项目（待最终检索确认） |
| 5. 可延展释义 | 必要时解释为「**I**ntegration **F**ramework for **Map**ping」 |

**若嫌"太工程化、缺品牌感"** → 选 `xmap`（最短）或 `linkflow`（最好念）；**若最看重"一眼看懂是配置驱动的映射器"** → 选 `confmapper`。

#### 命名唯一性检索（已执行）

| 检索项 | 结果 | 结论 |
|---|---|---|
| Maven Central | 存在 **`ifmapj`**（TCG IF-MAP 协议的 Java 客户端库，Hochschule Hannover，Apache-2.0，2014 年发版，**2015 年后无更新**） | ✅ **无坐标冲突**：`cn.cj:ifmap-core` / `ifmap-*` ≠ `ifmapj`（groupId 与 artifactId 均不同） |
| GitHub | `trustathsh/ifmapj`、`trustathsh/ifmapj-examples`（同源、2015 年停更） | ✅ 无活跃同领域项目 |
| 通用搜索 | 结果以 TCG **IF-MAP 协议**（可信计算组的网络安全元数据共享协议）客户端库为主 | ✅ **业务领域完全不同**（协议客户端 vs 接口报文映射引擎） |
| 与 JDK / 大厂项目撞名 | 无（非 Loom / Panama / Valhalla 等代号，非 Envoy / Nexus / Conductor 等中间件） | ✅ |
| 商标 | 检索未发现"ifmap"在软件类的显著商标（**需法务终审**） | ⚠️ 建议正式检索 |

**风险等级：低（可接受）**。理由：① Maven 坐标完全隔离，依赖解析无歧义；② `ifmapj` 已停维护 10 年且领域不同；③ 唯一实际影响是**搜索可发现性**（搜 `ifmap java` 会先出现 ifmapj）。

**缓解措施**：

1. artifactId 统一 `ifmap-*` 前缀（`ifmap-core`、`ifmap-spring-boot-starter`），**绝不使用 `ifmapj`**；
2. README / 文档站首页用全称标题「**ifmap** — Interface Mapping Engine」，并显式声明「**与 TCG IF-MAP 协议无关**」；
3. 文档站加 SEO 描述（"接口报文映射引擎 / interface mapping engine / 配置驱动"），提高区分度；
4. 若后续发现商标问题，`groupId` 保持 `cn.cj` 不变，仅换 artifactId 前缀即可（坐标隔离已把改造成本降到最低）。

#### 仓库与 Maven 坐标（D2 已定）

| 项 | 值 |
|---|---|
| GitHub | [`CJ8023/ifmap`](https://github.com/CJ8023/ifmap.git)（主仓；CI 用 GitHub Actions） |
| 本地开发路径 | `D:\cj\code\ifmap`（与 `trinasolar` / `cz` / `sz` / `zhongtian` 同级） |
| groupId | `cn.cj` |
| artifactId | `ifmap-parent`、`ifmap-core`、`ifmap-json-jackson`、`ifmap-json-fastjson`、`ifmap-provider-jdbc`、`ifmap-provider-remote`、`ifmap-json-tck`、`ifmap-spring-boot-starter`、`ifmap-admin-spring-boot-starter`、`ifmap-demo-pure-java`、`ifmap-demo-spring-boot3`（内存仓储不单独发模块，在 core 的 testkit 内） |
| 版本线 | `0.1.0-SNAPSHOT` → `1.0.0`（与 ECC 版本线**完全解耦**） |
| License | Apache-2.0 |

> **影响面（已完成替换）**：项目名 → `ifmap`；模块 `ifmap-*`；包名 `cn.cj.ifmap.*`；配置项前缀 `ifmap.`；类名前缀 `Ifmap*`（`IfmapEngine` / `IfmapRequest` / `IfmapException` …）。**表名统一为 `ifmap_*`**（`ifmap_config` / `ifmap_logic_branch_config` / `ifmap_execution_log` / `ifmap_config_history`，索引同步 `uk_ifmap_*` / `idx_ifmap_*`；与 ECC 存量表共存的三条路径见 §6.11）。
---

## 10. 迁移方案（ECC → Starter）

### 10.1 迁移原则

| 原则 | 说明 |
|---|---|
| ECC 不动 | 本设计不改 ECC 一行（P0-5） |
| 数据可迁 | 靠 §6.7（只增不改名）实现 `INSERT ... SELECT` 级迁移 |
| **先验后切** | 切换前跑启动期契约自检（`/audit` 0 违规）+ `dry-run` 逐接口核对，确认无误再切流量 |
| 可回滚 | 任何阶段都能退回 ECC 原链路 |

### 10.2 迁移路径（3 步）

| 阶段 | 动作 | 出口条件 |
|---|---|---|
| M1 数据迁移 | 在 starter 库建表（§6 DDL）→ 执行 §6.7 迁移脚本 → 回填 `deleted_seq`/`logic_branch_order` → 加唯一键与索引 | 行数一致；`deleted_seq` 回填完成；唯一键建立成功 |
| M2 规则与策略对齐 | ECC 侧为 `ConvertRule` 的 52 个唯一名加 `@IfmapRule`；38 个策略实现 `IfmapStrategyHook`；`discSuccess`/`discFail` 注册为 Action | 启动期契约自检 **0 违规**（用 §8 的 `/audit`）；`RuleRegistry` 覆盖内置规则数 = 0（无意外覆盖） |
| M3 切流 | 按 `busi_node` / 银行灰度切流；保留 ECC 原链路作为回退开关 | 全量切换 + 观察期结束 |

### 10.3 数据迁移风险

| 风险 | 说明 | 对策 |
|---|---|---|
| 时区 | `timestamp` → `datetime(3)`，跨时区实例迁移会漂 | 迁移前 `SET time_zone='+08:00'`；迁移后抽样比对 `add_time` |
| `deleted_seq` 冲突 | 历史软删除行若不留 `deleted_seq`，加唯一键会失败 | 先回填（`del_status=1 → deleted_seq=key_id`）再加唯一键 |
| `interface_order` 转换 | `char(2)` 里有非数字值会 `CAST` 失败 | 迁移前 `SELECT DISTINCT interface_order` 确认；非数字 → 置 0 并记录 |
| `logic_branch_order` 回填 | 无源数据，只能按 `key_id` 升序近似 | 回填后**必须人工复核**每个 `interface_no` 的分支顺序（尤其含默认兜底分支的） |
| 字段截断（1） | `varchar(255) → 64`（`interface_code`；`front_interface_no` 最终被整体删除，不再收窄）、`varchar(255) → 128`（`partner_name` / `logic_branch_name`） | 迁移前 `SELECT MAX(CHAR_LENGTH(...))` 逐列校验，超限值需人工决定截断或放宽 |
| 字段收窄（2） | `response_param longtext(4GB) → mediumtext(16MB)` | 报文不可能超 16MB；迁移前 `SELECT MAX(LENGTH(response_param))` 校验；若确有超限，保留 `longtext` 并从 §6.4 DDL 移除该变更 |
| `timestamp` 零值 | `timestamp` 的 `'0000-00-00 00:00:00'` 转 `datetime(3)` 会报错 | 迁移前查 `WHERE add_time = '0000-00-00 00:00:00'` 并修值 |

### 10.4 为什么不把 `JsonParseUtils` 抽进 core

| 原因 | 说明 |
|---|---|
| 它是跨限界上下文的公共工具（216 调用点 / 68 文件 / 3 个包） | 抽走会同时改动 `scfp.bill.infrastructure`、`yfl.thirdparty.application`，超出本次范围 |
| core 需要的是"引擎专用"的 JSON 能力 | 应以 `JsonOps` SPI 重新设计，而非搬运一个被 68 个文件依赖的类 |
| ECC 迁移期 | 可将 `JsonParseUtils` 作为 core `JsonOps` 的**适配实现**（薄包装），从而不改 ECC 调用方；待其他上下文并入后自然退场 |

---

## 11. 分期实施计划

> 已按 Q3（仅 SB3）与 Q6（管理端页面延后）调整。

| 阶段 | 内容 | 产出 | 人日 |
|---|---|---|---|
| **W1 · 骨架** ✅ 已交付 | `core` + `JsonOps`/`RuleRegistry`/`ConfigRepository` 三个核心 SPI + `provider-memory` + `demo-pure-java` | 纯 Java 跑通"模板渲染 + `@FUN` 规则" | 5 ~ 7 |
| **W2 · 建表与仓储** ✅ 已交付 | §6 全部 DDL + Liquibase changelog + `provider-jdbc`(JdbcTemplate) + `IdGenerator` + `deleted_seq` 语义 | 建表即用；CRUD 单测（H2 MODE=MySQL 跑生产 DDL；真库验证在发布侧） | 5 ~ 7 |
| **W3 · Spring 门面** ✅ 已交付（`TenantResolver` 顺延 W4） | `ifmap-spring-boot-starter`（SB3）+ `Properties` + SPI 自动注册 + `ConfigCache` + 自动建表 | SB3 示例跑通（**仅 SB3，无 SB2 分支**） | 4 ~ 5 |
| **W4 · 策略与动作** ✅ 已交付（`TenantResolver` 一并落地） | 三类策略 SPI + 三工厂（含三 key 注册）+ `ActionRegistry` + 执行顺序与环检测 | 契约自检 0 违规 | 5 ~ 7 |
| **W5 · 管理端 REST** ✅ 已交付 | `ifmap-admin`：CRUD + §8.3 全部校验 + 试跑 + 历史/回滚 + `/audit` + `/rules` | REST API 可用（**可视化页面不在本期**） | 6 ~ 8 |
| **W6 · 合规与日志** ✅ 已交付（`LogMasker` / 截断 / 异常体系 / 日志降级在 W4 已提前落地，W6 补齐保留期清理） | `JdbcExecutionLogCleaner` + `IfmapLogCleanTask` + §6.4 方案 A 全部配置 | 合规项自查通过 | 4 ~ 6 |
| **W7 · 开源工程化** ✅ 已交付 | Apache-2.0 LICENSE + NOTICE + license header + CI 矩阵 + spotbugs + 文档站 + README + CHANGELOG | CI 全绿；文档站 `--strict` 全绿 | 5 ~ 7 |
| **W8 · 迁移预热** | M1 数据迁移脚本 + M2 规则策略对齐（ECC 侧仅加注解）+ 双 JSON 实现一致性套件 | 迁移 kit 可演练；双 JSON 实现一致 | 6 ~ 9 |
| **合计** | | | **40 ~ 56 人日** |

**关键路径**：W1 → W2 → W4 → W5（管理端校验依赖 W4 的 `RuleRegistry` / 策略注册表）。
**可并行**：W6 与 W4；W7 与 W5。


### 11.0 W1 实际交付情况（回填，含实测证据）

落地仓库：`D:\cj\code\ifmap`（远端 <https://github.com/CJ8023/ifmap>）。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| `ifmap-parent` + `ifmap-core` + `ifmap-demo-pure-java` | ✅ 全部落地 | — |
| `JsonOps` SPI | ✅ 落地（core 只依赖 `slf4j-api`，JSON 能力经 `JsonOps` 注入，**公开 API 不出现任何 JSON 库类型**） | — |
| `RuleRegistry` SPI | ✅ 落地（方法级 `@IfmapRule` + 重载 + 显式 `override` + 启动期校验） | 审计文档 §6.3 初稿的 class-based 写法已按此修正 |
| `ConfigRepository` SPI | ⏸ W1 未落地 → ✅ **W2 已交付**（`provider-jdbc`，见 §11.0.1） | 无 DB 依赖则无法验证，随 W2 一起交付更自然 |
| `ifmap-provider-memory` | ⏸ 延后 | W1 用 `demo-pure-java` 内嵌 JSON 替代；该模块收益待 W2 后再评估 |
| 额外落地（计划外） | ✅ `ifmap-json-jackson`（Jackson + JsonPath 的 `JsonOps` 实现 + SPI 自动发现） | 没有它无法端到端验证 `JsonOps` 契约 |

**实测证据（可复现）**：

| 项 | 结果 | 复现命令 |
|---|---|---|
| 测试总数 | **47 个全绿**（core 25 + json-jackson 22） | `mvn -B -ntp clean verify` |
| JDK 8 构建 | ✅ BUILD SUCCESS（`Tests run: 47, Failures: 0, Errors: 0`） | `set JAVA_HOME=D:\cj\softwares\dev\JDK\jdk1.8&& ...mvn.cmd -B -ntp clean verify` |
| JDK 17 构建 | ✅ BUILD SUCCESS（`Tests run: 47, Failures: 0, Errors: 0`） | `set JAVA_HOME=D:\cj\softwares\dev\JDK\jdk17\jdk-17.0.20+8&& ...mvn.cmd -B -ntp clean verify` |
| core 字节码目标 | ✅ `major version: 52`（Java 8） | `javap -verbose -cp ifmap-core/target/classes cn.cj.ifmap.core.IfmapEngine` |
| 内置规则 | ✅ **18 个规则名 / 30 个规则方法**（11 个方法有重载） | `engine.describeRules()` / `grep -c @IfmapRule .../builtin/*.java` |
| 纯 Java 示例 | ✅ 渲染 18 个字段；`@FUN(farmatDate,...)` 拼写错误在**启动期**被拦下 | `mvn -q -pl ifmap-demo-pure-java exec:java` |
| 文档 | ✅ `README.md` + `docs/01-快速开始.md` + `docs/02-模板DSL语法.md` + `docs/03-规则清单与扩展.md` + `CHANGELOG.md` | — |
| CI | ✅ `.github/workflows/ci.yml`（JDK 8/11/17/21 矩阵） | — |

> 结论：W1 的"纯 Java 跑通模板渲染 + `@FUN` 规则"目标**已达成且经双 JDK 验证**；
> W2 起进入 DB 侧（建表 + `provider-jdbc` + `ConfigRepository`）。


### 11.0.1 W2 实际交付情况（回填，含实测证据）

目标（W2）：**建表即用 + CRUD 单测 + `deleted_seq` 语义 + `IdGenerator`**。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| §6 全部 DDL | ✅ 4 张表落地（28 / 17 / 17 / 11 列，索引与唯一键写在 `CREATE TABLE` 内） | 未拆 `005-create-indexes.sql`（见 §6.11） |
| Liquibase changelog | ✅ `db.changelog-master.yaml` + 4 个 `--changeset` SQL，`${tablePrefix}` 参数化 | 手工执行路径在 `docs/04` §1.2 说明 |
| `provider-jdbc`(JdbcTemplate) | ✅ `JdbcConfigRepository`（读）+ `JdbcConfigWriter`（新增/乐观锁更新/软删除） | 写入能力计划外补充（§6.8/§6.9 需要它才能验证） |
| `IdGenerator` | ✅ `SnowflakeIdGenerator`（10+12 位、2020 纪元、回拨 ≤5ms 等待、>5ms 抛错）+ `shared()` JVM 单例 + `AUTO_INCREMENT` 常量 | `shared()` 是为避免"多实例各自序列号独立导致撞 ID" |
| `deleted_seq` 语义 | ✅ 单条原子 `UPDATE ... SET del_status=1, deleted_seq=key_id, version=version+1`；删除→重建可反复进行 | 由 `canRecreateAfterSoftDelete` 断言 |
| H2 MODE=MySQL 单测 | ✅ **25 个**（`JdbcConfigRepositoryTest` 19 + `TableNameResolverTest` 6），直接执行生产 DDL | 计划里的 Testcontainers 因无 Docker 环境未启用（见 §6.11 自测口径） |
| 文档 | ✅ `docs/04-接入与建表.md`（390 行，含"示例值 ↔ 断言测试"对照表） | — |

**实测证据（可复现）**：

| 项 | 结果 | 复现命令 |
|---|---|---|
| 测试总数 | **80 个全绿**（core 33 + json-jackson 22 + provider-jdbc 25） | `mvn -B -ntp clean verify` |
| JDK 8 构建 | ✅ BUILD SUCCESS（`Tests run: 80, Failures: 0, Errors: 0`） | `set JAVA_HOME=D:\cj\softwares\dev\JDK\jdk1.8&& ...mvn.cmd -B -ntp clean verify` |
| JDK 17 构建 | ✅ BUILD SUCCESS（`Tests run: 80, Failures: 0, Errors: 0`） | `set JAVA_HOME=D:\cj\softwares\dev\JDK\jdk17\jdk-17.0.20+8&& ...mvn.cmd -B -ntp clean verify` |
| 字节码目标 | ✅ `major version: 52` | `javap -verbose -cp ifmap-provider-jdbc/target/classes cn.cj.ifmap.jdbc.JdbcConfigRepository` |
| 建表脚本可执行性 | ✅ 生产 DDL 在 H2 `MODE=MySQL` 下全部建成（仅剥离表尾存储引擎选项） | `mvn -pl ifmap-provider-jdbc test` |
| 列数 | ✅ 28 / 17 / 17 / 11（DDL 声明列数） | ``grep -cE '^  `[a-z_]+` +[a-z]' <file>`` |
| 唯一键语义 | ✅ 同(租户+接口+节点+顺序)拦截；不同节点同顺序可共存；软删后可重建 | `JdbcConfigRepositoryTest#uniqueKeyScopeIsPerBusiNode` / `#canRecreateAfterSoftDelete` |

**W2 中发现并修掉的两个真实缺陷（均由测试暴露）**：

1. **`NOT NULL DEFAULT ''` 列写入 `null` 会被真库拒绝**：SQL 默认值只在 INSERT **省略该列**时生效；显式绑定 `null` 时 MySQL 严格模式报 `Column 'xxx' cannot be null`。已由 `JdbcValues.orEmpty(...)` 统一归一化（H2 的报错与 MySQL 同源，因此该测试具备真库等价性）。
2. **`saveExecutionLog` 未回写生成的雪花主键**：调用方拿不到 `keyId`，无法关联回执。现已回写。

### 11.0.2 W3 实际交付情况（回填，含实测证据）

目标（W3）：**SB3 示例跑通** —— 引一个依赖 + 几行 yml 即完成建表 / 仓储 / 引擎 / 规则收集。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| `ifmap-spring-boot-starter` | ✅ 落地（`IfmapAutoConfiguration` + `IfmapProperties` + `AutoConfiguration.imports`） | 模块名去掉了设计稿里的 `3`（§4.2 已同步） |
| `Properties` | ✅ `ifmap.*`：`enabled` / `table-prefix` / `id-strategy` / `worker-id` / `null-policy` / `ddl.auto` / `cache.*` | 全部有默认值，不配也能跑 |
| SPI 自动注册 | ✅ `IfmapRuleRegistrar`（`static @Bean` 的 `BeanPostProcessor`）扫描宿主机 `@IfmapRule`；CGLIB 归一化 + 按类去重 + 懒取 `RuleRegistry` | — |
| `ConfigCache` | ✅ core 放 `ConfigCache` SPI + `InMemoryConfigCache`（零依赖 LRU + 惰性 TTL）；starter 检测到 Caffeine 时换 `CaffeineConfigCache`；`CachingConfigRepository` 只缓存 `queryLogicBranches` | 分层比计划更细（缓存装饰器放 core，实现按依赖可选） |
| `TenantResolver` | ⏸ **顺延 W4** | 依赖尚未定型的 `IfmapRequest` 模型（避免先写接口再改签名）；W3 的 `ConfigRepository` 已按 `String tenantId` 参数化，不受影响 |
| 自动建表（计划外补充） | ✅ `IfmapSchemaInitializer` 执行生产 DDL（与 Liquibase 同一份文件）、按表存在性幂等、多实例安全、**方言自适应**（非 MySQL 剥表尾表选项，断言式） | P0-4"starter 自带建表"的实现；H2 因此开箱可用 |
| `ifmap-demo-spring-boot3` | ✅ 落地（H2 内存库 + 宿主自定义规则 + 端到端 `@SpringBootTest`）；`java -jar` 实测跑通 | — |

**实测证据（可复现）**：

| 项 | 结果 | 复现命令 |
|---|---|---|
| 测试总数 | **124 个全绿**（core 52 + json-jackson 22 + provider-jdbc 25 + starter 21 + demo-sb3 4） | `mvn -B -ntp clean verify` |
| JDK 17 构建 | ✅ BUILD SUCCESS | `set JAVA_HOME=...jdk17...&& mvn.cmd -B -ntp clean verify` |
| JDK 8 构建 | ✅ BUILD SUCCESS（99 个测试；SB3 模块由 `<jdk>[17,)</jdk>` 剖面自动剔除） | `set JAVA_HOME=...jdk1.8&& mvn.cmd -B -ntp clean verify` |
| 字节码目标 | ✅ core/json/provider/demo-pure = `major 52`；starter/demo-sb3 = `major 61` | 逐 class 读第 6~7 字节（`python3` 脚本，见下） |
| 示例工程实跑 | ✅ exit 0；建 4 张表 → 写入 1 条配置 → 渲染 `{"orgNo":"000012","applyNo":"AP20250101001","applyDate":"20250308","acctName":"张*"}` | `java -jar ifmap-demo-spring-boot3/target/ifmap-demo-spring-boot3-0.1.0-SNAPSHOT.jar` |
| 方言自适应 | ✅ 日志 `ifmap 检测到非 MySQL 数据库（H2），建表时将剥掉表尾 MySQL 表选项`；4 张表建成且列数 28/17/17/11 | `mvn -pl ifmap-spring-boot-starter test` |
| spring-jdbc 覆盖 | ✅ starter 依赖顺序 `spring-boot-starter-jdbc` → `ifmap-provider-jdbc`，Boot 3 解析到 6.2.x（实测 6.2.19 运行期无 `NoSuchMethodError`） | `java -jar` 实跑日志 |

**W3 中发现并修掉的问题**：

1. **`spring-jdbc` 版本不能放父 POM 的 `dependencyManagement`**：会覆盖 Boot 3 传递来的 6.2.x，运行期 `NoSuchMethodError`。改为在 `ifmap-provider-jdbc` 就地声明，并把 `spring-boot-starter-jdbc` 声明在它之前。
2. **`InMemoryConfigCache.Entry` 与 `LinkedHashMap.Entry` 名字冲突**（继承的成员类优先于外部类作用域）→ 内部类改名 `CacheEntry`。
3. **建表脚本的"单条语句"校验被列注释里的分号误伤**（`COMMENT '删除标识;0:未删除1:已删除'`）→ 改为跳过单引号内容后再扫分隔符。
4. **H2 不认表尾 `COLLATE=utf8mb4_general_ci`**（即便 `MODE=MySQL`）→ 生产 DDL 与 H2 兼容性靠 starter 的方言自适应解决，而不是给测试另写一份 DDL。
5. **demo 的 h2 被父 POM 的 `dependencyManagement` 管成 `test` scope** → 打出来的 fat jar 缺驱动，`java -jar` 报 `Cannot load driver class: org.h2.Driver`；显式声明 `runtime` 修复。

### 11.0.3 W4 实际交付情况（回填，含实测证据）

目标（W4）：**三类策略 SPI + 三工厂 + `ActionRegistry` + 执行顺序与环检测**，契约自检 0 违规；W3 顺延的
`TenantResolver` / `IfmapRequest` 门面在此波落地（模型定型后不再返工）。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| `TenantResolver`（W3 顺延） | ✅ core `spi/TenantResolver` + `HeaderTenantResolver`（优先级：上下文 `tenantId` > 请求头 > 默认 `-1`；请求头名与默认租户可配） | — |
| `IfmapRequest` 门面 | ✅ `model/IfmapRequest`（不可变 + Builder、`of(Map)`、`empty()`、大小写不敏感 `getHeader`、`withAttribute`）与 `model/IfmapResult`、`model/PartnerCall` | 模型放 `model` 包（设计稿写作"门面"） |
| 三类策略 SPI | ✅ `IfmapStrategyHook` / `FullParamStrategy` / `LogicBranchStrategy`（方法名 `match(StrategyContext)`）+ `IfmapActionHandler`（`IfmapCallbackHandler` 已随后续版本移除，见下） | 动作一并落地，比计划多 |
| "三工厂" → 注册表 | ✅ `StrategyHookRegistry`（key = **bean 名**，含 `decapitalizeLoose` 别名与冲突诊断）/ `FullParamStrategyRegistry`（key = `bank\|busi`，查找 exact → `(partner,*)` → `(*,busi)` → `(*,*)`）/ `LogicBranchStrategyRegistry`（key → 多实现） | 用注册表替代"工厂 + 反射按方法名调用"，对应方案 v1 P0-7 / TS-6 |
| `ActionRegistry` | ✅ `@IfmapAction("submit")` 或显式 key；`failOnMissingAction` 默认 `true`（缺失即抛 `IfmapStrategyException`） | 取代 `method_flag` 反射（方法重命名不再静默失效） |
| `CallbackRegistry` | ❌ **已移除**（CHANGELOG Removed）：接收分支从未进入主链路，回调日志也永远进不了判定列；被调场景由 `call_direction=INBOUND` 走同一条编排链路取代 | 原计划取代存量 17 个回调方法，最终方案改为「配置化」 |
| 执行顺序与环检测 | ✅ `orchestrator/InterfaceOrdering`：`topological(sort(enabled))`（DFS + 环检测，异常含"循环依赖"与完整路径）；**并递归加载 `front_interface_no` 前置链**（`MAX_FRONT_DEPTH = 64` 护栏） | 递归加载是计划外补充 —— 仓储按单一 `interface_no` 查询，不递归拿不到前置接口；**该形态随后被整体删除**（改为 `interface_order` 单一定序 + 起点截断，见顶部「历史稿提示」） |
| 契约自检 0 违规 | ✅ `validate/ContractValidator` + `ContractReport.toMarkdown()`：模板 JSON 合法性 + `@FUN` 规则名存在 + 参数可匹配重载；`ContractValidatorTest` 断言"全注册时零违规" | 增补"模板非法 JSON"检查（渲染必经 jsonOps 解析，非法必在运行期炸） |
| 判定器（计划外） | ✅ `orchestrator/ResponseJudge`：`result_flag` 为空 = 成功、`success_value` 多值 `;`/`,`、忽略大小写、响应非 JSON 只判失败**不抛异常** | 存量判定逻辑分散在多处，此处收敛为一处 |
| 合规（W6 提前） | ✅ `spi/DefaultLogMasker`（值形态 + 字段名两种脱敏、排除字段整体 `***`、幂等、非 JSON 安全）+ `util/Logs.truncate`（`...truncated`） | 与 W6 并行（§11 计划表已标注"W6 与 W4 可并行"） |
| starter 自动收集 | ✅ `IfmapStrategyRegistrar`（`static @Bean` 的 `BeanPostProcessor`）收集 5 类策略 bean；**缺注解 = 启动期快速失败**（报错含 bean 名 + 类名）；注解查找走 `util/Annotations.find`（父类链 + 接口链 → CGLIB 代理安全） | 对应 P0-7 的"策略按 bean 名权威注册" |
| starter 装配 | ✅ `TenantResolver` / `LogMasker` / `ClockProvider` / 5 个注册表 / `ContractValidator` / `ExecutionLogSink` / `IfmapOrchestrator`，全部 `@ConditionalOnMissingBean`；`OrchestrationConfiguration` 以 `@ConditionalOnBean(ConfigRepository.class)` 门控 | 无 `DataSource` 时不装配编排器（其余照常），避免"引入 starter 就要求数据源" |
| 配置项（计划外） | ✅ `ifmap.tenant-header` / `ifmap.default-tenant-id` / `ifmap.orchestrator.{stop-on-failure,fail-on-missing-strategy,fail-on-missing-action}` / `ifmap.log.{enabled,truncate-threshold,mask-fields,exclude-fields}` | — |
| 文档 | ✅ 新增 `docs/06-执行编排与策略扩展.md`；`docs/05` 增补 §3.3 与配置项；README / CHANGELOG 同步 | — |

**实测证据（可复现）**：

| 项 | 结果 | 复现命令 |
|---|---|---|
| 测试总数 | **203 个全绿**（core 123 + json-jackson 22 + provider-jdbc 25 + starter 29 + demo-sb3 4） | `mvn -B -ntp clean verify` |
| JDK 17 构建 | ✅ BUILD SUCCESS（7 模块 reactor） | `set JAVA_HOME=...jdk17...&& mvn.cmd -B -ntp clean verify` |
| JDK 8 构建 | ✅ BUILD SUCCESS（**170 个**测试；SB3 模块由 `<jdk>[17,)</jdk>` 剖面自动剔除） | `set JAVA_HOME=...jdk1.8&& mvn.cmd -B -ntp clean test` |
| 示例工程实跑（含编排） | ✅ exit 0；「仅渲染」得到 `{"orgNo":"000012","applyNo":"AP20250101001","applyDate":"20250308","acctName":"张*"}`；「编排结果」`success=true, data={applyNo=AP20250101001}, 命中分支=submit, 耗时=38ms` | `java -jar ifmap-demo-spring-boot3/target/ifmap-demo-spring-boot3-0.1.0-SNAPSHOT.jar` |
| 策略自动收集 | ✅ 启动日志：`ifmap 已注册宿主机策略 bean：[demoPreStrategy]，类 [...]` 与 `[demoSubmitAction]` | 同上，或 `mvn -pl ifmap-spring-boot-starter test` |
| 契约自检 | ✅ `ContractValidatorTest`：全注册 → 零违规且 `toMarkdown()` 含「零违规」；错名规则检出 2 条（`farmatDate` / `cebFilePathJoin`）；非 JSON 模板报「模板非法」 | `mvn -pl ifmap-core test -Dtest=ContractValidatorTest` |
| 脱敏 | ✅ 手机 `13812348888 → 138****8888`、证件 `110101********1234`、卡号 `622202******7890`、姓名 `张三 → 张*`、排除字段 → `***`；幂等 | `mvn -pl ifmap-core test -Dtest=LogMaskerTest` |

**W4 中发现并修掉的问题（4 个，均由测试暴露）**：

1. **`DefaultLogMasker` 默认字段集误含 `mobile` / `phone` / `telephone`** → 手机号被按姓名规则脱敏成 `1**********`（无意义且不合规）。修复：默认只留姓名类字段（8 个），手机/证件/卡号统一走值形态正则。
2. **`DefaultLogMasker(maskFields, excludeFields)` 传空集合时会关闭默认脱敏** → 属性留空 = 姓名原样落库。修复：空/null 回退 `DEFAULT_MASK_FIELDS`（要完全关闭须自定义 `LogMasker` bean）。
3. **编排器未递归加载 `front_interface_no` 前置链** → 测试 `frontInterfacesExecuteInTopologicalOrderAndDataMerges` 只得到 `[MAIN]`。修复：广度递归收集 + `MAX_FRONT_DEPTH=64` 护栏（**该机制与其测试随后一起被删除**，见顶部「历史稿提示」）。
4. **策略注册表用 `getClass().getAnnotation(...)` 查注解** → Spring CGLIB 代理类取不到类注解（类注解不继承）。修复：统一走 `Annotations.find`（父类链 + 接口链）。
5. **`ContractValidator` 漏检「模板不是合法 JSON」** → `{not json` 且无 `@FUN` 时漏检。修复：补 `engine.getJsonOps().isJson(template)` 检查。

> 另修一处 core 测试自身笔误（非实现缺陷）：`topologicalKeepsUnrelatedOrder` 期望值由 `[A,C,B]` 改为 `[A,B,C]`（DFS 保持原顺序语义）；银行卡样例原写 20 位（超 ISO 7812 上限 19）改 16 位。

### 11.0.4 W5 实际交付情况（回填，含实测证据）

目标（W5）：**管理端 REST** —— CRUD + §8.3 全部校验 + 试跑 + 历史/回滚 + `/audit` + `/rules`；可视化页面不在本期。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| 模块形态 | ✅ 独立 `ifmap-admin-spring-boot-starter`（新模块，**不耦合** starter；引擎运行期不依赖它） | 计划稿写 `ifmap-admin`，落地为 starter（与"引入即用"一致） |
| 装配门控 | ✅ 三重门控：`ifmap.admin.enabled=true`（**默认 false**）+ classpath `DispatcherServlet`/`JdbcTemplate` + `DataSource`；全部 bean `@ConditionalOnMissingBean` | 管理端能改线上配置，默认必须关；**不内置鉴权**（不绑定宿主认证体系），文档明确要求挂网关 |
| CRUD | ✅ `GET/POST /configs`、`GET/PUT/DELETE /configs/{id}`、`POST /configs/{id}/status`（启停） | — |
| §8.3 校验 | ✅ `ConfigValidator`：必填列、模板 JSON 合法、`$.path` 合法（`JsonOps.isValidPath`）、`@FUN` 规则与重载可匹配（`ContractValidator`）、`pre_/post_strategy_name` 已注册、**池引用只指向本节点更早的步骤**、唯一键冲突（两个维度）、分支默认兜底 ≤1、分支顺序唯一、`method_flag` 已注册 | 无引擎时**至少**做模板 JSON 语法校验（历史脏数据真实存在，不能静默放过）；原「前置链自指/成环」一项已随 `front_interface_no` 删除 |
| 试跑 | ✅ `POST /configs/dry-run`：走完整编排链路（前置链 → 拓扑 → 渲染 → 策略钩子 → 判定 → 分支动作），只把出网换成 `mockResponse` | 计划稿路径为 `/configs/{id}/dry-run`，落地改为请求体承载（含 `tenantId` + `params: Map`），REST 语义更顺 |
| 历史 / 回滚 | ✅ `GET /configs/{id}/history` + `POST /configs/{id}/rollback`；快照 = **显式业务列 → 有序 Map → `JsonOps.toJson`**，附字段级 `diff` | 快照序列化放 admin 模块（provider-jdbc 保持零 JSON 依赖）；不用 Bean 反射序列化（少 JSR-310 模块、不写 `version`/审计列、字段增删不静默丢字段） |
| `/audit` | ✅ `ConfigAuditor`：跨租户全量巡检 + `audit-max-configs` 上限 + `truncated` 标记，可输出 Markdown | — |
| `/rules`、`/actions`、`/strategies` | ✅ 由服务端提供（`RuleRegistry.getDescriptors()` / `ActionRegistry.keys()` / 四个策略注册表） | 前端**不要硬编码**：这些值由宿主机代码决定 |
| 逻辑分支 | ✅ `GET/POST /branches`、`PUT/DELETE /branches/{id}` | 分支不写历史（分支表无 `version`，其变更由接口配置的 `UPDATE` 历史承载） |
| 乐观锁 | ✅ PUT / 状态变更 / 回滚**必带 `expectedVersion`**；冲突返回 `ok=false`（HTTP 200） | 与 §6.8 方案 B 一致；`version` 基线 **0**（DDL `int NOT NULL DEFAULT 0`） |
| 缓存一致性 | ✅ 每次写操作后 `CachingConfigRepository.invalidate(tenant, interface)`；分支变更同时失效**改前/改后两个接口** | 改完立刻生效，不等 TTL |
| 配置项 | ✅ `ifmap.admin.enabled` / `base-path`（默认 `/ifmap/admin`）/ `history-limit`（50）/ `audit-max-configs`（5000） | — |
| 文档 | ✅ 新增 `docs/07-管理端REST.md`；README / CHANGELOG 同步 | — |

**实测证据（可复现）**：

| 项 | 结果 | 复现命令 |
|---|---|---|
| 测试总数 | **245 个全绿**（core 123 + json-jackson 22 + provider-jdbc 25 + starter 30 + **admin 41** + demo-sb3 4） | `mvn -B -ntp clean verify` |
| JDK 17 构建 | ✅ BUILD SUCCESS（8 模块 reactor） | `set JAVA_HOME=...jdk17...&& mvn.cmd -B -ntp clean verify` |
| JDK 8 构建 | ✅ BUILD SUCCESS（**170 个**测试；SB3 模块由 `<jdk>[17,)</jdk>` 剖面自动剔除） | `set JAVA_HOME=...jdk1.8&& mvn.cmd -B -ntp clean test` |
| 管理端端到端 | ✅ `IfmapAdminWebEndpointTest` 5 个用例：H2 内存库 + **真 Tomcat + 真 HTTP**，覆盖前缀、状态码语义（200/400/422）、审计链、分支 + 试跑 | `mvn -pl ifmap-admin-spring-boot-starter -am clean test` |
| 校验清单 | ✅ `ConfigValidatorTest` 10 个用例逐条正反例 | 同上 |
| 默认关闭 | ✅ `IfmapAdminAutoConfigurationTest` 5 个用例：不配 `enabled=true` 时管理端 bean 一个都不装配 | 同上 |
| 非 MySQL 方言 | ✅ `IfmapSchemaInitializerTest#nonMysqlDowngradesJsonColumns`：`json` → `text`，且 H2 上写入的 JSON 文本原样读回 | `mvn -pl ifmap-spring-boot-starter -am clean test` |

**W5 中发现并修掉的问题（7 个，均由测试暴露）**：

1. **`JdbcConfigWriter` 把 `logic_branch_flag` / `method_flag` 当必填** → 真实数据必被拒（DDL 里前者 `NOT NULL DEFAULT ''`、后者可空且**空 = 默认兜底分支**）；改为只校验必填列，其余 `null → ""` 归一化。
2. **`ConfigValidator.validateBranch` 要求 `method_flag` 必填** → 默认兜底分支合法为空；同时补「**默认兜底分支最多 1 条**」的单条校验（引擎取首个命中者，第二条永远不生效）。
3. **无引擎时的模板校验被整段跳过** → 历史脏数据（手改 SQL / 旧版本写入的非法 JSON）会带到线上首次调用才炸；改为至少做 JSON 语法兜底。
4. **`whereClause` 总是过滤 `tenant_id = ?`（`null → -1`）** → 全量巡检只看 `-1` 租户、恒为 0 条；新增 `ConfigQuery.allTenants`，巡检按行取各自租户。
5. **`POST /configs/validate` 校验不通过也返回 200** → 改 422 + 明细；同时把「200 + `ok=false`」与「400 / 422」的边界写进文档（原 Controller 注释写 404/409，与实现不符）。
6. **H2 1.4.200 的 `JSON` 类型与 MySQL `json` 语义不等价** → JDBC 写字符串被包成 JSON 字符串字面量，快照读出来是 `"{\"a\":1}"`，回滚必然失败；非 MySQL 分支把 `json` 列降级为 `text`（断言式改写，注释里的 "json" 不受影响）。
7. **`@PathVariable` / `@RequestParam` 未显式写名** → 依赖宿主 `-parameters` 编译开关，宿主没开就 500；统一显式写名 + 模块自身开 `parameters=true` 双保险。

> 另有两条**测试自身**的坑（非实现缺陷，已写进 CHANGELOG/记忆）：`@FUN_x` 是错语法会被**静默忽略**（测试假绿），正确写法是 `@FUN(x,...)`；测试里预注册内置规则再交给 `IfmapEngine.builder()` 会因 builder 默认已注册而抛"规则签名重复"。

### 11.0.5 W6 实际交付情况（回填，含实测证据）

目标（W6）：**合规与日志** —— `LogMasker` + 截断 + 保留期清理 + 异常体系 + 日志降级；合规项自查通过。

**范围核查结论**：`LogMasker`（值形态 + 字段名 + `exclude-fields`）、`Logs.truncate`、异常体系（§7.6 全部异常）、**日志降级**（`IfmapOrchestrator.writeLog` 里 `try/catch` 写日志失败只 WARN）在 **W4 已提前交付**（§11.0.3 已标注）。因此 W6 的唯一实质交付物 = **执行日志保留期清理**。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| 保留期清理 | ✅ `JdbcExecutionLogCleaner`（provider-jdbc）：`clean(retentionDays[, batchSize, maxBatches, batchSleepMillis])`；常数 `DEFAULT_BATCH_SIZE=1000` / `DEFAULT_MAX_BATCHES=1000` / `DEFAULT_BATCH_SLEEP_MILLIS=50` | 计划稿只写"批大小 1000、每批 sleep"，落地补齐 **`maxBatches` 护栏**与**中断安全** |
| 定时调度 | ✅ `IfmapLogCleanTask`（任务：`cleanNow()` 手工触发、异常只 WARN 返回 `-1`）+ `IfmapLogCleanScheduler`（`CronExpression` 驱动，守护线程，上下文关闭即停） | 计划稿没写调度形态；落地用 Spring cron（**6 段**，含秒）。**刻意不用 `@EnableScheduling`**：自动配置打开宿主的全局调度设施属于越界，且 Spring 无 TaskScheduler 时建的是**非守护**线程 → 非 Web 应用跑完不退出（demo 实测卡死 → 见"W6 中发现的问题"） |
| 装配门控 | ✅ `LogCleanConfiguration`（清理器 bean，只要求 `DataSource`+`JdbcTemplate`）与 `LogCleanSchedulingConfiguration`（任务 + `IfmapLogCleanScheduler`，受 `ifmap.log.enabled` 与 `ifmap.log.clean-enabled` 双重开关门控） | **清理器 bean 与定时任务解耦**：关掉自动清理后仍可手工调用（运维窗口补跑）；调度**刻意不用 `@EnableScheduling`**（自动配置不该打开宿主全局调度设施，见本节的真实缺陷 1） |
| 配置项 | ✅ `ifmap.log.clean-enabled=true` / `retention-days=90` / `clean-batch-size=1000` / `clean-max-batches=1000` / `clean-batch-sleep-millis=50` / `clean-cron` | 计划稿只有 `retention-days` 与 `clean-batch-size` |
| 安全阀 | ✅ `retentionDays &le; 0` 抛 `IfmapConfigException`（配 0 = "删全表"） | 计划稿未写；由单测固定 |
| 删除可移植性 | ✅ 两步法（查 `key_id` → 按 id 删） | 避开 MySQL 1093 与 `DELETE ... LIMIT` 方言 |
| 分区方案 B | ✅ 模板保留在 `ifmap-provider-jdbc/src/main/resources/db/optional/execution-log-partition/`（默认不启用；W10 扩成 6 步手册并补齐方案 C 归档器） | 与 §6.4 一致 |
| 测试基座对齐 | ✅ provider-jdbc `TestSchema` 补做 `json` → `text` 降级（与生产 `IfmapSchemaInitializer` 同口径、断言式） | 测试库保留 H2 `JSON` 类型则测的不是生产语义 |
| 文档 | ✅ 新增 `docs/08-日志与合规.md`；`docs/06` 补交叉引用；README / CHANGELOG 同步 | — |

**实测证据（可复现）**：

| 项 | 结果 | 复现命令 |
|---|---|---|
| 保留期清理 | **8 个用例全绿**：只删过期行、保留期边界、按批续删（`maxBatches=1` 只删一批）、`retentionDays<=0` 拒绝、批参数非法拒绝、表前缀生效、空表无操作、中断即停 | `mvn -pl ifmap-provider-jdbc -am clean test` |
| starter 装配 | **9 个用例全绿**：默认装配（清理器 + 任务 + 调度处理器）、配置绑定（`retention-days=7`）、**非法 cron 启动期快速失败**、`clean-enabled=false` 只关任务、`ifmap.log.enabled=false` 不装配任务、真实清理、失败降级返回 -1、`retention-days=0` 不删数据、宿主清理器优先 | `mvn -pl ifmap-spring-boot-starter -am clean test` |
| 测试总数 | **264 个全绿**（core 123 + json-jackson 22 + provider-jdbc **33** + starter **41** + admin 41 + demo-sb3 4） | `mvn -B -ntp clean verify` |
| 非 Web 应用可退出 | ✅ demo `java -jar` **exit=0**（3.35s），启动日志含 `ifmap 执行日志定时清理已启动（cron=0 30 3 * * ?，保留 90 天）` | `java -jar ifmap-demo-spring-boot3/target/ifmap-demo-spring-boot3-0.1.0-SNAPSHOT.jar` |
| JDK 17 构建 | ✅ BUILD SUCCESS | `set JAVA_HOME=...jdk17...&& mvn.cmd -B -ntp clean verify` |
| JDK 8 构建 | ✅ BUILD SUCCESS（**178 个**测试；SB3 模块由剖面剔除） | `set JAVA_HOME=...jdk1.8&& mvn.cmd -B -ntp clean test` |

**W6 中发现并修掉的问题（1 个真实缺陷，由"跑起来看"暴露）**：

1. **自动配置里的 `@EnableScheduling` 会让非 Web / 批处理应用永不退出** —— 第一版把定时清理实现为 `@Scheduled(cron=...)` + `@EnableScheduling`，单测全绿（`ApplicationContextRunner` 里不会有人检查 JVM 能否退出）；但**实测 `ifmap-demo-spring-boot3` 的 `java -jar`**：加了之后 90 秒仍在运行（超时被杀），而它的既有契约是"跑完自然退出 exit=0"。根因：Spring 在没有 `TaskScheduler` bean 时用 `Executors.newSingleThreadScheduledExecutor()` 建**非守护**线程；`ContextClosedEvent` 只在上下文关闭时触发，而 JVM 又因非守护线程无法退出 —— 死锁。修法：改为自带**守护线程**的 `IfmapLogCleanScheduler`（`CronExpression` 计算下次执行时间），并**移除 `@EnableScheduling`**（自动配置也不该打开宿主的全局调度设施）。修复后实测 `EXITCODE=0`、耗时 3.35s，且启动日志出现 `ifmap 执行日志定时清理已启动`。**教训：自动装配类里的全局开关（`@EnableScheduling` / `@EnableAsync` / `@EnableCaching`）必须实测"应用能不能正常退出"**，单元测试覆盖不到。

> 另有 1 处**测试预期错误**（非实现缺陷）：`JdbcExecutionLogCleanerTest` 首跑时断言"`maxBatches=2` 必删满 2 批"，但只剩 3 行时实际删 3 行就结束 —— "批次"由"上一批是否满"驱动，测试已按实际语义修正。

### 11.0.6 W7 实际交付情况（回填，含实测证据）

目标（W7）：**开源工程化** —— LICENSE / NOTICE / 许可头 / CI 矩阵 / 静态分析 / 文档站 / README / CHANGELOG；CI 全绿。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| Apache-2.0 LICENSE | ✅ 已存在（W1 就落盘，202 行全文） | — |
| NOTICE | ✅ 新建（产品名 + `Copyright 2026 caijun` + Apache-2.0 + 第三方组件清单 + "内置 18 个规则为原创实现"） | 计划稿只写"NOTICE"，落地补齐**第三方组件清单**（jackson / json-path / spring-jdbc / h2 等）以免再单开一份 CREDITS |
| license header | ✅ **166 个 `.java` + 5 个 `.sql`** 全部补齐（Java 用 `/* */`；SQL 用 `--`，且 Liquibase changelog 的 `--liquibase formatted sql` 仍留在**首行**，许可头插其后） | 计划稿只说"加许可头"；落地把"标记行必须在最前"这个 Liquibase 约束也断言进了测试，否则 changelog 会被静默忽略 |
| 许可头校验方式 | ✅ **自写 `LicenseHeaderTest`（core，3 例）**，随 `mvn test` 跑（JDK 8 矩阵也覆盖）；**不引 `license-maven-plugin`** | 计划稿写"license header"没写怎么校验。取舍理由：零新增构建期依赖也能拦住漏加；插件方案在受限仓库/离线环境更易碎 |
| CI 矩阵 | ✅ JDK **8 / 11 / 17 / 21** × `clean verify`（每个 JDK 都含 SpotBugs 之外的全部门禁）+ `static-analysis` job（`spotbugs:check`）+ `docs` job（`mkdocs build --strict`） | 计划稿只说"CI 矩阵"；落地把静态分析与文档站拆成**独立 job**，让"是主题代码挂了还是文档挂了"一眼可见。MySQL 真库 job 仍保留为注释（无 Docker/Testcontainers 环境，见 §12.1 R2） |
| SpotBugs | ✅ `spotbugs-maven-plugin 4.8.6.6`，绑 `verify`，`effort=Max` / `threshold=Medium` / 不分析测试，**`<jdk>[11,)</jdk>` 剖面自动启用**（4.8.x 运行时要求 Java 11，JDK 8 矩阵跳过）→ 最终 **0 缺陷** | 计划稿没写 JDK 版本门槛；这不写清楚会直接把 JDK 8 的 CI 打红 |
| SpotBugs 排除清单 | ✅ `spotbugs-exclude.xml`：只放行 3 个**规则族**（`EI_EXPOSE_REP*` / `CT_CONSTRUCTOR_THROW` / `MS_SHOULD_BE_FINAL`），每条写理由 + 说明"放行规则族 ⇒ 人工 review 必须补三条" | 第一版按**类名正则**放行 `EI_EXPOSE_REP`，结果报告里还剩 **13 条**（FindBugs 的 `<Class name="~正则">` 是**整串匹配**，不是 contains，按类名放行没生效）→ 改为规则族级放行并补真修复 |
| 文档站 | ✅ `mkdocs.yml`（MkDocs 1.6.1 + Material 9.7.7；10 页 nav；`strict: true`；`exclude_docs` 排除内部文档）+ `docs/index.md` + `docs-requirements.txt` | 计划稿只说"文档站上线"；落地加了 `exclude_docs`：内部文档在开发机存在但不进公开仓，不排除则本地 `--strict` 必挂（CI 上文件不存在，行为反而一致） |
| 贡献指南 | ✅ 根 `CONTRIBUTING.md`（入口 + 三条底线）+ `docs/09-参与贡献.md`（完整版，9 节） | 计划稿未列；因 SpotBugs 放行规则族需要**人工 review 清单**，必须有地方写 |

**实测证据（可复现）**：

| 项 | 结果 | 复现命令 |
|---|---|---|
| 许可头（TDD 红→绿） | 先 3 例全红（无 NOTICE + 166 java 无头 + 5 sql 无头）→ 加头后 **3 例全绿** | `mvn -pl ifmap-core -am clean test -Dtest=LicenseHeaderTest` |
| SpotBugs 真缺陷修复 | 6 → 2 → 13 → **0**；真修复 **6 处**：`Integer` 引用比较（High，唯一键校验静默失效）、`Date` 未做副本、`SmartComparator` 不可序列化、`StepOutcome.getData` / `PageResult.getRows` 直接外泄、匿名类里 `size()` 歧义 | `mvn -B -ntp clean verify`（`check` 失败即构建失败，`failOnError=true`） |
| 唯一键 bug 的回归测试 | ✅ 先把修复**退回旧写法**跑测试 → `uniqueKeyDetectsConflictBeyondIntegerCache` **FAILURE**（`expected: <false> but was: <true>`）；恢复后绿 | `mvn -pl ifmap-admin-spring-boot-starter -am clean test -Dtest=ConfigValidatorTest -Dsurefire.failIfNoSpecifiedTests=false` |
| 测试总数 | **268 个全绿**（core **126** + json-jackson 22 + provider-jdbc 33 + starter 41 + admin **42** + demo-sb3 4） | `mvn -B -ntp clean verify` |
| JDK 17 构建 | ✅ BUILD SUCCESS（含 SpotBugs 门禁） | `set JAVA_HOME=...jdk17...&& mvn.cmd -B -ntp clean verify` |
| SpotBugs 缺陷数口径 | ✅ **6 个模块全为 0**（口径 = 各模块 `target/spotbugsXml.xml` 里 `<BugInstance ` 的条数；中途出现过的 6 / 13 条同口径） | `mvn -B -ntp spotbugs:spotbugs`，再 `grep -c '<BugInstance ' ifmap-*/target/spotbugsXml.xml` |
| JDK 8 构建 | ✅ BUILD SUCCESS（**181 个**测试；SB3 模块由剖面剔除；SpotBugs 按剖面**不执行**：日志里 spotbugs 出现 0 次） | `set JAVA_HOME=...jdk1.8&& mvn.cmd -B -ntp clean verify` |
| 文档站 | ✅ `mkdocs build --strict` **exit=0**（1.13s，10 页 + 404 + sitemap，`target/site-mkdocs` 3.3 MB） | `py -m pip install -r docs-requirements.txt && py -m mkdocs build --strict` |
| 许可头覆盖数 | **166 `.java` + 5 `.sql`** 全部含 `Copyright 2026 caijun` | `grep -rL "Copyright 2026 caijun" --include=*.java --include=*.sql .`（应只有 `target/` 下的产物） |

**W7 的取舍与"踩过的坑"（都不是实现缺陷，是工具/写法陷阱）**：

1. **`<Class name="~正则">` 是整串匹配** —— 第一版排除清单写 `EI_EXPOSE_REP.*` 之类的**类名**正则，以为能按类放行，实际放行不到（报告条数反而从 6 变成 13：早期按类名的"放行"没生效，去掉后又暴露了更多）。结论：要么精确到类全名，要么按**规则族**放行 —— 后者更稳（类改名不会让门禁悄悄失效）。
2. **SpotBugs 4.8.x 运行时要求 Java 11** —— 若把插件声明在默认 `build` 里，JDK 8 矩阵会直接构建失败。必须放进 `<jdk>[11,)</jdk>` 剖面（与 SB3 模块同一手法）。JDK 8 上不跑静态分析是**有意的取舍**（Java 8 能编过、单测能跑就够）。
3. **`git commit -m` 写多行中文会失败** —— 本仓库改用 `-F <UTF-8 无 BOM 的消息文件>`。
4. **僵尸 `java.exe` 会锁住 boot jar** —— `spring-boot-maven-plugin:repackage` 报 `Unable to rename '...jar' to '...jar.original'`；处理时必须先用 `powershell Get-CimInstance Win32_Process` 看清命令行，**别误杀 IntelliJ IDEA 的 Maven server 进程**。

### 11.0.7 W8 实际交付情况（回填，含实测证据）

目标（W8）：**迁移预热** —— M1 迁移 SQL kit + M2 规则策略对齐（ifmap 侧规则映射/自检能力 + 通用迁移指南）+ JsonOps TCK 双实现一致性套件。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| M1 迁移 SQL kit | ✅ `ifmap-provider-jdbc/src/main/resources/db/migration/ecc-to-ifmap/`：`README.md` + `00-precheck` / `01-add-columns` / `02-backfill` / `03-modify-and-index` / `04-rename-tables` / `05-verify`（共 7 文件、约 53 KB） | 计划稿只写"迁移脚本"；落地把**执行顺序按"何时能做"重排**：`04-rename-tables` 是**切换时刻**才执行的秒级元数据操作，放在最后（放最前会在共存期就切断 ECC 依赖）。`README` 另给"大表按体量分岔"（< 50 万行 COPY / ≥ 50 万行走 gh-ost）与 11 行排错表 |
| kit 是否随 changelog 自动执行 | ✅ **不自动**：不在 `db/changelog/` 主入口下、文件名也不符合 Flyway `V<版本>__<描述>.sql` 规范 → 必须运维手工执行；`README` 首节写明这一点 | 计划稿未提；这是最容易出事的隐含假设（以为引了 jar 就自动迁移） |
| M2 规则/策略对齐 | ✅ 落到公开文档 `docs/10-迁移指南.md`：**"配置里的字符串 → ifmap 注册点"对照表**（`@FUN(名字)` → 内置 18 规则 / `@IfmapRule`；`method_flag` → `@IfmapAction`；分支标识 → `@LogicBranch`；`strategy_name`（落地后为前序 / 后序两列）→ `IfmapStrategyHook` bean 名）+ **启动期自检动作**（`/audit`、`/rules`、`/actions`、`/strategies`、`/configs/validate`、`/configs/dry-run`） | 计划稿写的"52 个规则名映射清单"**不进公开仓**（`cmbInvoiceType`/`cebFilePathJoin` 这类名字属"银行合作细节"）→ 改为**通用方法论 + 自检机制**：对照表 + 巡检 0 违规作为验收动作 |
| D2 JsonOps TCK | ✅ 新模块 `ifmap-json-tck`：`JsonOpsConformanceTestBase`（**24 用例**）+ `JdkNativeValues`（递归断言"返回值只含 JDK 原生类型"）；`JacksonJsonOpsConformanceTest` 空壳继承 | 计划稿写"TCK"；落地把它做成**独立模块**（而非 core 的 test-jar）——外部实现者继承一个类即可，避免 test-jar 打包复杂度。24 个用例里 2 个首跑失败，**是我的期望写错而非实现有问题**（见下"实测证据"） |
| D4 通用迁移指南 | ✅ `docs/10-迁移指南.md`（M0 摸底 → M1 → M2 → M3 切换与回滚；共存三路径；10 条行为对齐清单；排错速查）；`mkdocs.yml` nav 加页（**11 页**）；README / CHANGELOG 同步 | 计划稿只写"迁移指南"；落地定下"**M2 契约自检 0 违规 + 切换 checklist**"作为可判定的门槛（否则"迁移做完了吗"没法回答） |
| **P0 级实现缺陷修复（W8 顺带）** | ✅ `IfmapOrchestrator` 的**兜底分支从不生效** → 改为**两趟匹配**（常规分支优先，全部未命中才走兜底） | **设计文档 §6.3 / 附录 C 与 `ConfigValidator` 都假定兜底分支会生效（`logic_branch_flag` 为空 = 兜底），但引擎实现把 blank flag 判 `false` 并打 WARN** → 存量（ECC `BankintConfigManager`）正是用"空标志"表达兜底 → 直接迁过来会出现**"分支命中不到 → 业务回调静默不执行"**。TDD：先加 2 个用例（红：`expected: <[fallback]> but was: <[]>`）→ 改引擎 → 绿。**数据零改动**（不需要回填 `logic_branch_flag`） |
| 文档口径修正（W8 顺带） | ✅ `method_flag` 空 = **该分支不执行动作**；`logic_branch_flag` 空 = **兜底分支**（两者正交）；修正 `LogicBranchConfig` javadoc 5 处 / `002-create-logic-branch.sql` 列注释 / `docs/04` §8.2 / `docs/07` / `CHANGELOG` | 原文档把"`method_flag` 为空"说成"默认兜底分支"，与 ECC 真实语义（空白 `logic_branch_flag` 才是兜底）**恰好相反**；不改就会把迁移实施者引到错误方向 |
| `LicenseHeaderTest` 规则收敛 | ✅ Liquibase 标记行检查 scope 到 `db/changelog/`（新增 `isLiquibaseChangelog(Path)`） | 原规则 `^\d{3}-.*\.sql$` 会误拦 `db/migration/` 下的迁移 kit，逼着给**非 changelog** 脚本加 `--liquibase formatted sql` 假标记。改测试比改文件正确 |

**实测证据（可复现）**：

| 项 | 结果 | 复现命令 |
|---|---|---|
| TCK（Jackson） | ✅ **24 例全绿**（首跑暴露 2 处**测试期望**写错：`$.items[0].qty` 应为 1 而非 2；`$.items[?(@.qty > 999)].sku` 实返 **`[]` 而非 null** → 已把"路径不存在 → null"与"过滤器不命中 → 空列表"明确区分并各加断言） | `mvn -pl ifmap-json-jackson -am clean test -Dtest=JacksonJsonOpsConformanceTest -Dsurefire.failIfNoSpecifiedTests=false` |
| 兜底分支回归（红→绿） | ✅ 红：`IfmapOrchestratorTest` → `Tests run: 15, Failures: 1`（`defaultBranchFiresWhenNoOtherBranchMatches:303 expected: <[fallback]> but was: <[]>`）；绿：`Tests run: 15, Failures: 0` | `mvn -pl ifmap-core -am clean test -Dtest=IfmapOrchestratorTest -Dsurefire.failIfNoSpecifiedTests=false` |
| 许可头测试（scope 修正后） | ✅ `Tests run: 3, Failures: 0`（kit 的 6 个 `.sql` 仍被要求带许可头，但不再被要求带 changelog 标记行） | `mvn -pl ifmap-core -am clean test -Dtest=LicenseHeaderTest -Dsurefire.failIfNoSpecifiedTests=false` |
| JDK 17 全量 | ✅ `clean verify` **BUILD SUCCESS**（含 SpotBugs 门禁）：**294 个测试全绿**（core **128** / json-jackson **46** / provider-jdbc 33 / starter 41 / admin 42 / demo-sb3 4） | `set JAVA_HOME=...jdk17...&& mvn.cmd -B -ntp clean verify`（**必须在仓库根目录执行**，见下） |
| JDK 8 全量 | ✅ `clean verify` **BUILD SUCCESS**：**234 个测试全绿**（core 155 / json-jackson 46 / provider-jdbc 33；SB3 模块与 SpotBugs 按剖面跳过） | `set JAVA_HOME=...jdk1.8&& mvn.cmd -B -ntp clean verify` |
| SpotBugs 缺陷数 | ✅ 仍为 **0**（口径 = 各模块 `target/spotbugsXml.xml` 里 `<BugInstance ` 条数；本轮新增 12 个 core 类与 1 个新模块后复测） | `grep -c '<BugInstance ' ifmap-*/target/spotbugsXml.xml` |
| 文档站 | ✅ `mkdocs build --strict` **exit=0**（11 页 + 404 + sitemap） | `py -m mkdocs build --strict` |

**W8 的踩坑记录（工具/语义陷阱，都是"先做错再纠正"）**：

1. **`${maven.multiModuleProjectDirectory}` 跟随"启动 Maven 时的工作目录"**（该目录无 `.mvn` 时）—— 在 `D:\cj\tmp` 上执行 `mvn -f D:\cj\code\ifmap\pom.xml verify` 时报 `Could not find resource 'D:\cj\tmp/spotbugs-exclude.xml'`，测试全绿但构建失败。结论：**构建必须在仓库根目录执行**（CI 天然满足）。
2. **`MySQL 5.7`：改列类型只能 `ALGORITHM=COPY`，且 COPY 下不允许 `LOCK=NONE`**（官方在线 DDL 表：`LOCK = NONE` "If supported, permit concurrent reads and writes. Otherwise, an error occurs"；改列类型 "only supported with `ALGORITHM=COPY`"）。设计文档此前的 `ALGORITHM=INPLACE, LOCK=NONE` 示例是错的 —— 已按"操作类型"补成判据表（本节 + §6.7 + 附录 C）。
3. **`ADD UNIQUE KEY` 必须在回填之后**：历史软删行的 `deleted_seq` 全是 0，提前建唯一键必撞 `1062`。
4. **`ROW_FORMAT=DYNAMIC` 不是"顺手加"**：唯一键 ≈ 1040 字节 > `COMPACT` 的 767 字节上限（1071），而 `DYNAMIC` 上限 3072 字节；`COPY` 本来就是重建表，同条语句带上零额外成本。
5. **体检脚本别对同一张表扫很多遍**：日志表逐列 `UNION ALL` 会扫 6~26 次 → 改成"每张表一次扫描"（一行多个 `MAX(CHAR_LENGTH(col))`、一行多个 `SUM(col IS NULL)`）。
6. **`GROUP_CONCAT(DISTINCT a ORDER BY b)` 在 5.7 报 3029**（`ORDER BY` 只能引用选列表达式）→ 去 `DISTINCT` 或改为对选列排序。
7. **`SELECT @@innodb_large_prefix` 在 8.0 报 1193**（变量已移除）→ 统一 `SHOW VARIABLES LIKE '...'`（8.0 返回空行、不报错）。
8. **别对可能非法的值 `CAST(... AS SIGNED)`**（strict 模式报 1292，且 SQL 的 `OR` 无短路保证）→ 用 `TRIM(x) NOT REGEXP '^[0-9]+$'` 先做格式体检，值域分布另用 `GROUP BY` 人工过目。
9. **`edit` 工具改大块 SQL/文本易失手**（漏传 `newText`、两段 `oldText` 边界重叠）→ 大块改写改走 python 补丁：`assert s.count(old) == 1` → `replace` → 断言式写回。

### 11.0.8 W9 实际交付情况（回填，含实测证据）

目标（W9）：**管理端可视化页面（Q6 / §8.1 的 v1.1 部分）+ 字典 SPI（Q8）**。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| 可视化页面 | ✅ `ifmap-admin-spring-boot-starter/src/main/resources/META-INF/ifmap-admin-ui/`（`index.html` + `app.css` + `app.js` ≈ 900 行原生 JS），入口 `{base-path}/ui/`（`/ui` 无尾斜杠时 302）；四个页签：配置（筛选/新建/编辑/校验/试跑/启停/删除/历史/回滚）、逻辑分支（含兜底标记）、巡检（Markdown 报告一键复制）、规则与字典（`/rules` `/actions` `/strategies` `/enums` 服务端驱动） | 计划稿只写"可视化页面"；落地把"**零 CDN、零框架、零构建链**"定为硬约束（内网离线可用），代价是页面要自己处理分页/表单/差异渲染 —— 用**静态契约测试**兜住（路径白名单、前缀不硬编码、`el(id)` 必须存在、无外链、有 node 时 `node --check`） |
| 前缀不硬编码 | ✅ `BASE` 从 `location.pathname` 反推 | 写死 `/ifmap/admin` 在改 `base-path` 的部署上会整页 404；单测直接断言 `app.js` 里没有 `/ifmap` 字面量 |
| 字典 SPI（Q8） | ✅ `IfmapEnumProvider`（函数式接口）+ `EnumOption`（不可变）+ `IfmapEnumCatalog`；`GET /enums` **未注册也返回 200 `{}`**（不是 404） | `IfmapEnumCatalog` **恒为 bean**（而非"有/无 provider"两条 `@ConditionalOnBean` 分支），避免两套装配路径；无 provider / `null` / 脏数据都安全降级 |
| 顺带修正 | ✅ `docs/07` §3.4 审计头文档与实现不一致（实现是 `X-Operator-Id` / `X-Request-Id`，文档原写 `X-Ifmap-Operator` / `X-Ifmap-Request-Id`） | 文档与实现不一致比没文档更糟 |
| 实测 | ✅ 新增 **17** 个测试（`IfmapEnumCatalogTest` 6 / `IfmapAdminUiEndpointTest` 4（真 Tomcat 下取静态资源 + 越界检查 + `/enums`）/ `IfmapAdminUiScriptTest` 5 / `IfmapAdminAutoConfigurationTest` +2）；JDK 17 全 reactor **338 全绿**、SpotBugs 0 缺陷；`mkdocs build --strict` exit=0（**11 页**） | 页面冒烟脚本（假 DOM + 假 fetch，43 断言）**只放仓库外临时目录**，不提交 |

### 11.0.9 W10 实际交付情况（回填，含实测证据）

目标（W10）：**执行日志按月分区（roadmap 的"日志冷热分离/分区"）**。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| 分区手册 | ✅ `ifmap-provider-jdbc/src/main/resources/db/optional/execution-log-partition/`（README + 6 个编号脚本：体检 / 改造 / 加分区 / 校验 / 建冷表 / 归档删除） | 计划稿以为"给个 DDL 就行"；落地发现真难点在**存量表怎么改**（`ALTER ... PARTITION BY` 在 MySQL 不原地支持）→ 手册给两条路线（停写窗口影子表 + 原子 `RENAME TABLE` / `pt-online-schema-change`，后者必须显式写 `DROP PRIMARY KEY, ADD PRIMARY KEY (key_id, add_time)` 否则报 1503） |
| 冷热分离 | ✅ `JdbcExecutionLogArchiver` + `LogArchiveResult`（`archived` / `removed` / `batches`）：查 → 剔除冷表已有 → `INSERT` → `DELETE`，**刻意不做跨表事务**（可重入换掉大事务） | 计划稿写"归档"；落地把"**热表列清单写死并自检**"作为硬契约（热表加列忘了同步 → 直接失败，而不是静默少归档一列）；避开 `DELETE ... IN (SELECT ... FROM 同一张表)`（MySQL 1093），因此能在 H2 上跑真实生产 DDL 做单测 |
| 不自动调度 | ✅ 只给库 + 手册，由宿主调度体系调用（文档给 `@Scheduled` / xxl-job 示例 + 分布式锁提醒） | 归档与分区都改变留存形态，属运维决策；与"分区 DDL 默认不启用"同口径 |
| 顺带修复 | ✅ `IfmapEnumCatalog` 的 SpotBugs（构造器转 private + 静态工厂 `of(provider)`） | W9 遗留项 |
| 实测 | ✅ 新增 **15** 个测试（`JdbcExecutionLogArchiverTest`）+ `TableNameResolverTest` 同步（`tables()` 4 → 5）；provider-jdbc 33 → **48**；全 reactor **353 全绿** | — |

### 11.0.10 W11 实际交付情况（回填，含实测证据）

目标（W11）：**fastjson 兼容实现（Q5 的"兼容模块"）**。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| 新模块 | ✅ `ifmap-json-fastjson`：`FastjsonJsonOps` / `FastjsonReadContext` / `FastjsonValues` / `FastjsonPaths` + `META-INF/services` | **定位 compat-only**（新项目用默认 Jackson）：模块 description 与文档都写明 |
| 版本 | ✅ fastjson **1.2.84**（而非设计稿的 1.2.83）：1.2.68~1.2.83 在**默认配置下**有可被利用的 AutoType RCE | 原因写进 `pom.xml` 注释，避免以后被"顺手降版本" |
| 两个"不做就错"的开关 | ✅ 解析必须 `Feature.OrderedField`（否则 Map 键序不可控 → 渲染顺序/快照指纹不可重复）；序列化必须 `SerializerFeature.WriteMapNullValue`（默认把 null 值键整个丢掉） | 实测得出 |
| 安全语义 | ✅ `@type` 报文：fastjson 加固后**返回 null 而不抛** → 原实现会变成"静默空值"，改为**显式抛 `JSONException`** | 与项目"禁静默取空值"口径一致 |
| 已知差异 | ✅ 6 条（对象根 `$[*]` 不展开 / `isJson` 更宽松 / `$..x` + `leafToNull` 不补偿 / `@type` 抛异常 / `isValidPath` 更严 / `toJson` 的 POJO·Date 形态），全部写进 `docs/05` §3.2 并被测试钉住 | `isValidPath` 不能只靠 `JSONPath.compile`（`$[?(` 编译期不报错、求值才抛）→ 先做"`$` 开头 + 引号外括号配对"体检 |
| 实测 | ✅ 新增 **53** 个测试（TCK 24 首跑即绿 + 本模块 8 + 路径 4 + 从 Jackson 侧复制的 DSL 端到端 17）；JDK 17 全 reactor **406 全绿**、JDK 8 侧 **302**；SpotBugs 0 缺陷；`mkdocs --strict` exit=0 | 复现命令见 `docs/03` §9 |

### 11.0.11 W12 实际交付情况（回填，含实测证据）

目标（W12）：**Q10 的替代交付（不做多数据源框架）+ 开源 roadmap 里"Feign 数据源"这一项**。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| 模块名 | ✅ **`ifmap-provider-remote`**（不是设计稿的 `ifmap-provider-feign`） | 不硬依赖 Spring Cloud OpenFeign：Feign 的坐标/默认实现随 SC 大版本变过，硬依赖等于把版本矩阵强加给宿主；而宿主自己的 Feign/RestTemplate 里已经有鉴权、超时、重试。改为 `ConfigFetcher` 函数式 SPI（`get` / `postJson`）→ 模块只依赖 core、**零 Spring、Java 8** |
| 远端配置读取 | ✅ `RemoteConfigRepository`：默认 `baseUri=/api/bankint`，`queryConfigs` / `queryLogicBranches` / `findConfig` / `saveExecutionLog`（`POST /insert_log`） | 三个"不信任远端"的兜底：**映射后本地再筛一遍**（`del_status=0 && status=1` / `del_status=0`）、**本地排序**（与 JDBC 的 `ORDER BY` 逐字一致）、**空白查询条件不拼进 query** |
| 容错映射 | ✅ `JsonConfigMapper`：键名归一化（`interfaceNo` / `interface_no` / `INTERFACE-NO` 等价）、缺失字段按 DDL 默认值补齐、必填 = DDL 里 `NOT NULL` 且无 `DEFAULT` 的列、数字容忍数字串、时间容忍 ISO-8601 与 `yyyy-MM-dd HH:mm:ss[.SSS]`、对象/数组落文本列**直接抛异常** | **`logic_branch_order` 缺失按下标（从 1 起，已删除行占位）补齐** —— 保住存量"按返回顺序生效"；一律填 0 会退化成按主键兜底 |
| 租户防串 | ✅ 映射时以**调用方查询的租户**为准，远端报文里的 `tenantId` 键被忽略；空白 → `-1`，非数字 → 抛异常 | 与 JDBC 仓储同口径 |
| 日志回写 | ✅ 远端显式返回 `false` → 抛 `IfmapConfigException`（不静默丢审计）；引擎侧照旧只降级 WARN | — |
| Q10 交付形态 | ✅ **不做多数据源框架**：`docs/05` §3.5 给"配置库 / 日志库分离"的完整宿主写法（`@Primary` 主库 + `logDataSource` + 覆盖 `ifmapExecutionLogSink`，Spring Boot 官方双数据源写法） | 接缝本来就是两个 SPI + 全部自动配置 bean 都是 `@ConditionalOnMissingBean`；**不做跨库事务**（本来就不在一个事务里） |
| 反向断言 | ✅ `IfmapSplitDatasourceTest`：日志库不可用（表都不建）时业务照常成功，且**配置库的日志表仍为 0 行** | 证明"分离"不会静默回落到配置库 |
| 顺带文档修正 | ✅ `success_value` 多值分隔符文档写 `|`、实现是 `,` / `;` → **以实现为准回写文档**（`docs/04` / `docs/06` / `docs/10` / `001-create-ifmap-config.sql` 列注释）；存量 `!值` 取反写法**不支持** → 如实写入行为对齐清单（row 12）并用测试钉住 | 核实存量：`BankintConfigManager.SEG_STR = ","`，真实数据里 `|` 只出现在 JSONPath 的 `||` 里 |
| 实测 | ✅ 新增 **30** 个测试（provider-remote **25** / `IfmapSplitDatasourceTest` **3** / `ResponseJudgeTest` **+2**）；JDK 17 全 reactor **436 全绿**（core 157 / json-jackson 46 / json-fastjson 53 / provider-jdbc 48 / provider-remote 25 / starter 44 / admin 59 / demo-sb3 4）、JDK 8 侧 **329**；双 JDK `clean verify` BUILD SUCCESS；**10 个模块 SpotBugs 全 0 缺陷**；新模块字节码 `major version: 52`；`mkdocs build --strict` exit=0（**12 页**） | 复现：`mvn -pl ifmap-provider-remote -am verify` |

### 11.0.12 W13 实际交付情况（回填，含实测证据）

目标（W13）：**标识符去「银行」化 —— `bank*` → `partner*`，Java / API / UI 与 DDL 同步改名**（ADR-15）。

| 计划项 | 实际 | 差异说明 |
|---|---|---|
| DDL | ✅ `ifmap_config`：`bank_code` → `partner_code`、`bank_name` → `partner_name`（列注释 `资方编码/名称` → `合作机构编码/名称`）；`idx_ifmap_config_list` 索引列同步 | 表注释「资方接口配置」**刻意不改**（避免与迁移脚本产生第二次口径差异） |
| 迁移脚本 | ✅ 由「0 处改名」变成 **「2 处改名」**：`03-modify-and-index.sql` 加两条 `CHANGE COLUMN`，与类型变更**合并进同一条 `ALTER`**（不多一次重建）；`00-precheck.sql` 的宽度体检**继续用源端旧列名**（它就是拿来体检旧列的，改了反而错） | 5.7 无 `RENAME COLUMN` → 只能 `CHANGE COLUMN`。官方在线 DDL 表「Renaming a column」标 In Place/不重建，但脚注限定 *"keep the same data type and only change the column name"*；本 kit 顺带改了注释（`bank_name` 还收窄类型）→ 实际仍 `COPY` |
| **新增运维口径（本次真正的新增风险）** | ✅ 迁移 kit `README.md` 新增：§1 破坏性变更警告 + §2「路径 A 下列改名怎么处置」**三种做法**（同步改存量 SQL / 可更新兼容视图 / 把改名挪到 `04` 停写窗口）+ §4 官方脚注 + §6 排错 1054 一行 + §8 checklist +1 条 + §9 免责 +1 条；[`docs/10-迁移指南.md`](10-迁移指南.md) 的 M1 概览 / 文件树 / 5.7 写法表 / §3.3 结构差异表 / §5.1 切换 checklist 同步；[`docs/04`](04-接入与建表.md) 加「为什么英文列名是 `partner_*`」 | 改 Java 标识符是编译期就能发现的事，**列名改动会打断共存期的存量模块**才是真风险，必须写进变更单 |
| Java | ✅ `getPartnerCode()` / `setPartnerCode()` / `getPartnerName()` / `setPartnerName()`；`BankCall` → `PartnerCall`、`BankServiceGateway` → `PartnerServiceGateway`、`DemoBankGateway` → `DemoPartnerGateway`；`FullParam` 注释 `bank|*` → `partner|*` | — |
| 远端兼容 | ✅ `JsonConfigMapper` 认 `partnerCode` / `partner_code` / `partnerName` / `partner_name`，**并兜底存量键** `bankCode` / `bank_code` / `bankName` / `bank_name`（**新键优先、旧键兜底**）；新增 2 个测试（旧键能收 / 新旧同现以新键为准） | 「不迁表、直接接存量接口」的能力因此不受改名影响，远端服务不用发版 |
| 刻意不改 | ✅ `bankOrgNo` / `bankAcctNo` / `bankCard`（真·银行领域字段）、`bankint_*` 表前缀与 `IBankintConfigApi` / `BankintConfigManager` 等存量类名、`BankRules`（示例规则）、迁移脚本的**源端列名** | — |
| 实测 | ✅ JDK 17 全 reactor **438 全绿**（core 157 / json-jackson 46 / json-fastjson 53 / provider-jdbc 48 / provider-remote **27**（+2）/ starter 44 / admin 59 / demo-sb3 4）、JDK 8 侧 **331**（157+46+53+48+27，SB3 三模块不在 JDK 8 reactor 内）；双 JDK `clean verify` **BUILD SUCCESS**；**10 个模块 SpotBugs 全 0 缺陷**；`mkdocs build --strict` exit=0 | 复现命令见 `docs/03` §9；改名后新增的 2 个远端兼容测试：旧键能收 / 新旧同现以新键为准 |

### 11.1 明确延后（不在 v1.0）

| 项 | 原因 | 目标版本 |
|---|---|---|
| 管理端可视化页面 | Q6：需要但可延后；先让 REST 稳定 | ✅ **已交付（W9）**：零构建静态页（`{base-path}/ui/`）+ `/enums` 可选字典 SPI |
| fastjson 兼容实现 | Q5：默认 Jackson，fastjson 只做兼容模块 | ✅ **已交付（W11）**：`ifmap-json-fastjson`（1.2.84）+ 同一套 TCK/DSL 用例全绿 + 已知差异清单 |
| 配置发布审批流 | Q7：不需要 | 不做（roadmap） |
| PostgreSQL / Oracle 方言 | 成本高、无需求 | roadmap |
| Micrometer 指标 | P1-10：不需要 Actuator | roadmap |
| 日志冷热分离 / 分区 | 保留期清理已够用（§6.4 方案 A）；需要时按可选手册切换 | ✅ **已交付（W10）**：分区 6 步手册 + `JdbcExecutionLogArchiver`（默认不启用、不自动调度） |
| 远端配置源（原"Feign 数据源"） | Q10：多数据源框架不做，但"配置不在本地库"这条路必须有 | ✅ **已交付（W12）**：`ifmap-provider-remote`（`ConfigFetcher` SPI + `RemoteConfigRepository`，**不硬依赖 Spring Cloud Feign**）+ [`docs/11-远端配置源.md`](11-远端配置源.md) |
| 配置库 / 日志库分离（Q10 替代交付） | 不做框架，只给接法 | ✅ **已交付（W12）**：[`docs/05-SpringBoot集成.md`](05-SpringBoot集成.md) §3.5 双数据源示例 + `IfmapSplitDatasourceTest`（含"日志库不可用不回落到配置库"的反向断言） |

---

## 12. 风险与开放问题

### 12.1 风险

| 编号 | 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|---|
| R1 | 未来出现 SB2 需求回流（已确认当前不需要） | 低 | 中 | §4.4 已预留补救路径；`core` 零 Spring 使其成本可控 |
| R2 | MySQL 5.7 / 8.0 行为差异（排序规则、`json` 细节）导致隐性 bug | 中 | 中 | CI 用 Testcontainers 跑双版本；显式 `COLLATE`（§6.11） |
| R3 | 去掉 ISF 后，ECC 适配层工作量大（`ApiReq` 渗透深） | 高 | 中 | 本设计不改 ECC → 适配器只在**新宿主**写；ECC 迁移走 §10.2 的 M2 / M3（切流） |
| R4 | 规则覆盖机制被滥用 → 生产行为与内置语义漂移 | 中 | 高 | `override=true` 强制显式 + 启动期 WARN + `/rules` 可查 + 审计报告列出全部覆盖 |
| R5 | `method_flag` 语义重定义后，ECC 存量配置被误读 | 中 | 中 | 迁移期强制"动作必须注册"，未注册**启动即失败**（不再静默跳过） |
| R6 | 开源后收到 PostgreSQL / Oracle 诉求 | 高 | 低 | 明确 v1 仅 MySQL；DDL 走 Liquibase 便于后续扩展 |
| R7 | `deleted_seq` 对使用者不直观，易被裸 SQL 绕过 | 中 | 中 | 只暴露管理端 API；仓储层封装删除语义；文档显著说明 |
| R8 | 执行日志增长快于清理速度 | 低 | 中 | 清理任务分批 + `maxBatches` 护栏 + 达上限 WARN + 失败只 WARN 下周期重试（W6 已落地）；文档给分区方案（§6.4 方案 B） |
| R9 | **命名未定**阻塞包名/坐标/仓库/文档站 | 高 | 中 | §9.1 候选已备，尽早定稿；定稿前所有产物用 `ifmap` 占位 |

### 12.2 开放问题结论（Q1 ~ Q10 已闭环）

| # | 问题 | 结论 | 对设计的影响 |
|---|---|---|---|
| **Q1** | 开源 License | ✅ **Apache-2.0** | 落 `LICENSE` + `NOTICE`；源码文件头加 license header（CI 校验） |
| **Q2** | 首次开源仓库位置 | ⏸ **待定**（依赖命名 §9.1） | 不影响代码结构；影响 CI 平台（GitHub Actions / GitLab CI） |
| **Q3** | 是否需要 SB2 兼容线 | ✅ **不需要** → **只做 SB3** | §4.4 改为仅 SB3；删除 SB2 模块与 `javax` 回避方案；CI 去掉 SB2 维度；工作量 **−15%** |
| **Q4** | `provider-jdbc` 实现 | ✅ **JdbcTemplate** | 不引入 MyBatis-Plus；SQL 由 starter 自带、完全可控；依赖更轻 |
| **Q5** | JSON 默认实现 | ✅ **Jackson**（另附选型对比，§4.5） | `JsonOps` SPI 签名**不含 JSON 库类型**；fastjson 仅作兼容模块 + 双实现一致性套件（**W11 已交付**：`ifmap-json-fastjson` 跑通同一套 24 条 TCK） |
| **Q6** | 配置管理端 | ✅ **需要，但延后**：v1.0 只交付 REST API，可视化页面 → **v1.1** | §8.1、§11 W5、§11.1 |
| **Q7** | 配置发布审批流 | ✅ **不需要** | 不加审批状态列；靠 `ifmap_config_history`（§6.5）+ 乐观锁（§6.9）支撑 |
| **Q8** | 是否建枚举字典表 | ✅ **不需要** | 不建 `ifmap_dict`；枚举由宿主机注册（`/api/enums` 作为可选 SPI，不强制） |
| **Q9** | ECC 与 starter 是否共用同一套配置表 | ✅ **不共用**（独立库/独立表） | 迁移期走 §10.2 M1 的独立建表 + ETL；`ifmap.table-prefix` 可配（§6.11） |
| **Q10** | 是否需要多数据源支持 | ✅ **不做**（理由见下） | `ConfigRepository` 与 `ExecutionLogSink` 已拆为两个 SPI → 天然接缝；不引入 `dynamic-datasource`、不做 `@DS`、不做 `DataSource` 路由。**W12 已交付替代方案**：文档示例（`docs/05` §3.5）+ `IfmapSplitDatasourceTest` 双库测试 |

#### Q10 澄清：多数据源的场景到底在哪里

原提问设想的两类场景，逐一核对后**都不需要在 starter 内实现**：

| 原设想场景 | 是否真实存在 | 处理方式 |
|---|---|---|
| ① **配置在 A 库、执行日志在 B 库**（合规常要求日志独立存储、独立保留期、独立权限） | 可能存在（部分企业会这么做） | `ConfigRepository`（连 A 库）与 `ExecutionLogSink`（连 B 库）**已经是两个独立 SPI** → 宿主机各自实现（或直接用内置 `JdbcConfigRepository` 各接一个 `DataSource`）。**已交付（W12）**：[`docs/05-SpringBoot集成.md`](05-SpringBoot集成.md) §3.5 给了一个配置类的完整示例（`@Primary` 主库 + `logDataSource` + 覆盖 `ifmapExecutionLogSink`），并由 `IfmapSplitDatasourceTest` 3 个用例钉住（双库读写 / 日志库不可用不回落 / 单库不受影响） |
| ② **ECC 的"配置在 `financing_scheme` 库、业务数据在 `ecc` 库"** | **真实存在，但它不是"多数据源"** | ECC 是**通过 HTTP 跨服务**取配置，不是同一应用连两个库 → 由 `ifmap-provider-remote` 解决（**W12 已交付**，§4.2 已列模块），与多数据源无关。落地时**刻意不硬依赖 Spring Cloud OpenFeign**（Feign 的坐标/默认实现随 SC 大版本变过，硬依赖等于把版本矩阵强加给宿主），改为 `ConfigFetcher` 函数式 SPI —— 宿主用自己已有的 Feign/RestTemplate 适配 10~20 行 |

**结论：不做多数据源框架。** 理由：

1. **没有不可替代的场景** —— 两个 SPI 已提供天然接缝，宿主机实现里想连几个库就连几个；
2. **避免重依赖与复杂度** —— 引入 `dynamic-datasource-spring-boot-starter` 等会显著增加依赖面；
3. **避免与宿主机策略冲突** —— 各公司都有自己的多数据源方案（`@DS` / 自研路由 / 分库分表中间件），starter 强行做会打架；
4. **保持 core 零依赖**（ADR-1）—— 多数据源能力与"纯 Java 可用"直接冲突。

**替代交付（W12 已落地）**：文档示例「配置库与日志库分离」（Spring Boot 官方双数据源写法：[`docs/05`](05-SpringBoot集成.md) §3.5），以及"宿主机只实现 `ConfigRepository`、`ExecutionLogSink` 用内置 JDBC 实现"的混合用法；另有反向场景 `ifmap-provider-remote`（配置在远端、日志也回写远端）。

### 12.3 剩余待定项（阻塞后续动作）

| # | 待定项 | 阻塞什么 | 建议 |
|---|---|---|---|
| **D1** | ~~项目命名~~ | — | ✅ **已定稿 `ifmap`**（§9.1）：模块 `ifmap-*`、包 `cn.cj.ifmap.*`、配置前缀 `ifmap.`、类名 `Ifmap*` |
| **D2** | ~~仓库位置（Q2）~~ | — | ✅ **已定**：GitHub [`CJ8023/ifmap`](https://github.com/CJ8023/ifmap.git) + Actions（必要时同步 Gitee 镜像）；本地路径 `D:\cj\code\ifmap`（§9.1） |
| **D3** | ~~MySQL 目标版本~~ | — | ✅ **已定**：**自测兼容 5.7、主推 8.0**，CI 双版本都跑（§6.11 + §9.5） |

---

## 附录 A · 目标表 vs 现有表 字段对照总表

| 表 | 现有字段数 | 目标字段数 | 改类型/长度/约束 | 新增 | 删除 | 改名 |
|---|---|---|---|---|---|---|
| `ifmap_config` | 25 | 28 | **22** | 3（`status`/`version`/`deleted_seq`） | 0 | **2**（`bank_code`→`partner_code`、`bank_name`→`partner_name`） |
| `ifmap_logic_branch_config` | **15** | **17** | **12** | 2（`logic_branch_order`/`deleted_seq`） | 0 | 0 |
| `ifmap_execution_log` | **16** | **17** | **13** | 1（`error_msg`） | 0 | 0 |
| `ifmap_config_history` | — | 11 | — | 新表 | — | — |

**总计**：**2 处改名**（都在 `ifmap_config`：`bank_code` → `partner_code`、`bank_name` → `partner_name`）、
0 处删列 → 迁移脚本仍为**纯增量**（改名 = `CHANGE COLUMN`，不动数据、不需要字段映射表）；
三表净增 5 列 + 新增 1 张历史表。

> **口径说明（可复现）**：「现有字段数」按现有 DDL 的列声明计数，「改类型/长度/约束」按 §6.2 / §6.3 / §6.4 目标 DDL 与现有 DDL **逐列 diff** 得出（含 `NOT NULL` 增减与显示宽度变化）。核对命令：
>
> ```sql
> SELECT TABLE_NAME, COUNT(*) AS col_cnt
>   FROM information_schema.COLUMNS
>  WHERE TABLE_SCHEMA = 'financing_scheme' AND TABLE_NAME LIKE 'ifmap%'
>  GROUP BY TABLE_NAME;
> ```
>
> 若实际 DDL 与本节列举有出入（多列/少列/类型不同），以实际 DDL 为准，并按 §6.7 的顺序调整迁移脚本。

## 附录 B · 目标 DDL 全量脚本

见 §6.2 / §6.3 / §6.4 / §6.5 的四个 `CREATE TABLE`。合并执行时**建表顺序**：

```
1. ifmap_config
2. ifmap_logic_branch_config
3. ifmap_config_history      （无外键，顺序仅为可读性）
4. ifmap_execution_log
5. 索引（若表数据量大，索引单独一条 DDL，见 §6.7 的两步法）
```

> 表名前缀 `ifmap_` 可替换（`ifmap.table-prefix`）；示例中的 `datetime(3)`/`COLLATE` 已按 §6.11 兼容 5.7 与 8.0。

## 附录 C · 迁移脚本骨架

```sql
-- ============ Step 1：新增列（一条 ALTER，5.7 铁律）============
ALTER TABLE `ifmap_config`
  ADD COLUMN `status`      tinyint(1) NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
  ADD COLUMN `version`     int        NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  ADD COLUMN `deleted_seq` bigint     NOT NULL DEFAULT 0 COMMENT '软删除唯一化',
  ALGORITHM=INPLACE, LOCK=NONE;

-- ============ Step 2：回填数据（分批）============
UPDATE `ifmap_config` SET `deleted_seq` = `key_id` WHERE `del_status` = 1 AND `deleted_seq` = 0 LIMIT 1000;   -- 循环至 0 行

UPDATE `ifmap_logic_branch_config` SET `logic_branch_order` = 0 WHERE `method_flag` IS NOT NULL;             -- 具名分支
UPDATE `ifmap_logic_branch_config` SET `logic_branch_order` = 9999
 WHERE (`logic_branch_flag` IS NULL OR `logic_branch_flag` = '')
   AND (`logic_branch_value` IS NULL OR `logic_branch_value` = '');                                            -- 默认兜底分支排最后

-- ============ Step 3：改列名 + 改类型 + 加唯一键与索引（一条 ALTER）============
ALTER TABLE `ifmap_config`
  CHANGE COLUMN `bank_code`       `partner_code` varchar(32)  NOT NULL                     COMMENT '合作机构编码',
  CHANGE COLUMN `bank_name`       `partner_name` varchar(128)          DEFAULT NULL        COMMENT '合作机构名称',
  MODIFY COLUMN `interface_order` smallint     NOT NULL DEFAULT 0,
  MODIFY COLUMN `result_flag`     varchar(512) NOT NULL DEFAULT '',
  MODIFY COLUMN `success_value`   varchar(512) NOT NULL DEFAULT '',
  MODIFY COLUMN `pre_strategy_name`  varchar(128) NOT NULL DEFAULT '',   -- 旧 strategy_name 一列拆两列
  MODIFY COLUMN `post_strategy_name` varchar(128) NOT NULL DEFAULT '',
  MODIFY COLUMN `busi_node`       varchar(32)  NOT NULL,
  MODIFY COLUMN `interface_code`  varchar(64)  NOT NULL,
  MODIFY COLUMN `financing_mode`  varchar(32)  DEFAULT NULL,
  MODIFY COLUMN `remark`          varchar(512) NOT NULL DEFAULT '',
  MODIFY COLUMN `add_user_id`     varchar(64)  NOT NULL DEFAULT '',
  MODIFY COLUMN `modify_user_id`  varchar(64)  NOT NULL DEFAULT '',
  MODIFY COLUMN `add_time`        datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  MODIFY COLUMN `modify_time`     datetime(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD UNIQUE KEY `uk_ifmap_config_biz`   (`tenant_id`,`interface_no`,`deleted_seq`),
  ADD UNIQUE KEY `uk_ifmap_config_order` (`tenant_id`,`busi_node`,`interface_order`,`deleted_seq`),
  ADD KEY `idx_ifmap_config_list`  (`tenant_id`,`del_status`,`busi_node`,`partner_code`),
  ADD KEY `idx_ifmap_config_code`  (`tenant_id`,`del_status`,`interface_code`),
  ROW_FORMAT=DYNAMIC,
  ALGORITHM=COPY, LOCK=SHARED;
```

> ⚠️ 执行前必须：① `SET SESSION lock_wait_timeout=10;` ② 比对 `information_schema.COLUMNS` 确认类型真会变 ③ 大表（>100 万行）改用 gh-ost / pt-osc。详见 `SQL/DDL/大表ALTER防主从延迟规范与踩坑记录.md`。
>
> ⚠️ **Step 1 与 Step 3 的算法不同**：Step 1 是纯 `ADD COLUMN` → `ALGORITHM=INPLACE, LOCK=NONE` 成立；Step 3 含 `CHANGE COLUMN`（改名）与 `MODIFY COLUMN`（改类型）→ **只能 `ALGORITHM=COPY, LOCK=SHARED`**，写成 `LOCK=NONE` 会被 5.7 直接拒绝（纯改名本身是 In Place，但它和类型变更同批，看的是整条语句最"重"的那个操作）。
>
> ⚠️ **Step 3 里的改名会打断存量模块**（旧列名 → 1054）：执行前先定好处置方案（迁移 kit `README.md` §2 的三种做法）。
>
> ⚠️ **唯一键必须在回填之后**（即 Step 3 放 Step 2 后面）：历史软删行的 `deleted_seq` 回填前全是 0，提前建唯一键必撞 `1062`。

## 附录 D · 设计决策记录（ADR）

| # | 决策 | 备选 | 理由 | 状态 |
|---|---|---|---|---|
| ADR-1 | core 零 Spring，Spring 只在门面模块 | 直接做成 Spring Starter | P2-13③ 要求支持纯 Java；也便于单测 | 已定 |
| ADR-2 | 公开 API 自定义模型，不引 ISF | 抽象出 `ApiReq` 接口 | P0-3 明确不能引；ISF 是闭源内部框架 | 已定 |
| ADR-3 | 表结构"只增不映射" | 重命名更清晰的字段 | 迁移成本从"ETL"降为"ALTER + 回填"；**表名随项目名统一为 `ifmap_*`**（RENAME，元数据操作秒级）；v1.0 定稿后仅有 **2 处列改名**（ADR-15，`CHANGE COLUMN`，同样不动数据） | ✅ 已定（W13 微调） |
| ADR-4 | `interface_order` 改 `smallint` | 保留 `char` 但应用层 `CAST` | 顺序是排序语义，DB 类型应匹配；且原字段从未被使用，无历史包袱 | 已定 |
| ADR-5 | 新增 `logic_branch_order` | 靠 `key_id` 兜底排序 | `key_id` 顺序 ≠ 业务优先级；且默认兜底分支必须排最后 | 已定 |
| ADR-6 | 软删除用 `deleted_seq` | 唯一键含 `del_status` | 后者只能删一次（§6.8） | 已定 |
| ADR-7 | `timestamp` → `datetime(3)` | 保持 `timestamp` | 2038 + 时区；开源产品不应背这个 | 已定 |
| ADR-8 | JSON 能力抽象 `JsonOps`，默认 **Jackson** | 直接依赖 fastjson / Gson | fastjson 1.2.83 有安全历史；Jackson 为 SB3 自带且是 `json-path` 默认 Provider（§4.5） | ✅ 已定（Q5） |
| ADR-9 | 规则用注解注册 + `override=true` 显式覆盖 | 直接扫描 `ConvertRule` 全部 public 方法 | 显式优于隐式；支持宿主机新增与覆盖（P1-7） | 已定 |
| ADR-10 | 执行日志清理用"保留期 + 分批删除" | 按月分区 | 开源产品装完即用，不强制运维先建分区 | 已定 |
| ADR-11 | **只做 SB3**（不做 SB2） | 双线齐发 | SB2.4 已 EOL；ECC 不依赖 starter；判定"不需要"（Q3） | ✅ 已定（Q3） |
| ADR-12 | **不做多数据源框架** | 引入 `dynamic-datasource` + `@DS` | `ConfigRepository` 与 `ExecutionLogSink` 已拆为两个 SPI，天然接缝；避免与宿主机数据源策略冲突；保住 core 零依赖（§12.2 Q10） | ✅ 已定（Q10） |
| ADR-13 | 管理端 **先 REST、页面延后 v1.1** | v1.0 就带可视化页面 | Q6：需要但可延后；先让校验/审计等核心能力稳定 | ✅ 已定（Q6） |
| ADR-15 | 标识符去「银行」化：`bank*` → `partner*`（Java / API / UI / DDL 同步） | 保留 `bank*`（改名要动 DDL 与迁移脚本） | 引擎要覆盖银行 / 保理 / 信托 / 小贷 / 保险等**各类合作机构**，`bank` 从第一天起就名不副实。**只改标识符、不动业务字段**：`bankOrgNo`/`bankAcctNo`/`bankCard` 保留，存量表前缀与类名（`bankint_*`、`IBankintConfigApi`、`BankRules`）保留；远端映射对旧键名 `bankCode`/`bankName` 做**兜底** → 宿主远端服务零改动 | ✅ 已定（W13） |
| ADR-14 | 项目名定为 **`ifmap`** | `xmap` / `linkflow` / `confmapper` / `patchbay` / `mapex` | 语义最准 + 天然满足"不限行业"（N1）+ 工程化友好；唯一近似重名 `ifmapj` 已核实**坐标不冲突、领域不同、停维护 10 年**（§9.1） | ✅ 已定 |

## 附录 E · 与现有方案文档的关系

| 文档 | 定位 | 关系 |
|---|---|---|
| `bankintconfig模块优化方案_v1.md` | **ECC 现状**的缺陷清单与修复计划（P0-1 ~ P2-12，T1~T25） | 本设计**不重复**其内容；其中跨切面的设计问题（P0-7 三 key、P1-7 规则覆盖、P1-16 静默、P1-18 顺序）在本设计中**以新架构形态**落地 |
| `ifmap-整体设计_v1.md`（本文） | **新引擎**的架构与表结构设计 | 承接上表的设计输入，输出可实施的产品设计 |
| `ifmap-内置规则审计与优化_v1.md` | **内置规则**（52 个）的去留判定、P0 缺陷、core 18 规则目标清单 | 本设计 §5.4 规则注册机制的**输入**；核心结论已摘要进 §5.4 |
| `tools/audit_bankint_config.py` | 配置静态审计工具（审计 ECC 存量 `bankint_*` 表，故文件名保留旧前缀） | 其 5 类检查全部并入管理端 `/audit`（§8.2） |
| `tools/gen_rule_inventory.py` | 内置规则清单生成器（统计 + markdown 表） | 生成审计文档 §2 的 52 行清单，可复现 |

**注意**：两份文档的**表结构结论不一致**——v1 方案基于"兼容 ECC 现有表"，本设计基于"starter 自带建表、可修正缺陷"。实施时**以本文 §6 为准**。

---

**文档状态**：初稿待评审。§12.2 的 Q1 ~ Q10 确认后，进入 W1（`core` 骨架）。
