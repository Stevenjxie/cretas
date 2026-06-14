# Demo Tenant Clone — Operational Runbook (validated on scratch 2026-06-14)

Validated end-to-end on `DEMO_REST_SCRATCH` (cloned from qhj `RES_3101_009`): clone + 脱敏 gates all pass, charts gold regenerated (agg_daily 3634 = source). This runbook is the exact, working sequence + the env/path gotchas the rehearsal surfaced.

## Where it runs
The clone + ETL must run **on server 47** (DBs are localhost-only). Code lives at
`/www/wwwroot/cretas/code/backend/python/scripts/demo/` (synced from the feature branch via `scp`).
Python = `venv38/bin/python` (Python 3.8 — hence `from __future__ import annotations` in clone_core).

## One-time prereqs (per session)
```bash
# 1. smartbi_prod_db has FORCE RLS; clone crosses tenants -> grant BYPASSRLS to smartbi_user (revert after).
ssh root@47 "sudo -u postgres psql -d smartbi_prod_db -c 'ALTER ROLE smartbi_user BYPASSRLS;'"
# 2. faker for the masker
ssh root@47 "/www/wwwroot/cretas/code/backend/python/venv38/bin/pip install -q faker"
# 3. sync engine
cd <worktree>/backend/python && scp -r scripts/demo root@47:/www/wwwroot/cretas/code/backend/python/scripts/
```

## Shared env (every command below)
```bash
CLONE_CRETAS_DSN defaults to cretas_user:cretas123@127.0.0.1/cretas_prod_db (in clone_config)
export ENVV='FOOD_KB_POSTGRES_HOST=127.0.0.1 FOOD_KB_POSTGRES_DB=cretas_prod_db FOOD_KB_POSTGRES_USER=cretas_user FOOD_KB_POSTGRES_PASSWORD=cretas123 POSTGRES_HOST=127.0.0.1 POSTGRES_DB=smartbi_prod_db POSTGRES_USER=smartbi_user POSTGRES_PASSWORD=smartbi_secure_password_2025 CLONE_SMARTBI_SUPER_DSN=postgresql://smartbi_user:smartbi_secure_password_2025@127.0.0.1:5432/smartbi_prod_db'
# the ETL scripts ALSO need PYTHONPATH=/www/wwwroot/cretas/code/backend/python/smartbi
#   (excel_parser does `from services...` which only resolves with smartbi/ on the path)
```

## Sequence (per tenant)
```bash
cd /www/wwwroot/cretas/code/backend/python

# 1. CLONE (cretas operational + smartbi POS silver/dims; de-identified). --reset = idempotent.
env $ENVV venv38/bin/python -u -m scripts.demo.clone_tenant --tenant rest --reset
#   (factory: --tenant factory ; scratch rehearsal: add --target-override DEMO_REST_SCRATCH)

# 2. VERIFY gates (parity / FK integrity / de-identify). Must print ALL GATES PASS.
env $ENVV venv38/bin/python -m scripts.demo.verify --tenant rest

# 3. POS revenue gold (agg_daily/product/channel/discount) from cloned fact_pos.
PYTHONPATH=/www/wwwroot/cretas/code/backend/python/smartbi env $ENVV \
  venv38/bin/python -u -m scripts.backfill_gold_for_chains --factory-ids DEMO_REST

# 4. restaurant_ops gold + supplier_price (regenerates dim_ingredient, fact_restaurant_*, agg_restaurant_*).
env $ENVV venv38/bin/python -u -m scripts.gold_etl_daily_refresh --factories DEMO_REST

# 5. restaurant finance gold (POS -> smart_bi_finance_data) via the running Python service:
curl -s -X POST http://127.0.0.1:8083/api/smartbi/restaurant/etl/finance-etl/trigger \
  -H 'Content-Type: application/json' -d '{"factoryId":"DEMO_REST","startDate":"2025-01-01","endDate":"2026-12-31"}'
```

## What clones vs what regenerates (the data-flow rule)
- **CLONE** (source-of-truth, not derivable): all cretas operational tables; smartbi `fact_pos_*` + `dim_store/product/payment_channel/discount`.
- **REGENERATE via ETL** (derived — do NOT clone, or you collide): `agg_daily/product/channel/discount` (step 3); `dim_ingredient` + `fact_restaurant_*` + `agg_restaurant_*` (step 4); smartbi `smart_bi_finance_data` (step 5).

## Cleanup
```bash
# delete a scratch/demo tenant entirely
env $ENVV venv38/bin/python -m scripts.demo.clone_tenant --tenant rest --target-override DEMO_REST_SCRATCH --reset --dry-run  # (then real --reset run is the start of a re-clone)
# revert the RLS grant when done with all clones
ssh root@47 "sudo -u postgres psql -d smartbi_prod_db -c 'ALTER ROLE smartbi_user NOBYPASSRLS;'"
```

## Known follow-ups
- **Factory tenant (DEMO_FACTORY ← F001)**: has sales_orders/sales_order_items (Gap 2 handled) AND may have `created_by`/`salesperson_id` pointing to cross-factory/platform users → NOT-NULL FK maps to NULL → INSERT fails (Gap 1). Fix before factory clone: fallback unresolved user-FK to the provisioned demo admin user id.
- **青花椒 in dish/ingredient names** kept (it's the Sichuan spice, not the brand). Brand identity removed from store/company/contact fields. Confirm acceptable, or switch to full dish-name replacement.
- **Supplier fake names** are tech companies (faker `company()`); cosmetically odd for a restaurant — optional: curate a food-supplier name pool.
- Scratch `DEMO_REST_SCRATCH` left on prod for inspection; `--reset` to remove.
