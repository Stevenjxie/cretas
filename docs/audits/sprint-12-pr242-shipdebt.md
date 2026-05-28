# PR #242 COST_FOOD ETL — ship debt audit (handoff)

**Date**: 2026-05-29 (Sprint 12 餐饮 backend Phase A.3, Q3 deliverable)
**Owner pending**: sprint12-cache-fix chat OR Sprint 13 ETL hardening
**Source**: sprint12-mealclaw-backend chat Phase A baseline + ETL source-data audit

---

## TL;DR

PR #242 (`feat(sprint11.5-phase-D): ETL populate smart_bi_finance_data — REVENUE + COST_FOOD`, merged 2026-05-23 `c0cff1586`) shipped 3-stage ETL but **prod has 0 COST rows** for RES_3101_009 — the COST_FOOD stage either never ran during backfill or silently produced 0 inserts. REVENUE stage worked (365 rows, sum ¥20.6M for full 2025). This document captures the root-cause evidence + recommended fix for the sister chat to pick up.

---

## 1. Evidence (Phase A.2 SSH SQL, 2026-05-29)

### 1.1 What `smart_bi_finance_data` actually contains

```sql
-- Under smartbi_user (RLS view, Java sees same):
SELECT record_type, COUNT(*) FROM smart_bi_finance_data
 WHERE factory_id='RES_3101_009' AND deleted_at IS NULL
 GROUP BY record_type;

 record_type | count
-------------+-------
 REVENUE     | 365     ← shipped, ¥20.6M total
 (no COST rows)        ← shipping debt
```

All 365 REVENUE rows have `created_at = 2026-05-23` (PR #242 ship date) and `upload_id = 0` (ETL sentinel) → confirms they came from `restaurant_finance_etl.sync_revenue_from_pos()`.

### 1.2 What COST source data IS available

Under postgres (RLS bypass):

```sql
-- smartbi_prod_db
SELECT COUNT(*) FROM fact_restaurant_wastage WHERE factory_id='RES_3101_009';
-- → 0 rows (Silver layer empty — restaurant_ops_etl Stage 3 never populated)

SELECT COUNT(*) FROM agg_restaurant_product_cost WHERE factory_id='RES_3101_009';
-- → 136 rows (recipe BOM derived, populated)

SELECT COUNT(*) FROM fact_pos_item WHERE factory_id='RES_3101_009';
-- → 646,946 rows (POS line items)

-- cretas_prod_db
SELECT COUNT(*), MIN(wastage_date), MAX(wastage_date)
  FROM wastage_records WHERE factory_id='RES_3101_009'
    AND deleted_at IS NULL AND status='APPROVED';
-- → 6 rows, 2026-03-31 to 2026-04-23 (cretas business table populated)
```

**Cretas-side wastage data IS present (6 rows)**, but the **smartbi Silver layer** (`fact_restaurant_wastage`) has 0 rows — meaning `restaurant_ops_etl.sync_fact_wastage()` was never invoked for RES_3101_009. PR #242 `sync_cost_from_wastage()` reads from `cretas_db.wastage_records` directly (correct), but the read path may have been bypassed by a race / error in the orchestrator backfill run.

### 1.3 PR #242 ETL code path inspection

`backend/python/smartbi/gold/restaurant_finance_etl.py` (commit `c0cff1586`):

- **Stage 1 `sync_revenue_from_pos`** (line 141-195): reads `fact_pos_transaction`, writes REVENUE rows. **Confirmed shipped** (365 rows in prod).
- **Stage 2 `sync_cost_from_wastage`** (line 226-279): reads `cretas_db.wastage_records WHERE status='APPROVED'`, writes COST food_wastage rows. **0 rows in prod despite 6 source rows**.
- **Stage 3 `sync_cost_from_pos_recipe`** (line 293-435): reads `fact_pos_item × dim_product → cretas product_types → agg_restaurant_product_cost`, writes COST food_recipe rows. **0 rows in prod despite 646K POS items + 136 dish costs**.

Orchestrator `run_full_finance_etl()` (line 442-508) wraps each stage in try/except — a Stage 2/3 failure logs an error but **does NOT roll back Stage 1**. That matches what we see: REVENUE shipped, COST_FOOD silently failed/skipped.

---

## 2. Root cause hypotheses

### H1: Stage 2/3 raised + got swallowed by per-stage try/except

`run_full_finance_etl` wraps each stage:
```python
try:
    stats.cost_wastage_upserted = await sync_cost_from_wastage(...)
except Exception as e:
    stats.errors.append(f"cost_wastage: {e}")
    logger.exception(...)
```

If Stage 2 raised (e.g. RLS violation on cretas connection, missing `app.factory_id` GUC, asyncpg pool exhaustion, transaction-conflict on UNIQUE INDEX during concurrent backfill), the exception lands in `stats.errors` but the orchestrator returns success → backfill operator sees `succeeded` and moves on.

**Diagnostic**: query `restaurant_etl_failures` table OR check journalctl logs for the Stage 2/3 ETL runs around 2026-05-23 PR #242 ship.

```sql
SELECT factory_id, run_at, status, attempt, error_class, error_msg
  FROM restaurant_etl_failures
 WHERE factory_id='RES_3101_009'
   AND run_at::date = '2026-05-23'
 ORDER BY run_at;
```

### H2: Stage 2/3 never invoked (backfill called Stage 1 only)

PR #242 admin trigger `POST /finance-etl/trigger` calls `run_full_finance_etl_with_retry` → `run_full_finance_etl` → all 3 stages. But if backfill was triggered via a manual SQL INSERT or partial Python script that ONLY called `sync_revenue_from_pos`, Stages 2/3 wouldn't have run.

**Diagnostic**: check git log around 2026-05-23 for any manual `psql` runbook or shell script that COPY'd REVENUE-only.

### H3: Stage 2 dropped silently (0 rows returned from cretas query)

`sync_cost_from_wastage` first SELECTs from `cretas_db.wastage_records`. If the cretas pool was misconfigured at ETL backfill time (e.g. wrong DB pointing at smartbi_db where wastage_records doesn't exist, OR RLS gate excluded all rows), the SELECT returns 0 rows → function logs "no wastage data" + returns 0 → orchestrator records success.

The cretas pool config: `smartbi.config.get_cretas_pool()` reads `FOOD_KB_DB_URL` env. If that env var was unset or wrong at backfill time, the pool would fail to acquire OR (worse) silently connect to the wrong DB.

**Diagnostic**:
```bash
ssh root@47 'grep FOOD_KB_DB /www/wwwroot/cretas/.env.prod'
# Confirm it points to cretas_prod_db on 47, not smartbi_prod_db
```

### H4: Phase F.1 manual COPY 31 rows + abandoned full backfill

Per memory `project_2026_05_24_sprint11_5_phase_f1_resolved.md`:
> ETL 写 smartbi_prod_db 但 Java 读 cretas_prod_db. COPY 31 rows 修复.

This is the **most likely root cause**. Sequence:
1. PR #242 ETL ran 2026-05-23, wrote REVENUE rows to `smartbi_prod_db.smart_bi_finance_data`
2. Sprint 11.5 Phase F.1 discovered Java reads `cretas_prod_db.smart_bi_finance_data` (per `feedback_smartbi_repo_uses_primary_datasource.md` HARD)
3. To unblock demo, 31 Dec 2025 rows were `COPY`'d from `smartbi_prod_db → cretas_prod_db`
4. **Only REVENUE rows were COPY'd** because the demo only needed P&L card render — COST rows for the same period were either (a) also missing in `smartbi_prod_db` OR (b) skipped by the COPY script

This explains both gaps: REVENUE present in `cretas_prod_db`, COST absent.

**Diagnostic**:
```bash
# Check if smartbi_prod_db has the missing COST rows
ssh root@47 'sudo -u postgres psql -d smartbi_prod_db -c "
  SELECT record_type, category, COUNT(*) FROM smart_bi_finance_data
   WHERE factory_id=''RES_3101_009'' GROUP BY 1,2 ORDER BY 1,2;
"'
```

If `smartbi_prod_db` ALSO has 0 COST rows → H1/H3 (ETL itself failed). If it has 365 REVENUE + N COST rows → H4 (only REVENUE was COPY'd to cretas).

---

## 3. Recommended fix design

### Step 1: Diagnose first

Run all 4 hypothesis diagnostics (~10 min):
- H1: `restaurant_etl_failures` table query
- H2: git log around 2026-05-23 for manual backfill scripts
- H3: `.env.prod` FOOD_KB_DB_URL value
- H4: smartbi_prod_db COST row count for RES_3101_009

### Step 2: Re-run ETL OR re-COPY (depends on root cause)

**If H4 (most likely)**: smartbi_prod_db has the COST rows → COPY them to cretas_prod_db.
```sql
-- Run on prod 47 as postgres superuser (RLS bypass)
INSERT INTO cretas_prod_db.smart_bi_finance_data (
    factory_id, upload_id, record_date, record_type, category,
    material_cost, total_cost, actual_amount, created_at, updated_at
)
SELECT factory_id, upload_id, record_date, record_type, category,
       material_cost, total_cost, actual_amount, created_at, updated_at
  FROM smartbi_prod_db.smart_bi_finance_data
 WHERE factory_id='RES_3101_009'
   AND record_type='COST'
   AND deleted_at IS NULL
ON CONFLICT (factory_id, upload_id, record_date, record_type, category)
  WHERE deleted_at IS NULL
  DO NOTHING;
```

Then verify Java fetcher returns non-zero foodCost via `RestaurantFinancialMetricsFetcher.fetch("RES_3101_009", "2025-12")`.

**If H1/H2/H3**: actual ETL bug — fix the bug, then re-run `POST /finance-etl/trigger {factoryId: "RES_3101_009", startDate: "2025-01-01", endDate: "2025-12-31"}` to backfill from scratch. Sprint 12 Phase D `trigger-bulk` (commit `397927970`) makes this easy for all 4 factories at once.

### Step 3: Long-term — fix the COPY drift architecturally

The ETL write target should match the Java read target. Two options:

- **Option A (recommended)**: change ETL to write `cretas_prod_db.smart_bi_finance_data` directly (single source of truth). Risk: cretas_prod_db is the operational app DB — adding ETL writes increases contention. Mitigation: write during off-peak (02:00 cron), use COPY for bulk.
- **Option B**: change Java to read `smartbi_prod_db.smart_bi_finance_data` by adding a secondary DataSource to `repository.smartbi.postgres.*` sub-package. Risk: bigger architectural change, touches dependency injection. Worth it long-term.
- **Option C (Sprint 12 stopgap)**: keep COPY runbook in the nightly cron — `scripts/ops/run-finance-etl-daily.sh` could be extended to COPY rows from smartbi → cretas after the bulk ETL finishes. Cheap fix, doesn't solve architectural drift.

Sprint 12 cache-fix chat picks A / B / C based on their assessment. The cache-purge endpoint they're building can hook into the ETL completion path to invalidate stale cached `(缓存结果)` entries pointing at the now-stale COST=0 data.

---

## 4. What sprint12-mealclaw-backend chat did NOT fix

This chat (sprint12-mealclaw-backend) **did NOT** modify the PR #242 ETL code paths because:
- Phase B (Shrinkage wiring) reads from `cretas_prod_db.wastage_records` directly — NOT from `smart_bi_finance_data` — so it works regardless of COST row state.
- Phase D (multi-factory orchestrator) extends PR #242 ETL to F001/R_GML_DEMO/R_XMX_CHAIN with the SAME 3-stage pipeline. **It does NOT re-run RES_3101_009 to fix the COST gap.**
- Q3 explicitly hands the COST_FOOD investigation to sister chat.

If H4 turns out to be the cause, fixing it is a 1-line SQL COPY (Step 2 above) — sister chat can ship within an hour. Long-term Option A/B is a bigger architectural decision.

---

## 5. Cross-references

| Source | Path |
|---|---|
| PR #242 ETL code | `backend/python/smartbi/gold/restaurant_finance_etl.py` @ commit `c0cff1586` |
| Spec | `docs/superpowers/specs/2026-05-23-sprint11.5-etl-design.md` |
| Phase F.1 manual COPY mention | memory `project_2026_05_24_sprint11_5_phase_f1_resolved.md` |
| Java DS rule | memory `feedback_smartbi_repo_uses_primary_datasource.md` HARD |
| Phase A.2 baseline | `docs/audits/sprint-12-mealclaw-backend-baseline.md` |
| Phase D multi-factory ETL | `backend/python/smartbi/gold/restaurant_finance_etl.py` @ commit `397927970` |

---

**End of shipdebt audit. Sister cache-fix chat: ping when you've picked A/B/C and need cretas-mealclaw-backend coordination on the cron hook.**
