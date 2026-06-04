# 设计 — 餐饮 chat 多轮 Phase 2a：门店实体续接 (那家店的<指标>)

**日期**: 2026-06-04
**分支**: `feat/restaurant-store-coref-p2a` (off `origin/main`)
**接续**: Phase 1 (#510 持久化 + #513 顺序 + #518 近N个月) 已 shipped。本 spec 是 `docs/superpowers/specs/2026-06-04-restaurant-multiturn-context-design.md` §7 列的 Phase 2 的第一刀 (2a)。
**审计**: 设计经 8-agent superpowers 对抗审计 (run `wf_549aa331-23e`, NEEDS_REWORK→本版已并入 DC1-DC10 + R1-R6)。下文 file:line 多来自该审计核对。
**关联**: `project_2026_06_04_restaurant_multiturn_context_shipped`, `.claude/rules/fool-proof-design.md`, `feedback_self_evidence_disqualified_cross_verify_required`, `feedback_worktree_main_only_deploy`。

---

## 1. 要解决的问题 (用户面)

餐饮 chat 第二轮"门店指代 + 门店指标"续接：

| 第一轮 | 第二轮 | 期望 | 现状 |
|---|---|---|---|
| 哪家店业绩最好 | 那家店的客单价呢 / 这家的客单价呢 | 该(#1)门店的 营收/单数/客单价 | ❌ 门店指代未消解, 答全局 or 误路由 |

用户原话点明逻辑: "哪家店业绩最好" → 系统报出 #1 门店 → "那么这家的客单价呢"，其中 **这家/那家 指那家业绩最好的店**。指代词可裸用 (`这家`，无"店")。

**范围**: 仅"门店实体 + 门店指标 (营收/单数/客单价/业绩)"续接，复用现有门店排行数据。

---

## 2. 范围边界

**本 spec (2a) 做**: 门店实体续接 (案例4)。
**不做**:
- 案例3 `它的趋势怎么样` (= 该菜的月度销售趋势) —— 需新建 per-dish 月度趋势 gold (Python ETL + Java 工具) → **Phase 2b 独立 spec**。
- 任意非 top-N 单店查询 (需 per-store gold endpoint) → backlog。
- RAG 改道 —— 案例4 不含 `怎么样`，本就到 `recognizeIntentWithConfidence`，无需改 (DC8 已核实)。

---

## 3. 关键事实 (审计核对，纠正初稿)

1. **客单价不是 gold 字段** (B2/DC1): Python `finance_summary` 的 `top_stores` 行只含 `{store_id, store_name, revenue, bill_count}` (`backend/python/smartbi/gold/queries.py:808-816`)；客单价由 Java `deriveAvgTicket` 从 revenue÷bill_count 现算 (`RestaurantStoreRevenueRankGoldTool.java:~135`)。→ revenue+bill_count 已在行内，**无需 ETL**，但 spec 不得写"客单价已在行内"。
2. **门店排行工具无 store_name 过滤、top-N 写死 5** (B1/DC2): `getParametersSchema` 仅 `month` (lines 43-56)；`queryGold` 调 `gold.fetchFinanceSummary(factoryId, start, end, 5)` (line 64)。
3. **两套 coref 服务串行** (B4/DC4): 管线 else 分支先跑 `CoreferenceResolutionService.resolve()` (`IntentRecognitionPipelineServiceImpl.java:535`→`3768`)；其 `inferSlotType('那家店')` 返 null (`CoreferenceResolutionServiceImpl.java:492`，**不会**误判 SUPPLIER)，且 DISTAL 引用被槽位兜底跳过 (line 457 仅 PROXIMAL/PRONOUN)。**之后**跑 `queryPreprocessorService.preprocess` (line 536)，其内部调 `ConversationMemoryServiceImpl.resolveReference()` (`QueryPreprocessorServiceImpl.java:470`)，该 SUPPLIER 模式含 `那家|这家` (`ConversationMemoryServiceImpl.java:238`)。注入工具的引用来自 `PreprocessedQuery.resolvedReference` (第二套)。
4. **EntitySlot 无 STORE** (B3): `EntitySlot.java:29-50` 有 BATCH/SUPPLIER/CUSTOMER/PRODUCT/MATERIAL_TYPE/TIME_RANGE/WAREHOUSE/EQUIPMENT/EMPLOYEE/ORDER。
5. **实体捕获用 resultData，不用 affectedEntities** (B5/R6/DC5): affectedEntities 是写审计字段 (`action=CREATED/UPDATED/DELETED`)，0 个工具填它；`mapEntityTypeToSlotType` 无 STORE 分支 (default→null, line 1620)；resultData 兜底只读 `content`/List，不读嵌套 `门店营收排行`；`extractSlot` 只 set `.id()` 不 set `.name()` (line 1595)；Java `format()` 循环**丢弃 store_id** (line 84-91)。
6. **客单价路由已对** (DC8): 客单价 → `RESTAURANT_STORE_REVENUE_RANK` (phrase `IntentKnowledgeBase.java:7080` + keyword `V20260917_01`)。交接文档"→ORDER_STATISTICS 错"是过期信息。
7. **`doExecute(factoryId, params, context)` 有 context 参数** (`GoldBackedRestaurantTool.java:133`) 但它是 final 模板方法只转发 factoryId+params 给 `queryGold`，故 **params 是唯一载体**；`ToolDispatchService` 已注入 `conversationMemoryService` (line 116) 且在 line 193-208 有 entityType switch (现仅 BATCH/SUPPLIER/PRODUCT)。

---

## 4. 设计

### 4.1 数据流 (端到端)

```
第一轮 "哪家店业绩最好"
  → RESTAURANT_STORE_REVENUE_RANK → RestaurantStoreRevenueRankGoldTool
  → fetchFinanceSummary(top_n = 全部门店, 见 4.4) → format(): 每行带 store_id(新)+门店名+营收+单数+客单价(派生)
  → resultData.top_store = {id, name, revenue, bill_count, avg_ticket}  (新)
  → orchestrator updateConversationMemory → extractAndUpdateEntitySlots 读 resultData.top_store
     → EntitySlot.store(id,name) 存入 STORE 槽 (靠 Phase1 持久化, 短路路径也写)

第二轮 "那家店的客单价呢" / "这家的客单价呢"
  → maybeAugmentContinuation 返 null (含领域名词 店/客单价, gate3 拒) → else 分支 coref 照跑
  → coref (两套, 槽位门控 STORE 优先): 那家/这家/那家店 → STORE 槽门店名
     → PreprocessedQuery.resolvedReference = {type=STORE, name=门店名, id=store_id}
  → 识别: 客单价/营收/单数/业绩 → RESTAURANT_STORE_REVENUE_RANK
  → ToolDispatchService switch case STORE → params.store_name = ref.name (+ store_id)
  → 工具 store_name 非空 → 过滤 fetch 行到该店一行 → 返该店 营收/单数/客单价
```

### 4.2 统一 coref 规则 (槽位门控)

> `那家|这家|那家店|这家店|该店|那个店|这个店|该门店` → 解析成 **STORE**，**仅当 STORE 槽已填**；否则回退 **SUPPLIER**（行为不变）。

- STORE 槽只在餐饮门店排行轮后存在 → 工厂租户永无 STORE 槽 → **业态天然安全，工厂 CRM 的 SUPPLIER coref 零回归** (解 R3)。
- 覆盖裸 `这家/那家` (用户点明) 与带"店"形式。
- 两套服务都按此规则改 (DC4)，并以**第二套** (`ConversationMemoryService.resolveReference` 经 QueryPreprocessor) 产出的 `resolvedReference` 作为注入工具的单一事实源；第一套 (`CoreferenceResolutionService`) 仅需"不破坏文本" (给 DISTAL 加槽位兜底或令其对门店指代 no-op 不误替换)，避免它在第二套之前污染输入 (解 R5)。

### 4.3 组件清单 (DC3-DC9)

| # | 文件 | 改动 |
|---|---|---|
| DC3 | `dto/conversation/EntitySlot.java` | `SlotType` 加 `STORE`；加 `EntitySlot.store(String id,String name)` 工厂 (仿 batch()/supplier()) |
| DC6 | `RestaurantStoreRevenueRankGoldTool.java` format 循环 (~84-91) | 每行加 `entry.put("store_id", row.get("store_id"))` (Python 已返, 现被丢) |
| DC2 | 同上 `queryGold` | top_n 由 5 调到覆盖全部门店 (e.g. `fetchFinanceSummary(...,50)`)；schema 加可选 `store_name`；非空时 format 过滤到该店一行 (找不到→优雅空, 不报错) |
| DC5a | 同上 | 填 `resultData.top_store = {id,name,revenue,bill_count,avg_ticket}` (#1 行) |
| DC5b | `IntentExecutionOrchestrator.mapEntityTypeToSlotType` (1612-1622) | 加 `case "STORE" -> SlotType.STORE` |
| DC5c | `IntentExecutionOrchestrator.extractAndUpdateEntitySlots`/`extractSlot` (1567-1605) | 读 `resultData.top_store` → `EntitySlot.store(id,name)`；`extractSlot` 同时 set `.id()` 和 `.name()` |
| DC4i | `ConversationMemoryServiceImpl` (~60-100, 237-238) | 加 `STORE_REFERENCE_PATTERN`；`resolveReferences` 中 STORE 在 SUPPLIER **之前**，且 STORE 仅当 STORE 槽存在才命中 |
| DC4ii | `CoreferenceResolutionServiceImpl` (457, 476-492) | `inferSlotType` 加 店/门店/裸指代→STORE (STORE 槽存在时)；给槽位兜底条件加 `ReferenceType.DISTAL`；保证不在第二套前误替换 |
| DC7 | `ToolDispatchService` (~208) | switch 加 `case "STORE": params.put("store_name", ref.getEntityName()); break;` |
| DC9 | `IntentExecutionOrchestrator` (store-metric 意图路径) | **防呆 Rule 5**: 输入含门店指代标记 (coref 命中) 但无 STORE 槽 → 返 CLARIFICATION `请问您指的是哪家店？`(可列上一轮门店)，不静默走全局 top-N |

### 4.4 门店过滤实现 (DC2 细化)

- 续接实体 = 第一轮 #1 门店 (来自 STORE 槽，已知)；它必在 fetch 结果内。
- 工具 `store_name` 参数非空 → format() 在已 fetch 的行里过滤出该店一行 (按 store_id 优先，名次之，防重名 R2)。
- top_n 调到 ≥ 全部门店 (~19→取 50 冗余)，消除"引用门店落在 top-5 外"风险。
- **不**加 Python store_name WHERE (DC2 推荐 a 方案，零 Python 改动)。

---

## 5. 路由 & 风险护栏

- **R1 (客单价 意图撞车)**: 客单价 同时是 `RESTAURANT_STORE_REVENUE_RANK` (V20260917_01) 与 `INDICATOR_QUERY/SMART_INDICATOR_QUERY` (V20260823_04:25, V20260825_07:41 AVG_TICKET_PRICE) 关键词。餐饮租户 phrase 短路应胜，但须**回归断言**: 真实 RESTAURANT 租户下 post-coref `<门店名>的客单价呢` → `RESTAURANT_STORE_REVENUE_RANK`，**非** INDICATOR_QUERY。
- **R5 (双 coref 互踩)**: line 535 (第一套) 在 line 536 (第二套) 前改 `processedInput`。须在真实路径核对 `那家店的客单价呢` 经过 535 后的字符串，确保第二套仍能产出正确 resolvedReference。
- **R2 (排名两轮间漂移 / 重名)**: 门店从槽位取 (round-1 #1 已定)，按 store_id 键控。
- **R3 (SUPPLIER 回归)**: 槽位门控 (4.2) 保证工厂无 STORE 槽 → SUPPLIER 不变。
- **R4 (Flyway 撞号)**: 本 spec 若加路由/keyword 迁移，merge 后部署前查 `origin/main` 最新号 (现 ≥ V20260917_01) 防静默跳过。

---

## 6. 测试 (DC10)

### 单元
- coref 优先级 (新建 `CoreferenceResolutionServiceImplTest` / `ConversationMemoryServiceImplTest`，今缺): STORE 槽存在时 `那家/这家/那家店` → STORE；STORE 槽不存在时 `那家供应商/那家公司/上家` 仍 → SUPPLIER (不回归)。
- `mapEntityTypeToSlotType("STORE")` → STORE；`extractSlot` set name+id。
- 工具: `store_name` 非空 → 返该店一行 (营收/单数/客单价)；找不到 → 优雅空。
- `RestaurantStoreRevenueRankGoldTool` format 带 store_id + resultData.top_store。

### 回归 (硬闸)
- `IntentParityTest` 70/70 + `IntentGoldenAssertionTest` 15/15 绿。

### 真实会话集成 (live, 必须)
- prod qhj_sales_mgr@RES_3101_009 两轮: `哪家店业绩最好` → `那家店的客单价呢` / `这家的客单价呢`。
- 判据: (a) 第一轮后 STORE 槽写入 (DB conversation_memory entity_slots 含 STORE)；(b) 第二轮 `store_name` 参数真到达工具 (日志)；(c) 返 **#1 门店那一行** 的 营收/单数/客单价 (非全局 top-N)；(d) intentCode = `RESTAURANT_STORE_REVENUE_RANK` (非 INDICATOR_QUERY)；(e) 无 STORE 槽时 (新 session 直接问 `那家店的客单价`) → 反问 `请问您指的是哪家店？`。
- 复用 `scripts/qa-multiturn-context-probe.sh` 模式 (qhj_sales_mgr/123456, 服务器 localhost 绕 SSH RST, 蓝绿端口探测)。

---

## 7. 流程 / 部署

worktree `feat/restaurant-store-coref-p2a` (off origin/main) → PR → 合 main (合前 `git diff origin/main...HEAD --stat` 查 scope + Flyway 撞号查) → 从 main 部署 prod → live 验收 → 核对运行 jar。单文件 `git commit -- <file>` 防并发污染。

---

## 8. 验收标准

1. 单测全绿 (新增 coref/工具/捕获 + 回归 70/70 + 15/15)。
2. live: 第一轮写 STORE 槽；第二轮门店指代解析 + `store_name` 到达工具 + 返 #1 门店单行 + 路由 `RESTAURANT_STORE_REVENUE_RANK`；无槽位 → 反问。
3. 工厂租户 SUPPLIER coref 零回归 (槽位门控)。
4. 从 main 部署 prod，运行 jar 含改动。

---

## 9. Phase 2b 预告 (不在本 spec)

案例3 `它的趋势` = 该菜月度销售趋势：需 Python gold 新聚合 (per-dish × month sales) + 新 Java 工具 (restaurant_dish_trend_gold) + DISH 实体续接 (复用本 2a 的 coref/槽位/注入机制，把 STORE 换 DISH)。2a 的 coref+实体捕获+param 注入框架是 2b 的复用底座。
