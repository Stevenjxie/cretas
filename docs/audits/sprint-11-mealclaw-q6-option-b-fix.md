# Sprint 11 Q6 Option B — Workdesk context.month fix + Vue event-arg bug

**Date**: 2026-05-24
**Branch**: `fix/sprint11-q6-option-b-workdesk-context-2026-05-24`
**Author**: Q6 Option B subagent (single chat dispatch)
**Verification**: Playwright 4/4 PASS on test env 139:8097

---

## Background

Sprint 11 audit (PR #215) + Sprint 11.5 Phase F.1 (DB COPY 31 rows, 2026-05-24) + Q7/Q8 fresh
Playwright (PR #253) flagged 2 NEW P0 bugs:

* **Bug A (UI)**: `WarehouseKeeperWorkdesk.vue:555-564` `callIntentExecute()` doesn't pass
  `context.month`. Restaurant-economics queries from warehouse keeper Workdesk arrive at
  backend with `body = {userInput}` only, no period bound, no role-context hint.
* **Bug B (Backend routing)**: Curl test 2026-05-24 06:42 with `{userInput,context:{month:"2025-12"}}`
  routed `哪个菜亏钱` → `RESTAURANT_ECONOMICS_ANALYSIS` ✅, but UI test (no context) routed
  → `MATERIAL_TODAY_RECEIVING_QUERY` ❌. PM hypothesis: BERT classifier uses text-only and
  misroutes on `role=warehouse_manager` context.

Steve picked Option B: fix Workdesk + re-verify Playwright, ≤6 hr budget.

---

## Root cause investigation

### Bug A confirmed but turned out partly cosmetic

`callIntentExecute()` in WarehouseKeeperWorkdesk + SalesOwnerWorkdesk never accepted a
`context` arg. Fix: add 5th param `context?: Record<string, unknown>` (Warehouse) / 3rd
param (Sales), and in `sendQuery` attach `context: {month}` when input looks like a
restaurant-economics query (`损溢/损益/亏/利润/毛利/成本/盈利/赚/p&l/经营` keyword match).
`parseMonthFromInput` resolves explicit `YYYY-MM` / `YYYY年M月` / `M月` / `本月` / `上月`
forms and falls back to "上月" when nothing parses.

### Bug B turned out to be Vue event-arg bug, not BERT classifier

After deploying the context.month fix, Playwright STILL returned
`intentCode=MATERIAL_TODAY_RECEIVING_QUERY` for all 4 phrases. Investigation via a
diagnostic Playwright script that captured raw `request.postData()` revealed:

```
[user-0] body: {"userInput":"哪个菜亏钱","intentCode":"MATERIAL_TODAY_RECEIVING_QUERY"}
```

The user-triggered request was carrying `intentCode='MATERIAL_TODAY_RECEIVING_QUERY'`.

**Root cause**: All 7 Workdesk Vue templates wire the send button as
`@click="sendQuery"` (no parens). Vue passes the `MouseEvent` as the first positional arg
→ `sendQuery(event)` → JavaScript reads `event` as truthy → ternary
`autoTrigger ? 'MATERIAL_TODAY_RECEIVING_QUERY' : undefined` resolves to the auto-trigger
path → backend gets explicit intentCode and routes the user-typed phrase to the
warehouse-keeper auto-query intent.

The 2026-05-24 06:42 curl test passed because curl-level requests never went through this
path — they hit `/ai-intents/execute` directly without forced intentCode. **The misroute
was a UI bug all along, NOT a BERT classifier bias.** Bug B is downgraded to "not
confirmed at backend".

### Fix (2-part, narrow)

1. `@click="sendQuery"` → `@click="sendQuery()"` (explicit zero-arg call)
2. `@keydown.enter.ctrl="sendQuery"` → `@keydown.enter.ctrl="sendQuery()"`

Applied to WarehouseKeeperWorkdesk + SalesOwnerWorkdesk only (other 5 Workdesks have
same bug — see Task 2 list below).

---

## Task 2 — Sister Workdesk audit (read-only)

All 7 Workdesks share the same Vue event-arg bug and the same missing-context bug:

| File | Has @click="sendQuery" bug? | Passes context? | This PR fixes? |
|---|---|---|---|
| WarehouseKeeperWorkdesk.vue | YES (line 58) | NO | **YES** |
| SalesOwnerWorkdesk.vue | YES (line 51) | NO | **YES** (per brief, customer audit depends) |
| PurchaserWorkdesk.vue | YES (line 57) | NO | NO — defer |
| ProductionManagerWorkdesk.vue | YES (line 62) | NO | NO — defer |
| FinanceManagerWorkdesk.vue | YES (line 51) | NO | NO — defer |
| QualityManagerWorkdesk.vue | YES (line 56) | NO | NO — defer |
| QualityChiefWorkdesk.vue | YES (line 57) | NO | NO — defer |

**Recommendation for follow-up sprint**: bulk-fix the remaining 5 Workdesks with the same
2-line template change (`sendQuery` → `sendQuery()`). Low risk, high consistency value.
Context.month attachment is more nuanced for non-restaurant Workdesks — should be evaluated
per Workdesk role.

---

## Task 3 — BERT classifier root-cause investigation

**Conclusion**: Bug B (BERT misroute on `哪个菜亏钱` for `role=warehouse_manager`) is NOT
confirmed. The apparent misroute was the Vue event-arg bug masking actual routing.

Direct curl evidence on test env 8097 (without context, after fix not deployed yet at curl
moment):

```
$ curl ... '{"userInput":"哪个菜亏钱"}'
intentCode: RESTAURANT_ECONOMICS_ANALYSIS  ✅
```

The phrase-shortcut path in `IntentExecutionOrchestrator.tryOrchestratorPhraseShortcut`
(commit b58, line 200-214) catches `哪个菜亏钱` via `IntentKnowledgeBase.restaurantPhraseMapping`
BEFORE the BERT classifier ever runs. As long as the input doesn't force `intentCode`,
routing is deterministic.

**However, observations about BERT classifier still worth following up**:

* `backend/python/classifier/services/intent_classifier.py` uses **text-only** input
  (no role / no factoryType / no context). When users type free-text NL that isn't in
  `restaurantPhraseMapping`, classifier has no role bias signal to use.
* The Java side (`IntentRecognitionPipelineServiceImpl`) doesn't pass role to the Python
  classifier per the inspected setter chain.

**Recommended follow-up sprint scope (NOT this PR)**:

1. Audit `IntentKnowledgeBase.restaurantPhraseMapping` for completeness — every NL phrase
   customer naturally types for restaurant economics should hit the phrase shortcut.
   Sprint 11 Round 6+7 added 30+ phrases; Sprint 12 should continue NL coverage.
2. Add role/factoryType signal to the Python BERT classifier API — would catch NL phrases
   not yet in `restaurantPhraseMapping`. Modest API change.
3. Per `feedback_negative_keywords_useless_in_cretas_intentmatching` HARD: tuning DB
   negative_keywords won't help; weights dominate. Address via `IntentKnowledgeBase` or
   per-intent weight override.

---

## Task 4 — Playwright re-verify (4/4 PASS)

Run cmd (test env):
```
cd web-admin && E2E_BASE_URL=http://139.196.165.140:8097 \
  E2E_API_BASE=http://139.196.165.140:8097/api/mobile \
  E2E_USER=qhj_warehouse_mgr E2E_PASS=123456 E2E_FACTORY_ID=RES_3101_009 \
  npx playwright test --project=mealclaw-customer-ui --reporter=list
```

Result: **5 passed (2.4m)**. All 4 Q7 phrases now route to
`intentCode: RESTAURANT_ECONOMICS_ANALYSIS`. Q8 UX state capture also PASS.

| Phrase | API status | intentCode | UI formattedText (head 80 char) |
|---|---|---|---|
| 帮我看上月损溢异常 | 200 | RESTAURANT_ECONOMICS_ANALYSIS | 部分数据不可用: P&L 一页纸 / 档口损溢 / 成本刚性. ... |
| 损益分析 | 200 | RESTAURANT_ECONOMICS_ANALYSIS | 部分数据不可用: P&L 一页纸 / 档口损溢 / 成本刚性. ... |
| 上月成本 | 200 | RESTAURANT_ECONOMICS_ANALYSIS | 部分数据不可用: P&L 一页纸 / 档口损溢 / 成本刚性. ... |
| 哪个菜亏钱 | 200 | RESTAURANT_ECONOMICS_ANALYSIS | 部分数据不可用: P&L 一页纸 / 档口损溢 / 成本刚性. ... |

**The "部分数据不可用" content is a separate data-availability issue** — fixed at the DB
level in Sprint 11.5 Phase F.1 (2026-05-24 COPY 31 rows from smartbi_prod_db ETL),
but the analysis Tool still can't fetch financial_metrics for the test factory because
the test env's smartbi_db doesn't have the same Phase F.1 data backfill (only prod
smartbi_prod_db received the 31 rows). Routing is now correct; data backfill on test env
is a follow-up.

New PNGs saved at `docs/audits/sprint-11-mealclaw-screenshots/round2/`:
* `phrase-1-shangyue-sunyi-happy-path.png`
* `phrase-2-sunyi-fenxi-happy-path.png`
* `phrase-3-shangyue-chengben-happy-path.png`
* `phrase-4-nage-cai-kuiqian-happy-path.png`

---

## Local quality checks

* `npx vue-tsc --noEmit` — clean (no errors)
* `npx vite build` — clean (3.4 MB tarball, 586 assets, 40s)
* `npx vitest run` — 387 passed | 5 skipped (no test broke)
* Deploy `--env test` — HTTP 200 on 139:8097
* Entry chunk hash matches local dist (verified post-deploy)

---

## Files changed (scope-locked)

* `web-admin/src/views/workdesk/WarehouseKeeperWorkdesk.vue` — Q6 Option B fix + Vue event-arg fix
* `web-admin/src/views/workdesk/SalesOwnerWorkdesk.vue` — Q6 Option B fix + Vue event-arg fix
* `docs/audits/sprint-11-mealclaw-q6-option-b-fix.md` — this doc
* `docs/audits/sprint-11-mealclaw-screenshots/round2/*.png` (4 new PNGs)

**Wall-clock**: ~2 hr (under 6 hr budget).
