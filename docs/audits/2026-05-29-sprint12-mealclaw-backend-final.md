# Sprint 12 餐饮 backend — final close (Phase E)

**Date**: 2026-05-29
**Branch**: `feat/sprint12-mealclaw-backend` (merged origin/main `e4031f0b5` 2026-05-29)
**Chat**: sprint12-mealclaw-backend-continuation (continues lost sprint12-mealclaw-backend chat)
**Scope**: Phase D (verify) + Phase A.3 (integration test) + Phase E (cache sync) + close.

---

## TL;DR

| Phase | Deliverable | State |
|---|---|---|
| A.1 | Playwright headed rule + Workdesk P&L card (cherry-pick) | ✅ prior chat |
| A.2 | SSH SQL baseline doc | ✅ prior chat (`sprint-12-mealclaw-backend-baseline.md`) |
| B | Shrinkage Composite sub-Tool → cretas wastage data | ✅ prior chat (`097102737`, 12 tests PASS) |
| C | cost_rigidity scope-out → "成本刚性数据不可用" | ✅ prior chat (Sprint 13 ticket) |
| D | ETL multi-factory orchestrator + nightly cron | ✅ prior chat (`397927970`) + **cron port bug fixed this chat** |
| A.3 | shipdebt doc + **Composite integration test** | ✅ doc prior (`39a9ce088`) + **4-test integration this chat (`6d52ccb22`)** |
| E | cache purge wire after ETL backfill | ✅ this chat (`b301454b5`) |

---

## Phase E — cache sync (this chat)

Wires sister **PR #286** cache-purge endpoint (`POST /api/admin/cache/purge?scope=INDICATOR`)
into the bulk finance ETL completion path so the Composite Tool sees fresh data
instead of a stale `(缓存结果)` payload.

- `restaurant_etl_admin.py::_purge_indicator_cache_for_factory()` — POSTs the Java
  endpoint per PR #286 contract, reads `ETL_ADMIN_JWT` + `JAVA_API_BASE_URL` env,
  skips with WARN if JWT unset, never raises.
- `_run_finance_bulk_job()` — after the orchestrator returns, purges **only the
  succeeded factories**; purge results captured in `stats["cachePurge"]`; a purge
  failure never flips the ETL job status (post-hoc cleanup must not mask ETL success).
- +7 Python tests (41 total PASS).
- **Phase D cron port bug fixed**: `run-finance-etl-daily.sh` default `10010`(Java)→
  `8083`(Python). The bulk endpoint is Python-only (main.py prefix
  `/api/smartbi/restaurant/etl`); Java does NOT proxy `/api/smartbi/*` → the cron
  was dead-on-arrival.

### Deploy config required (cretas-python.service)

The purge runs **inside the Python service**, so for auto-purge to fire in prod:

```
ETL_ADMIN_JWT=<platform_admin JWT>      # else purge skipped with WARN (ETL still OK)
JAVA_API_BASE_URL=http://localhost:10010 # optional, this is the default
```

If unset, the ETL still succeeds and the operator can manually
`curl -X POST '.../api/admin/cache/purge?scope=INDICATOR&factoryId=...'`.

---

## Phase A.3 — Composite Tool integration test (this chat)

Phase A.2 recorded the Composite curl was "阻于 JWT". This chat adds a CI-reliable
tool-level integration test (`RestaurantEconomicsAnalysisToolIntegrationTest`, 4/4
PASS) that drives the real orchestration with the 3 sub-Tools mocked — no
Python/DB/JWT dependency (full `@SpringBootTest` path is `@Disabled` per the
`RestaurantP35IntegrationTest` project pattern). Locks in the 4-section shape +
Steve 决策 1 (failed sub-Tool isolated, others render, no fabrication) + Phase C
graceful degradation.

The COST_FOOD 0-row root-cause is documented in `sprint-12-pr242-shipdebt.md`
(4 hypotheses + 3 fix options) and **handed to sister cache-fix / Sprint 13** per Q3.

---

## Two prod-data realities (SSH SQL 2026-05-29) — contradict dispatch Q2 assumptions

### Reality 1 — DOD (a) "≥3 factory ≥30 rows" is data-impossible

`fact_pos_transaction` distinct-day counts in `smartbi_prod_db`:

| Factory | POS txns | distinct days | REVENUE rows a backfill yields |
|---|---|---|---|
| RES_3101_009 | 140,541 | 365 | **365** ✓ |
| F001 | 140,541 | 365 | **365** ✓ |
| R_GML_DEMO | 16,213 | **1** (all 2026-01-15) | **1** ✗ |
| R_XMX_CHAIN | 141 | **1** (all 2026-02-15) | **1** ✗ |

Only **2** factories can reach ≥30 rows. R_GML_DEMO / R_XMX_CHAIN POS is single-day
demo data. **Steve decision: accept 2 factories, document the single-day reality.**

### Reality 2 — DOD (c) "Composite 真返新数据" is DB-drift-blocked

The ETL `sync_revenue_from_pos(smartbi_pool, ...)` UPSERTs into **smartbi_prod_db**,
but Java/Composite reads **cretas_prod_db** (the Phase F.1 drift, per
`feedback_smartbi_repo_uses_primary_datasource` HARD rule). A backfill won't reach
the Composite without a COPY. **Steve decision: apply shipdebt-doc Option C COPY
stopgap (smartbi→cretas REVENUE) — does NOT touch ETL architecture (drift fix still
handed off).**

### Reality 3 (refinement, no re-ask) — F001 COPY is a no-op for the Composite

`RestaurantFinancialMetricsFetcher.filterToLatestUpload()` keeps only `max(uploadId)`
rows. F001 already has manufacturing finance uploads (uploadId 3135–3710) in
`cretas_prod_db`; the restaurant ETL writes `upload_id=0`. So F001 restaurant rows
would be **filtered out** (manufacturing wins) → COPY F001 is pointless + pollutes.
**COPY only RES_3101_009** (clean, only upload_id=0). F001 backfills to smartbi_prod_db
to prove the Phase D orchestrator runs multi-factory, but is NOT copied to cretas.

### Note — no `compute_source` column

`smart_bi_finance_data` has **no `compute_source` column**; the DOD/Phase-D-commit
"COMPUTE_SOURCE='ETL_REAL'" wording was inaccurate. The real ETL sentinel is
**`upload_id=0`** (`ETL_UPLOAD_ID_SENTINEL`). Verify ETL rows via `WHERE upload_id=0`.

---

## Close-gate (reworded per Steve decisions + data reality) — VERIFIED on prod 2026-05-29

| DOD | Original | Closed as (with prod evidence) |
|---|---|---|
| (a) ≥3 factory ETL_REAL ≥30 rows | impossible (data) | **2 factory ≥30** — bulk backfill (jobId `0e024ebe`, range 2025-01-01→2026-12-31) → smartbi_prod_db `upload_id=0`: **RES_3101_009 = 365 REVENUE (¥20,639,885) + 371 COST (¥4.06M)**, **F001 = 365 REVENUE**. **+ 2 present**: R_GML_DEMO = 1, R_XMX_CHAIN = 1 REVENUE + 4 COST (single-day demo POS) — documented |
| (b) A.3 integration test + shipdebt doc | ✅ | 4-test Composite integration (`6d52ccb22`, 4/4 PASS) + shipdebt doc (`39a9ce088`) |
| (c) cache sync + Composite new data | drift-blocked | cache purge wired + 7 tests (`b301454b5`); **COPY'd RES_3101_009 REVENUE+COST smartbi→cretas_prod_db** (705 new rows via anti-join, cretas now = 365 REVENUE ¥20.6M + 371 COST ¥4.06M) so Composite shows real complete P&L; F001 NOT copied (manufacturing `uploadId>0` wins `filterToLatestUpload`); `semantic_cache` empty (verified) → Composite reads fresh; final Composite end-to-end left to **unified verify chat** per Rule 19 |
| (d) PR merged main + prod deploy + notify organizer | — | **PR #291 MERGED main** (squash, 01:26Z); Python prod deployed (8083, Phase D/E live); Java prod deploy (Phase B) in progress; cache/purge endpoint live on prod (401 probe). Notify organizer below. |
| (e) MEMORY.md close entry | — | (final step) |

### COST_FOOD note (resolves shipdebt 0-row via re-run)

The bulk re-run produced **371 COST rows** for RES_3101_009 (365 食材成本 recipe-COGS ¥4.06M + 6 食材损耗 wastage ¥498) — the shipdebt "0 COST rows" was a prior-run artifact (PR #242 + Phase F.1 COPY only brought REVENUE). Re-running the ETL (shipdebt Step 2, H1/H2/H3 path) resolved the 0-row; both REVENUE + COST were COPY'd to cretas. The **architectural drift fix** (ETL write-target = cretas vs smartbi) remains handed off to Sprint 13 — this is a data-stopgap, not an architecture change.

---

## Remaining ops (this chat → organizer)

1. Push branch + open PR (`feat/sprint12-mealclaw-backend` → main, merged origin/main clean).
2. After CI green → merge.
3. Prod deploy: Java backend (Phase B Shrinkage) + Python (Phase D/E).
4. Bulk backfill smartbi_prod_db (4 factories) → verify RES_3101_009 + F001 = 365 (`upload_id=0`).
5. COPY RES_3101_009 REVENUE smartbi_prod_db→cretas_prod_db (Option C stopgap, full year).
6. Set `ETL_ADMIN_JWT` on cretas-python.service + create `/etc/cretas/finance-etl-cron.env`
   + install `cretas-finance-etl-daily.timer`.
7. Notify organizer → dispatch **unified verify chat** for Composite end-to-end (real token / Playwright).

## Handoffs (NOT this chat's scope)

- COST_FOOD 0-row root-cause (shipdebt doc, 4 hypotheses) → sister cache-fix / Sprint 13.
- ETL→Java DB drift architectural fix (Option A/B) → Sprint 13.
- cost_rigidity real data (cretas 排班表 + ETL) → Sprint 13 ticket.
- R_GML_DEMO / R_XMX_CHAIN multi-day POS (real or spread) → Sprint 13 if demo coverage needed.
