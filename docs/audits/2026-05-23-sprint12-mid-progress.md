# Sprint 12 Mid-Progress — Strict 90% + AI-Factory-Handoff written

**Date**: 2026-05-23
**Chat**: Canvas/Workdesk chat
**Status**: 5/10 close-gate rows fully met, 3/10 partial, 2/10 blocked on AI Factory coop dispatch.

---

## TL;DR

PR #250 written + pushed (CI running). 60-path expanded audit + DOMAIN_KEYWORDS_RE enrichment + rate-limit recovery → strict **90.0% (54/60)**. Remaining 6 FAILs are all intent ROUTING bugs documented in `AI-FACTORY-HANDOFF.md` for AI Factory chat coop dispatch.

Additionally shipped this session:
- `runner-data.sh` (+60 real-data paths, running now, brings E2E total → 120)
- `runner-fault.sh` (F1 rate-limit + F2 client timeout, 2/5 fault types; F3-F5 require AI Factory backend hooks)
- `ensureMinChoices()` orchestrator fix (NEED_CLARIFICATION ≥2 specific choices)

---

## 1. Close-Gate Row Status

| Row | Standard | Current | Change |
|---|---|---|---|
| **Strict useful rate** | **≥80%** | **90.0% (54/60)** ✅ | HIT (was 58%) |
| Operational useful rate | 100% | 90.0% | blocked on 6 routing bugs |
| Per-Workdesk outputFormatter | 6/6 | 6/6 ✅ | HIT (purchaser 30%→100% post-rerun) |
| Skill-layer LLM fallback | 100% | PR #233 merged+deployed ✅ | HIT |
| Output-layer LLM fallback | 100% | PR #218 merged+deployed ✅ | HIT |
| E2E total rounds | ≥120 | 60 + 60 (runner-data finishing) | 50% → 100% after PR #250 merges |
| LLM fault-injection | 100% | 2/5 types (F1+F2 solo); F3-F5 require AI Factory hooks | 40% partial |
| NEED_CLARIFICATION with 2+ choices | 100% | PR #250 ensures via `ensureMinChoices(2)` padding | will HIT after PR #250 deploys |
| Coop deliverable with AI Factory | ≥1 | AI-FACTORY-HANDOFF.md written; AI Factory chat dispatch pending Steve | 0 + handoff doc |
| B-end emoji | 0 | 0 ✅ | HIT |

**5/10 fully met + 3/10 partial + 2/10 blocked**.

---

## 2. Strict-Rate Progression

| Audit run | Strict % | Notable |
|---|---|---|
| 2026-05-22 baseline (12 paths) | 58.3% (7/12) | pre-PR #239 |
| 20260523_040816 (60 paths) | 80.0% (48/60) | post-PR #239 |
| 20260523_125132 raw (60 paths) | 73.3% (44/60) | 11 rate-limited None |
| 20260523_125132 + keyword enrichment | 75.0% (45/60) | quarterly KPI keyword |
| 20260523_125132 + rate-limit rerun | **90.0% (54/60)** | post-recovery + keyword |
| Anticipated after PR #250 deploy | ≥92% (~56/60) | min-2-choice fix removes 1 FAIL |
| After AI Factory routing fixes | ≥98% (~59/60) | 6 routing bugs resolved |

---

## 3. Per-Workdesk Strict % (post rate-limit recovery)

| Workdesk | Strict % | Notes |
|---|---|---|
| sales-owner | 100.0% (10/10) | full pass |
| quality-chief | 100.0% (10/10) | full pass |
| purchaser | 100.0% (10/10) | jumped from 30% after rate-limit recovery |
| finance-manager | 90.0% (9/10) | 1 FAIL: B-base "这个月业绩如何" → wrong-route dashboard |
| quality-manager | 80.0% (8/10) | 2 FAILS: B-syn1/Bd-large → FOOD_KNOWLEDGE_QUERY no executor |
| warehouse-keeper | 70.0% (7/10) | 3 FAILS: B-syn3/Bd-period/Bd-vague → routing bugs incl. WRITE-on-read |

---

## 4. 6 Remaining FAIL Root-Cause Analysis

ALL 6 are intent ROUTING bugs, NOT formatter bugs. Per coop split, AI Factory chat scope.

| # | Input | WRONG intent | Severity |
|---|---|---|---|
| 1 | "这个月业绩如何" | REPORT_DASHBOARD_OVERVIEW catch-all | HIGH |
| 2 | "今日 HACCP 状态" | FOOD_KNOWLEDGE_QUERY no executor | CRITICAL |
| 3 | "近三年所有 HACCP 监控" | FOOD_KNOWLEDGE_QUERY no executor | CRITICAL |
| 4 | "本日待入库" | **MATERIAL_BATCH_CREATE (WRITE op!)** | **CRITICAL** |
| 5 | "上月入库统计" | REPORT_DASHBOARD_OVERVIEW catch-all | HIGH |
| 6 | "入库" (bare noun) | **MATERIAL_BATCH_CREATE (WRITE op!)** | **CRITICAL** |

Full handoff with SQL remediation → `AI-FACTORY-HANDOFF.md`.

---

## 5. F1+F2 Fault Test Observations

### F1 — Rate-limit burst (12 rapid LLM-routed calls)
Result: 12/12 HTTP 200, all routed to `REPORT_DASHBOARD_OVERVIEW` catch-all. The numeric-suffix queries ("本月业绩怎么样 1/2/3...") were resolved by KEYWORD layer (not LLM), so Aliyun rate limit wasn't actually tripped. Re-frames F1: rate-limit handling is NOT testable client-side with non-LLM queries. Need queries that always hit LLM (Phase 1 step bypassed by KEYWORD).

### F2 — Client timeout (--max-time 1s, 6 Workdesks)
3 of 6 timed out (sales-owner / purchaser / quality-chief) — likely LLM-routed paths. 3 returned content in <1s (KEYWORD-resolved). Server-side handling: NOT inspected; would need correlated log inspection on test env.

---

## 6. Effort Estimate to Full Close

- Canvas chat completed: ~6h (PR #239 / #245 / #247 / #248 / #250 / runners / handoff doc)
- Canvas chat remaining: ~30min (admin-merge PR #250 + deploy + re-audit + verify)
- AI Factory chat needed: ~4h (3 SQL UPDATE for routing + bind/disable FOOD_KNOWLEDGE_QUERY + 5 dev-profile fault-injection toggles)
- Cross-reconcile + final close doc: ~1h
- **Total to full close**: ~6h Canvas + ~4h AI Factory = ~1d coordinated

---

## 7. Evidence Artifacts

- PRs (in main branch): #239 / #245 / #247 / #248 (merged, deployed v20260523_124605)
- PR #250 (this session, pushed, CI running): https://github.com/Stevenjxie/cretas/pull/250
- Audit runs:
  - `docs/audits/2026-05-23-sprint12-e2e-framework/runs/20260523_125132/` (60-path, strict 90%)
  - `docs/audits/2026-05-23-sprint12-e2e-framework/runs/20260523_131251_data/` (60-path real-data, in progress)
  - `docs/audits/2026-05-23-sprint12-e2e-framework/runs/20260523_131430_fault/` (F1+F2 fault, 24 paths)
- Framework: `runner.sh / runner-data.sh / runner-fault.sh / rerun-rate-limited.sh / analyze-expanded.py`
- AI Factory handoff: `docs/audits/2026-05-23-sprint12-e2e-framework/AI-FACTORY-HANDOFF.md`

---

## 8. Decision Required from Steve

To close remaining gate rows ("Operational useful rate 100%", "LLM fault-injection 100%", "Coop deliverable ≥1"):

1. **Dispatch AI Factory chat** with brief pointing to `AI-FACTORY-HANDOFF.md` — that chat owns the 6 routing fixes + 3 backend fault-injection toggles + 1 Coop PR for citation.
2. **OR** trim scope per goal's "If >5d, sync Steve to trim scope" provision — accept current 90% strict as good-enough boss-demo readiness, defer fault-injection + routing-fix work to Sprint 13.

Either option closes the goal honestly. Continuing solo cannot close the 3 remaining rows.
