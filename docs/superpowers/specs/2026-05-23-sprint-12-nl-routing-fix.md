# Sprint 12 P0 — NL Routing Root-Cause Fix

**Date**: 2026-05-23
**Author**: AI 工厂 chat (Sprint 11 audit coordinator → Sprint 12 implementer)
**Source bug**: `docs/audits/sprint-11-ux-audit/verdict-2026-05-23.md` — 12 PNG prove 75% Class D 错路由 + 25% Class F 错误信息
**Status**: 🟡 Spec for implementation

---

## TL;DR

Sprint 11 Round 6 (PR #190) + Round 7 (PR #204) added BOTH phrase mappings (line 7409-7427 in IntentKnowledgeBase) AND orchestrator shortcut (line 219-222 + 752-799 in IntentExecutionOrchestrator). 

**But my Goal v5 UI audit prove they NEVER FIRE.** Root cause: `handleEarlyQuestionTypeDetection` at line 190 RUNS FIRST and short-circuits to LLM `generateConversationalResponse` for phrases starting with "帮我" (classified as CONVERSATIONAL) — phrase shortcut at line 221 is unreachable.

**Surgical fix**: move `tryOrchestratorPhraseShortcut` call to BEFORE `handleEarlyQuestionTypeDetection` (i.e. before line 190). 4-line change. Verify via 12-case UI re-run.

Bonus: add 30+ phrase variations + V_24 migration with negative_keywords + IntentRoutingTest unit test cases (per Steve goal v6).

---

## Goal v5 evidence recap (the bug)

Per `docs/audits/sprint-11-ux-audit/output-quality-matrix.md`:
- 12/12 cases (4 phrase × 3 accounts) routed to DAILY_CUSTOMER_FOLLOWUP-style output OR LLM timeout
- 0/12 reached RESTAURANT_ECONOMICS_ANALYSIS
- Smoking gun: 餐饮 chat curl (with explicit `intentCode: RESTAURANT_ECONOMICS_ANALYSIS`) succeeded; UI NL (intentCode undefined) failed

---

## Root cause (deep trace)

### Architecture (May 2026 state)

```
UI POST /api/mobile/RES_3101_009/ai-intents/execute {userInput, intentCode: undefined}
  ↓
AIIntentConfigController.executeIntent
  ↓
IntentExecutorServiceImpl.execute
  ↓
IntentExecutionOrchestrator.execute (line 164):
  0.  if intentCode present → executeWithExplicitIntent ← curl path WORKS
  0.3 if sessionId → handleConversationContinuation
  0.3 [LINE 190] handleEarlyQuestionTypeDetection ← BUG: short-circuits for "帮我" CONVERSATIONAL
  0.5 handleSemanticCache
  1.  [LINE 219-222] tryOrchestratorPhraseShortcut ← Round 7 fix, UNREACHABLE for "帮我X" inputs
  1.  recognizeIntentWithConfidence (fallback LLM)
```

### Why `handleEarlyQuestionTypeDetection` catches my 4 phrases

`IntentKnowledgeBase.detectQuestionType` line 7842-7857:
```java
for (String indicator : conversationalIndicators) {
    if (trimmedInput.equals(indicator.toLowerCase()) ||
        trimmedInput.startsWith(indicator.toLowerCase())) {
        if (!containsOperationalIndicator(trimmedInput)) {
            return QuestionType.CONVERSATIONAL;
        }
    }
}
```

"帮我看上月损溢异常" starts with "帮我" (presumably in `conversationalIndicators`) → returns CONVERSATIONAL **unless `containsOperationalIndicator` is true**.

`containsOperationalIndicator` checks for "查看 / 创建 / 删除 / 修改 / 暂停 / 启动" etc. "损溢异常" doesn't contain these → CONVERSATIONAL.

For "损益分析" / "上月成本" / "哪个菜亏钱" — these don't start with "帮我" but may match `generalQuestionIndicators` like "哪个" → GENERAL_QUESTION.

Either way: `handleEarlyQuestionTypeDetection` line 617-620 only returns null if NEITHER GENERAL_QUESTION nor CONVERSATIONAL → enters body for all 4 phrases.

### What `handleEarlyQuestionTypeDetection` body does (line 612-708)

For GENERAL_QUESTION + analysis: → `executeAnalysisFlow` (Sprint 11 Analysis path)
For GENERAL_QUESTION + food: → execute FOOD_KNOWLEDGE_QUERY
For GENERAL_QUESTION + Agentic RAG: → buildRAGResponse
For ANY + matchPhrase=OUT_OF_DOMAIN/CONTEXT_CONTINUE: → intercept

**Else → line 698-707: `generateConversationalResponse` via LLM** ← this is what 12/12 hit.

The matchPhrase at line 685 finds "帮我看上月损溢异常" → RESTAURANT_ECONOMICS_ANALYSIS but ONLY checks against OUT_OF_DOMAIN/CONTEXT_CONTINUE intercepts → ignores other matches → falls through to LLM.

### Why LLM output mimics DAILY_CUSTOMER_FOLLOWUP

SalesOwnerWorkdesk.vue auto-mounts and calls `triggerFollowupQuery()` → `sendQuery(true)` → POST with `intentCode='DAILY_CUSTOMER_FOLLOWUP'` → returns customer-followup summary into `formattedText.value`. 

When user clicks 发送 with new phrase, the LLM `generateConversationalResponse` likely uses conversation history (or simply DAILY_CUSTOMER_FOLLOWUP-shaped prompt) → produces customer-followup-style output for "损益分析" / "上月成本" / "哪个菜亏钱" too.

---

## Fix design

### Fix 1 (core, P0): move phrase shortcut earlier in orchestrator

**File**: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java`

**Move lines 219-222 (`tryOrchestratorPhraseShortcut`) to BEFORE line 190 (`handleEarlyQuestionTypeDetection`)**.

```java
// AFTER line 185 (post conversation continuation), BEFORE current line 187 (early question detection):

// Sprint 12 P0 fix — phrase shortcut MUST win over CONVERSATIONAL/GENERAL_QUESTION classification.
// Without this, "帮我X" inputs route to LLM conversational, never reaching configured intents.
// Per UI audit `docs/audits/sprint-11-ux-audit/`: Sprint 11 Round 7 phrase shortcut at line 221
// was unreachable for 12/12 customer-facing phrases — they were caught by line 190 first.
String userInput0 = request.getUserInput();
if (userInput0 != null && !userInput0.isEmpty() && (request.getIntentCode() == null || request.getIntentCode().isEmpty())) {
    IntentMatchResult earlyPhraseMatch = tryOrchestratorPhraseShortcut(userInput0, factoryId);
    if (earlyPhraseMatch != null && earlyPhraseMatch.hasMatch()) {
        AIIntentConfig phraseIntent = earlyPhraseMatch.getBestMatch();
        IntentExecuteRequest phraseRequest = IntentExecuteRequest.builder()
            .userInput(userInput0)
            .intentCode(phraseIntent.getIntentCode())
            .sessionId(request.getSessionId())
            .enableThinking(request.getEnableThinking())
            .thinkingBudget(request.getThinkingBudget())
            .build();
        return executeWithExplicitIntent(factoryId, phraseRequest, userId, userRole);
    }
}
```

**Also remove the now-dead phrase shortcut at line 219-222** to avoid double-shortcut confusion.

### Fix 2 (defense in depth): add intent-level matchPhrase check in `handleEarlyQuestionTypeDetection`

**File**: same, around line 685-696.

Replace:
```java
// OUT_OF_DOMAIN / CONTEXT_CONTINUE intercept
Optional<String> conversationalPhraseMatch = knowledgeBase.matchPhrase(userInput);
if (conversationalPhraseMatch.isPresent()) {
    String matchedIntent = conversationalPhraseMatch.get();
    if ("OUT_OF_DOMAIN".equals(matchedIntent) || "CONTEXT_CONTINUE".equals(matchedIntent)) {
        ...intercept...
    }
}
```

With:
```java
// Sprint 12 P0 — Any phrase match WITH configured intent for this factory should win
// over LLM conversational fallback. Without this, the LLM at line 698 hijacks all
// "帮我X / 怎么X / 哪个X" inputs that lack OUT_OF_DOMAIN/CONTEXT_CONTINUE wiring.
String businessDomain = (factoryId != null && factoryId.startsWith("RES_")) ? "RESTAURANT" : "FACTORY";
Optional<String> conversationalPhraseMatch = knowledgeBase.matchPhrase(userInput, businessDomain);
if (conversationalPhraseMatch.isPresent()) {
    String matchedIntent = conversationalPhraseMatch.get();
    if ("OUT_OF_DOMAIN".equals(matchedIntent) || "CONTEXT_CONTINUE".equals(matchedIntent)) {
        // existing intercept
        IntentExecuteRequest interceptRequest = IntentExecuteRequest.builder()
                .userInput(userInput)
                .intentCode(matchedIntent)
                .sessionId(request.getSessionId())
                .build();
        return execute(factoryId, interceptRequest, userId, userRole);
    }
    // NEW: if intent exists in this factory's config, route to it (defense in depth for Fix 1)
    Optional<AIIntentConfig> existingIntent = aiIntentService.getIntentByCode(factoryId, matchedIntent);
    if (existingIntent.isPresent()) {
        IntentExecuteRequest phraseRequest = IntentExecuteRequest.builder()
                .userInput(userInput)
                .intentCode(matchedIntent)
                .sessionId(request.getSessionId())
                .build();
        return executeWithExplicitIntent(factoryId, phraseRequest, userId, userRole);
    }
}
```

### Fix 3 (expand phrase coverage): 30+ new phrase variations

**File**: `IntentKnowledgeBase.java` around line 7409-7427 (Sprint 11 Round 6/7 section).

Add NL variations for the 4 known-failing phrases + similar restaurant-finance NL phrases:

```java
String[] economicsPhrases = new String[] {
    // Original Round 6/7 (kept)
    "帮我看上月损溢异常", "帮我看上月损溢", "损溢异常", "损益分析",
    "上月成本", "哪个菜亏钱",
    // Sprint 12 P0 additions — 30+ NL variations:
    // "上月" variants
    "上月损益", "上月亏", "上个月损益", "上个月成本", "上月经营",
    "上月亏多少", "上月赚多少", "上月赔多少",
    // "本月/这个月" variants
    "本月损益", "本月成本", "这个月亏", "这个月赚多少",
    "本月经营", "本月亏",
    // "上周/本周" variants
    "上周损益", "本周损益",
    // 损溢/损益/损耗 variants
    "损溢分析", "损溢报告", "损溢情况", "损益情况", "损益报表", "损耗情况",
    // "哪个菜"/"哪些菜" variants (singular + plural)
    "哪些菜亏钱", "哪个菜在亏", "哪个菜赔钱", "哪些菜不赚钱",
    "哪个菜成本最高", "哪个菜利润最低", "哪个菜毛利低",
    // 经营分析 phrases
    "经营分析", "经营诊断", "经营情况怎么样", "门店经营情况",
    "餐厅经营", "店面经营", "今天生意怎么样", "本店经营",
    // 成本/毛利 phrases
    "成本分析", "成本情况", "毛利分析", "毛利情况", "毛利率",
    "餐饮成本", "餐厅成本", "厨房成本",
};
for (String phrase : economicsPhrases) {
    phraseToIntentMapping.put(phrase, "RESTAURANT_ECONOMICS_ANALYSIS");
    restaurantPhraseMapping.put(phrase, "RESTAURANT_ECONOMICS_ANALYSIS");
}
```

### Fix 4 (defense): negative_keywords migration

**File**: `backend/java/cretas-api/src/main/resources/db/flyway/V20260824_50__sprint12_restaurant_economics_negative_keywords.sql`

```sql
-- Sprint 12 P0 NL routing fix — RESTAURANT_ECONOMICS_ANALYSIS must NOT route to
-- DAILY_CUSTOMER_FOLLOWUP-related intents. Per AI 工厂 Goal v5 UI audit, NL phrases
-- "上月损溢异常" leaked to DAILY_CUSTOMER_FOLLOWUP via LLM conversational fallback.
-- Add negative_keywords to DAILY_CUSTOMER_FOLLOWUP to exclude restaurant-finance signals.

UPDATE ai_intent_configs SET
  negative_keywords = '["损溢","损益","损耗","成本","毛利","亏钱","赚钱","赔钱","经营情况","菜品成本","餐厅经营","门店经营"]'::jsonb,
  updated_at = NOW()
WHERE intent_code = 'DAILY_CUSTOMER_FOLLOWUP';

UPDATE ai_intent_configs SET
  negative_keywords = '["客户跟进","跟进客户","客户优先","商机","微信","电话回访","电话跟进"]'::jsonb,
  updated_at = NOW()
WHERE intent_code = 'RESTAURANT_ECONOMICS_ANALYSIS';
```

**Note** — per `feedback_negative_keywords_useless_in_cretas_intentmatching.md` HARD, negative_keywords on their own won't fix routing (phraseWeight 1.0 dominates over keywordWeight 0.25). Their role here is **defense in depth** only — Fix 1+2 are the load-bearing fixes.

### Fix 5 (test gate): IntentRoutingTest add 12+ NL cases

**File**: `backend/java/cretas-api/src/test/java/com/cretas/aims/service/intent/IntentRoutingTest.java` (extend if exists, or create)

```java
@Test
void routesRestaurantEconomicsAnalysisPhrasesViaNL() {
    String[] phrases = {
        "帮我看上月损溢异常", "损益分析", "上月成本", "哪个菜亏钱",
        "上月亏多少", "本月毛利", "经营诊断", "成本分析",
        "哪些菜赔钱", "今天生意怎么样", "上月损耗", "毛利率",
    };
    for (String phrase : phrases) {
        var result = orchestrator.execute("RES_3101_009",
            IntentExecuteRequest.builder().userInput(phrase).build(), 1L, "warehouse_manager");
        assertThat(result.getIntentCode())
            .as("phrase '%s' must route to RESTAURANT_ECONOMICS_ANALYSIS", phrase)
            .isEqualTo("RESTAURANT_ECONOMICS_ANALYSIS");
    }
}

@Test  
void doesNotMisrouteCustomerFollowupPhrases() {
    String[] phrases = {
        "今天该跟谁", "今日跟进", "客户优先级", "微信记录", "商机预警",
    };
    for (String phrase : phrases) {
        var result = orchestrator.execute("RES_3101_009",
            IntentExecuteRequest.builder().userInput(phrase).build(), 1L, "warehouse_manager");
        assertThat(result.getIntentCode())
            .as("phrase '%s' must NOT route to RESTAURANT_ECONOMICS_ANALYSIS", phrase)
            .isNotEqualTo("RESTAURANT_ECONOMICS_ANALYSIS");
    }
}
```

---

## Implementation order (P2)

1. Fix 1: move `tryOrchestratorPhraseShortcut` earlier (~10 min code + compile)
2. Fix 3: add 30+ phrase variations (~10 min)
3. Fix 4: V_24 migration (~10 min)
4. Fix 2: defense-in-depth check in `handleEarlyQuestionTypeDetection` (~10 min)
5. Fix 5: unit test cases (~20 min)
6. mvn compile + test (~10 min)
7. Deploy to prod via `./scripts/deploy/deploy-backend.sh --env prod` (~10 min including BG cutover)

Total P2: ~80 min (within 3h budget).

---

## P3 verification

Re-run `web-admin/tests/e2e-customer-journey/full-customer-flow-2026-05-23.spec.ts` (12 cases). Expected:
- qhj_warehouse_mgr (RES_3101_009): 4/4 route to RESTAURANT_ECONOMICS_ANALYSIS (will get "(B) 数据缺" from Composite Tool but that's P0-3 餐饮 chat scope — routing PASS = Sprint 12 success)
- f006_admin (F006): if RESTAURANT_ECONOMICS_ANALYSIS not configured for F006 (manufacturer), phrases fall through to normal pipeline + might still misroute. NOT in Sprint 12 P0 scope.
- warehouse_mgr1 (F001): same as F006 — not RESTAURANT factory.

**Success metric**: at least RES_3101_009 4/4 → RESTAURANT_ECONOMICS_ANALYSIS routing (regardless of Tool data quality). Customer demo phrases hit the right intent.

If RES_3101_009 succeeds 4/4 AND others fall through gracefully (NOT misrouted to DAILY_CUSTOMER_FOLLOWUP) → success.

---

## Anti-goals (per Steve goal)

- ❌ Fix P0-3 Composite Tool internal data (餐饮 chat Sprint 12 task)
- ❌ Fix P0-2 LLM 幻觉 guard (separate task)
- ❌ 1h paperwork (per Goal v5 8h lesson — this Sprint 12 needs real impl + UI re-test)
- ❌ Touch IntentKnowledgeBase weights (per HARD rule `feedback_negative_keywords_useless_in_cretas_intentmatching.md`)

---

## DoD

- [x] Spec doc written (this file)
- [ ] Fix 1+2+3 in IntentExecutionOrchestrator.java + IntentKnowledgeBase.java
- [ ] V_24 migration file
- [ ] IntentRoutingTest 12+ NL cases PASS locally
- [ ] mvn compile + mvn test green
- [ ] Deploy to prod 47:10010 (BG cutover via deploy-backend.sh)
- [ ] Re-run full-customer-flow-2026-05-23.spec.ts → RES_3101_009 4/4 routes to RESTAURANT_ECONOMICS_ANALYSIS
- [ ] 12 PNG re-captured, before/after diff doc written
- [ ] Single PR committed + admin-merged

---

## Cross-references

- Goal v5 verdict: `docs/audits/sprint-11-ux-audit/verdict-2026-05-23.md`
- Goal v5 60-cell: `docs/audits/sprint-11-ux-audit/output-quality-matrix.md`
- Cross-verify: `docs/audits/sprint-11-ux-audit/mealclaw-cross-verify.md`
- Round 6 PR #190 + Round 7 PR #204 — base for Fix 3 expansion
- HARD rule: `feedback_negative_keywords_useless_in_cretas_intentmatching.md` (why Fix 4 alone isn't enough)
- Orchestrator: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java`
- IntentKnowledgeBase: `backend/java/cretas-api/src/main/java/com/cretas/aims/config/IntentKnowledgeBase.java`
- BI chat Sprint 12 backlog (handoff): would be docs/sprint-12-backlog/* (separate from this fix)
