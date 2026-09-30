# 07 · 管理端 REST

> 前情：[`04-接入与建表.md`](04-接入与建表.md)（4 张表的口径）、[`06-执行编排与策略扩展.md`](06-执行编排与策略扩展.md)（引擎与契约自检）。
> 本文对应设计文档 §8：把"改资方接口配置"从**改库 + 发版**变成**页面点点 + 留痕 + 可回滚**。

## 1. 这个模块解决什么问题

存量做法是：运营提需求 → 开发改 `bankint_config` 的 JSON 模板 → 走发布。问题有三：

| 问题 | 后果 |
|---|---|
| 配置错只能等**线上第一次调用**才炸 | 客户侧先看到失败 |
| 谁改的、改前是什么，**查不到** | 出问题只能靠翻 binlog |
| 想回退只能**手工拼 SQL** | 拼错就是二次事故 |

管理端做的事就三件：**保存前拦**（校验）、**改完留痕**（历史快照 + 差异）、**一键回滚**（写回旧快照，同样走乐观锁）。

> 边界：管理端**只写配置表**，引擎运行期**只读**；引擎侧不依赖本模块，去掉 `ifmap-admin-spring-boot-starter` 一切照常。

## 2. 启用方式

```xml
<dependency>
    <groupId>cn.cj</groupId>
    <artifactId>ifmap-admin-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
ifmap:
  admin:
    enabled: true          # 默认 false —— 这个模块能改线上配置，必须显式打开
```

**必须自己加鉴权**：本模块不内置登录/权限（不绑定宿主的认证体系），请把 `/ifmap/admin/**` 挂到你们现有网关或 `SecurityFilterChain` 上，只给配置管理员放行。

三重门控，误开不了（任一不满足就不装配）：`ifmap.admin.enabled=true` → classpath 有 Web 与 `JdbcTemplate` → 有 `DataSource`。

打开后除 REST 端点，还附带一个**零构建可视化页面**：浏览器打开 `{base-path}/ui/`（默认前缀下即 `http://<host>:<port>/ifmap/admin/ui/`）。原生 HTML/CSS/JS，**无 CDN、无前端框架、无构建链**，内网离线可直接用；详见 §11。

### 2.1 配置项

| 配置项 | 默认 | 说明 |
|---|---|---|
| `ifmap.admin.enabled` | `false` | 总开关 |
| `ifmap.admin.base-path` | `/ifmap/admin` | 所有端点的统一前缀（自动加，不需要 Controller 里写死） |
| `ifmap.admin.history-limit` | `50` | `GET /configs/{id}/history` 默认返回条数（上限也是它） |
| `ifmap.admin.audit-max-configs` | `5000` | 单次巡检最多扫多少条配置，超出置 `truncated=true`（报告不完整而不是 OOM） |

所有 bean 都是 `@ConditionalOnMissingBean`：要用自己的 Controller / Service 覆盖，声明同类型 bean 即可。

## 3. API 一览

前缀统一 `ifmap.admin.base-path`（下表省略）。`tenantId` 在查询参数里是 `String`（引擎内部也是 `String` 口径），写请求体里是 `long`。

### 3.1 配置

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/configs` | 分页列表：`tenantId` / `interfaceNo` / `busiNode` / `bankCode` / `status` / `includeDeleted` / `page` / `size` |
| GET | `/configs/{keyId}` | 详情 |
| POST | `/configs` | 新增（含校验 + 写 `CREATE` 历史） |
| PUT | `/configs/{keyId}` | 修改（**必带 `expectedVersion`**，写 `UPDATE` 历史 + `diff`） |
| DELETE | `/configs/{keyId}?reason=` | 逻辑删除（写 `DELETE` 历史，快照存删除前内容） |
| POST | `/configs/{keyId}/status` | 启用 / 停用（`status=1/0`，写 `ENABLE`/`DISABLE` 历史） |
| POST | `/configs/validate` | **保存前校验**（不落库） |
| POST | `/configs/dry-run` | **试跑**：拿现有配置 + 假应答跑一遍，绝不出网 |
| GET | `/configs/{keyId}/history` | 变更历史（`limit` 可选，默认 `history-limit`） |
| POST | `/configs/{keyId}/rollback` | 回滚到指定历史（`historyId` + `expectedVersion`） |

写请求体统一用包装对象，便于以后加字段：

```json
{ "config": { ... }, "reason": "运营工单 12345", "expectedVersion": 0 }
```

### 3.2 逻辑分支

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/branches?tenantId=&interfaceNo=&includeDeleted=` | 查某接口的分支（按 `logic_branch_order` 升序） |
| POST | `/branches` | 新增分支 |
| PUT | `/branches/{keyId}` | 修改分支 |
| DELETE | `/branches/{keyId}` | 逻辑删除分支 |

分支**不写历史**（设计 §6.5 决策）：分支表没有 `version` 列，且分支属于接口配置的一部分，改分支的"留痕"由接口配置的 `UPDATE` 历史承载。分支唯一键是 `(tenant_id, interface_no, method_flag, logic_branch_name, deleted_seq)`，所以「同接口 + 同方法 + 同名」不允许重复；`method_flag` 可为空（空 = 该分支**不执行动作**）；**“兜底分支”是另一个维度**：`logic_branch_flag` 为空才是兜底分支（不参与常规匹配，只在常规分支全部未命中时生效，每个接口最多 1 条）。

### 3.3 元数据（配置页的下拉与即时校验用）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/rules` | 已注册规则清单（`@FUN` 可用项，含签名与示例） |
| GET | `/actions` | 已注册动作（`method_flag` 取值） |
| GET | `/strategies` | 已注册策略：`specialDeals` / `fullParams` / `logicBranches` / `actions` / `callbacks` |
| GET | `/audit?tenantId=&busiNode=&markdown=` | 全量巡检，`markdown=true` 返回可贴工单的报告 |
| GET | `/enums` | 宿主机注册的**字典**（可选 SPI，见 §11.3）；没注册就返回 `{}`（不是 404） |

**这些端点由服务端提供，前端不要硬编码**：`@FUN` 规则、`method_flag` 动作、`strategy_name` 都是宿主机代码决定的，硬编码必然和宿主漂移。

### 3.4 审计头

| 请求头 | 必填 | 用途 |
|---|---|---|
| `X-Operator-Id` | 否 | 操作人 → `add_user_id` / 日志 `by xxx` |
| `X-Request-Id` | 否 | 请求号 → `add_request_id`（与调用链日志对齐） |

不传也能用（落空串），但**上线请让网关强制带上**，否则留痕只有时间没有人。头名常量在 `IfmapAdminHeaders`，页面发的是同一对头（`IfmapAdminWebEndpointTest` 里断言操作人确实落到了历史记录的 `operatorId`）。

## 4. 状态码与错误体

| 状态码 | 何时 | 响应体 |
|---|---|---|
| 200 | 成功；**也包括"业务上没做成"**：乐观锁冲突、目标已删除/不存在时返回 `ok=false` | `{"ok":true,...}` / `{"ok":false,"reason":"版本已过期"}` |
| 400 | 参数或目标问题：`config 不能为空`、`修改配置必须提供 keyId`、`配置不存在：#123`、dry-run 缺 `interfaceNo`/`busiNode` | `ApiError` |
| 422 | **保存前校验未通过**（`errors` 非空） | `ApiError` + `errors` / `warnings` |
| 502 | 外呼失败（试跑只用假应答，正常不会出现） | `ApiError` |
| 500 | 兜底（同时打 ERROR 日志） | `ApiError` |

`ApiError` 形状：

```json
{
  "ok": false,
  "error": "配置 #7 校验未通过（2 项）",
  "type": "IfmapValidationException",
  "errors": ["bankCode 不能为空", "分支顺序重复：logic_branch_order=1（分支 命中提交）"],
  "warnings": ["接口 IF_A 尚未配置逻辑分支"]
}
```

**为什么乐观锁冲突是 200 + `ok=false` 而不是 409**：这是"并发下的正常结果"，前端要拿它提示"别人刚改过，请刷新后重试"，走 HTTP 错误通道会被通用错误弹窗吃掉上下文。目标不存在则是**用错接口**，所以走 400。

## 5. 保存前校验清单（设计 §8.3）

校验在 `ConfigValidator`，新增 / 修改 / 回滚 / `POST /configs/validate` 走的是**同一套**。

| 校验 | 依据 |
|---|---|
| 必填列非空 | `interfaceNo` / `interfaceCode` / `interfaceName` / `busiNode` / `bankCode` |
| 模板是合法 JSON | `request_param_template` / `response_param_template` |
| `$.path` 路径合法 | `JsonOps.isValidPath`（JsonPath 编译期就能拦住 `$.a[`） |
| `@FUN` 规则存在且参数可匹配重载 | `ContractValidator`（引擎在位时）；引擎缺席时**至少校验模板 JSON 合法**（历史脏数据真实存在，不能静默放过） |
| `strategy_name` 已注册 | `SpecialDealStrategyRegistry` |
| `front_interface_no` 前置链可解析 | 不能自指、不能成环（护栏 64 层） |
| 唯一键不冲突 | `(tenant_id, interface_no, busi_node, interface_order, deleted_seq)` |
| 分支集合：默认兜底 ≤ 1、顺序唯一、`method_flag` 已注册 | `validateBranches` / `validateBranch` |

关于最后一行：**默认兜底分支（`logic_branch_flag`/`value` 都空）最多 1 条**——引擎取首个命中者，第二条永远不会生效，属于"配了但没用"的静默陷阱，所以新增第二条时直接拒。

## 6. 变更历史与回滚

历史落在 `ifmap_config_history`（11 列，设计 §6.5）。每次写操作：

1. 校验 → 2. 落库（乐观锁）→ 3. **回读**（拿到 DB 归一化后的真实值，如审计列/时间）→ 4. 写历史 → 5. 失效缓存。

| 字段 | 内容 |
|---|---|
| `change_type` | `CREATE` / `UPDATE` / `DELETE` / `ENABLE` / `DISABLE` |
| `snapshot` | **变更后**的配置快照（`DELETE` 存删除**前**快照，否则删完就没法回滚） |
| `diff` | 与上一版的字段差异（`CREATE` / 状态变更 / 删除时为 `null`） |

**快照怎么序列化**：显式业务列（白名单）→ 有序 `LinkedHashMap` → `JsonOps.toJson`。为什么不用 Bean 反射序列化：① 少一个 Jackson JSR-310 模块依赖；② 反射会把 `version`/审计列等"不该回滚的东西"一起写进去；③ 字段增删时**静默丢字段**比编译错误危险得多。

**回滚不是"反向 SQL"**：读历史快照 → 转成 `IfmapConfig` → **先跑一遍校验**（旧快照也可能是脏数据）→ 走 `writer.update(.., expectedVersion, ..)` 写回 → 再记一条 `UPDATE` 历史（原因写成「回滚到历史 #N：<原因>」）。

所以回滚**同样是乐观锁 + 同样留痕**，不是绕过管控的后门。

## 7. 试跑（dry-run）

```http
POST /ifmap/admin/configs/dry-run
{
  "tenantId": "7",
  "interfaceNo": "IF_A",
  "busiNode": "apply",
  "params": { "bizNo": "B9", "amount": 1 },
  "mockResponse": "{\"resultCode\":\"0000\",\"data\":{\"applyNo\":\"A9\"}}"
}
```

执行链路与线上**完全一致**（前置接口链 → 拓扑排序 → 组包 → 渲染 → 特殊处理 → 判定 → 分支动作），只把"出网"换成 `mockResponse`。因此能看到：渲染后的请求报文、映射后的响应数据、命中的分支、失败原因。

`tenantId` 是 `String`、`params` 是 `Map<String,Object>`（不是 JSON 字符串），前端直接用表单值即可。

## 8. 全量巡检（audit）

`GET /audit` 把"启动期契约自检"扩到"运行期全量巡检"：扫全库配置（**跨租户**，`tenantId` 不传即全量），逐条跑校验清单，汇总成

| 字段 | 说明 |
|---|---|
| `configCount` / `branchCount` | 扫到的配置数 / 分支数 |
| `errors` | 必须修的问题（模板非法、规则不存在、`strategy_name` 缺失、死分支、重复默认分支……） |
| `warnings` | 提醒（接口没有分支等） |
| `truncated` | 超过 `audit-max-configs` 时为 `true`：**报告不完整**，别当"全绿" |
| `markdown` | `markdown=true` 时的报告正文（可直接贴工单） |

## 9. 排错

| 现象 | 原因 / 处理 |
|---|---|
| 启动后 `/ifmap/admin/**` 全 404 | `ifmap.admin.enabled` 没开（默认 false）；或没有 `DataSource` / 不是 Servlet Web 应用 |
| 端点被宿主的鉴权拦掉（401/403） | 本模块不内置鉴权，请把前缀加进放行名单 |
| `GET /configs` 看不到某租户数据 | 查询参数 `tenantId` 不传时**只看 `-1` 默认租户**；要看全部请显式用巡检（`/audit` 不带 `tenantId`） |
| PUT / 状态变更 / 回滚返回 `ok=false` | 版本过期：`expectedVersion` 要填**上一次读到的 version**（新建后是 `0`，改一次变 `1`） |
| `history` 里 `snapshot` 是带转义的一整串 | 历史快照列在 MySQL 上是 `json` 类型；**H2 1.4.200 的 `JSON` 类型不能用**（JDBC 写字符串会被包成 JSON 字符串字面量），所以 starter 在非 MySQL 方言下把 `json` 列建为 `text` |
| 回滚报「历史快照不是 JSON 对象」 | 快照列被手动改过（不是对象），或历史来自旧版本；用 `/audit` 找脏数据 |
| 校验一直 422 说规则不存在 | 自定义规则没被收集：确认 `@IfmapRule` 方法所在 bean 被 Spring 管理（starter 的 `IfmapRuleRegistrar` 只扫 bean，不扫静态类） |
| 打开 `/ui/` 是空白页 / 资源 404 | 静态资源挂在 `{base-path}/ui/**`；前面有 Nginx / 网关反向代理时**必须把 `{base-path}` 整段转发**（页面靠当前 URL 反推前缀，前缀被截断就调不到接口）；另看浏览器控制台报错 |
| 页面上字典下拉是空的（其它都正常） | 宿主机没注册 `IfmapEnumProvider`（可选 SPI）→ `/enums` 返回 `{}`，页面退化成普通输入框，功能不受影响（§11.3） |

## 10. 最小可运行示例

`ifmap-admin-spring-boot-starter` 的测试就是可跑通的用法样本（H2 内存库真跑 Tomcat + 真 HTTP）：

| 测试类 | 覆盖 |
|---|---|
| `ConfigAdminServiceTest` | 14 个用例：CRUD / 乐观锁 / 回滚 / 分支 / 试跑 / 校验 |
| `IfmapAdminWebEndpointTest` | 5 个用例：前缀、状态码语义、审计链、分支 + 试跑 |
| `ConfigValidatorTest` | 11 个用例：§5 校验清单逐条正反例 |
| `ConfigAuditorTest` | 3 个用例：巡检报告（含 Markdown） |
| `IfmapAdminAutoConfigurationTest` | 7 个用例：三重门控（默认关闭）、字典 bean 两态 |
| `ConfigSnapshotMapperTest` | 4 个用例：快照与差异序列化 |
| `IfmapAdminUiEndpointTest` | 4 个用例：真 Tomcat 下 `/ui` 302、`index.html`/`app.js`/`app.css` 可取、静态资源不越界、`/enums` 带出宿主机字典 |
| `IfmapAdminUiScriptTest` | 5 个用例：页面脚本的**静态契约**（路径白名单、不硬编码前缀、`el('id')` 与 HTML 对得上、无外链、`node --check`） |
| `IfmapEnumCatalogTest` | 6 个用例：字典目录（无 provider / 脏数据 / 防御性拷贝） |

跑法：

```bash
mvn -pl ifmap-admin-spring-boot-starter -am clean test
```

## 11. 可视化页面（v1.1）

REST 是给机器和 curl 用的，运营同学要的是能点的页面。所以随 starter 一起打包了一个**零构建**的管理页面：

```
{ifmap.admin.base-path}/ui/          默认前缀下 = http://<host>:<port>/ifmap/admin/ui/
```

（`/ui` 会自动 302 到 `/ui/index.html`，手敲地址不用记文件名。）

### 11.1 页面上能做什么

| 页签 | 能力 |
|---|---|
| 配置 | 分页查询（租户 / 接口 / 节点 / 资方 / 状态 / 含已删）、新建、编辑、**保存前校验**、**试跑**、启停、逻辑删除、变更历史、一键回滚 |
| 逻辑分支 | 按接口查分支（含兜底分支标记）、新建 / 编辑 / 删除（`logic_branch_flag` 留空即兜底分支） |
| 巡检 | 全量巡检结果分条展示，一键**复制 Markdown 报告**（直接贴工单） |
| 规则与字典 | 已注册规则（`@FUN` 可用项）、动作（`method_flag`）、策略、宿主机字典——**全部由服务端 `/rules`、`/actions`、`/strategies`、`/enums` 提供，页面不硬编码** |

页面顶部的「操作人」会存进浏览器 `localStorage`，之后每个写请求都自动带 `X-Operator-Id` / `X-Request-Id`（§3.4），所以"谁改的"照样留痕。

### 11.2 为什么不用前端框架 / 不引 CDN

| 约束 | 原因 |
|---|---|
| 零 CDN | 这套东西部署在**内网**，外网 CDN 拿不到会白屏（打包时也不能保证能联网） |
| 零前端框架、零构建链 | 加了 React/Vue 就多一条 Node 构建链要维护、要发版；页面本身就是一堆表单与表格，原生 JS 够用（`app.js` 约 900 行） |
| 资源**不缓存**（`Cache-Control: no-cache`） | 升级 ifmap 后刷新必须立刻是新版页面，避免"页面是旧的、接口是新的"这种最难查的错 |

### 11.3 字典 SPI：`IfmapEnumProvider`（可选，不强制）

ifmap **不建字典表**（设计 Q8）：码值含义是宿主机业务，引擎只负责把值填进模板。想让页面把某字段渲染成下拉，宿主机注册一个 bean 即可：

```java
@Bean
public IfmapEnumProvider bankEnums() {
    return () -> Map.of(
            "bankCode", List.of(EnumOption.of("CMB", "招商银行"), EnumOption.of("ICBC", "工商银行")),
            "status",   List.of(EnumOption.of("1", "启用"), EnumOption.of("0", "停用")));
}
```

| 约定 | 说明 |
|---|---|
| key | **模板里的字段名**（元素 `data-field`），如 `bankCode`；页面按名字取，取不到就是普通输入框 |
| 返回 | `Map<String, List<EnumOption>>`；`EnumOption.value` 必填（空值直接 `IllegalArgumentException`），`label` 可省（省了用 `value`） |
| 没注册 | `/enums` 返回 `200 {}`（**不是 404**）：页面只有"没有字典"这一条正常分支，少一个 SPI 不会让页面报错 |
| 脏数据 | `null` key / `null` 列表 / `null` 项会被跳过，坏一项不影响其它字段 |

### 11.4 页面自己怎么测（手写 JS 的护栏）

编译期管不到的错，用测试管：

| 测试 | 钉住的东西 |
|---|---|
| `IfmapAdminUiScriptTest` | ① `app.js` 里出现的每个接口路径都在白名单内（写错一个字母 = 空白页面，编译期发现不了）；② **不许硬编码 `/ifmap/admin`**（前缀可配，写死就整页 404）；③ `el('xxx')` 引用的元素必须在 `index.html` 里存在（否则交互静默失效）；④ 页面无任何外链（内网可用）；⑤ 有 `node` 时跑 `node --check`（没有则跳过，不让构建挂） |
| `IfmapAdminUiEndpointTest` | 真 Tomcat 下发 HTTP：`/ui` 302、三个静态资源 200 且类型正确、静态资源**不越界**（`/ui/../` 与 `META-INF/...` 都 404）、`/enums` 真的带出宿主字典 |

> 页面只是 REST 的消费者：**关掉它不影响任何端点**，去掉静态资源也不影响引擎（引擎侧本就不依赖管理端）。
