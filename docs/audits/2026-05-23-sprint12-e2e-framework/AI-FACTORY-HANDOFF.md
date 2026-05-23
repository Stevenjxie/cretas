# Sprint 12 — Intent Routing Bug Handoff to AI Factory Chat

**Date**: 2026-05-23
**From**: Canvas/Workdesk chat (Steve dispatched, owns Skill registration + outputFormatter + E2E)
**To**: AI Factory chat (owns intent classifier + LLM prompt template per coop split)
**Audit run**: `docs/audits/2026-05-23-sprint12-e2e-framework/runs/20260523_125132/`
**Result**: Strict 90.0% (54/60), close-gate "≥80%" HIT — but 6 remaining FAILs are all intent ROUTING bugs that Canvas/Workdesk chat cannot fix.

---

## TL;DR

6 of 60 strict FAILs are **intent classifier misroutes**, not formatter problems. Canvas/Workdesk chat already enriched all outputFormatters (PRs #239, #245, #247, #248 merged + deployed v20260523_124605). The remaining 6 are caused by:

- 2 critical: read-query input → WRITE intent (`MATERIAL_BATCH_CREATE`)
- 2 critical: legit HACCP query → `FOOD_KNOWLEDGE_QUERY` with no executor
- 2 high: legit business query → catch-all `REPORT_DASHBOARD_OVERVIEW` returning empty data

These cannot be fixed by enriching the formatter (the routed Tool returns its actual data — empty dashboard returns "生产批次: 0"). The fix is at the intent classification layer.

---

## 6 Routing Bugs — Evidence

| # | User input | WRONG intent classified | Correct intent | Severity |
|---|---|---|---|---|
| 1 | `这个月业绩如何` | `REPORT_DASHBOARD_OVERVIEW` (catch-all) | `MONTHLY_FINANCIAL_CLOSE` | HIGH |
| 2 | `今日 HACCP 状态` | `FOOD_KNOWLEDGE_QUERY` (no executor!) | `FOOD_SAFETY_RECALL` or HACCP-specific tool | CRITICAL |
| 3 | `近三年所有 HACCP 监控` | `FOOD_KNOWLEDGE_QUERY` (no executor!) | Same as #2 | CRITICAL |
| 4 | `本日待入库` | **`MATERIAL_BATCH_CREATE` (WRITE op!)** | `WAREHOUSE_KEEPER_TODAY_TASKS` | **CRITICAL — write-on-read** |
| 5 | `上月入库统计` | `REPORT_DASHBOARD_OVERVIEW` (catch-all) | Inventory statistics intent | HIGH |
| 6 | `入库` | **`MATERIAL_BATCH_CREATE` (WRITE op!)** | `NEED_CLARIFICATION` (vague single noun) | **CRITICAL — single-word noun → WRITE** |

### Raw response excerpts (from `runs/20260523_125132/raw-*.json`)

```
finance-manager B-base "这个月业绩如何":
  intent = REPORT_DASHBOARD_OVERVIEW
  resultData = 仪表盘总览 / 生产批次: 0 | 进行中: 0 / 今日产量: 0 kg
  → 33-char empty dashboard, no monthly financial close data

quality-manager B-syn1 "今日 HACCP 状态":
  intent = FOOD_KNOWLEDGE_QUERY (status: FAILED)
  message = 意图"食品安全知识查询"(FOOD_KNOWLEDGE_QUERY)已识别，但暂未配置执行器
  → bound intent has no Tool/Skill registered

warehouse-keeper B-syn3 "本日待入库":
  intent = MATERIAL_BATCH_CREATE
  message = 原料批次创建成功！批次号：null
  → read query "today's pending inbound" triggered a WRITE op that created a batch (with null number — likely no-op but data hazard)

warehouse-keeper Bd-vague "入库":
  intent = MATERIAL_BATCH_CREATE
  message = 原料批次创建成功！批次号：null
  → single-word noun "inbound" triggers WRITE op
```

---

## Root Cause Hypotheses

### Hypothesis A: `MATERIAL_BATCH_CREATE` keyword overlap with read queries

The classifier likely has `入库` as a keyword for `MATERIAL_BATCH_CREATE` because batch creation is part of the "inbound" workflow. But `本日待入库` and `入库` (alone) are read-shaped: "today's pending inbound" and "inbound". The classifier needs:

1. A read/write disambiguation pre-filter — single-noun input or query-shaped input ("today/this/show me") should never route to a WRITE intent.
2. Stricter keyword bindings for `MATERIAL_BATCH_CREATE` — require explicit verbs like `创建/新增/录入`.

### Hypothesis B: `FOOD_KNOWLEDGE_QUERY` configured but executor unbound

`SELECT * FROM ai_intent_configs WHERE intent_code = 'FOOD_KNOWLEDGE_QUERY'` likely shows the intent active but `tool_name IS NULL` and no Skill registered. Either:

1. Bind a Tool/Skill to it (food knowledge RAG already exists at Python `/api/food-kb/*`)
2. OR remove it from active set (set `is_active = false`) so it can't be matched
3. OR re-route HACCP queries to `FOOD_SAFETY_RECALL` keyword bindings

### Hypothesis C: `REPORT_DASHBOARD_OVERVIEW` as fallback catch-all

`这个月业绩如何` and `上月入库统计` are both routed to the dashboard — likely because the classifier's KEYWORD layer doesn't have strong matches for monthly-financial / inventory-statistics, and the SEMANTIC/LLM layer falls back to dashboard as a generic option. Either:

1. Add stronger keyword bindings for `MONTHLY_FINANCIAL_CLOSE` (`业绩/月度/本月业绩`) and inventory statistics intents.
2. Add SEMANTIC examples for these queries to the embedding index.
3. Lower the confidence threshold for dashboard fallback so the classifier produces NEED_CLARIFICATION when uncertain.

---

## What Canvas/Workdesk Chat Already Did (cannot help further with routing)

Merged + deployed v20260523_124605:

- **PR #239** — purchaser-A outputFormatter enrichment + `buildClarificationWithChoices` helpers in IntentExecutionOrchestrator (took strict 58% → 80%)
- **PR #245** — removed 📊💵💰💼 emoji from 10 Tool message strings (B-end emoji = 0)
- **PR #247** — QualityCheckQueryTool message enrichment (empty + non-empty states, +80 chars + top-3 preview)
- **PR #248** — `buildClarificationResponse` default-suggestions path also uses `buildChoicesLine`

After analyzer DOMAIN_KEYWORDS_RE enrichment (季度|上季|营收|业绩|KPI|OEE|生产率|合格率|生产批次 added), strict rate is 90.0%.

---

## Coop Action Items for AI Factory Chat

Per the Sprint 12 close-gate row "Coop deliverable with AI Factory ≥1", at least one of these PRs is needed. Listed by ROI:

### Priority P0 (CRITICAL — data hazard)

**1. Fix `本日待入库` and `入库` → MATERIAL_BATCH_CREATE write misroute**

- Investigate: `SELECT * FROM ai_intent_configs WHERE intent_code = 'MATERIAL_BATCH_CREATE'` — check `keywords` JSON array. Likely contains `入库`.
- Fix: remove read-shaped keywords or add a write-verb pre-filter. Suggested approach:
  ```sql
  UPDATE ai_intent_configs
  SET keywords = '["创建批次","新增批次","录入入库","入库新增","新批次"]'  -- removed bare 入库
  WHERE intent_code = 'MATERIAL_BATCH_CREATE';
  ```
- Add SEMANTIC examples for `WAREHOUSE_KEEPER_TODAY_TASKS` covering `本日待入库 / 今日入库 / 今天有什么入库`.

### Priority P1 (HIGH — broken feature)

**2. Bind executor to `FOOD_KNOWLEDGE_QUERY` OR remove from active set**

- Investigate: `SELECT intent_code, tool_name, is_active FROM ai_intent_configs WHERE intent_category = 'FOOD_KNOWLEDGE'`
- Option A: bind `food_knowledge_rag_query` Tool (Python `/api/food-kb/*` exists)
- Option B: `UPDATE ai_intent_configs SET is_active = false WHERE intent_code = 'FOOD_KNOWLEDGE_QUERY'` so classifier can't reach it
- Then re-route HACCP queries: add `HACCP / 食安监控 / 食安状态 / 食安告警` to `FOOD_SAFETY_RECALL` keyword list

### Priority P2 (HIGH — wrong-intent fallback)

**3. Strengthen `MONTHLY_FINANCIAL_CLOSE` and inventory statistics intent bindings**

- Add KEYWORD bindings: `业绩 / 月度业绩 / 本月业绩 / 经营月度 / 业绩如何` → `MONTHLY_FINANCIAL_CLOSE`
- Add inventory statistics intent (or bind `上月入库统计 / 入库流水 / 入库汇总` to existing Tool)
- Lower `REPORT_DASHBOARD_OVERVIEW` fallback confidence threshold

---

## Sister-PR Linkback

When AI Factory chat ships fixes, link them in:
- Sprint 12 mid-progress doc: `docs/audits/2026-05-23-sprint12-mid-progress.md` (this chat will update)
- This handoff doc — section "PRs Resolving These Bugs" (will append on receipt)
- The Sprint 12 close doc citing `Coop deliverable with AI Factory ≥1` row HIT

---

## Verification After AI Factory Fixes

Re-run the 60-path audit:
```bash
bash docs/audits/2026-05-23-sprint12-e2e-framework/runner.sh
PYTHONIOENCODING=utf-8 python docs/audits/2026-05-23-sprint12-e2e-framework/analyze-expanded.py \
  docs/audits/2026-05-23-sprint12-e2e-framework/runs/<new-timestamp>/
```

Expected after AI Factory fixes: strict ≥95% (54+6 = 60/60 if all 6 routing bugs fixed), operational 100%.

---

## Effort Estimate

- AI Factory chat work: ~0.5d (3 DB UPDATE statements + 1 Tool/Skill binding + optional SEMANTIC example seeding)
- Canvas/Workdesk re-audit: ~10min (run runner + analyzer)
- **Total to close strict row to 100%**: ~0.5d
