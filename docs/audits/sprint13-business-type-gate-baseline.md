# Sprint 13 业态门控 (Business-Type Gating) — Phase A Baseline

**Issue:** #305 — Cretas has 2 customer types (RESTAURANT vs FACTORY), but AI intents aren't gated by business type, so a 卤味 manufacturer (F006) can trigger restaurant analysis and get a half-broken "数据不可用".
**Date:** 2026-05-29 · **Chat:** sprint13-business-type-gate · **Branch:** `feat/sprint13-business-type-gate`
**Method:** systematic-debugging Phase 1 (reproduce + understand before changing). All evidence = fresh exec on prod (cold cache, random sessionId) + SSH DB + code read.

---

## 1. 业态错配 reproduced (prod, fresh exec, random sessionId — not cache)

Same query `"2025年12月哪个菜亏钱"` → intent `RESTAURANT_ECONOMICS_ANALYSIS`, status SUCCESS, on both factories:

| Factory | factory.type | fresh-exec result |
|---|---|---|
| **F006** (六膳门食品科技, `f006_admin`) | `FACTORY` | `summary.dataAvailable=FALSE`, "P&L 一页纸数据获取失败: 未提供 financial_metrics" — **misleading half-broken state** |
| **RES_3101_009** (QHJ_PROD, `qhj_prod`) | `RESTAURANT` | `dataAvailable=TRUE`, ¥1,935,193 (Dec) / ¥1,840,457 (Nov) — correct |

F006 has **0** `smart_bi_finance_data` + **0** `restaurant_pos_data` → no restaurant P&L is even possible. The honest answer should be *"本厂为制造业态，无餐饮经营数据"* + a factory-analysis next-action, NOT a half-broken restaurant P&L error.

(Note: the fake ¥1,541,082 a F006 user saw earlier = RES_3101_009's Dec **net profit** ¥1,935,193 − ¥394,111 = ¥1,541,082 → stale cross-factory cache; see #274 trigger-bug.)

## 2. factory.type — the clean gate key

`factories.type` cleanly distinguishes the two; `IntentConfigManagementServiceImpl.resolveBusinessDomain(factoryId)` already maps it:
```
F006         → type=FACTORY    → resolveBusinessDomain = "FACTORY"
RES_3101_009 → type=RESTAURANT → resolveBusinessDomain = "RESTAURANT"
```

## 3. Intent classification — `ai_intent_configs.business_type`

Distribution: **FACTORY 403 / COMMON 233 / RESTAURANT 32**. Of `RESTAURANT_*` codes: **32 correctly = RESTAURANT, 20 mis-tagged = COMMON** (the root cause). COMMON = available to every factory type, so the 20 leak onto FACTORY factories.

**20 mis-tagged `RESTAURANT_* business_type=COMMON` (all genuinely restaurant-exclusive → must become RESTAURANT):**
RESTAURANT_ECONOMICS_ANALYSIS, RESTAURANT_BOM_VARIANCE, RESTAURANT_COMBO_SPLIT, RESTAURANT_DAILY_RECONCILIATION, RESTAURANT_LABOR_PRODUCTIVITY, RESTAURANT_PERFORMANCE_EVAL, RESTAURANT_PERFORMANCE_RULE, RESTAURANT_PIECEWORK_CALC, RESTAURANT_PIECEWORK_CONFIG, RESTAURANT_PROCUREMENT_FORECAST, RESTAURANT_RETURN_ANOMALY, RESTAURANT_REVIEW_COMPETITIVE, RESTAURANT_SALES_PLAN_CREATE, RESTAURANT_SALES_PLAN_TRACK, RESTAURANT_SEAT_CONFIG_MANAGE, RESTAURANT_SEAT_OCCUPANCY, RESTAURANT_SHIFT_ANALYSIS, RESTAURANT_SHIFT_CREATE, RESTAURANT_SMART_REORDER, RESTAURANT_STORE_KPI_DASHBOARD.

> ⚠️ Note: `FACTORY_*` codes are NOT uniformly business_type=FACTORY — e.g. `FACTORY_CONFIG_AGENT`, `FACTORY_MR_*` are COMMON (config / material-requisition = genuinely universal). So **gate by `business_type`, NOT by code prefix** (prefix-gating would wrongly block universal config intents on restaurants). This is why the anti-goal "gate 太狠把通用 intent 挡了" matters.

## 4. Existing gate (`v32.1 业态隔离`) — why it's incomplete

`IntentRecognitionPipelineServiceImpl.tryLlmFallback` (line ~2175) already filters candidates by domain:
- RESTAURANT factory → keep `business_type ∈ {null, COMMON, RESTAURANT}`
- FACTORY factory → **exclude `business_type=RESTAURANT`** (keep FACTORY/COMMON/null)

Three gaps:
1. **Mis-classification (#3)** — `RESTAURANT_ECONOMICS_ANALYSIS` is COMMON, so the filter KEEPS it for F006. Re-tagging (Phase B.1) fixes this.
2. **LLM-path only** — phrase shortcut (`tryOrchestratorPhraseShortcut`), keyword/semantic scoring, and explicit-`intentCode` from the frontend all bypass this filter.
3. **No honest message** — it only *drops* candidates (→ generic no-match), never returns the "本厂非餐厅业态 + 工厂替代" guidance (fool-proof Rule 5).

## 5. BI indicator misseed — already mitigated

The 5 `RESTAURANT`-category indicators on F006 (RESTAURANT_AVG_ORDER_VALUE / DISH_MARGIN / FOOD_SAFETY_PASS / TABLE_TURNOVER / WASTAGE_RATE) are now `is_active=f` (deactivated, likely #263/#306). Remaining: confirm they don't render; decide hard-delete vs leave-inactive.

---

## Phase B design (gate by business_type, honest empty-state)

1. **Data:** re-tag the 20 `RESTAURANT_* COMMON → RESTAURANT` (Flyway migration). Makes `business_type` the single source of truth + fixes the existing v32.1 filter.
2. **Execution gate (catch-all):** in the execution path (after intent resolved by ANY route, before tool/skill execute) add:
   ```
   domain = resolveBusinessDomain(factoryId)            // RESTAURANT | FACTORY
   intentBiz = intent.getBusinessType()                  // RESTAURANT | FACTORY | COMMON | null
   if (intentBiz domain-exclusive && intentBiz != domain) → honest empty-state response
   ```
   - COMMON / null → pass (universal, per anti-goal).
   - Honest response (fool-proof Rule 5): `"本厂为[制造]业态，无餐饮经营数据。工厂经营分析请用：库存 / 采购 / 质检 / 出品率"` + actionHint with alternative intents. Reverse for FACTORY-exclusive on RESTAURANT.
3. **BI:** confirm 5 F006 RESTAURANT indicators don't render (is_active=f); hard-delete if any path ignores is_active.

## Phase C verify (fresh exec, random sessionId)
- F006 + RESTAURANT_ECONOMICS → honest 业态 message + factory next-action (not "数据不可用").
- RES_3101_009 + RESTAURANT_ECONOMICS → unchanged ¥ P&L.
- Same-pattern sweep: all RESTAURANT_* / FACTORY-exclusive intents gate consistently cross-type; COMMON intents pass for both.
- Independent verify by organizer (per Rule 19 — no self-verify).
