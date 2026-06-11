# Factory Production Gold ETL — Phase 1 Design Spec

**Version**: 1.0  
**Date**: 2026-06-11  
**Author**: Sonnet in-harness (Opus gate pending)  
**Branch**: feat/factory-production-gold-etl  
**Status**: Implementation complete — awaiting Opus 🔒 schema review + migration deployment

---

## 1. Overview

Phase 1 ETL pipeline that reads completed production batches from `cretas_db.production_batches` (Java backend) and materialises them into a **Silver + Gold** data warehouse in `smartbi_db`, enabling factory cost/yield analytics in the SmartBI dashboard.

Mirrors the **restaurant_ops_etl pattern** exactly: two pools, RLS tenant GUC, UNNEST bulk upsert, Gold INSERT…SELECT aggregation, 3-retry wrapper.

---

## 2. Architecture

```
cretas_db (Java prod)
  └─ production_batches
        WHERE status='COMPLETED'
          AND is_trial IS NOT TRUE
          AND deleted_at IS NULL
             │
             │  asyncpg cross-pool bridge
             ▼
smartbi_db
  ├─ fact_production_batch          ← Silver (row-per-completed-batch)
  └─ agg_factory_batch_daily        ← Gold  (daily aggregate × product_type)
```

### Pool pattern

```python
cretas_pool = await get_cretas_pool()   # food_kb_db_url, max_size=6
smartbi_pool = await get_pg_pool()      # POSTGRES_URL, default max_size=15
```

No `dblink`. Python is the bridge — read from cretas_pool, write to smartbi_pool.

---

## 3. Schema

### 3.1 Silver: `fact_production_batch`

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGSERIAL PK | |
| `factory_id` | VARCHAR(50) NOT NULL | RLS tenant key |
| `source_pk` | VARCHAR(191) NOT NULL | `str(production_batches.id)` |
| `batch_number` | TEXT | |
| `product_type_id` | TEXT | nullable (some batches may lack) |
| `product_name` | TEXT | |
| `planned_qty` | NUMERIC(14,2) | nullable |
| `actual_qty` | NUMERIC(14,2) | nullable |
| `good_qty` | NUMERIC(14,2) | nullable |
| `defect_qty` | NUMERIC(14,2) | nullable |
| `unit` | TEXT | kg, box, etc. |
| `status` | TEXT | always 'COMPLETED' at insert time |
| `start_time` | TIMESTAMP | nullable |
| `end_time` | TIMESTAMP | nullable |
| `stat_date` | DATE | `DATE(end_time)` preferred; fallback `DATE(start_time)`; NULL if both null |
| `worker_count` | INTEGER | nullable |
| `work_minutes` | INTEGER | nullable |
| `material_cost` | NUMERIC(14,2) | **nullable** — honest null, never zero-filled |
| `labor_cost` | NUMERIC(14,2) | nullable |
| `equipment_cost` | NUMERIC(14,2) | nullable |
| `other_cost` | NUMERIC(14,2) | nullable |
| `total_cost` | NUMERIC(14,2) | nullable |
| `unit_cost` | NUMERIC(12,4) | nullable |
| `yield_rate` | NUMERIC(5,2) | nullable |
| `created_at` | TIMESTAMP | |
| `updated_at` | TIMESTAMP | auto via trigger |
| UNIQUE | `(factory_id, source_pk)` | idempotent upsert |

**RLS**: `FORCE ROW LEVEL SECURITY` — policy: `factory_id = current_setting('app.factory_id', true)`

### 3.2 Gold: `agg_factory_batch_daily`

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGSERIAL PK | |
| `factory_id` | VARCHAR(50) NOT NULL | |
| `stat_date` | DATE NOT NULL | |
| `product_type_id` | TEXT | **nullable** — NULL row = all-product rollup |
| `batch_count` | INTEGER | |
| `total_planned_qty` | NUMERIC(18,2) | |
| `total_actual_qty` | NUMERIC(18,2) | |
| `total_good_qty` | NUMERIC(18,2) | |
| `avg_yield_rate` | NUMERIC(5,2) | nullable; NULL if all Silver rows have null yield_rate |
| `total_material_cost` | NUMERIC(18,2) | nullable (SQL SUM of all-NULL = NULL) |
| `total_labor_cost` | NUMERIC(18,2) | nullable |
| `total_equipment_cost` | NUMERIC(18,2) | nullable |
| `total_other_cost` | NUMERIC(18,2) | nullable |
| `total_cost` | NUMERIC(18,2) | nullable |
| `version` | INTEGER | incremented on each re-aggregation |
| `updated_at` | TIMESTAMP | |
| `_pk_helper` | VARCHAR(150) GENERATED ALWAYS AS | `factory_id \|\| '\|' \|\| stat_date::text \|\| '\|' \|\| COALESCE(product_type_id, '__ALL__')` |
| UNIQUE INDEX | `uq_agg_factory_batch_daily (_pk_helper)` | enables `ON CONFLICT (_pk_helper) DO UPDATE` |

**Why `_pk_helper` instead of a composite PK?** PostgreSQL PRIMARY KEY cannot include nullable columns. `product_type_id` IS nullable (the all-product rollup row has NULL). Solution: generated stored column that substitutes `'__ALL__'` sentinel for NULL, then UNIQUE INDEX on the generated column.

**RLS**: `FORCE ROW LEVEL SECURITY` — same policy as Silver.

---

## 4. ETL Pattern

### 4.1 Flyway Migration

- File: `backend/python/smartbi/database/migrations/V20261002_01__factory_production_gold.sql`
- Version: `V20260930_01` — strictly > frontier `V20260929_01` (verified via `git ls-tree origin/main`)
- Includes: CREATE TABLE Silver, CREATE TABLE Gold, indexes, RLS policies, GRANT DML to smartbi_user, conditional trigger for `updated_at`

### 4.2 ETL File: `factory_production_etl.py`

Located at `backend/python/smartbi/gold/factory_production_etl.py`.

#### Stage 1 — Silver (`sync_fact_production_batch`)

```
cretas_pool READ: SELECT ... FROM production_batches WHERE status='COMPLETED' AND is_trial IS NOT TRUE AND deleted_at IS NULL ORDER BY id
  ↓
Build 22 parallel arrays (source_pks, batch_numbers, ..., yield_rates)
  ↓
smartbi_pool WRITE: INSERT INTO fact_production_batch ... FROM UNNEST($1::text[], ...) ON CONFLICT (factory_id, source_pk) DO UPDATE SET ...
  ↓
Returns count of RETURNING rows
```

**Cost null honesty**: `_to_float_or_none(v)` returns `None` when `v is None`, never coerces to `0`. PostgreSQL `SUM(all_nulls)` also returns NULL — honesty propagates through Gold.

#### Stage 2 — Gold (`materialize_factory_gold`)

Two `INSERT … SELECT … GROUP BY … ON CONFLICT (_pk_helper) DO UPDATE SET version = version + 1`:

1. **Per-product daily**: `GROUP BY factory_id, stat_date, product_type_id WHERE product_type_id IS NOT NULL`
2. **All-product daily rollup**: `GROUP BY factory_id, stat_date` (product_type_id LEFT AS NULL)

Both run in a single transaction with RLS GUC set first.

#### Retry Wrapper (`run_factory_etl_with_retry`)

```python
_RETRY_BACKOFFS_SEC = [60, 300, 900]  # 1m, 5m, 15m
_MAX_ATTEMPTS = 3
```

Writes failures to `restaurant_etl_failures` (shared failure log table, mirrors restaurant ETL).

### 4.3 Admin Endpoints

Base prefix: `/api/smartbi/factory/etl` (registered in `main.py`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/trigger` | admin | Trigger ETL for one factory; returns jobId |
| GET | `/status?factoryId=` | admin | Row counts (Silver/Gold) + last run time + job status |

Admin auth: `require_admin()` from `smartbi.canonical.provenance._admin_auth` (same as restaurant ETL).

Job registry: in-memory `_running_jobs` dict (Phase 1 simplification; Phase 2 can replace with Redis for multi-worker).

### 4.4 Gold Read Endpoints

Base prefix: `/api/smartbi` (registered in `main.py`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/factory/cost-structure` | any authenticated | Daily cost breakdown + summary; cost fields stripped for non-price roles |
| GET | `/factory/yield-trend` | any authenticated | Yield rate trend; optional `productTypeId` filter |
| GET | `/factory/product-cost-compare` | any authenticated | Per-product cost comparison, sorted by totalCost DESC; cost stripped for non-price roles |

All endpoints:
- Require `factoryId` query param
- Set RLS GUC `app.factory_id` before querying Gold
- Apply `_apply_rbac(data, role)` — strips `_COST_KEYS` frozenset for non-`PRICE_VIEW_ROLES` roles

---

## 5. Key Design Decisions

| ID | Decision | Rationale |
|----|----------|-----------|
| D1 | Source filter: `status='COMPLETED' AND is_trial IS NOT TRUE AND deleted_at IS NULL` | Trial batches skew production metrics; soft-deleted rows must be excluded; non-completed batches have partial data |
| D2 | `stat_date = DATE(end_time)` preferred, fallback `DATE(start_time)` | End time represents when cost was realised; if only start_time known, use that rather than NULL |
| D3 | All cost columns nullable in Silver and Gold | Some factories haven't configured cost rates; honest NULL is better than zero-fill which would make analytics appear to show "zero cost" batches |
| D4 | `_pk_helper` generated column for Gold unique constraint | PostgreSQL PRIMARY KEY disallows nullable columns; `product_type_id` IS nullable (all-product rollup row = NULL); generated column with `'__ALL__'` sentinel solves this cleanly without application logic |
| D5 | Cross-pool Python bridge (no dblink) | `cretas_db` and `smartbi_db` are on the same host but different databases; asyncpg pools are already configured in `config.py`; dblink would require superuser and is harder to RLS-audit |
| D6 | `FORCE ROW LEVEL SECURITY` on both Silver and Gold | All smartbi tables use FORCE RLS per project convention; tenant isolation must hold even for superuser queries |

---

## 6. RBAC + Cost Security

**Cost fields** (`_COST_KEYS` frozenset):
```python
{"totalMaterialCost", "totalLaborCost", "totalEquipmentCost", "totalOtherCost",
 "totalCost", "materialCost", "laborCost", "equipmentCost", "otherCost", "costPerUnit"}
```

**`_apply_rbac(data, role)`**:
- If `role in PRICE_VIEW_ROLES`: pass through unchanged
- Otherwise: call `strip_price_for_role` + sweep `_COST_KEYS` → set each to `None`
- Works recursively on `data["products"]` list if present

**PRICE_VIEW_ROLES**: imported from `smartbi_compat._rbac_strip` (shared with restaurant analytics).

---

## 7. Tests

File: `backend/python/smartbi/tests/test_factory_production_etl.py`

| Test class | Coverage |
|-----------|----------|
| `TestToFloatOrNone` | `None→None`, `Decimal("0.00")→0.0` (honest zero), int/float/Decimal conversion |
| `TestSyncFactProductionBatch` | Basic upsert count, empty source, cost null honesty (arg index mapping), stat_date from end_time, stat_date fallback to start_time, stat_date=None when both times null, source_pk as string |
| `TestMaterializeFactoryGold` | Both Gold SQLs executed, tenant GUC set first |
| `TestRunFactoryEtl` | Success path (Silver+Gold), Silver failure → Gold skipped |
| `TestRbacCostStripping` | Price role sees costs, non-price role stripped, unknown role stripped, None role stripped, nested list stripped |
| `TestSourceFilterSql` | WHERE clause includes `status`/`COMPLETED`, `is_trial`, `deleted_at` |

All tests use asyncpg-compatible fake pools/connections — no real DB required.

---

## 8. Phase 2 Backlog

| Item | Priority | Notes |
|------|----------|-------|
| **Process-level Silver** | P1 | Add `fact_production_report` table (per-WorkProcessReport granularity); enables per-operation cost attribution |
| **Frontend integration** | P1 | Wire `GET /factory/cost-structure` and `/yield-trend` into SmartBI factory dashboard Vue components |
| **Scheduled ETL** | P2 | APScheduler job to run `run_factory_etl_with_retry` nightly (or on-demand after batch COMPLETED event) |
| **Supplier price linkage** | P2 | Join `material_cost` against supplier purchase price to compute margin |
| **Redis job registry** | P3 | Replace in-memory `_running_jobs` dict with Redis for multi-worker reliability |
| **Strict-byte gate** | P3 | Phase 2A uses dict-eq parity; upgrade to strict-byte when frontend contracts require it |

---

## 9. Files Created

| File | SHA (commit) |
|------|-------------|
| `backend/python/smartbi/database/migrations/V20261002_01__factory_production_gold.sql` | `7bd386928` |
| `backend/python/smartbi/gold/factory_production_etl.py` | `4510ddd64` |
| `backend/python/smartbi/api/factory_etl_admin.py` | `5c56a3669` |
| `backend/python/main.py` (2 router registrations) | `5c56a3669`, `cdc54a074` |
| `backend/python/smartbi/api/factory_production_gold.py` | `cdc54a074` |
| `backend/python/smartbi/tests/test_factory_production_etl.py` | (this commit) |
| `docs/superpowers/specs/2026-06-11-factory-production-gold-etl.md` | (this commit) |

---

## 10. 🔒 Opus Gate Checklist (migration runs are Opus's job)

Before deployment, Opus must verify:

- [ ] Migration number `V20260930_01` is still strictly > `git ls-tree origin/main backend/python/smartbi/database/migrations` frontier (no collision from other merged PRs)
- [ ] `FORCE ROW LEVEL SECURITY` + policy syntax valid for target PostgreSQL version
- [ ] `GRANT SELECT, INSERT, UPDATE, DELETE ON fact_production_batch, agg_factory_batch_daily TO smartbi_user` — confirm `smartbi_user` exists on prod
- [ ] `_pk_helper` GENERATED ALWAYS AS STORED syntax supported (PostgreSQL 12+)
- [ ] `ON CONFLICT (_pk_helper)` references the UNIQUE INDEX, not a constraint name — verify PostgreSQL accepts index name in ON CONFLICT
- [ ] Cost fields nullable in prod schema won't break existing Gold queries on other tables (isolation check)
- [ ] Run migration on test env (`smartbi_db`) first, verify both tables created + RLS policies applied
- [ ] Deploy Python service with `./scripts/deploy/deploy-smartbi-python.sh --env test` (migration runner auto-applies V20260930_01)
- [ ] Trigger ETL via `POST /api/smartbi/factory/etl/trigger` with test factory_id
- [ ] Verify Silver + Gold row counts via `GET /api/smartbi/factory/etl/status`
- [ ] If test env clean → repeat for prod
