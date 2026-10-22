# 05 · JSON 实现与一致性测试

覆盖 `ifmap-json-jackson`（8 文件 / 794 行）、`ifmap-json-fastjson`（11 文件 / 1,246 行）、
`ifmap-json-tck`（4 文件 / 882 行）。

三个模块的关系是**一份契约、两个实现、一套测试**：

```
ifmap-core          JsonOps 接口（4 模块里唯一不含 JSON 库依赖的地方）
   ▲                ▲
   │ 实现           │ 实现
ifmap-json-jackson  ifmap-json-fastjson
   │                │
   └──── 都继承 ────┴──► ifmap-json-tck（3 个抽象基类，各自的 test 只写 1 行 extends）
```

- `ifmap-core` **零 JSON 库依赖**（只定义 `JsonOps` / `JsonReadContext`）；
- `ifmap-json-jackson` 是**推荐实现**（Jackson + jayway json-path），经 `ServiceLoader` 自动发现；
- `ifmap-json-fastjson` 是**存量兼容实现**（fastjson 1.2.84 + fastjson JSONPath），只为让老配置字节级一致地跑；
- `ifmap-json-tck` 是**契约的活文档**：把「两种实现必须一致」的每一条都写成测试。

---

## 1. `ifmap-json-jackson`

### 1.1 `JacksonJsonOps.java`（213 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 57 / 58 | 两套 Option | `NO_OPTION`（严格）/ `LEAF_TO_NULL`（`DEFAULT_PATH_LEAF_TO_NULL`） |
| 61–62 | 两个 Configuration | JsonPath 的配置按需切换，**不为每次读取重建** |
| 64 | 无参构造 | `defaultMapper()` |
| 68 | 带 mapper 构造 | 宿主可注入自己的 `ObjectMapper`（Spring Boot 里常见） |
| 77 | `defaultMapper()` | 见下方「小数契约」 |
| 89 | `configuration(mapper, options)` | `JacksonJsonProvider` + `JacksonMappingProvider` |
| 97 | `getMapper()` | 暴露给宿主（管理端 `JsSafeLongSerializer` 就挂在这上面） |
| 102 | `name()` | `"jackson"` |
| 107 | `parse(json)` | 空/空白 → null；失败 → `IllegalArgumentException`（**不静默**） |
| 119 | `toJson(value)` | `null` → 字面量 `"null"` |
| 131 | `readContext(json)` | `json == null` 按 `"{}"` 处理 |
| 136 | `isJson(text)` | `readTree` 试解析（**不抛**，返回 false） |
| 149 | `isValidPath(path)` | `JsonPath.compile` 试编译（**不抛**） |

**小数契约（77–87）—— 三个开关缺一不可**：

| 位置 | 开关 | 作用 |
|---|---|---|
| 81 | `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS` | 解析：小数读成 `BigDecimal`（保留源文字 `scale`） |
| 82 | `SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN` | 序列化：`ObjectMapper` 自己创建的 generator 走 `toPlainString()` |
| 85 | `JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN` | 序列化：**外部传入的 generator / JsonPath 内部路径**也走 plain（前一个只管 `ObjectMapper` 层） |

只开 81 就会在 `toJson` 最外层丢尾零（`10.00` → `10.0`）；只开 82/85 则值早就是 `Double`，回不到源文字。

### 1.2 `JacksonReadContext`（内部类，162–212）

| 行 | 成员 | 说明 |
|---|---|---|
| 167 / 168 | 两个懒建 `DocumentContext` | 严格版 / leafToNull 版，各自只解析一次 |
| 177 | `read(path)` | 等价 `read(path, false)` |
| 182 | `read(path, leafToNull)` | `PathNotFoundException` → **null**（唯一允许的"正常空值"）；其他异常 → `IfmapConfigException`（**路径语法错/源报文非法必须显式失败**，不走存量"静默取空值"老路） |
| 197 | | 结果是 `JsonNode` 时转 JDK（`JsonNodes.toJdk`），否则原样 |
| 200 | `context(leafToNull)` | 按需建、建后复用 |

### 1.3 `JsonNodes.java`（85 行）

| 行 | 分支 | 说明 |
|---|---|---|
| 41 | `toJdk(node)` | null / `isNull` / `isMissingNode` → null |
| 45 | 对象 | `LinkedHashMap`（**保序**） |
| 54 | 数组 | `ArrayList` |
| 61 | 文本 | `textValue()` |
| 64 | 布尔 | `booleanValue()` |
| 67 | 整数 | `int` → `long` → `BigInteger` 按需降级（**大整数不丢精度**） |
| 76 | 浮点 | `decimalValue()`（null 时退 `doubleValue`） |
| 80 | 二进制 | `asText()` |
| 83 | 兜底 | `asText()` |

**为什么必须转**：`JsonNode.toString()` 会带引号（`"abc"` 变成 `"\"abc\""`），直接进模板会污染报文。

---

## 2. `ifmap-json-fastjson`

### 2.1 `FastjsonJsonOps.java`（111 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 51 | `NAME = "fastjson"` | 与 `name()` 同源 |
| 59 | `parse(json)` | `FastjsonValues.parseDocument` → `toJdk`；异常包成 `IllegalArgumentException` |
| 73 | `toJson(value)` | **两个 SerializerFeature**：`WriteMapNullValue`（显式 null 的键要留在 JSON 里）+ `WriteBigDecimalAsPlain`（小数契约） |
| 90 | `readContext(json)` | `FastjsonReadContext` |
| 95 | `isJson(text)` | `JSON.parse(text, Feature.OrderedField)` 试解析 |
| 108 | `isValidPath(path)` | `FastjsonPaths.isValid` |

**类注释里的三条安全约定（33–42）值得单独记住**：
1. 只用 `JSON.parse(String)` / `JSON.toJSONString` / `JSONPath`，**不用** `parseObject(Class)`、`TypeReference`（会触发 AutoType）；
2. **不修改** `ParserConfig.getGlobalInstance()`（改全局配置会污染宿主其他 fastjson 用法）；
3. 遇 `@type` 时 fastjson 会"静默解析成 null"，本实现把它转成显式异常。

> 注释里引用的 CVE 编号（`CVE-2026-16723`）与官方公告的编号形式不太一致，
> 需要以 `pom.xml` 里锁定的版本（1.2.84）与实际公告为准；本文只记录"代码怎么做的"，不背书编号。

### 2.2 `FastjsonValues.java`（101 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 36 | `ABBREVIATE_LIMIT = 200` | 异常信息里最多展示 200 字 |
| 41 | `isBlank` | — |
| 56 | `parseDocument(json)` | `JSON.parse(json, Feature.OrderedField)`；**返回 null 且原文不是字面量 `null` → 抛 `JSONException`**（把"静默空对象"变成显式失败） |
| 66 | `toJdk(value)` | `Map` → `LinkedHashMap`（键 `String.valueOf`）、`Collection`/`Object[]` → `ArrayList`、其他原样 |
| 95 | `abbreviate(text)` | 截断 |

`Feature.OrderedField` 是**保序的关键**：模板渲染顺序、快照/指纹的可重复性都依赖它。

### 2.3 `FastjsonReadContext.java`（120 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 48 | 构造 | `json == null` → `"{}"` |
| 54 | `read(path)` | 等价 `read(path, false)` |
| 59 | `read(path, leafToNull)` | `JSONPath.eval`；`leafToNull` 时 `padMissingLeaves` 补偿；**任何 RuntimeException → `IfmapConfigException`**；`node == null` 且路径是多值形状 → **空列表**（与 Jackson 口径一致：调用列表规则不该拿到 null） |
| 81 | `root()` | 懒解析一次；解析出 null → 空 `JSONObject(true)`（保序） |
| 97 | `padMissingLeaves(root, path, node)` | 见下 |

**`padMissingLeaves` 要解决的差异**：fastjson 的 JSONPath 没有 jayway 的 `DEFAULT_PATH_LEAF_TO_NULL`，
通配路径上缺失的叶子会被**跳过**，导致列表变短、与父节点下标错位。
补偿逻辑只在「有界多值段 + 末段属性名」形状下生效（`boundedMultiValuePrefix` 非 null），
且**数量一致时直接返回**（绝大多数报文走这条路，不多做求值）。

### 2.4 `FastjsonPaths.java`（256 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 45 | `isValid(path)` | 语法体检（**不能只靠 `JSONPath.compile`**：fastjson 对 `$[?(` 编译期不报错，1.2.84 实测要到求值才抛 `StringIndexOutOfBoundsException`）：非空 → 首字符 `$` → 不以 `.` 结尾 → 引号外括号配对 → 才交给 `JSONPath.compile` |
| 68 | `isBalanced(path)` | **引号外的** `[]` / `()` 是否配对（`$[?(` 与 `$.a[` 都判非法） |
| 117 | `isMultiValue(path)` | 是否是"多值形状"（`*` / `?` / 切片 / 多选）—— 决定"null 还是空列表" |
| 162 | `boundedMultiValuePrefix(path)` | 最后一个**有界**多值段的前缀（给 `padMissingLeaves` 用） |
| 175 | `lastMultiValueSegmentEnd(path)` | 最后一个有界多值段结束位置（无则 -1） |
| 207 | `segmentEnd(path, start)` | 括号配对查找（引号/嵌套感知） |
| 234 | `isMultiValueSegment(segment)` | 段内是否含 `*` / `?` / `:` / `,`（**引号内的不算**） |

---

## 3. `ifmap-json-tck`（4 文件 / 882 行）

三个抽象基类 + 一个工具类，**每加一个 JSON 实现只需写一行 `extends`**。

| 文件 | 行 | 作用 |
|---|---|---|
| `JsonOpsConformanceTestBase` | 424 | `JsonOps` 契约：30 个 `@Test` |
| `TemplateFidelityTestBase` | 246 | 模板渲染的数值保真：17 个 `@Test` |
| `JudgeConsistencyTestBase` | 155 | 判定结论跨实现一致：6 个 `@Test` |
| `JdkNativeValues` | 57 | 断言辅助：`assertJdkNative(value, path)`（:36）递归检查"不含 JSON 库类型"，失败时抛出带路径的 `AssertionError` |

### 3.1 `JsonOpsConformanceTestBase`（69 行起）

唯一抽象方法 `jsonOps()`（72）。`ops()`（74）是懒加载单例。

测试分组（按 `@DisplayName`）：

| 组 | 覆盖点 |
|---|---|
| 身份 | `name()` 非空 |
| `parse` | 对象/数组/标量/null/非法 JSON/大整数 |
| **小数保真** | `10.00 ≠ 10.0`、`0.100`、负零、科学计数法展开、容器内递归、`readContext` 取回仍是 `Number` 且保位数 |
| `toJson` | `null` 字面量、往返结构、转义（引号/反斜杠/换行/制表符/中文/emoji） |
| `isJson` / `isValidPath` | 不抛异常 |
| `readContext` | 根/嵌套/下标/切片/`*`/`..`/**过滤器**/路径不存在 vs 过滤器不命中/**leafToNull 下标对齐**/null 源报文/类型不转换/重复取值稳定/非法表达式显式失败/数组作根 |

`ORDER_JSON`（404）是键序断言用的固定报文；`listOf(...)`（417）是断言辅助。

### 3.2 `TemplateFidelityTestBase`（51 行起）

两个引擎：`engine()`（`SKIP_FIELD` + WARN）与 `failFastEngine()`（`StrictTypes.FAIL_FAST`）。

| 组 | 覆盖点 |
|---|---|
| `$.path` 取小数 | 尾零保留、科学计数法在渲染期展开 |
| 数组内数字 | 只改写法不改类型（不加引号） |
| `@sum@` | 返回**字符串**、保留操作数最大小数位、数组逐元素累加、与 `numSum` 口径一致、非数值按 0（warn）、缺失按 0（两模式一致） |
| `@concat@` / `@append@` / `@and@` | 同样不出科学计数法 |
| 整体文本 | 数值仍是 JSON number，只有 `@sum@` 是带引号字符串 |
| FAIL_FAST | `concat` 收到数组实参拒绝、`@sum@` 非数值拒绝、缺失仍是 0 |
| 启动期校验 | 错语法**两种模式都拒**、未闭合 `@FUN(` 被拒（不是渲染成字面量） |

### 3.3 `JudgeConsistencyTestBase`（46 行起）

| 组 | 覆盖点 |
|---|---|
| 字符串等值 | 命中/未命中/缺字段/非 JSON 四种结论与归因固定 |
| 小数尾零 | 规格里的 `"10.00"` 必须与报文 `10.00` 判等（读成 `Double` 会退化成 `10.0`） |
| `in` 数组 | 标量数组逐元素比较，数字两边同形态 |
| 数组路径 + `any` | 下标取值与「任一命中」两实现一致 |
| `ignoreCase` / `always` | 大小写不敏感只在显式声明时生效；`always` 不看报文 |
| 规格非法 | **不抛异常**，统一 `SPEC_INVALID`（换 JSON 库也不能变成抛错） |

---

## 4. 两个实现的差异清单（写代码时必须知道）

| 维度 | Jackson（jayway） | fastjson | 抹平方式 |
|---|---|---|---|
| 路径不存在 | 抛 `PathNotFoundException` | 返回 null | Jackson 接住 → null |
| 通配/过滤器不命中 | 空列表 | null | fastjson 侧按 `isMultiValue` 转空列表 |
| 缺失叶子 | `DEFAULT_PATH_LEAF_TO_NULL` 补 null | **跳过**（列表变短） | fastjson 侧 `padMissingLeaves` |
| 键序 | `ObjectMapper` 默认保序 | 需 `Feature.OrderedField` | fastjson 显式开 |
| 显式 null 值的键 | 默认保留 | 默认**丢弃** | fastjson 开 `WriteMapNullValue` |
| 小数解析成 `BigDecimal` | 需 `USE_BIG_DECIMAL_FOR_FLOATS` | 默认就是 `BigDecimal` | 各自显式配置 |
| 小数序列化 plain | 两处开关（`ObjectMapper` + `factory`） | `WriteBigDecimalAsPlain` | — |
| 返回类型 | `JsonNode` | `JSONObject`/`JSONArray` | 都转 JDK（`JsonNodes` / `FastjsonValues`） |
| 非法 JSON | 抛 `IllegalArgumentException` | `parseDocument` 抛 `JSONException` → 包成 `IllegalArgumentException` | 同口径 |
| 源报文 null | 按 `"{}"` | 按 `"{}"` | 同口径 |

---

## 5. 看起来多余但别删的点

| 点 | 位置 | 为什么保留 |
|---|---|---|
| `ifmap-json-fastjson` 整个模块 | — | 存量 216 个调用点走 fastjson 方言；翻译路径语义比重装 SPI 风险高得多。**但它是过渡件**，新项目用 Jackson |
| `JacksonJsonOps(ObjectMapper)` 构造 | `:68` | Spring Boot 宿主已有定制 mapper（雪花 ID、`JavaTimeModule`），必须能注入而不是自己 new |
| 两个 `Configuration` 字段 | `:61-62` | 按需切换 leafToNull 语义；每次读都 `JsonPath.using(...).parse(...)` 会重复解析整份报文 |
| `JsonNodes` / `FastjsonValues` 整类 | — | 「公开 API 不出现 JSON 库类型」是 `JsonOps` 的硬契约，靠这两个转换器守住 |
| `FastjsonPaths.isBalanced` 手写配对 | `:68` | `$[?(` / `$.a[` 这类"看着像路径、实际是语法错"的输入必须判非法，用正则判不对 |
| TCK 里 `@DisplayName` 写这么长 | 三个 base | 它们就是**契约文档**：`clean verify` 输出直接可读成"哪些承诺被验证过" |
| `JdkNativeValues` 单独成类 | — | "递归检查不含 JSON 库类型"在 3 个 base 里复用，且断言逻辑比一行 `instanceof` 复杂 |
