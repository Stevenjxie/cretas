# 意图分类器 — 否定否决 + 读写孪生排序 (W1b)

**日期**: 2026-06-03
**状态**: 设计 (待 review)
**关联**: W0 写护栏 `project_2026_06_02_intent_classifier_w0_writeguard`、W1a shadow harness、`fool-proof-design.md`
**分支**: `feat/intent-negation-twin` (off `origin/main` @ 42d776740)

---

## 1. 背景与动机

W1a shadow harness 上线后,我们对 test 环境跑了 ~95 个工厂/餐饮/边界场景的综合测试。初版通过**未认证 demo `/recognize` 端点**(单意图、固定 `F_DEMO`、无业态门控)暴露了一大批"问题",但在**真实认证 F001(工厂)`execute/multi` 多意图路径**上逐一复核后,绝大多数是 **demo 端点的产物,不是生产路径的真实 bug**:

| demo 看到的"问题" | 真实 F001 路径 | 判定 |
|---|---|---|
| 业态泄漏(成本分析→`RESTAURANT_DISH_COST_ANALYSIS`) | → `COST_TREND_ANALYSIS`;餐饮意图被业态门控**主动拒绝+引导** | ✅ 已正常 |
| 库存报表→餐饮 | → `INVENTORY_SUMMARY_QUERY` | ✅ 正常 |
| 过度拒绝(生产进度/暂停生产→`OUT_OF_DOMAIN`) | → 正常识别 `PROCESSING_BATCH_*` | ✅ demo-only |
| 读→写错配 / CLOCK twin | 识别 + W0 写护栏全拦 | ✅ 已被护栏兜住 |
| **否定被忽略** | 🔴 **`不用查库存了` → `INVENTORY_CLEAR`(清空库存)排第一** | 🔴 **真实 bug** |

**真实路径上唯一的真问题是否定处理**;此外存在一个次要的读写孪生排序问题(读措辞查询有时把写孪生排在前面,造成多余确认弹窗)。

**安全前置说明**:此否定错配**不是安全漏洞** —— W0 写护栏已拦住它(`「清空库存」是写入/修改操作,执行前需要确认`)。本设计是**正确性/体验**修复("不用查库存"不该弹"要清空库存吗?"),不是补安全洞。安全不变量(见 §6.2)进一步保证修复本身不会引入静默写。

### 1.1 已验证证据 (test 10011, 真实 F001 认证多意图路径)

```
不用查库存了   → intents=[INVENTORY_CLEAR 0.75, MATERIAL_BATCH_QUERY 0.72, RESTAURANT_INGREDIENT_LOW_STOCK 0.71]
                 status=PARTIAL_SUCCESS  msg="「清空库存」是写入/修改操作，执行前需要确认。..."   ← 否定被忽略，且首位是破坏性写
别给我看订单   → status=NEED_CONFIRMATION (3 intents)                                          ← 否定被忽略
生产进度怎么样 → intents=[PROCESSING_BATCH_COMPLETE 0.76, REPORT_PRODUCTION 0.74, PROCESSING_BATCH_START 0.71]  ← 读措辞却把写排首位(已被护栏拦)
完成生产       → PROCESSING_BATCH_* 写 + 护栏                                                   ← 写动词，正确保持为写
取消订单       → (合法写，应保持)                                                               ← 动作动词，绝不能被误判为"否定查询"
```

---

## 2. 范围

### In scope
- **组件 1 — 否定否决**:正确处理三类否定(写动词否定 / 读动词否定 / 内容排除),覆盖单意图 + 多意图两条路径。
- **组件 2 — 读写孪生排序**:读措辞查询(无写动词)误把写孪生排在前面时,在 margin 内优先读,减少多余确认弹窗。
- 合并 `convertNegationIntent` map 与 `SemanticRouterServiceImpl.READ_WRITE_TWIN_PAIRS` 为**单一事实源**(供 live 路径使用)。
- 扩充否定检测词表(`NEGATION_PATTERN` 缺 `不用/甭/无需/不必/先不/暂时不/独立的别`)。

### Out of scope (明确不做)
- **不改 demo `/recognize` 端点**(销售演示用,单意图 + `F_DEMO` 无业态门控是已知设计,非 bug)。仅在 spec/代码注释里记一句"demo 路径不代表生产路由"。
- **不做分类器/路由重建(W1c)** —— 那要等 prod shadow 攒够数据(≥48h/≥500 query)数据驱动决策。
- **不动业态门控**(已正常)。
- **不放宽 OOD/`怎么样` 阈值**(demo-only,真实路径不过度拒绝)。
- **不改 W0 写护栏的 isWrite 判定**(复用,不改)。

---

## 3. 根因分析 (已定位,origin/main @ 42d776740)

| # | 根因 | 位置 | 类型 |
|---|---|---|---|
| R1 | `NEGATION_PATTERN` 漏词:只含 `除了\|排除\|不要\|不包括\|去掉\|去除\|不含\|除开\|不是\|非\|不想要\|别给我`。**`不用查库存了` 不匹配任何词 → `hasNegation=false`** → 不转换 | `QueryPreprocessorServiceImpl.java:102` | CODE(data-in-code) |
| R2 | 现有 `detectNegationSemantics` 语义是**"排除内容"**(`除了X`,捕获 `excludedContent`),不是**"否决查询/动作"**。两种否定被混为一谈 | `QueryPreprocessorServiceImpl.java:803` | CODE |
| R3 | 否定转换 (`convertNegationIntent` + 632-645 区) **只在单意图最佳匹配路径生效**;`execute/multi` 多意图路径完全不做否定处理 | `IntentRecognitionPipelineServiceImpl.java:647-655` 单;`recognizeMultiIntent:664/669` 多(无否定步) | CODE |
| R4 | `convertNegationIntent` 孪生表不全(无 `INVENTORY_CLEAR` 等),且只做"写→读转换",**不"否决写"**;且 map 与 `SemanticRouterServiceImpl.READ_WRITE_TWIN_PAIRS:113` 是两份重复 | `IntentRecognitionPipelineServiceImpl.java:5433` | CODE + 重复 |

---

## 4. 设计概览

```
                         ┌─────────────────────────────────────────────┐
用户输入 ── 预处理 ──────▶│ QueryPreprocessor.detectNegation*           │
                         │  扩词表 + 新增 detectNegationVeto()          │  (R1/R2)
                         │  → NegationInfo{ kind, negatedVerb, ... }     │
                         └───────────────┬─────────────────────────────┘
                                         │ NegationInfo + 候选列表 + actionType
        单意图出口(IRP ~647) ───────┐    ▼
                                    ├──▶ NegationTwinPolicy (新, stateless)   (R3/R4)
        多意图出口(recognizeMulti)─┘    │  canonical TWIN map (单一事实源)
                                         │  applyNegationVetoAndTwinRerank(...)
                                         │   组件1: 否定否决(3 模式)
                                         │   组件2: 读写孪生排序
                                         └──▶ 复用 WriteGuardService.isWriteIntent()
                                              安全不变量: 否决后绝不产出写
```

新增一个**无状态、线程安全**的策略服务 `NegationTwinPolicy`(与 `WriteGuardService` 同性质,可从 worker 线程调用,**禁读 ThreadLocal/SecurityContext**),持有合并后的孪生表 + 纯决策函数。pipeline 在单/多意图两个出口各调一次。

---

## 5. 新服务: `NegationTwinPolicy`

`com.cretas.aims.ai.tool.NegationTwinPolicy`(与 WriteGuardService 同包,`@Service`,无状态)。

### 5.1 单一事实源:canonical 孪生表
合并 `convertNegationIntent` map(IRP:5433)与 `SemanticRouterServiceImpl.READ_WRITE_TWIN_PAIRS`(SR:113)为一份 `WRITE_TO_READ_TWIN: Map<String,String>`(写意图 → 对应读意图)。现有两份**改为引用本服务**(SemanticRouter 与 convertNegationIntent 都委托给 `NegationTwinPolicy.readTwinOf(writeCode)`),消除重复。

合并后的表 = 现有 22 对 + 补齐(**已对真实 code 核验**,见审计 finding 1):
```
INVENTORY_CLEAR        → INVENTORY_QUERY        (✅ INVENTORY_QUERY 是 SQL-seeded 读意图;
                                                 ⚠️ 不要用 INVENTORY_SUMMARY_QUERY —— 它非 config-backed,
                                                 仅是 phraseToIntentMapping 的 target,getAllIntents 校验会被剔)
ORDER_DELETE / ORDER_CANCEL → ORDER_LIST        (用于"别给我看订单"的读回退;
                                                 注:`取消订单` 经 IntentKnowledgeBase:3652 实际解析为 ORDER_DELETE)
```
**确认不存在(不要放进表,别浪费 getAllIntents 往返)**:`MATERIAL_BATCH_CLEAR` / `MATERIAL_BATCH_DISPOSE` 在 .java/.sql 0 命中(实际 MATERIAL_BATCH 写意图是 CONSUME/CREATE/RELEASE/RESERVE/UPDATE/USE,无 CLEAR/DISPOSE)。

> 补齐项仍以"实际 intent_code 存在"为准(实现时对 `configService.getAllIntents` 校验,不存在的不放进表 → 该写无读孪生时,VETO_WRITE 走"抑制"而非"转读")。
> 注:`INVENTORY_CLEAR` 缺读孪生**不影响**核心案例 `不用查库存了` —— 它走 `VETO_READ`(§6.2.2)剔全部候选→澄清,根本不用读孪生;读孪生只在 `VETO_WRITE` 用。

### 5.2 公开方法 (纯函数,易单测)
> ⚠️ 审计 finding 5:`IntentMatchResult.CandidateIntent`(IntentMatchResult.java:177)**只有标量**(`intentCode`/`confidence:Double`/`matchScore:Integer`/…),**没有 `.config` 字段也没有 `sensitivityLevel`**。`WriteGuardService.isWriteIntent(AIIntentConfig)` 同时读 `getSensitivityLevel()`(HIGH/CRITICAL→写)和 `getIntentCode()` —— 只靠 CandidateIntent 重建会漏掉 sensitivity 写信号。因此**必须传入 config 解析器**,在安全过滤时把 code 还原成 `AIIntentConfig`(沿用 IRP:652 现有 `configService.getIntentConfigByCode(factoryId, code)` 模式)。多意图侧的 `MultiIntentResult.SingleIntentMatch` 同样只有 code+confidence,用同一解析器。

> ⚠️ 审计 finding 2:`queryActionType` 的类型是 **`IntentKnowledgeBase.ActionType { QUERY, CREATE, UPDATE, DELETE, AMBIGUOUS, UNKNOWN }`**(`detectActionType(String)` 的返回,IntentKnowledgeBase.java:8257/7831)—— **没有 `READ` 枚举值**。读用 `QUERY` 表示。**不要** import `ToolExecutor.ActionType`(那个有 READ 但不是这里的返回类型)。

```java
/** 否定/孪生重排的统一决策。返回处理后的候选列表(不修改入参)。 */
List<CandidateIntent> applyNegationVetoAndTwinRerank(
        List<CandidateIntent> candidates,            // 已按分数降序
        NegationInfo negation,                       // 预处理产出(可能 kind=NONE)
        IntentKnowledgeBase.ActionType queryActionType,  // detectActionType(原始输入);读=QUERY,非 READ
        java.util.function.Function<String, AIIntentConfig> configResolver);  // code → AIIntentConfig(IRP 已绑 factoryId)

/** 否定否决是否触发到"全部候选被剔除" → caller 应返回澄清而非空执行。 */
boolean isVetoToClarification(List<CandidateIntent> original, List<CandidateIntent> afterPolicy, NegationInfo negation);

String readTwinOf(String writeIntentCode);   // 无则返回 null
```

---

## 6. 组件设计

### 6.1 否定分类法 (NegationInfo.kind)
预处理把否定细分为三类(新增 `enum NegationKind { NONE, EXCLUDE_CONTENT, VETO_WRITE, VETO_READ }`):

| kind | 触发模式(示例) | 例 | 处理 |
|---|---|---|---|
| `EXCLUDE_CONTENT` | `除了\|排除\|不包括\|不含\|除开` + 内容 | "查订单除了已完成" | **现有逻辑不动**(内容过滤) |
| `VETO_WRITE` | `(别\|不用\|不要\|甭\|无需\|不必\|先不\|暂时不)` + **写动词**(开始/创建/完成/暂停/删除/发货…) | "别开始生产了" | → **转读孪生**(显示状态),`readTwinOf(write)`;无孪生则抑制+澄清 |
| `VETO_READ` | `(别\|不用\|不要\|甭\|无需\|不必\|先不\|暂时不)` + **读动词**(查/看/显示/统计/看看) 或 `别给我看…` | "不用查库存了"、"别给我看订单" | → **抑制 + 追问澄清**(不显示、不写) ← 用户已选定 |

**关键区分(R2)**:`取消/作废/撤销/删除/停止` 这类**本身是动作动词**(用户**要**执行的写),**不算"否定查询"** —— 它们不带前置否定副词(别/不用),`detectNegationVeto` 必须只在"否定副词 + 动词"结构下触发,`取消订单`(无前置否定副词)→ `kind=NONE` → 保持为合法写。

### 6.2 组件 1 — 否定否决规则 + 安全不变量

`applyNegationVetoAndTwinRerank` 在 `negation.kind` 为 `VETO_*` 时:

1. **`VETO_WRITE`**:对每个写候选 `w`,若 `readTwinOf(w)` 存在 → 用读孪生替换(分数继承);若不存在 → 从候选中剔除 `w`。读候选保留。
2. **`VETO_READ`**:**剔除全部候选**(用户明说不要看/不要查)。
3. **安全不变量(铁律)**:`VETO_*` 触发后,产出候选中**绝不能含写意图**(`WriteGuardService.isWriteIntent==true`)。实现末尾对结果做一次断言式过滤:`result.removeIf(c -> writeGuard.isWriteIntent(configResolver.apply(c.getIntentCode())))`(用 §5.2 的 configResolver 把 code 还原成 config —— CandidateIntent 无 `.config`,见 finding 5)。这保证 `不用查库存了` **永不**产出 `INVENTORY_CLEAR` —— 即使上游别的分支重新引入写候选。configResolver 返回 null 时(code 无 config)按"非写"放行(此时由 hasWriteSuffix(code) 兜一层:`isWriteIntent(null)` 返 false,故对 null 额外补 `|| writeGuard.hasWriteSuffix(c.getIntentCode())`)。
4. **空集 → 澄清**:`VETO_READ` 或 `VETO_WRITE` 剔空后,`isVetoToClarification` 返 true,caller 返回 `NEED_MORE_INFO` + 文案 `"您是要取消这次操作吗?需要我帮您查询或处理什么?"`(防呆:dead-end 改追问)。

### 6.3 组件 2 — 读写孪生排序 (margin 门控)

仅当 `negation.kind == NONE`(否定路径由组件 1 处理)且 `queryActionType == QUERY`(读措辞 —— `IntentKnowledgeBase.ActionType.QUERY` 即 READ 等价,见 finding 2;无写动词):

- **单意图**:若 top 候选是写(`isWriteIntent`)且存在一个读候选 `r`,满足 `topScore - r.score <= TWIN_RERANK_MARGIN` → 把 `r` 提为 top。
  - 验证案例:`生产进度怎么样` → `PROCESSING_BATCH_COMPLETE 0.76` vs `REPORT_PRODUCTION 0.74`(margin 0.02)→ 提 `REPORT_PRODUCTION` 为 top。
- **多意图**:read-phrased 查询若产出写+读混合候选,**剔除**那些"在 margin 内存在读替代"的写候选(减少多余确认弹窗)。保守:仅当至少有一个读候选在场、且查询无写动词时才剔。
- `TWIN_RERANK_MARGIN` 起始 `0.10`(略宽于 SR 的 0.08,因这里读写不必是注册孪生对),**对 golden 调参**。
- **绝不**降级明确写动词意图:`完成生产`/`暂停生产`(`detectActionType` 返 `UPDATE`/`DELETE` 等非 `QUERY`)→ 组件 2 不触发,保持为写。
- **绝不**跨业态/跨领域乱提:只在同一候选列表内重排,不引入新意图。

### 6.4 否定检测扩展 (R1/R2)

`QueryPreprocessorServiceImpl`:
- **新增** `VETO_PATTERN`(与现有 exclusion `NEGATION_PATTERN` 分开,不动后者以保 exclusion 行为):
  ```
  VETO_PATTERN = (别|不用|不要|甭|无需|不必|先不|暂时不|不想|不需要)\s*(给我)?\s*(查|看|显示|统计|看看|要|开始|创建|完成|暂停|删除|发货|入库|出库|调拨|审批|...)?
  ```
- 新增 `detectNegationVeto(input)`:判定 `VETO_WRITE` vs `VETO_READ`(看否定副词后跟的是写动词还是读动词;用 `knowledgeBase.detectActionType` 或动词词表)。
  - **⚠️ 双重否定守卫(finding 6,必须)**:`detectNegationVeto` **第一步**先 `if (DOUBLE_NEGATIVE_PATTERN.matcher(input).find()) return NegationKind.NONE;`。否则 `不是不想查库存` 含子串 `不想` → `VETO_PATTERN.find()`(子串锚定非 `matches()`)会误命中 `不想查` → 错判 `VETO_READ`。当前代码两个 detector(detectNegationSemantics @1473、convertDoubleNegative @1481)**互相独立、无优先级**,必须由本守卫显式建立"双重否定优先于 veto"。
- **⚠️ 两个 NegationInfo 类型(finding 4)**:
  - 接口 `QueryPreprocessorService.NegationInfo`(:334,Lombok `@Builder`)—— 加 `kind` 字段经 builder **安全**(唯一构造点 @1506 是 builder)。
  - 内部 `QueryPreprocessorServiceImpl.NegationInfo`(:1869,**plain class**,显式 3-arg ctor `(boolean,String,String)`,被 detectNegationSemantics 在 805/812/814 调 3 次)—— 加 `kind` 会**断这 3 处**。修法:保留 3-arg ctor(默认 `kind=NONE`)+ 加 4-arg ctor;或重载。spec 实现 task 必须显式改这 3 个 call site。
- `NegationInfo` 加字段 `NegationKind kind`(默认 `NONE`)。**兼容公式(完整)**:`hasNegation()` 的返回**不变**(仍 = 原 `hasNegation` boolean,由 `NEGATION_PATTERN` exclusion 驱动);`kind` 是**新增正交字段**,exclusion 命中→`kind=EXCLUDE_CONTENT`,VETO 命中→`kind=VETO_*`,都不命中→`kind=NONE`。
- **向后兼容(已核验 finding 4)**:接口 `getNegationInfo()` 仅 2 个 caller(IRP:648、:5468),都 gate `convertNegationIntent`;这两处**本来就要改成委托 `NegationTwinPolicy`**(§6.5),是预期改动。其余消费者(内部 hasNegation() @1474/@1505 的 exclusion 路径)行为不变,因为 `NEGATION_PATTERN`/`detectNegationSemantics` 不动。

### 6.5 插入点

> **⚠️ 架构关键(finding 7,WRONG→已修)**:否定否决**不能只放在下游识别出口** —— `IRP` 有**三个早退写短路**会在到达下游 policy **之前**就 `return` 一个写:
> | 早退路径 | 位置 | 对 `别开始生产`/`不用创建` 的行为 |
> |---|---|---|
> | v33.1 早期短语匹配(raw input,contains) | IRP ~456-482 | `别开始生产` contains `开始生产` → 返 `PROCESSING_BATCH_START` 写,无 actionType/否定检查 |
> | v22.0 verb-noun 短路 | IRP ~592-625 | `isNegatedVerb` 只查 `未/没/没有/非`,**不含 别/不用` → `别开始生产` 当成写返回 |
> | doRecognize 内 verb-noun 短路 | IRP ~1010-1045 | 返写,无否定检查 |
>
> 故**必须加早期 VETO 前置门**(在 ~456 之前,紧跟预处理/否定检测之后):

| 路径 | 位置 | 改动 |
|---|---|---|
| **早期 VETO 前置门(新增)** | IRP 预处理后、~456 早期短语匹配**之前** | `if (kind==VETO_READ) return 澄清结果(NEED_MORE_INFO)` 立即返回(零写风险);`if (kind==VETO_WRITE) negationVetoWrite=true` 设标志 → **守卫**三个早退写短路块(`if(!negationVetoWrite && ...)`)使其跳过 → 落到 doRecognize + 下游 policy 做"写→读孪生" |
| 单意图下游 | `IRP` 647-655 否定转换块 | 替换为 `negationTwinPolicy.applyNegationVetoAndTwinRerank(...)`;`VETO_WRITE` 转读孪生 / 剔空→澄清;**组件 2**(kind==NONE)读写孪生排序也在此 |
| 多意图 | `recognizeMultiIntent`(664/669) | ① 该方法**当前不做否定检测**(finding 3)—— 需在此调预处理/否定检测拿 `NegationInfo`;② `VETO_READ` → `isMultiIntent=false` + 澄清单意图;③ 构建 `intents` 后应用 policy(组件 2 剔写孪生)。注:`MultiIntentResult.SingleIntentMatch` 只有 code+confidence,用 configResolver 还原 config |
| 委托(消重) | `convertNegationIntent`(5433)、`SemanticRouter.READ_WRITE_TWIN_PAIRS`(113) | 改为委托 `NegationTwinPolicy`(单一事实源) |

---

## 7. 数据流

**单意图** `不用查库存了`(VETO_READ):
预处理 → `detectNegationVeto` → `VETO_READ`(否定副词`不用`+读动词`查`)→ **早期 VETO 前置门**(§6.5,在早退写短路**之前**)直接 `return` 澄清结果 `NEED_MORE_INFO`「您是要取消这次操作吗?...」。**根本不进** doRecognize / 短语匹配 / verb-noun 短路 → `INVENTORY_CLEAR` 永不被识别,零写风险。

**单意图** `别开始生产了`(VETO_WRITE):
预处理 → `VETO_WRITE`(否定副词`别`+写动词`开始`)→ 早期门设 `negationVetoWrite=true` → 守卫跳过三个早退写短路(否则 ~592 verb-noun 会把 `PROCESSING_BATCH_START` 当写直接返回)→ doRecognize 产候选 → 下游 policy `readTwinOf(PROCESSING_BATCH_START)=PROCESSING_BATCH_LIST` → 显示状态,不执行写。

**多意图** `生产进度怎么样`(read-phrased, `kind=NONE`, actionType=QUERY):
recognizeMultiIntent 产 `[PROCESSING_BATCH_COMPLETE(w), REPORT_PRODUCTION(r), PROCESSING_BATCH_START(w)]` → 组件2:有读候选 `REPORT_PRODUCTION` 在 margin 内 → 剔写孪生 → `[REPORT_PRODUCTION]` → 单读执行,无确认弹窗。

**合法写** `取消订单`(`kind=NONE`,detectActionType=`DELETE`):
`取消`无前置否定副词 → `detectNegationVeto` 返 `NONE`。实际在 IRP ~592 verb-noun 短路命中(`取消+订单`→`ORDER_DELETE` conf 0.85,verbIdx=0 故 isNegatedVerb 跳过)→ **早退返回 `ORDER_DELETE` 写**,根本不进否定/孪生 policy(组件 2 也只在 kind==NONE+QUERY 触发,此处 actionType=DELETE 不触发)→ 保持写 + W0 护栏确认。**结果正确**(应保持写);policy 不介入是预期。

---

## 8. 错误处理 / 失败安全

- `NegationTwinPolicy` 任何异常 → **fail-open 到原候选列表**(不因策略 bug 阻断识别),但记 WARN。注意:fail-open 到原列表时**安全不变量仍由 W0 写护栏在执行层兜底**(护栏不依赖本策略)。
- 空候选 → 澄清,**绝不**静默成功、**绝不**默认执行任何写。
- 线程安全:无状态、不可变表、不读 ThreadLocal(可在 `@Async` shadow / worker 调用)。

---

## 9. 边界案例 (必进单测)

| 输入 | 期望 | 理由 |
|---|---|---|
| `不用查库存了` | 早期门抑制→澄清,**无** `INVENTORY_CLEAR` | VETO_READ 核心案例 |
| `别给我看订单` | 早期门抑制→澄清 | VETO_READ |
| `别开始生产了` | → `PROCESSING_BATCH_LIST`(读孪生),**不** short-circuit 成写 | VETO_WRITE 经早期门守卫 verb-noun 短路(finding 7) |
| `不用创建订单` | 不 short-circuit 成 `ORDER_CREATE` 写;转读孪生/抑制 | VETO_WRITE 早期门必须挡住 ~592 verb-noun 写短路 |
| `取消订单` | 保持 `ORDER_DELETE`(写)+护栏 | 动作动词无前置否定副词;verb-noun 短路返写(预期) |
| `作废这张单` | 保持写 | 同上 |
| `查订单除了已完成的` | 现有 exclusion 不变,`kind=EXCLUDE_CONTENT` | exclusion 路径不动 |
| `生产进度怎么样` | 偏读 `REPORT_PRODUCTION` | 组件2 read-phrased(actionType=QUERY) |
| `完成生产` | 保持写 `PROCESSING_BATCH_COMPLETE` | 写动词(detectActionType=UPDATE≠QUERY),组件2 不触发 |
| `暂停生产` | 保持写 `PROCESSING_BATCH_PAUSE` | 写动词 |
| `不是不想查库存` | 双重否定 → 查询(不抑制),`kind=NONE` | detectNegationVeto 首步 DOUBLE_NEGATIVE 守卫返 NONE(finding 6) |
| `不能不查质检` | 双重否定 → 查询(不抑制) | 同上,`不` 系列不得被 VETO 误吞 |
| `查库存和今天的销售` | 多意图正常拆,不被组件2乱剔 | 真复合查询,非过度生成 |
| 空候选 + VETO | `NEED_MORE_INFO` 澄清 | dead-end 改追问 |

---

## 10. 测试策略

- **单测**:`NegationTwinPolicyTest`(纯函数,覆盖 §9 全表)+ `QueryPreprocessorNegationTest`(VETO_PATTERN 词表 + kind 判定,含双重否定不误触)。
- **回归 golden**:跑现有意图识别 golden,确保 exclusion / 写动词 / 普通读路径**零回归**。
- **live 验证**(test 10011 真实 F001 认证多意图):§1.1 全部案例复跑,断言 `不用查库存了` 不再产 `INVENTORY_CLEAR`、`取消订单` 仍是写、`生产进度怎么样` 偏读。餐饮(qhj/RES)侧抽样验证无回归。
- **CI**:e2e-pr-gate(若 pre-existing broken 则 admin-merge,但本 PR 纯 Java 必须先确认 backend 启动绿)。

---

## 11. Rollout

**实施 task 顺序(early-gate 是独立关键 task,不能与下游 policy 混)**:
1. T1 `NegationTwinPolicy`(新 service:canonical twin 表 + 纯函数 + configResolver 签名)+ 单测。
2. T2 `QueryPreprocessor` 否定检测扩展(VETO_PATTERN + `detectNegationVeto` + 双重否定守卫 + `NegationKind kind` + 两个 NegationInfo 类型的 ctor 改动)+ 单测。
3. T3 **早期 VETO 前置门**(IRP ~456 之前:VETO_READ 立即澄清 / VETO_WRITE 设标志守卫三个早退写短路)—— **finding 7 的核心修复,单独 task 单独 review**。
4. T4 下游 policy 接线(单意图 647-655 + 多意图 recognizeMultiIntent 加否定检测 + 委托消重 convertNegationIntent/SemanticRouter)。
5. T5 回归 golden + live 验证脚本。

- worktree `feat/intent-negation-twin` off `origin/main`(已建)。subagent-driven(每 task fresh implementer + spec review + code-quality review)。
- **5-agent 对抗终审**(安全攸关分类器):重点查 (a) 三个早退写短路真被 VETO_WRITE 守卫挡住(`别开始生产`/`不用创建` 不 short-circuit 成写)、(b) 否定误吞合法写(`取消订单` 仍写)、(c) 双重否定守卫真生效、(d) 安全不变量(VETO 后无写候选)、(e) 多意图剔候选不误伤复合查询、(f) fail-open 不破识别、(g) 两个 NegationInfo ctor 改动不漏 call site。
- PR → `git diff origin/main...HEAD --stat` 确认 scope 干净 → merge main。
- 从 **main** 部署 test(10011)→ live 验证 → 从 main 部署 prod(蓝绿)→ live 验证 → 核对运行 jar 含改动。
- 无需 feature flag:改动 fail-open + W0 护栏兜底,风险低;保留"`NegationTwinPolicy` 异常→原列表"的天然降级。**注**:早期门 VETO_READ 立即返回是**唯一非 fail-open 点**(它主动短路),故该门的判定必须严格(双重否定守卫 + 仅"否定副词+读动词"结构),终审重点核。

---

## 12. 风险

| 风险 | 缓解 |
|---|---|
| 否定误吞合法写(`取消订单`被当否定) | `detectNegationVeto` 只在"否定副词+动词"结构触发;`取消`无前置副词→NONE;§9 单测锁 |
| **早退写短路绕过否决**(`别开始生产`→写) | **早期 VETO 前置门 + negationVetoWrite 守卫三个早退块**(finding 7);§9 `别开始生产/不用创建` 单测锁 |
| 组件2 误降级真实写 | `queryActionType==QUERY` 门控 + margin 门控 + 写动词排除;只重排不引入新意图 |
| 多意图剔候选误伤真复合查询 | 仅 read-phrased + 有读替代在 margin 内才剔;`查库存和销售` 单测锁 |
| 双重否定 `不是不想查` 被新 VETO 误触 | `detectNegationVeto` 首步 `DOUBLE_NEGATIVE_PATTERN.find()→NONE`(finding 6,代码建立优先级);单测锁 |
| `NegationInfo` 加 `kind` 漏改内部 ctor 3 处 call site | 保留默认-NONE 3-arg ctor + 加 4-arg(finding 4);编译即暴 |
| 策略 bug 阻断识别 | fail-open 到原列表 + W0 护栏执行层兜底(早期门 VETO_READ 立即返回除外,须严格判定) |

---

## 13. 并行工作建议

### Subagent: ✅ 适合
- T1 `NegationTwinPolicy`(新文件)+ 单测 与 T2 `QueryPreprocessor` 否定检测扩展 可由不同 implementer 并行(`NegationKind enum` + `NegationInfo.kind` 字段契约先定)。
- T3 早期 VETO 前置门 + T4 下游 policy 接线 需在 T1/T2 完成后,**串行**(都改 `IntentRecognitionPipelineServiceImpl`,且 T4 依赖 T3 的标志)。T3 单独 review(finding 7 核心)。

### 多Chat: ❌ 不适合
- 全部集中在 `IntentRecognitionPipelineServiceImpl` + `QueryPreprocessorServiceImpl` 两个共享大文件,多 chat 并行有覆盖冲突风险。单 chat + worktree 隔离。
