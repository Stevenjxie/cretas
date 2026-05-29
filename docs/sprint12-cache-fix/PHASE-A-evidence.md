# Phase A — Cache + Routing Bug Evidence (2026-05-29)

**Chat**: sprint12-cache-fix
**Branch**: `feat/sprint12-cache-fix-2026-05-29`
**Source brief**: AI 工厂 chat 5/28 PM handoff — 5 DOD covering cache purge + 2 routing FAIL edge case + Playwright race
**Source audit**: `docs/audits/2026-05-28-sprint11-ai-workdesk-audit/audit.md` + gh issue #277

---

## TL;DR — Brief's H1 (stale cache poisoning) is partially falsified

| Hypothesis | Brief claim | Phase A evidence | Verdict |
|---|---|---|---|
| **H1**: stale `tool_call_cache` rows serve pre-Sprint-12 DAILY_CUSTOMER_FOLLOWUP routing | "5 days accumulation → 10/12→3/12 regression" | `tool_call_cache` GLOBAL **0 rows**; `semantic_cache` GLOBAL **0 rows**; TTL is **1 hour** | Cache NOT currently poisoning. Was plausible AT THE MOMENT of 5/28 audit but data has expired |
| **H2**: SalesOwner auto-mount → `handleConversationContinuation` (line 564-622) inherits `intentCode=DAILY_CUSTOMER_FOLLOWUP` via `request.setIntentCode(conversationResp.getIntentCode())` (line 578) | Hypothesized in audit, not in brief | Code path confirmed at line 576-580; runs **before** phrase shortcut (#0.25 line 187-216). `conversation_sessions` table exists with status/intent_code columns | **Plausible** — needs live repro to confirm |
| **H3**: Playwright `page.on('request')` captures last POST not user-click POST | HARD rule + brief Phase D | Audit smoke proved phrase1 captured wrong body | **Confirmed** — independent of routing bug |
| **H4 (new)**: Restaurant intent_configs missing for RES_3101_009 / F006 — phrase shortcut returns null when factory-scoped intent absent (line 809-815) | Not in brief or audit | `ai_intent_configs WHERE factory_id IN ('RES_3101_009','F006')` returns **0 rows**; RESTAURANT_ECONOMICS_ANALYSIS exists only as 1 GLOBAL row (factory_id NULL) | **Probable** root cause — needs verification of `aiIntentService.getIntentByCode` fallback behavior |

**Bottom line**: Brief's cache-purge fix is **necessary but not sufficient**. True root cause likely combination of H2 (session continuation pollution) + H4 (intent config seed missing) + H3 (test infra race).

---

## Evidence (from prod 47.100.235.168)

### E1. semantic_cache state
```sql
SELECT COUNT(*) AS total_rows, COUNT(DISTINCT factory_id) AS factories
FROM semantic_cache WHERE deleted_at IS NULL;
-- total_rows=0, factories=0
```
Config: enabled=true, factory_id='*', cache_ttl_hours=1, similarity_threshold=0.85

### E2. tool_call_cache state
```sql
SELECT COUNT(*) FROM tool_call_cache;  -- 0
SELECT COUNT(*) FROM tool_call_cache WHERE cached_result::text LIKE '%缓存结果%';  -- 0
```

### E3. Intent configs missing
```sql
SELECT factory_id, COUNT(*) FROM ai_intent_configs
WHERE factory_id IN ('RES_3101_009','F006','F001') GROUP BY factory_id;
-- F001: 291 (none of which are RESTAURANT/DAILY_CUSTOMER)
-- F006: 0
-- RES_3101_009: 0
```

```sql
SELECT factory_id, intent_code FROM ai_intent_configs
WHERE intent_code = 'RESTAURANT_ECONOMICS_ANALYSIS';
-- 1 row: factory_id=NULL/empty (global fallback only)
```

### E4. V_24 Sprint 12 migration applied
```sql
SELECT version, description, installed_on FROM flyway_schema_history
WHERE description ILIKE '%sprint12%';
-- 20260824.50 | sprint12 restaurant economics negative keywords | 2026-05-23 16:14
```
The negative_keywords migration shipped but **does not seed intent_configs per-factory**.

### E5. Code path order in IntentExecutionOrchestrator.execute() (line 164-244)
```
0.   if explicit intentCode → executeWithExplicitIntent
0.3. if sessionId → handleConversationContinuation (line 178-185)    ← H2 trigger point
       └ if conversationResp.isCompleted && intentCode → setIntentCode + forceExecute → executeWithExplicitIntent
0.25. if userInput → tryOrchestratorPhraseShortcut (line 200-216)     ← Sprint 12 fix
       └ returns null if factory-scoped intent missing (line 809-815) ← H4 fall-through
0.3.  handleEarlyQuestionTypeDetection (line 218-225) — has defense-in-depth phrase route at line 714-727
0.5.  handleSemanticCache (line 227-230) — primes context, doesn't return
1+.   normal recognition pipeline
```

**Key insight**: `handleConversationContinuation` runs at **#0.3** which is BEFORE the Sprint 12 phrase shortcut at **#0.25**. Wait — actually re-reading, **#0.3 conversation continuation is at line 178-185 which executes BEFORE #0.25 phrase shortcut at line 200-216**. Yes — order is #0 explicit → #0.3 session continuation → #0.25 phrase shortcut → #0.3.5 early question type → #0.5 semantic cache → #1 pipeline.

So if auto-mount establishes a session with completed DAILY_CUSTOMER_FOLLOWUP intent, subsequent user query hits #0.3 first and inherits the intent. Phrase shortcut never gets a chance.

---

## Sister chat / concurrent state

- **`my-prototype-logistics-sprint12-bi-backend` worktree active** on `feat/sprint12-indicator-service-rewrite` — will need cache purge endpoint (per brief)
- **`mealclaw-pm-coord` worktree active** on `worktree-mealclaw-pm-coord` (commit `067b8281b` — WarehouseKeeperWorkdesk-only scrubber)
- **Dirty WIP in main worktree** from prior session: IntentKnowledgeBase.java (+35 lines Sprint 12 finance phrase shortcuts) + AccountsReceivableAgingTool.java (enriched emoji-free message). **Stashed** as `stash@{0}` — preserved for owner recovery, not part of this PR.

---

## Scope decision matrix

| Brief DOD | Phase A evidence impact | Recommended action |
|---|---|---|
| (a) Cache purge endpoint + IndicatorChangeListener / RoutingChangeListener | Cache currently empty BUT TTL 1hr means new pollution can re-accumulate. Sister chats (BI + 餐饮) need this regardless | **PROCEED** as planned — still valuable infrastructure |
| (b) Reproduce 5/28 Steve screenshot + Rule 22 miss/hit consistent | Cache empty → cannot reproduce H1 visible (缓存结果) leak NOW. Can still write unit tests for the cache layer | **PROCEED** with unit-level Rule 22 test (no live cache to reproduce, but hygiene gate still valuable) |
| (c) 2 routing FAIL edge case fix + 12/12 PASS | Real bug appears to be 9/12 not 2/12. Root cause is NOT IntentKnowledgeBase phrase — likely H2 session continuation OR H4 intent_config seed | **REVISE** — needs deeper investigation + likely orchestrator order fix OR intent_configs seed |
| (d) Playwright capture race fix | Confirmed valid independent of root cause | **PROCEED** as planned |
| (e) PR merge + sister sync | — | **PROCEED** as planned |

---

## Proposed next actions

1. **Phase B (cache purge endpoint)** — proceed unmodified. Sister chats need it; future cache hygiene gate.
2. **Phase B.2 (new)** — investigate H2: read `ConversationService.continueConversation` + reproduce live API misroute with sessionId injection
3. **Phase B.3 (new)** — investigate H4: trace `aiIntentService.getIntentByCode(factoryId, code)` global fallback logic
4. **Phase C revised** — based on B.2/B.3, fix the dominant root cause (likely orchestrator phrase shortcut moves before session continuation, OR intent_configs seed migration, OR both)
5. **Phase D** — proceed unmodified (Playwright race independent)
6. **Phase E** — final PR + sync

---

## Audit trail

- Phase A discovery: 2026-05-29 morning, ~1.5h tool time
- SSH probe via root@47.100.235.168 on cretas_prod_db
- Live API auth login OK (token 259 chars), execute endpoint returned 401 with that token format — needs further header debugging (not blocking Phase A conclusions)
