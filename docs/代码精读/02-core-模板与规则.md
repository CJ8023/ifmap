# 02 · core：模板与规则

覆盖 `template`（5 文件）、`rule`（7 文件）、`rule/builtin`（7 文件）、`util`（4 文件）、
`validate`（2 文件）。

这一层是**引擎真正干活的地方**：模板是「报文怎么来」，规则是「值怎么算」。
两者共用一套记号（`@FUN(...)`、`@or@`、`@array`），所以 DSL 的边界行为必须放在一起看。

---

## 1. DSL 记号总表（`template/TemplateConstants.java`，63 行）

这些字符串**与存量引擎逐字对齐**，改动即破坏线上所有模板。

| 行 | 常量 | 值 | 语义 |
|---|---|---|---|
| 26 | `KEY_ARRAY` | `@array` | 字段名后缀：把值包装成数组（对象则**列转行**） |
| 29 | `KEY_ARRAYS` | `@array@` | 字段名内嵌：`"rows@array@$.items[*]"` 按源数组**逐元素**渲染子模板 |
| 32 | `SEPARATOR_AND` | `@and@` | 取多个值并**合并成数组** |
| 35 | `SEPARATOR_OR` | `@or@` | 取第一个非空值（**全落空返回 null**，见 §2.3） |
| 38 | `SEPARATOR_CONCAT` | `@concat@` | 取值后用 `,` 拼接 |
| 41 | `SEPARATOR_APPEND` | `@append@` | 取值后用 ``（空串）拼接 |
| 44 | `SEPARATOR_SUM` | `@sum@` | 数值求和，返回**字符串** |
| 47 | `FUN_PREFIX` | `@FUN(` | 规则调用前缀 |
| 50 | `FUN_SUFFIX` | `)` | 规则调用结尾 |
| 53 | `PATH_PREFIX` | `$.` | 字面量 vs 路径的判定依据 |
| 56 | `PATH_SEQ_NO` | `$.seqNo` | 伪路径：流水号（不走源报文） |
| 59 | `ARG_SEPARATOR` | `,` | 函数参数分隔（**顶层**逗号，见 §3.4） |

---

## 2. `template/TemplateEngine.java`（498 行）

渲染主流程：`renderToJson` → `render(Object template, ctx, ...)` → `build`（递归三个分支）。

### 2.1 字段与构造（63–107）

| 行 | 成员 | 说明 |
|---|---|---|
| 67 | `SEQ_NO_FORMATTER` | `yyyyMMddHHmmssSSS` |
| 69–72 | 四个依赖 | `jsonOps` / `registry` / `nullPolicy`（默认 `SKIP_FIELD`）/ `strictTypes`（默认 `WARN`） |
| 75 / 79 | 两个构造 | 3 参（旧）/ 4 参（带 strictTypes） |
| 92–104 | 四个 getter | 观测与测试用 |

### 2.2 入口（109–129）

| 行 | 方法 | 说明 |
|---|---|---|
| 109 | `renderToJson(template, source, ctx)` | 渲染后 `toJson` 成文本（宿主直接拿去发 HTTP） |
| 115 | `render(templateJson, sourceJson, ctx)` | 文本进文本出 |
| 126 | `render(Object template, JsonReadContext source, ctx)` | **已解析模板 + 已解析源**，编排器每步复用同一个源上下文 |

### 2.3 递归构建（132–214）

| 行 | 方法 | 说明 |
|---|---|---|
| 132 | `build` | 分派：`Map` → `buildObject`；`List` → `buildList`；`String` → `resolve`；其他原样 |
| 146 | `buildObject` | 按 key 顺序处理；`fieldPath`（`$.a.b[0]`）一路往下传，**只用于日志与报错定位** |
| 153–157 | `@array@` 分支 | key 里含 `@array@` → 左半是结果 key、右半是源路径 → `expandArray` |
| 158–163 | 嵌套 Map/List | 递归后再 `putWithArraySuffix`（让子对象也能用 `@array` 后缀） |
| 164–176 | 字符串值 | `isKey` 为真 → `resolve`；取到 null → `applyNullPolicy`；否则**字面量原样输出** |
| 177–178 | 模板值是 JSON null | 直接走 `applyNullPolicy(key, "null", ...)` |
| 187 | `buildList` | 数组元素逐个处理；元素取到 null 时按 `NullPolicy`（`FAIL` 抛、其他 **跳过该元素**，注意与对象分支「省字段」不同） |

**字段顺序**：全程 `LinkedHashMap`，输出顺序 = 模板书写顺序（对方系统常按位置解析，不能乱）。

### 2.4 `@array` 与 `@array@`（216–297）

| 行 | 方法 | 说明 |
|---|---|---|
| 222 | `putWithArraySuffix` | key 以 `@array` 结尾 → 去后缀 + `toArray` 包成数组 |
| 233 | `toArray` | `List` 原样；`Map` **列转行**；标量包成单元素数组 |
| 238–267 | 列转行细节 | 行长 = 各字段中最长数组的长度；非数组字段（或 null）作为常量复制到**每一行**；某行全空则不产出该行；最终为空时 WARN |
| 274 | `expandArray` | 源路径用 `read(path,false)` 取（**不补 null**）；取不到 → WARN + 空数组；每个元素 `toJson` 后新建 `JsonReadContext` 作为子模板的源 |

**为什么 `expandArray` 要重新建上下文**：子模板的 `$.x` 必须相对**数组元素**解析，
而不是相对整条回参。这是 `@array@` 与 `@array` 的本质区别（前者「一次渲染多份」，后者「一份里多行」）。

### 2.5 空值策略（299–310）

| 行 | 分支 | 行为 |
|---|---|---|
| 301 | `EMPTY_STRING` | 字段写 `""` |
| 304 | `FAIL` | 抛 `IfmapConfigException`（消息带 `fieldPath` 与原始表达式） |
| 306 | `SKIP_FIELD`（默认） | **省略该字段** + WARN |

### 2.6 表达式求值（313–421）

`isKey` → `DslExpressions.isExpression`（判定见 §3.3）。

| 行 | 分支 | 说明 |
|---|---|---|
| 323 | `@FUN(` | → `invokeFun` |
| 326–337 | `@and@` | 逐个 resolve，值为 List 就展平 addAll，非 null 标量直接 add → 返回合并后的 List |
| 338–340 | `@concat@` | `joinResolved(..., ",")` |
| 341–343 | `@append@` | `joinResolved(..., "")` |
| 344–354 | `@or@` | 返回第一个非 null；**全落空 → WARN + null**（存量会回落成模板原文，这是刻意修正的差异） |
| 355–370 | `@sum@` | 逐个 `toDecimal` 求和；操作数是 List 时逐元素加；结果用 `Values.stringify(sum)` 返回**字符串**（保留尾零、不出科学计数法） |
| 371–373 | `$.seqNo` | 直接 `seqNo()` |
| 374–376 | `$.` 开头 | `source.read(valueKey, valueKey.indexOf('*') >= 0)` —— **路径含通配符才 `leafToNull`**，再 `normalize` |
| 377 | 其他 | 原样返回（当作字面量） |

### 2.7 规则调用（395–421）

| 行 | 说明 |
|---|---|
| 396–405 | `inner` / `ruleName` 为空 → WARN + 返回 null（不抛） |
| 407 | `registry.descriptor(ruleName)` —— **规则不存在时立刻抛 `RuleNotFoundException`**（不做静默降级） |
| 408–419 | 参数逐个 resolve；空实参表达式按 `""` 处理；**取到 null 且规则不允许 null 实参** → WARN + 整个字段按 null 处理（走 `NullPolicy`） |
| 408 | `DslExpressions.funArgs(valueKey)` 切参数 —— **不用自己切** |
| 420 | `registry.invoke(ruleName, ctx, resolved)` —— 上下文不进模板参数位 |
| 424 | `splitTopLevel(inner)` 只是 `DslExpressions.splitTopLevel` 的**包私有转发**（给测试用，生产路径不经过它） |

`allowNullArgs` 是**按规则名聚合**的（`RuleRegistry.descriptor` 会合并同名重载），
所以给某个重载加 `allowNullArgs=true` 就等于给了整个规则名。

### 2.8 数值口径（436–497）

| 行 | 方法 | 说明 |
|---|---|---|
| 436 | `normalize` | `Map`/`List`/`Object[]` → `Values.plainify`（保持 JSON 层类型）；标量 → `Values.stringify`（小数保尾零）；**null 原样返回**（归一成空串会让 `SKIP_FIELD` 失效） |
| 457 | `toDecimal` | `@sum@` 操作数转数值：null/空串 → **静默 0**（两种模式都不报）；`BigDecimal`/`Number` 直接转；容器或非数字文本 → `nonNumeric` |
| 482 | `nonNumeric` | `strictTypes.failFast()` 抛 `IfmapConfigException`，否则 `log.error` + 保持 0 |
| 494 | `seqNo()` | `yyyyMMddHHmmssSSS` + 3 位随机（与存量一致） |

> `@sum@` 的「缺失 → 0」与「非数值 → 0」是**两条不同的路径**，`FAIL` 模式下前者不报错、后者报错。
> 这条差异是设计文档 §4.4 U4-B 明确要求的，别"统一"掉。

---

## 3. `template/DslExpressions.java`（304 行）

`TemplateEngine` 的"语法层"：所有「这是不是表达式 / 参数怎么切 / 路径怎么抽」都在这里。
**它是纯静态的**（无状态、无依赖），因此管理端校验与启动期自检可以共用。

| 行 | 方法 | 说明 |
|---|---|---|
| 36 | `INFIX_TOKENS` | 中缀记号数组（顺序即优先级判定顺序，见 `TemplateEngine.resolve`） |
| 48 | `isFun(value)` | 是否 `@FUN(` 开头且 `)` 结尾 |
| 55 | `funName(value)` | 取函数名 |
| 66 | `funArgs(value)` | 取参数列表（**顶层逗号切分**） |
| 79 | `hasInfix(value, token)` | 是否含某中缀记号 |
| 84 | `infixOperands(value, token)` | 按记号切操作数（顶层） |
| 112 | `isExpression(value)` | 综合判定：函数 / 中缀 / `$.` 路径 |
| 128 | `isLiteral(value)` | 明确是字面量（给校验用） |
| 136 | `extractPathTokens(value)` | 抽表达式里的全部 `$.` 路径（自检报告用） |
| 173 | `operandProblems(value)` | 报「会被静默忽略」的语法问题（`@sum@$.a,$.b` 这类） |
| 225 | `expressionPart` | 去掉外层无关修饰 |
| 236 | `inner(fun)` | 取 `@FUN(` 与 `)` 之间的内容 |
| 242 | `splitTopLevel(inner)` | 顶层逗号切分（**扫描括号深度**，所以 `@FUN(a,@FUN(b,c))` 不会被切坏） |
| 264 | `indexOfTopLevel` | 顶层查找某记号 |
| 283 | `skipBrackets` | 跳过一层括号（`[]` / `()`） |
| 301 | `isPathChar` | 路径字符判定 |

**为什么不能用正则切参数**：正则从原文里"剜"路径时遇到 `,` 就截断 ——
`@sum@$.a,$.b` 会抽出 `$.a`（还是个合法路径），于是**静默放行、线上求和成 0**。
`TemplateScanner.expressionProblems` 的 javadoc（57–65 行）记录了这个真实缺陷。

---

## 4. `template/TemplateScanner.java`（116 行）

只扫描、不渲染：给「保存前校验」和「启动期自检」用。

| 行 | 方法 | 说明 |
|---|---|---|
| 35 | `FUN_PATTERN` | 匹配 `@FUN(name` 的正则 |
| 39 | `STRING_LITERAL` | 匹配 JSON 字符串字面量（含转义） |
| 45 | `ruleNames(templateJson)` | 抽全部被引用的规则名（去重保序）→ 喂给 `RuleRegistry.assertRulesExist` |
| 69 | `expressionProblems(templateJson)` | 同时扫 **value 与 key**（`@array@` 形态把路径写在字段名里，只扫 value 会漏）；用 `DslExpressions` 的 DSL 感知切分 |
| 87 | `unescape` | 少量反转义（`\n` / `\t` / 其他） |
| 111 | `requireNonEmpty` | 模板非空校验（抛 `IfmapConfigException`） |

---

## 5. `template/NullPolicy.java`（36 行）

`SKIP_FIELD`（默认）/ `EMPTY_STRING` / `FAIL` 三个枚举值，`TemplateEngine.applyNullPolicy` 消费。
**永不回写模板原文**（存量 bug 修正）。

---

## 6. 规则体系（`rule` 包）

### 6.1 三个核心类的分工

```
@IfmapRule("name")  ──►  RuleMethod（一个方法 + 元数据）
                            │  持多个重载
                            ▼
                       RuleRegistry（名字 → List<RuleMethod>）
                            │  canInvoke / score 选最优
                            ▼
                       Coercions（实参 → 形参类型转换）
```

### 6.2 `rule/RuleRegistry.java`（349 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 58 | `MAX_MAP_DEPTH = 3` | List 逐元素映射的最大递归深度（防无限递归） |
| 60 | `index` | `LinkedHashMap<String, List<RuleMethod>>`（保序，`describeRules` 输出稳定） |
| 61 | `overridden` | 被 `override=true` 整体替换过的规则名 |
| 62 | `resolutionCache` | **注意：只声明与 `clear()`，没有任何读写** —— 死字段（见本篇 §10 待办） |
| 65 | `register(bean)` | 扫 bean 的全部方法，带 `@IfmapRule` 的进索引 |
| 101 | `registerAll(beans)` | 批量 |
| 110 | `register(bean, method)` | 单个：处理「同名默认重载 / `override=true` 整体替换 / 覆盖者唯一」 |
| 152 | （register 尾部） | `resolutionCache.clear()` |
| 161 | `contains(name)` | 是否注册过 |
| 166 | `getRuleNames()` | 全部规则名 |
| 171 | `getDescriptors()` | 排序后的清单（`IfmapEngine.describeRules` 用） |
| 182 | `descriptor(name)` | 取描述符；**同名重载的 `allowNullArgs` 取"或"**（任一重载允许即可）；未注册抛 `RuleNotFoundException` |
| 201 / 205 | `invoke(...)` | 公共入口 → 带 `depth` 的私有实现 |
| 205–244 | 解析算法 | ① 找 `canInvoke(args)` 候选；② 空 → `tryMapOverList`；③ 唯一 → 调用；④ 多个 → 按 `score` 取最高；⑤ 仍并列 → **抛歧义异常**（要求显式区分类型或改名） |
| 246 | `NOT_MAPPED` | 哨兵对象（区分"映射结果就是 null"与"没做映射"） |
| 249 | `tryMapOverList` | 实参里**有 List 且无候选匹配**时，逐元素递归调用；`depth >= 3` 停止 |
| 275 / 287 | `describe` / `signatures` | 报错文案（实参类型 + 已注册签名） |
| 298 | `notFound(name)` | 构造 `RuleNotFoundException` |
| 308 / 335 | `assertRulesExist(...)` | 启动期/契约自检：模板引用但未注册的规则 → 抛 `StartupValidationException` |

**自动逐元素映射是公开契约**（`docs/02-模板DSL语法.md` §3.1、多个模板示例、嵌套深度上限 3 都写在文档里），
不是"隐式魔法"，删它会破坏兼容。

### 6.3 `rule/RuleMethod.java`（160 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 42–55 | 构造 | 从 `Method` 推导元数据；`setAccessible(true)` |
| 47 | `hasContext` | `params[0]` 是 `RuleContext` → 它**不占模板参数位** |
| 50 | `minArity` | 变长参数时为 `dataParamCount - 1` |
| 52–54 | `descriptor` | 从 `@IfmapRule` 读 `desc`/`example`/`override`/`allowNullArgs` |
| 61 | `acceptsArity(count)` | 变长 ≥ minArity，否则严格相等 |
| 66 | `dataParamType(i)` | 第 i 个**数据**参数类型（变长取组件类型） |
| 74 | `canInvoke(args)` | 元数 + 每个实参 `Coercions.canCoerce` |
| 87 | `score(args)` | 精确匹配 2 分、可强转 1 分（分高者优先） |
| 96 / 101 / 109 | `owner` / `signatureKey` / `signature` | 全名 / 去重键 / 人读签名 |
| 121–159 | `invoke(ctx, args)` | 组装实参（context 占第 0 位、变长打包成数组）→ 反射调用；异常拆包：`IfmapException` 原样抛、其余包成 `RuleInvocationException`（带签名与实参） |

### 6.4 `rule/Coercions.java`（180 行）

| 行 | 方法 | 说明 |
|---|---|---|
| 39 | `box(t)` | 原始类型装箱 |
| 71 | `canCoerce(src, target)` | 能否转（用于候选筛选） |
| 115 | `isExact(src, target)` | 是否精确匹配（用于打分） |
| 124 | `coerce(src, target)` | 实际转换（含 String → 数字/日期/枚举/boolean） |
| 168 | `isNumeric(s)` | 数字文本判定 |

`coerce` 的失败以 `RuleArgumentException` 抛出（`StrictTypes.FAIL_FAST` 下不再兜底）。

### 6.5 其余小文件

| 文件 | 行 | 说明 |
|---|---|---|
| `rule/IfmapRule.java` | 71 | 注解：`value`（DSL 名）/ `desc` / `example` / `override` / `allowNullArgs` |
| `rule/RuleDescriptor.java` | 81 | 不可变描述符（name/owner/signature/desc/example/override/allowNullArgs），`describeRules` 与 `/rules` 端点用 |
| `rule/RuleContext.java` | 125 | 调用上下文：`tenantId` / `interfaceNo` / `attributes`；`ATTR_HOLIDAY_CALENDAR="holidayCalendar"`；`empty()` 单例；`with(k,v)` 派生；`getHolidayCalendar()` 类型不符返回 null |
| `rule/StrictTypes.java` | 43 | `WARN`（默认）/ `FAIL_FAST`；`failFast()` 是唯一判定入口 |

---

## 7. 内置规则（`rule/builtin`，7 文件 / ~1,060 行）

`BuiltinRules.registerTo(registry[, strictTypes])`（42/47 行）一次性装好 5 组规则；
`newRegistry()`（57 行）是纯 Java 场景的便捷入口。

### 7.1 `StringRules`（183 行）

| 行 | 规则 | 说明 |
|---|---|---|
| 56–59 | `concat(Object... parts)` | `allowNullArgs=true`；`Map`/`Collection`/`Object[]` 实参 → `rejectContainer`（FAIL_FAST 抛 `RuleArgumentException`，否则 `log.error` 保持旧输出） |
| 94–97 | `strDefault(value, def)` | 空串（含 null）兜底 |
| 102/110 | `strTruncate(value, max[, suffix])` | 超长截断，可带后缀 |
| 125/156 | `strMask(value, type[, keepPrefix, keepSuffix])` | 掩码：`type` 取 `ID_CARD`/`MOBILE`/`BANK_CARD`/`NAME`/`EMAIL`（默认保留位数 4/2、3/2、4/3、1/0、首字符），未知 type **原样返回** |
| 176 | `repeat(c, n)` | 内部工具 |

### 7.2 `NumberRules`（154 行）

| 行 | 规则 | 说明 |
|---|---|---|
| 38/46 | `numRound(value, scale[, rounding])` | 四舍五入，可指定 `RoundingMode` |
| 58/75 | `numSum(values[, scale, rounding])` | 集合求和（返回字符串以保尾零） |
| 93 | `numOffset(value, delta)` | 加减偏移 |
| 105 | `numFormat(value, style)` | `style` 取 `PLAIN`/`STRIP_ZERO`/`THOUSAND`/`CENT_TO_YUAN`/`YUAN_TO_CENT`，未知 style 抛 `RuleArgumentException` |
| 135 / 147 | `parseRounding` / `stripZeros` | 内部工具 |

### 7.3 `DateRules`（180 行）+ `Dates`（156 行）

| 行 | 规则 | 说明 |
|---|---|---|
| 39/47 | `dateFormat(value[, pattern])` | 格式化 |
| 55 | `dateConvert(value, src, dst)` | 换格式 |
| 63/71/79 | `dateAdd(value, amount[, unit[, pattern]])` | 日期加减（天/月/年） |
| 93/101 | `dateDiff(begin, end[, unit])` | 差值 |
| 114 | `dateEndOfMonth(value, pattern)` | 当月最后一天 |
| 127/135 | `workdayAdd(ctx, value, amount[, pattern])` | 工作日推进（用 `RuleContext.getHolidayCalendar()`，无则按周一~周五） |
| 163 / 172 / 177 | `isWorkday` / `workday` / `nvl` | 内部工具 |

`Dates` 是格式表持有者：

| 行 | 成员 | 说明 |
|---|---|---|
| 45 | `static { ... }` | 初始化两组格式（`DATE_TIME_FORMATTERS` 9 种、`DATE_FORMATTERS` 8 种） |
| 70 | `parseOrNull(value)` | 逐个尝试；**解析失败只 WARN 返回 null**（不抛） |
| 100 | `parseStrict(value, pattern, what)` | 指定格式严格解析，失败抛 |
| 118 | `format(time, pattern)` | 格式化 |
| 127 | `parseUnit(unit, what)` | `d/day` → DAYS、`m/month` → MONTHS、`y/year` → YEARS、`h` → HOURS、`mi` → MINUTES、`s` → SECONDS、`w` → WEEKS；默认 DAYS；未知抛 `RuleArgumentException` |

`parseOrNull` 容忍的格式（用于「对方给的日期写法不统一」）：`yyyy-MM-dd HH:mm:ss`、
`yyyy-MM-dd'T'HH:mm:ss`、带 `.SSS`、`yyyy-MM-dd HH:mm`、`yyyy/MM/dd HH:mm:ss`、
`yyyyMMddHHmmssSSS`、`yyyyMMddHHmmss`、`yyyyMMddHHmm`、`ISO_LOCAL_DATE_TIME`；
纯日期：`yyyy-MM-dd`、`yyyy/MM/dd`、`yyyy.MM.dd`、`yyyyMMdd`、`yyyy-MM`、`yyyyMM`、
`BASIC_ISO_DATE`、`ISO_LOCAL_DATE`。

### 7.4 `DictRules`（128 行）

| 行 | 规则 | 说明 |
|---|---|---|
| 49/57 | `dictVal(value, expr[, default])` | 单值翻译；null → default |
| 71/79 | `dictValArray(values, expr[, default])` | 逐元素翻译（**保序**） |
| 99 | `parse(expr)` | 解析 `01:待处理;02:已处理`；不写冒号时兼容 `-` 作键值分隔；空表达式/无映射对抛 `RuleArgumentException` |

**expression 里不能出现逗号** —— `@FUN` 的参数按顶层逗号切分，逗号会被当成参数分隔符。
分隔符只有 `;`（条目）与 `:`（键值，不写冒号时兼容 `-`）。

### 7.5 `ListRules`（161 行）

| 行 | 规则 | 说明 |
|---|---|---|
| 39 | `listJoin(values, sep)` | 拼接 |
| 50/58 | `listOp(values, op[, arg])` | `op` 取 `DISTINCT`（默认）/ `REVERSE` / `SORT` / `SORT_DESC` / `LIMIT`（带 `arg`）/ `MAX` / `MIN`；未知 op 抛 `RuleArgumentException`（65 行 `doOp`，比较用 `SmartComparator`） |
| 114 | `parseInt(arg, what)` | 内部工具 |

---

## 8. `util`（4 文件 / 406 行）

| 文件 | 行 | 关键成员 |
|---|---|---|
| `util/Text.java` | 157 | `isEmpty` / `isBlank` / `nvl(Object, def)` / `trimToNull` / `isNumeric` / `toDecimal(Object, what)` / `asList(Object)` / `isStructured` / `join(Collection, sep)` |
| `util/Values.java` | 140 | `stringify(Object)`（小数保尾零、不出科学计数法）/ `plainify(Object)`（递归转成可进 JSON 的结构且保持层类型）/ `toPlainDecimal(Number)` |
| `util/Logs.java` | 43 | `TRUNCATED = "...truncated"` + `truncate(text, threshold)`（落库前统一截断） |
| `util/Annotations.java` | 66 | `find(type, annotationType)`：先本类、再父类、再接口（`findOnInterfaces`）—— `ActionRegistry` / 策略注册都靠它读注解值 |

**`Values.stringify` 是"金额口径"的唯一实现**：`@sum@`、`numSum`、`numRound` 都经由它，
所以「小数永不出现科学计数法」这件事只有在它被绕开时才会破。

---

## 9. `validate`（2 文件）

### 9.1 `validate/ContractValidator.java`（118 行）

| 行 | 方法 | 说明 |
|---|---|---|
| 40 | 构造 | 只依赖 `IfmapEngine`（要 `jsonOps` + `validateTemplate`） |
| 48 | `validate(configs)` | 遍历每个配置：请求模板、响应模板各查一次，再查判定规格 |
| 60–61 | 两次 `check` | `KIND_REQUEST` / `KIND_RESPONSE` |
| 62–64 | `checkJudgeSpec` | 统计 `always` 数量（"不做判定"的接口数，报告里单独列） |
| 75 | `checkJudgeSpec` | **复用 `ResultJudgeSpec.validate`** —— 管理端 422、运行期 `RuleSpecJudge`、启动自检三处口径同源 |
| 87 | `check` | ① 空模板跳过；② 非 JSON → 违规；③ `engine.validateTemplate` 抓 `StartupValidationException` 拆成「未注册规则」「表达式错语法」两类违规；④ 兜底 `RuntimeException` |

### 9.2 `validate/ContractReport.java`（158 行）

| 行 | 成员 | 说明 |
|---|---|---|
| 27 | 类 | 聚合报告：`violations` + `totalConfigs` + `alwaysCount` |
| 49 / 53 / 57 | `isClean` / `getViolations` / `size` | 基本查询 |
| 62 | `judgeSpecViolations()` | 只挑判定类违规 |
| 83 | `toMarkdown()` | Markdown 表格输出（`/validate` 端点直接用） |
| 116 | `Violation` | 内部类：`KIND_REQUEST` / `KIND_RESPONSE` / `KIND_JUDGE` + interfaceNo/order/templateKind/message |

---

## 10. 本篇发现的待办（不改代码，仅登记）

| # | 位置 | 现象 | 建议 |
|---|---|---|---|
| 1 | `rule/RuleRegistry.java:62` | `resolutionCache` 只被声明和 `clear()`，**从未读写** | 要么实现缓存（键 = 规则名 + 实参类型元组），要么删除。当前是"看起来有优化、实际每次全扫候选"的误导性死字段 |
| 2 | `TemplateEngine.joinResolved`（380） | `@concat@` 固定 `,`、`@append@` 固定空串，两者只差分隔符 | 无需改（存量兼容），但新增组合符时别再复制一份 |
| 3 | `StringRules.rejectContainer`（83） | `WARN` 模式下容器实参仍会 `toString()` 进报文 | 属于兼容取舍，文档 03 篇已写明；排查"报文里出现 `[a, b]`"时先看这里 |

| 4 | `TemplateEngine.java:424` | `splitTopLevel` 是纯转发（`return DslExpressions.splitTopLevel(inner)`），生产路径走 `DslExpressions`，只有测试用 | 保留无害（测试夹具靠它），但**不要**在这里加逻辑，否则两份切分规则会漂移 |

> 第 1 条是本次精读新发现的（此前"过度设计"诊断未列出）。它不影响正确性，只是让
> 「解析结果有缓存」这个说法不成立 —— 若要动它，属于性能优化任务，需单独评审。
> 第 4 条同理：无功能影响，但属于"看起来还在用"的迷惑项。
