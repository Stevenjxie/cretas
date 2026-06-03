# 餐饮 AI 路由收口 + 自愈加固 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复餐饮 AI 常用问题的错路由(撞制造业/写意图)，并让 LLM-路由的查询首次后自愈(下次 0-token EXACT 命中)，同时防中毒学习。

**Architecture:** 三轨 — A) 确定性短语 + gold 工具派生客单价; B) 动态工具选择路径业态过滤 + 重标 2 个误挂 COMMON 的制造业意图; C) 动态路径带护栏自愈学习 + 现有学习点防中毒守卫。共享 `BusinessTypeScope.isCompatible` 谓词为单一事实源。

**Tech Stack:** Java 21 + Spring Boot 3.2 + JPA + PostgreSQL + Flyway; JUnit 5 + Mockito; Maven (server: `./mvnw.cmd`).

**Spec:** `docs/superpowers/specs/2026-06-03-restaurant-route-consolidation-selfheal-design.md` (committed 418930701)

**Worktree:** `C:\Users\Steve\cretas-rtroute` branch `feat/restaurant-route-selfheal` off origin/main `8afc52bb5`

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `ai/tool/BusinessTypeScope.java` (NEW) | 业态兼容单一事实源 `isCompatible(intentBiz, factoryBiz)` | T1 |
| `service/intent/impl/IntentRecognitionPipelineServiceImpl.java` | 重构 2 处重复过滤用 BusinessTypeScope (T1); 学习防中毒守卫 (T5) | T1, T5 |
| `config/IntentKnowledgeBase.java` | restaurantPhraseMapping 加堂食外卖/客单价短语 | T2 |
| `ai/tool/impl/restaurant/gold/RestaurantStoreRevenueRankGoldTool.java` | 派生输出客单价=营收/单数 | T2 |
| `resources/db/flyway/V20260913_01__retag_manufacturing_common_intents.sql` (NEW) | 重标 SKU_GROSS_MARGIN/REVENUE_REPORT_GENERATE → MANUFACTURING + 餐饮关键词补强 | T2(关键词)/T3(重标) |
| `service/execution/DynamicToolSelectionService.java` | 候选业态过滤 (T3) + 带护栏自愈学习 (T4) | T3, T4 |
| `entity/learning/LearnedExpression.java` | SourceType 加 DYNAMIC_SELECTION | T4 |

**依赖顺序**: T1 先行(T3/T4/T5 依赖 BusinessTypeScope)。T2 独立(可与 T1 并行)。T3 → T4 串行(同改 DynamicToolSelectionService)。T5 依赖 T1。

---

## Task 1: BusinessTypeScope 共享谓词 + 重构重复过滤

**Files:**
- Create: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/BusinessTypeScope.java`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java` (~line 2408 早期过滤, ~2415 tryLlmFallback 过滤)
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/BusinessTypeScopeTest.java`

- [ ] **Step 1: Write the failing test**

`BusinessTypeScopeTest.java`:
```java
package com.cretas.aims.ai.tool;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BusinessTypeScopeTest {

    @Test
    void restaurantFactory_allowsNullCommonRestaurant_excludesManufacturing() {
        assertTrue(BusinessTypeScope.isCompatible(null, "RESTAURANT"));
        assertTrue(BusinessTypeScope.isCompatible("COMMON", "RESTAURANT"));
        assertTrue(BusinessTypeScope.isCompatible("RESTAURANT", "RESTAURANT"));
        assertFalse(BusinessTypeScope.isCompatible("MANUFACTURING", "RESTAURANT"));
    }

    @Test
    void nonRestaurantFactory_excludesOnlyRestaurant() {
        assertFalse(BusinessTypeScope.isCompatible("RESTAURANT", "FACTORY"));
        assertTrue(BusinessTypeScope.isCompatible("MANUFACTURING", "FACTORY"));
        assertTrue(BusinessTypeScope.isCompatible("COMMON", "FACTORY"));
        assertTrue(BusinessTypeScope.isCompatible(null, "FACTORY"));
        assertTrue(BusinessTypeScope.isCompatible("MANUFACTURING", "COMMON"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend/java/cretas-api && mvn -q -Dtest=BusinessTypeScopeTest test`
Expected: FAIL — `cannot find symbol BusinessTypeScope`

- [ ] **Step 3: Create BusinessTypeScope**

`BusinessTypeScope.java`:
```java
package com.cretas.aims.ai.tool;

/** 业态兼容判断 — 单一事实源。镜像 IntentRecognitionPipelineServiceImpl 历史 v32.1 过滤逻辑。 */
public final class BusinessTypeScope {
    private BusinessTypeScope() {}

    /**
     * 意图业态是否对当前工厂业态可见(可路由/可学习)。
     * 餐饮工厂: 放行 {null, COMMON, RESTAURANT}; 其它工厂: 排除 RESTAURANT 专属。
     */
    public static boolean isCompatible(String intentBusinessType, String factoryBiz) {
        String bt = intentBusinessType;
        if ("RESTAURANT".equals(factoryBiz)) {
            return bt == null || "COMMON".equals(bt) || "RESTAURANT".equals(bt);
        }
        return !"RESTAURANT".equals(bt);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=BusinessTypeScopeTest test`
Expected: PASS (2 tests)

- [ ] **Step 5: Refactor the 2 duplicated filters to use BusinessTypeScope**

In `IntentRecognitionPipelineServiceImpl.java`, find the two `"RESTAURANT".equals(biz)` filter blocks (one ~line 2408 early filter, one ~line 2415 in `tryLlmFallback`). Each looks like:
```java
String biz = configService.resolveBusinessDomain(factoryId);
if ("RESTAURANT".equals(biz)) {
    List<AIIntentConfig> filtered = allIntents.stream()
        .filter(i -> { String bt = i.getBusinessType();
            return bt == null || "COMMON".equals(bt) || "RESTAURANT".equals(bt); })
        .collect(Collectors.toList());
    allIntents = filtered;
} else {
    List<AIIntentConfig> filtered = allIntents.stream()
        .filter(i -> !"RESTAURANT".equals(i.getBusinessType()))
        .collect(Collectors.toList());
    allIntents = filtered;
}
```
Replace each block's filtering with the shared predicate (keep the surrounding log lines):
```java
String biz = configService.resolveBusinessDomain(factoryId);
List<AIIntentConfig> filtered = allIntents.stream()
    .filter(i -> com.cretas.aims.ai.tool.BusinessTypeScope.isCompatible(i.getBusinessType(), biz))
    .collect(Collectors.toList());
log.info("业态过滤: {} → {} intents (biz={})", allIntents.size(), filtered.size(), biz);
allIntents = filtered;
```
Add `import com.cretas.aims.ai.tool.BusinessTypeScope;` at top; then use `BusinessTypeScope.isCompatible(...)` unqualified.

- [ ] **Step 6: Run regression — zero behavior change**

Run: `mvn -q -Dtest='BusinessTypeScopeTest,IntentParityTest,IntentGoldenAssertionTest' test`
Expected: PASS (BusinessTypeScope new; IntentParityTest + IntentGoldenAssertionTest unchanged — the refactor is behavior-identical).

- [ ] **Step 7: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/BusinessTypeScope.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/BusinessTypeScopeTest.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java
git commit -m "refactor(intent): extract BusinessTypeScope.isCompatible, dedupe 2 biz filters

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" \
  -- backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/BusinessTypeScope.java \
     backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/BusinessTypeScopeTest.java \
     backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java
```

---

## Task 2: Track A — 确定性短语 + 客单价派生 + 关键词补强

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/config/IntentKnowledgeBase.java` (restaurantPhraseMapping, ~line 6861 区域)
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/gold/RestaurantStoreRevenueRankGoldTool.java`
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20260913_02__restaurant_route_keywords.sql`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/restaurant/gold/RestaurantStoreRevenueRankGoldToolAvgTicketTest.java`

- [ ] **Step 1: Write the failing test (客单价 派生)**

`RestaurantStoreRevenueRankGoldToolAvgTicketTest.java` — test the derivation helper. The tool builds per-store `entry` maps with `营收` + `单数`. Add a package-private static helper `deriveAvgTicket(Object revenue, Object billCount)` returning `BigDecimal` or null, and test it:
```java
package com.cretas.aims.ai.tool.impl.restaurant.gold;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class RestaurantStoreRevenueRankGoldToolAvgTicketTest {

    @Test
    void derivesAvgTicket_revenueOverBillCount() {
        assertEquals(new BigDecimal("125.50"),
            RestaurantStoreRevenueRankGoldTool.deriveAvgTicket("12550.00", "100"));
    }

    @Test
    void nullWhenBillCountZeroOrMissing() {
        assertNull(RestaurantStoreRevenueRankGoldTool.deriveAvgTicket("12550.00", "0"));
        assertNull(RestaurantStoreRevenueRankGoldTool.deriveAvgTicket("12550.00", null));
        assertNull(RestaurantStoreRevenueRankGoldTool.deriveAvgTicket(null, "100"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RestaurantStoreRevenueRankGoldToolAvgTicketTest test`
Expected: FAIL — `cannot find symbol deriveAvgTicket`

- [ ] **Step 3: Add deriveAvgTicket helper + wire into entry/message**

In `RestaurantStoreRevenueRankGoldTool.java` add the static helper:
```java
/** 派生客单价 = 营收/单数 (单数>0 才算)。真实数据, 非编造。 */
static java.math.BigDecimal deriveAvgTicket(Object revenue, Object billCount) {
    if (revenue == null || billCount == null) return null;
    try {
        java.math.BigDecimal rev = new java.math.BigDecimal(revenue.toString());
        java.math.BigDecimal bill = new java.math.BigDecimal(billCount.toString());
        if (bill.compareTo(java.math.BigDecimal.ZERO) <= 0) return null;
        return rev.divide(bill, 2, java.math.RoundingMode.HALF_UP);
    } catch (NumberFormatException e) { return null; }
}
```
After the `entry.put("单数", row.get("bill_count"));` line, add:
```java
java.math.BigDecimal avgTicket = deriveAvgTicket(row.get("revenue"), row.get("bill_count"));
if (avgTicket != null) entry.put("客单价", avgTicket);
```
In the message builder loop (where each store line is appended), append客单价 when present, e.g.:
```java
if (entry.get("客单价") != null) sb.append("，客单价 ¥").append(entry.get("客单价"));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=RestaurantStoreRevenueRankGoldToolAvgTicketTest test`
Expected: PASS (2 tests)

- [ ] **Step 5: Add restaurant phrases (堂食外卖 + 客单价)**

In `IntentKnowledgeBase.java`, in the `restaurantPhraseMapping.put(...)` block near the #480 dish phrases (~line 6861), add:
```java
// Track A (2026-06-03): 堂食外卖 → order_type_mix_gold (服务模式真数据)
restaurantPhraseMapping.put("堂食外卖对比", "RESTAURANT_ORDER_STATISTICS");
restaurantPhraseMapping.put("堂食外卖占比", "RESTAURANT_ORDER_STATISTICS");
restaurantPhraseMapping.put("内用外卖对比", "RESTAURANT_ORDER_STATISTICS");
restaurantPhraseMapping.put("堂食和外卖", "RESTAURANT_ORDER_STATISTICS");
// Track A: 客单价 → store_revenue_rank_gold (派生客单价列)
restaurantPhraseMapping.put("哪家店客单价最高", "RESTAURANT_STORE_REVENUE_RANK");
restaurantPhraseMapping.put("客单价排行", "RESTAURANT_STORE_REVENUE_RANK");
restaurantPhraseMapping.put("客单价最高的门店", "RESTAURANT_STORE_REVENUE_RANK");
restaurantPhraseMapping.put("人均消费最高", "RESTAURANT_STORE_REVENUE_RANK");
```

- [ ] **Step 6: Add keyword reinforcement flyway**

`V20260913_02__restaurant_route_keywords.sql`:
```sql
-- Track A 关键词双保险: 让 KEYWORD 层也命中, 减少落动态路径。短语层已覆盖, 此为冗余加固。
UPDATE ai_intent_configs
   SET keywords = '["堂食外卖","堂食","外卖占比","内用外卖","服务模式","外卖生意"]',
       updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_ORDER_STATISTICS';

UPDATE ai_intent_configs
   SET keywords = '["门店营收","门店排行","营收对比","客单价","人均消费","笔单价","哪家店"]',
       updated_at = NOW()
 WHERE intent_code = 'RESTAURANT_STORE_REVENUE_RANK';
```
**注意**: 上面 keywords 覆盖现有值 — 实现时先 `SELECT keywords FROM ai_intent_configs WHERE intent_code IN (...)` 读现值, **合并**(并集去重)而非裸覆盖, 避免丢失既有关键词。最终 SQL 用合并后的完整数组。

- [ ] **Step 7: Verify flyway version not colliding**

Run: `git -C C:/Users/Steve/cretas-rtroute fetch origin main -q && git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway | grep -oE 'V2026091[0-9]_[0-9]{2}' | sort | uniq -d`
Expected: empty (no dup). If V20260913_02 已被 sister 占用, 顺延 _03。

- [ ] **Step 8: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/config/IntentKnowledgeBase.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/restaurant/gold/RestaurantStoreRevenueRankGoldTool.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/restaurant/gold/RestaurantStoreRevenueRankGoldToolAvgTicketTest.java \
        backend/java/cretas-api/src/main/resources/db/flyway/V20260913_02__restaurant_route_keywords.sql
git commit -m "feat(restaurant): Track A 堂食外卖/客单价短语 + gold派生客单价 + 关键词补强

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" -- <same paths>
```

---

## Task 3: Track B — 动态候选业态过滤 + 重标制造业意图

**Files:**
- Create: `backend/java/cretas-api/src/main/resources/db/flyway/V20260913_01__retag_manufacturing_common_intents.sql`
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/DynamicToolSelectionService.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/DynamicToolSelectionBusinessFilterTest.java`

- [ ] **Step 1: Write the failing test**

Make `filterCandidatesByBusinessType` **package-private** for testability. Test mocks `IntentConfigManagementService`:
```java
package com.cretas.aims.service.execution;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ToolRouterService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicToolSelectionBusinessFilterTest {

    private ToolRouterService.ToolCandidate cand(String tool) {
        ToolRouterService.ToolCandidate c = new ToolRouterService.ToolCandidate();
        c.setToolName(tool); c.setSimilarity(0.8); return c;
    }
    private AIIntentConfig intent(String code, String biz, String tool) {
        AIIntentConfig i = new AIIntentConfig();
        i.setIntentCode(code); i.setBusinessType(biz); i.setToolName(tool); return i;
    }

    @Test
    void restaurantFactory_dropsManufacturingTool_keepsRestaurantAndOrphan() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        when(cfg.resolveBusinessDomain("RES_1")).thenReturn("RESTAURANT");
        when(cfg.getAllIntents("RES_1")).thenReturn(List.of(
            intent("SKU_GROSS_MARGIN", "MANUFACTURING", "sku_gross_margin_tool"),
            intent("RESTAURANT_ORDER_STATISTICS", "RESTAURANT", "restaurant_order_type_mix_gold")
        ));
        DynamicToolSelectionService svc = new DynamicToolSelectionService(/* see ctor note */);
        ReflectionTestUtils.setField(svc, "configService", cfg);

        List<ToolRouterService.ToolCandidate> in = new ArrayList<>(List.of(
            cand("sku_gross_margin_tool"),          // manufacturing → drop
            cand("restaurant_order_type_mix_gold"), // restaurant → keep
            cand("some_orphan_tool")                // no owning intent → keep
        ));
        List<ToolRouterService.ToolCandidate> out = svc.filterCandidatesByBusinessType(in, "RES_1");
        Set<String> kept = new HashSet<>();
        out.forEach(c -> kept.add(c.getToolName()));
        assertFalse(kept.contains("sku_gross_margin_tool"));
        assertTrue(kept.contains("restaurant_order_type_mix_gold"));
        assertTrue(kept.contains("some_orphan_tool"));
    }

    @Test
    void resolveFailure_returnsUnfiltered() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        when(cfg.resolveBusinessDomain(any())).thenThrow(new RuntimeException("boom"));
        DynamicToolSelectionService svc = new DynamicToolSelectionService(/* ctor */);
        ReflectionTestUtils.setField(svc, "configService", cfg);
        List<ToolRouterService.ToolCandidate> in = List.of(cand("any_tool"));
        assertEquals(1, svc.filterCandidatesByBusinessType(in, "F").size());
    }
}
```
**Ctor note:** DynamicToolSelectionService 有 `final` 构造注入字段 (toolRouterService/toolRegistry/objectMapper)。测试构造时传 `mock(...)` 或 null（这些方法路径不用它们）。`configService`/`expressionLearningService` 用 `@Autowired` field（同 writeGuardService），用 `ReflectionTestUtils.setField` 注入。若 final 字段构造繁琐，实现者按现有 `WriteGuardWiringTest` 模式构造。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=DynamicToolSelectionBusinessFilterTest test`
Expected: FAIL — `filterCandidatesByBusinessType` 不存在 / configService 字段不存在。

- [ ] **Step 3: Add configService injection + filter method + wire into executeWithDynamicToolSelection**

In `DynamicToolSelectionService.java` add field (同 writeGuardService 的 `@Autowired` field 风格):
```java
@Autowired
private com.cretas.aims.service.intent.IntentConfigManagementService configService;
```
Add method:
```java
/** Track B: 按工厂业态过滤动态候选工具, 排除异业态(如餐饮工厂排除制造业工具)。 */
List<ToolRouterService.ToolCandidate> filterCandidatesByBusinessType(
        List<ToolRouterService.ToolCandidate> candidates, String factoryId) {
    String biz;
    try { biz = configService.resolveBusinessDomain(factoryId); }
    catch (Exception e) { return candidates; }   // 解析失败不过滤(保守)
    if (biz == null || biz.isEmpty()) return candidates;
    java.util.Map<String, java.util.List<com.cretas.aims.entity.config.AIIntentConfig>> byTool =
        configService.getAllIntents(factoryId).stream()
            .filter(i -> i.getToolName() != null && !i.getToolName().isEmpty())
            .collect(java.util.stream.Collectors.groupingBy(
                com.cretas.aims.entity.config.AIIntentConfig::getToolName));
    java.util.List<ToolRouterService.ToolCandidate> kept = candidates.stream().filter(c -> {
        java.util.List<com.cretas.aims.entity.config.AIIntentConfig> owners = byTool.get(c.getToolName());
        if (owners == null || owners.isEmpty()) return true;   // 孤儿工具保留
        return owners.stream().anyMatch(i ->
            com.cretas.aims.ai.tool.BusinessTypeScope.isCompatible(i.getBusinessType(), biz));
    }).collect(java.util.stream.Collectors.toList());
    if (kept.size() < candidates.size()) {
        log.info("动态候选业态过滤: {} → {} (biz={})", candidates.size(), kept.size(), biz);
    }
    return kept;
}
```
Wire it in `executeWithDynamicToolSelection` right after `retrieveCandidateTools`:
```java
List<ToolRouterService.ToolCandidate> candidates = toolRouterService.retrieveCandidateTools(query, 10);
candidates = filterCandidatesByBusinessType(candidates, factoryId);   // ← Track B
if (candidates.isEmpty()) {
    log.warn("动态工具选择: 业态过滤后无候选工具, query={}", query);
    return buildNoToolResponse(intent);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=DynamicToolSelectionBusinessFilterTest test`
Expected: PASS (2 tests)

- [ ] **Step 5: Create re-tag flyway V20260913_01**

`V20260913_01__retag_manufacturing_common_intents.sql`:
```sql
-- SKU_GROSS_MARGIN / REVENUE_REPORT_GENERATE 是制造业概念误挂 COMMON → 餐饮业态过滤放行 → 垃圾路由源。
-- 重标 MANUFACTURING: 餐饮工厂 isCompatible=false 排除; 非餐饮工厂(只排 RESTAURANT)仍放行, 零回归。
UPDATE ai_intent_configs SET business_type = 'MANUFACTURING', updated_at = NOW()
 WHERE intent_code IN ('SKU_GROSS_MARGIN', 'REVENUE_REPORT_GENERATE')
   AND business_type = 'COMMON';
```

- [ ] **Step 6: Verify flyway version collision-free**

Run: `git fetch origin main -q && git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway | grep -oE 'V20260913_[0-9]{2}' | sort | uniq -d`
Expected: empty. If V20260913_01 占用 → 顺延并同步 T2 的 _02。

- [ ] **Step 7: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/DynamicToolSelectionService.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/DynamicToolSelectionBusinessFilterTest.java \
        backend/java/cretas-api/src/main/resources/db/flyway/V20260913_01__retag_manufacturing_common_intents.sql
git commit -m "feat(intent): Track B 动态候选业态过滤 + 重标 SKU/REPORT COMMON→MANUFACTURING

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" -- <same paths>
```

---

## Task 4: Track C — 动态路径带护栏自愈学习

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/learning/LearnedExpression.java` (SourceType enum +DYNAMIC_SELECTION)
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/DynamicToolSelectionService.java`
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/DynamicSelectionSelfHealLearnTest.java`

- [ ] **Step 1: Add SourceType.DYNAMIC_SELECTION**

In `entity/learning/LearnedExpression.java` enum (line ~245), append:
```java
    LLM_RERANKING,
    DYNAMIC_SELECTION   // 动态工具选择路径自愈学习
```
(将原 `LLM_RERANKING` 末尾分号/逗号调整正确。)

- [ ] **Step 2: Write the failing test**

`DynamicSelectionSelfHealLearnTest.java` — make `maybeLearnFromDynamicSelection` **package-private**; verify learnExpression called/not-called:
```java
package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.service.ExpressionLearningService;
import com.cretas.aims.service.ToolRouterService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;

class DynamicSelectionSelfHealLearnTest {

    private ToolRouterService.SelectedTools singleTool(String tool) {
        ToolRouterService.SelectedTools.SelectedTool st = new ToolRouterService.SelectedTools.SelectedTool();
        st.setToolName(tool);
        ToolRouterService.SelectedTools sel = new ToolRouterService.SelectedTools();
        sel.setTools(List.of(st));
        return sel;
    }
    private ToolRouterService.ToolCandidate cand(String tool, double sim) {
        ToolRouterService.ToolCandidate c = new ToolRouterService.ToolCandidate();
        c.setToolName(tool); c.setSimilarity(sim); return c;
    }
    private IntentExecuteResponse ok() {
        IntentExecuteResponse r = new IntentExecuteResponse();
        r.setStatus("SUCCESS"); r.setResultData(Map.of("x", 1)); return r;
    }
    private AIIntentConfig owner(String tool) {
        AIIntentConfig i = new AIIntentConfig();
        i.setIntentCode("RESTAURANT_ORDER_STATISTICS"); i.setBusinessType("RESTAURANT"); i.setToolName(tool);
        return i;
    }
    private DynamicToolSelectionService svc(IntentConfigManagementService cfg, ExpressionLearningService els) {
        DynamicToolSelectionService s = new DynamicToolSelectionService(/* ctor mocks/null */);
        ReflectionTestUtils.setField(s, "configService", cfg);
        ReflectionTestUtils.setField(s, "expressionLearningService", els);
        return s;
    }

    @Test
    void learns_whenSingleToolSuccessCompatibleHighSim() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        when(cfg.resolveBusinessDomain("RES_1")).thenReturn("RESTAURANT");
        when(cfg.getAllIntents("RES_1")).thenReturn(List.of(owner("restaurant_order_type_mix_gold")));
        svc(cfg, els).maybeLearnFromDynamicSelection("外送生意占几成",
            singleTool("restaurant_order_type_mix_gold"),
            List.of(cand("restaurant_order_type_mix_gold", 0.72)), "RES_1", ok());
        verify(els).learnExpression(eq("RES_1"), eq("RESTAURANT_ORDER_STATISTICS"),
            eq("外送生意占几成"), eq(0.72), eq(LearnedExpression.SourceType.DYNAMIC_SELECTION));
    }

    @Test
    void doesNotLearn_whenLowSimilarity() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        when(cfg.resolveBusinessDomain("RES_1")).thenReturn("RESTAURANT");
        when(cfg.getAllIntents("RES_1")).thenReturn(List.of(owner("restaurant_order_type_mix_gold")));
        svc(cfg, els).maybeLearnFromDynamicSelection("q",
            singleTool("restaurant_order_type_mix_gold"),
            List.of(cand("restaurant_order_type_mix_gold", 0.40)), "RES_1", ok());
        verifyNoInteractions(els);
    }

    @Test
    void doesNotLearn_whenNotSuccessOrMultiTool() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        IntentExecuteResponse failed = new IntentExecuteResponse(); failed.setStatus("FAILED");
        svc(cfg, els).maybeLearnFromDynamicSelection("q", singleTool("t"),
            List.of(cand("t", 0.9)), "RES_1", failed);
        verifyNoInteractions(els);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -Dtest=DynamicSelectionSelfHealLearnTest test`
Expected: FAIL — `maybeLearnFromDynamicSelection` / `expressionLearningService` 不存在。

- [ ] **Step 4: Add expressionLearningService injection + constant + method + wire**

Add field + constant:
```java
@Autowired
private com.cretas.aims.service.ExpressionLearningService expressionLearningService;

private static final double DYNAMIC_LEARN_MIN_SIMILARITY = 0.55;
```
Add method:
```java
/** Track C: 动态单工具成功执行后自愈学习 (带业态/成功/相似度护栏)。fail-open。 */
void maybeLearnFromDynamicSelection(String query, ToolRouterService.SelectedTools selected,
        List<ToolRouterService.ToolCandidate> candidates, String factoryId, IntentExecuteResponse response) {
    try {
        if (selected == null || selected.getTools() == null || selected.getTools().size() != 1) return;
        if (response == null || !"SUCCESS".equals(response.getStatus())
                || response.getResultData() == null) return;
        String toolName = selected.getTools().get(0).getToolName();
        if (toolName == null) return;
        double sim = candidates.stream()
            .filter(c -> toolName.equals(c.getToolName()))
            .mapToDouble(ToolRouterService.ToolCandidate::getSimilarity).max().orElse(0.0);
        if (sim < DYNAMIC_LEARN_MIN_SIMILARITY) return;
        String biz = configService.resolveBusinessDomain(factoryId);
        com.cretas.aims.entity.config.AIIntentConfig owner = configService.getAllIntents(factoryId).stream()
            .filter(i -> toolName.equals(i.getToolName()))
            .filter(i -> com.cretas.aims.ai.tool.BusinessTypeScope.isCompatible(i.getBusinessType(), biz))
            .findFirst().orElse(null);
        if (owner == null) return;
        expressionLearningService.learnExpression(factoryId, owner.getIntentCode(), query.trim(), sim,
            com.cretas.aims.entity.learning.LearnedExpression.SourceType.DYNAMIC_SELECTION);
        log.info("动态选择自愈学习: query={}, intent={}, tool={}, sim={}",
            query.length() > 40 ? query.substring(0,40) : query, owner.getIntentCode(), toolName, sim);
    } catch (Exception e) { log.warn("动态选择学习失败: {}", e.getMessage()); }
}
```
Wire in `executeWithDynamicToolSelection` single-tool path, after the SUCCESS response is built and before `return response;`. The `candidates` (post-filter) and `selectedTools` are both in scope:
```java
maybeLearnFromDynamicSelection(query, selectedTools, candidates, factoryId, response);
return response;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -Dtest=DynamicSelectionSelfHealLearnTest test`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/entity/learning/LearnedExpression.java \
        backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/DynamicToolSelectionService.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/execution/DynamicSelectionSelfHealLearnTest.java
git commit -m "feat(intent): Track C 动态路径带护栏自愈学习 (single-tool+success+biz+sim)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" -- <same paths>
```

---

## Task 5: Track C — Loop-1 学习防中毒守卫

**Files:**
- Modify: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java` (`tryAutoLearnExpression` line ~4537)
- Test: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/impl/AutoLearnPoisonGuardTest.java`

- [ ] **Step 1: Write the failing test**

`tryAutoLearnExpression` 是 private。改为 **package-private** 以便测试。Test 用 ReflectionTestUtils 注入 mock 的 configService + expressionLearningService 到 IRP, 验证不兼容意图不学:
```java
package com.cretas.aims.service.intent.impl;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.service.ExpressionLearningService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.Mockito.*;

class AutoLearnPoisonGuardTest {

    private AIIntentConfig intent(String code, String biz) {
        AIIntentConfig i = new AIIntentConfig(); i.setIntentCode(code); i.setBusinessType(biz); return i;
    }

    @Test
    void rejects_incompatibleIntent_forRestaurantFactory() {
        IntentRecognitionPipelineServiceImpl irp =
            (IntentRecognitionPipelineServiceImpl) ReflectionTestUtils.invokeConstructor(...); // see note
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        when(cfg.resolveBusinessDomain("RES_1")).thenReturn("RESTAURANT");
        when(cfg.getAllIntents("RES_1")).thenReturn(List.of(intent("SKU_GROSS_MARGIN","MANUFACTURING")));
        ReflectionTestUtils.setField(irp, "configService", cfg);
        ReflectionTestUtils.setField(irp, "expressionLearningService", els);
        ReflectionTestUtils.invokeMethod(irp, "tryAutoLearnExpression",
            "付款方式占比", "SKU_GROSS_MARGIN", "RES_1", 0.9, LearnedExpression.SourceType.LLM_FALLBACK);
        verify(els, never()).learnExpression(any(), any(), any(), anyDouble(), any());
    }

    @Test
    void allows_compatibleIntent() {
        IntentRecognitionPipelineServiceImpl irp = /* construct */ null;
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        when(cfg.resolveBusinessDomain("RES_1")).thenReturn("RESTAURANT");
        when(cfg.getAllIntents("RES_1")).thenReturn(List.of(intent("RESTAURANT_ORDER_STATISTICS","RESTAURANT")));
        ReflectionTestUtils.setField(irp, "configService", cfg);
        ReflectionTestUtils.setField(irp, "expressionLearningService", els);
        ReflectionTestUtils.invokeMethod(irp, "tryAutoLearnExpression",
            "外送占比", "RESTAURANT_ORDER_STATISTICS", "RES_1", 0.9, LearnedExpression.SourceType.LLM_FALLBACK);
        verify(els).learnExpression(eq("RES_1"), eq("RESTAURANT_ORDER_STATISTICS"), eq("外送占比"),
            eq(0.9), eq(LearnedExpression.SourceType.LLM_FALLBACK));
    }
}
```
**Construction note:** IntentRecognitionPipelineServiceImpl 构造依赖很多。实现者按 `IntentParityTest` 的现有构造方式（或 Mockito `@Mock`+构造）建实例，只需 configService + expressionLearningService 被 mock 注入即可（其余 mock/null）。若构造过重，可用 Mockito `mock(IntentRecognitionPipelineServiceImpl.class, CALLS_REAL_METHODS)` 仅对目标方法走真实逻辑 + 注入两个字段。优先复用 IntentParityTest 既有 setup。

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AutoLearnPoisonGuardTest test`
Expected: FAIL — guard 未加, 不兼容意图仍调 learnExpression。

- [ ] **Step 3: Add poison-guard at top of tryAutoLearnExpression**

Change visibility `private void` → `void` (package-private). Insert guard after the empty-input check:
```java
void tryAutoLearnExpression(String userInput, String intentCode, String factoryId,
                            double confidence, LearnedExpression.SourceType sourceType) {
    if (userInput == null || userInput.trim().isEmpty()) return;
    // Track C 防中毒: 拒绝学习业态不兼容的意图(餐饮工厂永不学 SKU_GROSS_MARGIN 等)
    try {
        String biz = configService.resolveBusinessDomain(factoryId);
        AIIntentConfig cfg = configService.getAllIntents(factoryId).stream()
            .filter(i -> intentCode.equals(i.getIntentCode())).findFirst().orElse(null);
        if (cfg != null && !com.cretas.aims.ai.tool.BusinessTypeScope.isCompatible(cfg.getBusinessType(), biz)) {
            log.warn("拒绝中毒学习: intent={} business_type={} 与工厂 biz={} 不兼容",
                intentCode, cfg.getBusinessType(), biz);
            return;
        }
    } catch (Exception e) { /* 解析失败不阻断既有学习, 保守放行 */ }
    // ... 原有 learnExpression try/catch 逻辑不变
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=AutoLearnPoisonGuardTest test`
Expected: PASS (2 tests)

- [ ] **Step 5: Full regression**

Run: `mvn -q -Dtest='BusinessTypeScopeTest,RestaurantStoreRevenueRankGoldToolAvgTicketTest,DynamicToolSelectionBusinessFilterTest,DynamicSelectionSelfHealLearnTest,AutoLearnPoisonGuardTest,IntentParityTest,IntentGoldenAssertionTest' test`
Expected: ALL PASS, zero regression.

- [ ] **Step 6: Commit**

```bash
git add backend/java/cretas-api/src/main/java/com/cretas/aims/service/intent/impl/IntentRecognitionPipelineServiceImpl.java \
        backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/impl/AutoLearnPoisonGuardTest.java
git commit -m "feat(intent): Track C Loop-1 学习防中毒守卫 (业态不兼容拒学)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" -- <same paths>
```

---

## Final: 5-agent 对抗终审 + 部署

After all 5 tasks pass:

- [ ] **Full build**: `cd backend/java/cretas-api && mvn -q clean package -DskipTests` (or with tests). Confirm compiles + jar built.
- [ ] **5-agent adversarial review** (safety-critical: intent routing + self-learning, mirrors W1b). Focus: (1) BusinessTypeScope refactor truly behavior-identical (no routing regression); (2) filterCandidatesByBusinessType doesn't over-filter (orphan tools / multi-owner tools kept); (3) self-heal can't poison (business-type guard + success guard hold); (4) re-tag flyway zero regression for non-restaurant factories; (5) no NPE / fail-open swallow hiding bugs.
- [ ] **PR → merge main**: confirm scope clean `git diff origin/main...HEAD --stat` (only this feature's files). Flyway re-check `git ls-tree origin/main ...flyway | grep -oE 'V20260913_[0-9]{2}' | sort | uniq -d` empty before merge.
- [ ] **Deploy from main** (worktree off origin/main after merge): `git checkout main && git pull && ./scripts/deploy/deploy-backend.sh --env all` (test 10011 + prod blue-green).
- [ ] **Verify running jar**: `ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar 'BOOT-INF/classes/com/cretas/aims/ai/tool/BusinessTypeScope.class' | strings | grep -c isCompatible"` (>0).
- [ ] **Prod live verify (RES_3101_009)** via authenticated execute:
  - 堂食外卖对比 → intentCode RESTAURANT_ORDER_STATISTICS (非 REVENUE_REPORT_GENERATE/REVENUE_TREND)
  - 哪家店客单价最高 → message 含 "客单价 ¥"
  - 付款方式占比 → intentCode 非 SKU_GROSS_MARGIN
  - 自愈: 选一未预设长尾问法跑 2 次 → 2nd `ai_learned_expressions` 新增 DYNAMIC_SELECTION 行 + matchMethod=EXACT
- [ ] **superpowers:finishing-a-development-branch**

---

## 并行工作建议

### Subagent: ✅ T1‖T2 可并行（不同文件: BusinessTypeScope/IRP-filter vs IntentKnowledgeBase/gold-tool）。T3→T4 必须串行（同改 DynamicToolSelectionService）。T5 依赖 T1（用 BusinessTypeScope）。
### 多Chat: ❌ 不建议（全在同一意图子系统, IRP/DynamicToolSelectionService 多任务交叉, 单 session subagent-driven 最稳, 避免并发改同文件）。
