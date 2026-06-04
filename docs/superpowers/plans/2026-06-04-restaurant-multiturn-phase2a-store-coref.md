# 餐饮 AI chat 多轮续接 Phase 2a（门店实体续接）TDD 实现计划

**目标**：第二轮「门店指代 + 门店指标」续接生效：`哪家店业绩最好` -> `那家店的客单价呢` / `这家的客单价呢` 返回第一轮 #1 门店那一行的营收、单数、客单价。复用现有 `finance-summary.top_stores`，不新增 Python gold ETL。

**权威输入**：
- `docs/handoff/2026-06-04-phase2a-store-coref-codex-handoff.md`
- `docs/superpowers/specs/2026-06-04-restaurant-multiturn-phase2a-store-coref-design.md`

**硬约束**：TDD、小步提交、`git commit -- <file>` 控制 scope；回归硬闸 `IntentParityTest` 70/70 + `IntentGoldenAssertionTest` 15/15；prod 只从 main 部署；live 验收必须真实 execute + 真实 session。

---

## 当前代码事实

- Phase 1 持久化已在 `IntentExecutionOrchestrator.executeWithExplicitIntent` 尾部调用 `persistConversationMemoryForExplicitIntent(...)`，短路路径会进入 `updateConversationMemory -> extractAndUpdateEntitySlots`。
- `GoldBackedRestaurantTool.doExecute(...)` 是 final 模板方法，`format(Map<String,Object> goldResult)` 不接收 params。因此 `store_name/store_id` 要在 `RestaurantStoreRevenueRankGoldTool.queryGold(...)` 从 `params` 复制进返回的 `goldResult`，再由 `format(...)` 使用。
- `ToolDispatchService` 当前没有 `ConversationMemoryService` 依赖，但已从 `PreprocessedQuery.resolvedReferences` 注入 BATCH/SUPPLIER/PRODUCT；STORE 只需加同类 switch case。
- 第一套 `CoreferenceResolutionServiceImpl` 当前对 `那家店` 不推断 slot type，且 DISTAL 不走最近槽位兜底，默认会原样透传给第二套。优先不改，后续 live 验证。

---

## Task 1：EntitySlot 增加 STORE

**文件**：`backend/java/cretas-api/src/main/java/com/cretas/aims/dto/conversation/EntitySlot.java`

**先写测试**：`backend/java/cretas-api/src/test/java/com/cretas/aims/dto/conversation/EntitySlotStoreTest.java`

测试断言：
```java
EntitySlot slot = EntitySlot.store("101", "人民广场店");
assertThat(slot.getType()).isEqualTo(EntitySlot.SlotType.STORE);
assertThat(slot.getId()).isEqualTo("101");
assertThat(slot.getName()).isEqualTo("人民广场店");
assertThat(slot.getDisplayValue()).isEqualTo("门店 人民广场店");
assertThat(slot.getMentionCount()).isEqualTo(1);
assertThat(slot.getConfidence()).isEqualTo(1.0);
```

**实现**：
- `SlotType` 加 `STORE`。
- 加 `public static EntitySlot store(String id, String name)`，仿 `supplier(...)`，displayValue 为 `门店 ` + name。

**测试命令**：
```powershell
cd backend/java/cretas-api
& "C:\tools\apache-maven-3.9.6\bin\mvn.cmd" "-Dtest=EntitySlotStoreTest" test
```

**预期**：新增测试通过。

**提交**：
```powershell
git status --short
git commit -m "feat(ai-memory): add STORE entity slot" -- backend/java/cretas-api/src/main/java/com/cretas/aims/dto/conversation/EntitySlot.java backend/java/cretas-api/src/test/java/com/cretas/aims/dto/conversation/EntitySlotStoreTest.java
```

---

## Task 2：门店排行工具输出 store_id/top_store，并支持单店过滤

**文件**：`backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/gold/RestaurantStoreRevenueRankGoldTool.java`

**先写测试**：`backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/restaurant/gold/RestaurantStoreRevenueRankGoldToolStoreFilterTest.java`

测试覆盖：
1. `format` 的 `门店营收排行` 每行包含 `store_id`。
2. `top_store` 等于第一名，含 `store_id/门店/营收/单数/客单价`。
3. `store_id` 过滤优先于 `store_name`，只返回匹配门店单行。
4. 仅 `store_name` 时可过滤匹配单行。
5. 找不到门店时返回 `dataAvailable=false`，message 复用 `emptyMessage()`，不抛异常。

建议测试数据：
```java
Map<String,Object> goldResult = new LinkedHashMap<>();
goldResult.put("start_date", "2026-03-01");
goldResult.put("end_date", "2026-03-31");
goldResult.put("total_revenue", 3000.0);
goldResult.put("store_count", 2);
goldResult.put("top_stores", List.of(
    Map.of("store_id", 101, "store_name", "人民广场店", "revenue", 2000.0, "bill_count", 20),
    Map.of("store_id", 102, "store_name", "陆家嘴店", "revenue", 1000.0, "bill_count", 25)
));
```

**实现**：
- `getParametersSchema()` 增加可选 `store_name`、`store_id`。
- `queryGold(...)` 调 `gold.fetchFinanceSummary(factoryId, start, end, 50)`。
- `queryGold(...)` 将 `params.get("store_name")`、`params.get("store_id")` 复制进返回 map。
- `format(...)` 构造 entry 时加入 `store_id`。
- `format(...)` 根据 goldResult 的 `store_id/store_name` 过滤，优先 `store_id`。
- `format(...)` 结果中加入 `top_store`。
- 过滤后无匹配时返回 actionable empty：`dataAvailable=false`、`message=emptyMessage()`、`actionHint=...`。

**测试命令**：
```powershell
cd backend/java/cretas-api
& "C:\tools\apache-maven-3.9.6\bin\mvn.cmd" "-Dtest=RestaurantStoreRevenueRankGoldToolAvgTicketTest,RestaurantStoreRevenueRankGoldToolStoreFilterTest" test
```

**提交**：
```powershell
git status --short
git commit -m "feat(restaurant-gold): support store scoped revenue rank result" -- backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/gold/RestaurantStoreRevenueRankGoldTool.java backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/restaurant/gold/RestaurantStoreRevenueRankGoldToolStoreFilterTest.java
```

---

## Task 3：第一轮 top_store 写入 STORE 槽

**文件**：`backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java`

**先扩展测试**：`backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/IntentExecutionOrchestratorMemoryPersistTest.java`

新增测试：显式意图响应 `resultData.top_store` 包含 `store_id` 和 `门店` 时，`persistConversationMemoryForExplicitIntent(...)` 会调用 `conversationMemoryService.updateEntitySlot(sessionId, STORE, slot)`，slot 的 id/name 正确。

示例断言：
```java
verify(memory).updateEntitySlot(eq("sess-store"), eq(EntitySlot.SlotType.STORE), slotCaptor.capture());
assertThat(slotCaptor.getValue().getId()).isEqualTo("101");
assertThat(slotCaptor.getValue().getName()).isEqualTo("人民广场店");
```

**实现**：
- `mapEntityTypeToSlotType` 增加 `case "STORE" -> EntitySlot.SlotType.STORE`。
- `extractAndUpdateEntitySlots(...)` 在 content/list fallback 前读取 `response.getResultData()` 的 `top_store` map。
- `top_store` 支持 key：`store_id` / `id`；名称 key：`门店` / `store_name` / `name`。
- 调用 `EntitySlot.store(id, name)` 并 update STORE 槽。
- 泛化 `extractSlot` 可选 name keys：本任务不是必须，但 STORE 必须 set id+name。

**测试命令**：
```powershell
cd backend/java/cretas-api
& "C:\tools\apache-maven-3.9.6\bin\mvn.cmd" "-Dtest=IntentExecutionOrchestratorMemoryPersistTest" test
```

**提交**：
```powershell
git status --short
git commit -m "feat(ai-memory): persist restaurant top store slot" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/IntentExecutionOrchestratorMemoryPersistTest.java
```

---

## Task 4：第二套 coref 支持 STORE 槽位门控

**文件**：
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ConversationMemoryServiceImpl.java`
- `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/QueryPreprocessorServiceImpl.java`

**先写测试**：
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ConversationMemoryServiceImplStoreReferenceTest.java`
- `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/QueryPreprocessorStoreReferenceTest.java`

测试覆盖：
1. STORE 槽存在：`那家店的客单价呢` -> `门店 人民广场店的客单价呢`。
2. STORE 槽不存在但 SUPPLIER 槽存在：`那家供应商...` / `那家...` 仍按 SUPPLIER 行为消解，工厂 CRM 不回归。
3. `QueryPreprocessorServiceImpl.preprocess(...)` 对 STORE 槽产出 `resolvedReferences`，`entityType=store`，id/name 正确。
4. `这家的客单价呢` 裸指代也能解析 STORE。

**实现**：
- `ConversationMemoryServiceImpl` 增加：
```java
private static final Pattern STORE_REFERENCE_PATTERN = Pattern.compile(
    "(那家店|这家店|该店|那个店|这个店|该门店|那家|这家)"
);
```
- `resolveReference(...)` 中 STORE 在 SUPPLIER 前执行。
- `QueryPreprocessorServiceImpl.getReferencePatterns(...)` 增加 `case STORE`：`那家店/这家店/该店/那家/这家/那个店/这个店/该门店`。

**测试命令**：
```powershell
cd backend/java/cretas-api
& "C:\tools\apache-maven-3.9.6\bin\mvn.cmd" "-Dtest=ConversationMemoryServiceImplStoreReferenceTest,QueryPreprocessorStoreReferenceTest" test
```

**提交**：
```powershell
git status --short
git commit -m "feat(ai-coref): resolve restaurant store references" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ConversationMemoryServiceImpl.java backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/QueryPreprocessorServiceImpl.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ConversationMemoryServiceImplStoreReferenceTest.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/QueryPreprocessorStoreReferenceTest.java
```

---

## Task 5：ToolDispatchService 注入 STORE 参数

**文件**：`backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/ToolDispatchService.java`

**先写/扩测试**：建议新增 `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/ToolDispatchServiceStoreReferenceTest.java`。

测试目标：构造带 `PreprocessedQuery.resolvedReferences` 的 `IntentMatchResult`，执行一个捕获 params 的 fake `ToolExecutor`，断言 params 中包含：
```java
store_name = "人民广场店"
store_id = "101"
```

**实现**：在 resolvedReferences switch 中增加：
```java
case "STORE":
    params.put("store_name", ref.getEntityName());
    if (ref.getEntityId() != null) {
        params.put("store_id", ref.getEntityId());
    }
    log.info("从上下文解析门店: id={}, name={}", ref.getEntityId(), ref.getEntityName());
    break;
```

**测试命令**：
```powershell
cd backend/java/cretas-api
& "C:\tools\apache-maven-3.9.6\bin\mvn.cmd" "-Dtest=ToolDispatchServiceStoreReferenceTest" test
```

**提交**：
```powershell
git status --short
git commit -m "feat(ai-dispatch): inject store reference params" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/ToolDispatchService.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/ToolDispatchServiceStoreReferenceTest.java
```

---

## Task 6：无 STORE 槽门店指代反问，不走全局排行

**文件**：`backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java`

**先写测试**：扩展 `IntentExecutionOrchestratorNegationTest` 或新建 `IntentExecutionOrchestratorStoreClarificationTest.java`。

测试目标：识别结果为 `RESTAURANT_STORE_REVENUE_RANK`，原始输入 `那家店的客单价`，`PreprocessedQuery.unresolvedReferences` 含 `那家店` 且无 store resolved reference 时，返回：
- `intentRecognized=false` 或 true 均可，但 status 必须 `NEED_CLARIFICATION`
- message/formattedText = `请问您指的是哪家店？`
- 不调用 `toolDispatchService.executeWithTool(...)`

**实现建议**：
- 增加 helper：`requiresStoreReferenceClarification(IntentExecuteRequest request, IntentMatchResult matchResult)`。
- 条件：
  - bestMatch intentCode == `RESTAURANT_STORE_REVENUE_RANK`
  - 原始 input 命中门店指代 pattern：`那家店|这家店|该店|那个店|这个店|该门店|那家|这家`
  - `matchResult.getPreprocessedQuery().getResolvedReferences()` 中没有 `entityType=store`
- 在主流程拿到 `AIIntentConfig intent` 后、权限检查前调用。
- 返回 `buildStoreReferenceClarificationResponse(request)`。

**测试命令**：
```powershell
cd backend/java/cretas-api
& "C:\tools\apache-maven-3.9.6\bin\mvn.cmd" "-Dtest=IntentExecutionOrchestratorStoreClarificationTest" test
```

**提交**：
```powershell
git status --short
git commit -m "feat(ai-coref): clarify unresolved restaurant store references" -- backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/IntentExecutionOrchestratorStoreClarificationTest.java
```

---

## Task 7：第一套 coref 只验证，不默认修改

**文件**：`backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/CoreferenceResolutionServiceImpl.java`

默认不改。通过真实路径和日志确认 `那家店的客单价呢` 未被第一套 coref 污染，仍能进入第二套 `QueryPreprocessorServiceImpl` 解析 STORE。

若 live 发现第一套误改，再追加最小补丁：
- `inferSlotType` 对含 `店/门店` 返回 STORE（仅当 STORE 槽存在时才实际 resolve）。
- DISTAL 允许最近槽位兜底，但要有 STORE 槽门控，防止 SUPPLIER 回归。

---

## Task 8：本地回归硬闸

```powershell
cd backend/java/cretas-api
& "C:\tools\apache-maven-3.9.6\bin\mvn.cmd" "-Dtest=EntitySlotStoreTest,RestaurantStoreRevenueRankGoldToolAvgTicketTest,RestaurantStoreRevenueRankGoldToolStoreFilterTest,IntentExecutionOrchestratorMemoryPersistTest,ConversationMemoryServiceImplStoreReferenceTest,QueryPreprocessorStoreReferenceTest,ToolDispatchServiceStoreReferenceTest,IntentExecutionOrchestratorStoreClarificationTest,IntentParityTest,IntentGoldenAssertionTest" test
```

预期：全部通过；特别是 `IntentParityTest` 70/70、`IntentGoldenAssertionTest` 15/15。

---

## Task 9：PR、合 main、从 main 部署 prod

合并前：
```powershell
git diff origin/main...HEAD --stat
git status --short
```

PR 合 main 后，从 main 部署：
```bash
git fetch origin main
git worktree add ../cretas-deploy-phase2a --detach origin/main
cd ../cretas-deploy-phase2a
bash scripts/deploy/deploy-backend.sh --env prod
```

部署后核对运行 jar 含关键标记：
```bash
ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar 'BOOT-INF/classes/com/cretas/aims/dto/conversation/EntitySlot.class' | strings | grep -c 'STORE'"
```

---

## Task 10：prod live 验收

在服务器 localhost 执行，账号 `qhj_sales_mgr/123456`，租户 `RES_3101_009`。

验收点：
1. 第一轮 `哪家店业绩最好` 后，DB `conversation_memory.entity_slots` 包含 STORE，含门店 id/name。
2. 第二轮 `那家店的客单价呢` 返回 #1 门店单行，含营收/单数/客单价；intentCode 为 `RESTAURANT_STORE_REVENUE_RANK`，非 `INDICATOR_QUERY/SMART_INDICATOR_QUERY`。
3. 第二轮 `这家的客单价呢` 同样通过。
4. 新 session 直接问 `那家店的客单价` 返回 `请问您指的是哪家店？`。
5. 工厂租户 SUPPLIER coref smoke：无 STORE 槽时 `那家` 不被 STORE 吞掉。

DB 检查：
```bash
PGPASSWORD=cretas123 psql -h 127.0.0.1 -U cretas_user -d cretas_prod_db -tAc "SELECT entity_slots FROM conversation_memory WHERE session_id='<sid>';"
```

日志检查：
```bash
grep -aE "从上下文解析门店|store_name|restaurant_store_revenue_rank_gold" /www/wwwroot/cretas/logs/cretas-backend.log | tail -50
```
