# ifmap 模板 DSL 语法

> 适用版本：`0.1.0-SNAPSHOT`　作者：caijun

模板本身是**合法的 JSON**：key 是目标报文字段名，value 是「取值表达式」。引擎按 key/value 逐层递归，
输出结构完全由模板的嵌套结构决定。

```json
{
  "bizNode": "GP81",
  "orderNo": "$.orderNo",
  "dueDate": "@FUN(dateFormat,$.dueDate,yyyyMMdd)",
  "items@array@$.items": {
    "sku": "$.sku",
    "label": "@FUN(concat,$.sku,号)"
  }
}
```

---

## 1. 取值表达式总表

引擎对 value 的判定顺序（**先命中先返回**，一旦命中就不再往下判断）：

| 序 | 形态 | 判定条件 | 语义 |
| --- | --- | --- | --- |
| 1 | 函数调用 | 以 `@FUN(` 开头**且**以 `)` 结尾 | 调用注册规则，见 §3 |
| 2 | 合并数组 | 含 `@and@` | 各分支取值**合并成数组**，List 分支会被展平 |
| 3 | 逗号拼接 | 含 `@concat@` | 各分支取值用**半角逗号**拼成字符串 |
| 4 | 直接拼接 | 含 `@append@` | 各分支取值**无分隔符**拼成字符串 |
| 5 | 取首个非空 | 含 `@or@` | 返回第一个非 null 分支；**全空返回 null**（交给空值策略） |
| 6 | 数值求和 | 含 `@sum@` | 各分支按数值求和（`List` 逐元素累加，非数值按 0） |
| 7 | 内置流水号 | 恰好等于 `$.seqNo` | 返回 `yyyyMMddHHmmssSSS` + 3 位随机数（20 位数字字符串） |
| 8 | 路径取值 | 以 `$.` 开头 | JsonPath 取值 |
| 9 | 字面量 | 以上都不满足 | **原样输出该字符串** |

> 一个 value 里**只应出现一种多值符号**：命中第 2 条后，`@or@` 等不会再被解析（它们会作为普通文本参与拼接）。
> 需要混合逻辑时，用嵌套 `@FUN` 表达。

---

## 2. 取值：JsonPath

底层使用 JsonPath（默认实现为 Jayway JsonPath），因此以下写法都可用：

| 写法 | 说明 | 示例 |
| --- | --- | --- |
| `$.a.b` | 逐级属性 | `$.applicant.name` |
| `$.list[0]` | 下标（支持负数，`[-1]` 为最后一个） | `$.items[-1].sku` |
| `$.list[*].x` | 通配：收集所有元素的 `x`，结果为数组 | `$.items[*].sku` |
| `$..x` | 递归下降 | `$..sku` |
| `$.list[?(@.sku=='A')]` | 过滤 | `$.items[?(@.qty>1)].sku` |
| `$.list[0:2]` | 切片 | `$.items[0:2]` |
| `$.list.length()` | 函数 | `$.items.length()` |

### 2.1 数字与布尔会被转成字符串

目标报文里的金额、数量、标志位在银行接口中通常是**字符串**，因此路径取到的 `Number`/`Boolean`
会被统一转成字符串（`100` → `"100"`，`true` → `"true"`），数组/对象内的值保持原类型。

### 2.2 通配路径的「叶子补 null」

只要路径里含 `*`，引擎就会开启 **leafToNull**：某个元素缺该字段时补 `null`，保证数组等长、下标对齐。
这是「并行数组列转行」能对齐的关键，见 §4.2。

```json
{ "items": [ { "sku": "A", "qty": 1 }, { "sku": "B" } ] }
```

- `$.items[*].qty` → `[1, null]`（开启 leafToNull）
- 不开 leafToNull 时 JsonPath 会**直接丢掉**取不到的叶子 → `[1]`，下标就错位了

### 2.3 路径写错 / 报文非法会显式失败

| 情况 | 行为 |
| --- | --- |
| 路径**不存在**（`$.notExist`） | 返回 null，按空值策略处理（正常业务场景，不报错） |
| 路径**语法错误**、源报文不是合法 JSON | 抛 `IfmapConfigException`（附路径），**不静默降级** |

---

## 3. 规则调用 `@FUN`

```
@FUN(规则名,参数1,参数2,...)
```

- 参数按**顶层逗号**切分（括号深度感知），所以参数里可以嵌套 `@FUN`：
  `@FUN(concat,@FUN(dateFormat,$.dueDate,yyyy),/,@FUN(dateFormat,$.dueDate,MM))` → `2026/03`
- 参数可以是：`$.` 路径、字面量、另一个 `@FUN(...)`
- 空参数（两个逗号之间什么都没有）按**空字符串**传入
- 规则**不存在** → 抛 `RuleNotFoundException`（启动期校验能提前拦下，见 §7）
- 参数取值为 null 且该规则**不允许 null 入参** → 该字段按空值策略处理（不会调用规则，也不报错）
- 规则**内部抛异常** → 包成 `RuleInvocationException` 向上抛（不吞）

### 3.1 列表实参自动逐元素映射

形参是标量（如 `String`），实参却是 `List` 时，引擎会**自动逐元素调用并收集**结果：

| 模板 | 源报文 | 输出 |
| --- | --- | --- |
| `@FUN(numFormat,$.amounts,CENT_TO_YUAN)` | `"amounts":[100.5,200.25]` | `["1.01","2.00"]` |
| `@FUN(strTruncate,$.tags,2)` | `"tags":["abcd","efgh"]` | `["ab","ef"]` |

嵌套深度上限为 3（防止异常数据无限递归）。需要显式控制时，用 `dictValArray`、`numSum` 这类
**形参就是 List** 的规则。

---

## 4. key 上的数组符号

### 4.1 `key@array`：值统一变数组

| 值的类型 | 结果 |
| --- | --- |
| `List` | 原样（元素顺序不变） |
| `Map` | **列转行**，见 §4.2 |
| 标量 / null | 包成单元素数组 |
| 取不到值 | 空数组 `[]`（并打 WARN 日志） |

```json
// 模板
{ "single@array": "$.invoiceType", "multi@array": "$.invoiceTypes" }
// 源：{"invoiceType":"01","invoiceTypes":["01","02"]}
// 输出
{ "single": ["01"], "multi": ["01","02"] }
```

### 4.2 `Map` + `@array` = 列转行（并行数组转对象数组）

当 `@array` 的值是一个**对象**时，引擎把它当作「若干**等长的并行数组**」处理：
对象里的每个字段先各自取值，然后**按下标对齐**组装成对象数组。这是银行接口里最常见的形态
（源报文用并行数组传明细，目标报文要对象数组）。

```json
// 模板
{
  "invoiceItems@array": {
    "code":   "$.items[*].sku",
    "qty":    "$.items[*].qty",
    "amount": "$.items[*].price"
  }
}
// 源
{ "items": [ { "sku": "A", "qty": 2, "price": "10.50" },
             { "sku": "B", "qty": 3, "price": "66.66" } ] }
// 输出
{ "invoiceItems": [
    { "code": "A", "qty": "2", "amount": "10.50" },
    { "code": "B", "qty": "3", "amount": "66.66" } ] }
```

对齐规则（与存量引擎一致）：

| 规则 | 说明 |
| --- | --- |
| 行数 | 取各字段数组长度的**最大值**，下标从 **1** 开始计数（即第 0 个元素是第 1 行） |
| List 字段 | 按下标取值；元素为 null 或**下标越界** → **跳过该字段** |
| 非 List 字段 | 作为**常量复制进每一行** |
| 整行为空 | 跳过该行，但**不终止**后续行（不会 break） |

> 这意味着 `$.items[*].qty` 里缺失的叶子（leafToNull 补的 null）会被自动跳过，
> 输出里该对象就没有 `qty` 字段，而不会出现 `"qty": null`。

### 4.3 `key@array@$.path`：逐元素展开子模板

key 的写法是 `字段名@array@JsonPath`（**字段名不能省**，前缀为空会导致输出 key 变成空串）：

```json
// 模板
{ "items@array@$.items": { "sku": "$.sku", "label": "@FUN(concat,$.sku,号)" } }
// 源：{"items":[{"sku":"A"},{"sku":"B"}]}
// 输出
{ "items": [ { "sku": "A", "label": "A号" }, { "sku": "B", "label": "B号" } ] }
```

- 源数组的**每个元素成为子模板的源报文**（`$.sku` 取的是当前元素，不是外层报文）
- 数据源不存在 → 输出空数组 `[]` + WARN（存量引擎会回写模板原文）
- 元素为 null → 用空对象兜底，子模板字段全部按空值策略处理

---

## 5. 多值符号（value 上）

| 模板 | 源 | 输出 | 说明 |
| --- | --- | --- | --- |
| `"$.a@and@$.list"` | `a=PO1, list=[01,02]` | `["PO1","01","02"]` | List 展平 |
| `"$.a@or@$.b"` | `a=null, b=PO1` | `"PO1"` | 取首个非空 |
| `"$.a@concat@$.b"` | `a=PO1, b=2026-03-31` | `"PO1,2026-03-31"` | 逗号拼接 |
| `"$.a@append@-X"` | `a=PO1` | `"PO1-X"` | 无分隔符拼接 |
| `"@sum@$.amounts"` | `amounts=[1,2.5]` | `3.5` | 数值求和 |

拼接类符号遇到 List 会**展平后参与拼接**；null 分支被忽略（不会输出 "null" 文本）。

---

## 6. 空值策略（NullPolicy）

当某个字段最终取值为 null（路径不存在、`@or@` 全空、规则返回 null、参数为 null 触发短路）时：

| 策略 | 行为 | 输出示例 |
| --- | --- | --- |
| `SKIP_FIELD`（默认） | 字段整条不出现 | `{}` |
| `EMPTY_STRING` | 输出空字符串 | `{"remark":""}` |
| `FAIL` | 抛 `IfmapConfigException`，消息含出问题的模板字段路径 | — |

```java
IfmapEngine engine = IfmapEngine.builder().nullPolicy(NullPolicy.EMPTY_STRING).build();
```

> **与存量引擎的关键差异**：ifmap **任何情况下都不会把 `@FUN(...)` 或 `$.path` 原文写进报文**。
> 存量引擎在「参数取不到值」时会 `return 模板原文`，导致生产报文里出现
> `"dueDate": "@FUN(farmatDate,$.dueDate,yyyy-MM-dd)"` 这类脏数据。

---

## 7. 启动期模板校验

```java
engine.validateTemplate("czb-apply-01", templateJson);           // 单个
engine.validateTemplates(templateIdToJson);                       // 批量
```

发现未注册的规则时抛 `StartupValidationException`：

```
模板中存在未注册的规则，已阻断启动：
  - czb-apply-01 -> [farmatDate]
已注册规则：[concat, dateAdd, ...]
```

```java
catch (StartupValidationException e) {
    e.getMissingRules();   // Map<模板标识, Set<规则名>>，可直接打日志/告警
}
```

**建议**：把模板存在数据库/配置中心的应用，在应用启动时（`ApplicationRunner` / `@PostConstruct`）
把全部模板扫一遍做校验，把「运行期静默 null」提前成「启动失败」。

---

## 8. 与存量引擎的语义差异（迁移必读）

| # | 场景 | 存量引擎 | ifmap |
| --- | --- | --- | --- |
| 1 | 规则名拼错 | 运行期静默返回 null（`InvokeUtils.invokeMethodIfExist` 不抛异常） | 抛 `RuleNotFoundException`；可用启动期校验提前拦下 |
| 2 | 参数取不到值 | **回写模板原文**（`return valKey`） | 按 `NullPolicy` 处理，绝不回写原文 |
| 3 | `@or@` 全部分支落空 | 回写模板原文 | 返回 null，交 `NullPolicy` |
| 4 | `@array` 数据源缺失 | 回写模板原文 | 空数组 `[]` + WARN |
| 5 | `@array` 的 Map 值 | 列转行 | 列转行（**已对齐**，见 §4.2） |
| 6 | `@array@` 展开 | 每个元素作为子模板源报文 | 一致 |
| 7 | 源报文解析次数 | 每条路径重新解析一遍 | 解析一次、多次取值（`JsonReadContext`） |
| 8 | 公开 API 的 JSON 类型 | fastjson 类型（`JSONObject`）到处流转 | 只有 JDK 类型（`Map`/`List`/`String`/`Number`） |
| 9 | 规则名大小写/重载 | 反射按精确参数类型匹配，重载不对称 | 同名 = 重载，按参数类型匹配 + `String` 形参自动逐元素 |
| 10 | `dictVal` 表达式 | `-` 作键值分隔；值里含 `-` 会被截断 | `:` 作键值分隔；`-` 作回退分隔（按**首个**分隔符切分） |

---

## 9. 完整示例

模板（`ifmap-demo-pure-java/src/main/resources/demo/template.json`）：

```json
{
  "bizNode": "GP81",
  "orderNo": "$.orderNo",
  "seqNo": "$.seqNo",
  "dueDate": "@FUN(dateFormat,$.dueDate,yyyyMMdd)",
  "totalAmount": "@FUN(numSum,$.amounts,2,HALF_UP)",
  "invoiceTypeName": "@FUN(dictVal,$.invoiceType,01:增值税专用发票;02:增值税普通发票,其他)",
  "maskedIdCard": "@FUN(strMask,$.applicant.idCard,ID_CARD)",
  "invoiceItems@array": {
    "code": "$.items[*].sku",
    "qty": "$.items[*].qty"
  },
  "items@array@$.items": {
    "sku": "$.sku",
    "amount": "@FUN(numRound,$.price,2)"
  },
  "remark": "$.remark"
}
```

输出（源报文里 `remark` 为 null，默认策略下整条不出现）：

```json
{
  "bizNode": "GP81",
  "orderNo": "PO20260101001",
  "seqNo": "20260930174412721758",
  "dueDate": "20260331",
  "totalAmount": "400.74",
  "invoiceTypeName": "增值税专用发票",
  "maskedIdCard": "3201************34",
  "invoiceItems": [
    { "code": "SKU-A1", "qty": "2" },
    { "code": "SKU-B2", "qty": "3" }
  ],
  "items": [
    { "sku": "SKU-A1", "amount": "100.50" },
    { "sku": "SKU-B2", "amount": "66.66" }
  ]
}
```

运行：`mvn -q -pl ifmap-demo-pure-java exec:java`
