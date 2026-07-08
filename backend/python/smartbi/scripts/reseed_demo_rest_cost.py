"""Re-runnable DEMO_REST cost reseed — closes the P1 temporal-drift gap.

The one-time migration ``V20260709_02__demo_rest_cost_seed.sql`` seeds
``agg_daily_cost`` from ``agg_daily`` at migration time. ``agg_daily`` for
DEMO_REST is seeded with CURRENT_DATE-relative dates (V20260706_01 uses
``generate_series`` off ``CURRENT_DATE``), so if it is ever RE-materialized with
newer dates (materializer / restaurant-etl), those revenue-days would get NO
cost row → SUM(cost) covers only old dates while SUM(revenue) covers all →
net_profit / net_margin silently OVERSTATED (P1 adversarial-audit finding).

This script re-applies the SAME per-store cost ratios (口径 identical to the
migration — Opus-pinned, do NOT change) idempotently: ``ON CONFLICT DO NOTHING``
covers any new dates without disturbing existing rows. **Run it after any
DEMO_REST agg_daily rebuild** to keep profit honest.

    cd /www/wwwroot/cretas/code/backend/python && source venv38/bin/activate
    PYTHONPATH=.:smartbi python -m smartbi.scripts.reseed_demo_rest_cost
    # or explicit DSN:
    PYTHONPATH=.:smartbi python -m smartbi.scripts.reseed_demo_rest_cost --dsn postgresql://...

Both the read (agg_daily) and the write-CHECK (agg_daily_cost) are RLS FORCE, so
the tenant GUC is set inside the transaction.
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from pathlib import Path

_PYTHON_ROOT = Path(__file__).resolve().parent.parent.parent
for _p in (str(_PYTHON_ROOT), str(_PYTHON_ROOT / "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("reseed_demo_rest_cost")

# 口径 IDENTICAL to migration V20260709_02 (Opus-pinned). Food 30-38% / labor
# 22-30% / overhead 14-20%, per-store spread via store_id%9 / %7 so net margin
# varies across stores. Changing these would diverge the reseed from the
# deployed cost basis — DO NOT edit without re-pinning the migration too.
_RESEED_SQL = """
INSERT INTO agg_daily_cost (factory_id, date, store_id, material_cost, labor_cost, overhead_cost)
SELECT a.factory_id, a.date, a.store_id,
  ROUND(a.net_amount * (0.30 + (a.store_id % 9) * 0.01), 2),
  ROUND(a.net_amount * (0.22 + (a.store_id % 9) * 0.01), 2),
  ROUND(a.net_amount * (0.14 + (a.store_id % 7) * 0.01), 2)
FROM agg_daily a
WHERE a.factory_id = 'DEMO_REST' AND a.net_amount > 0
ON CONFLICT (factory_id, date, store_id) DO NOTHING
"""


async def _run(dsn: str | None) -> None:
    if dsn:
        import asyncpg
        pool = await asyncpg.create_pool(dsn, min_size=1, max_size=2)
    else:
        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("Postgres pool unavailable (check DB env / --dsn)")

    async with pool.acquire() as conn:
        async with conn.transaction():
            # RLS FORCE on both tables → set the tenant GUC in-txn (is_local=true)
            # so the read admits DEMO_REST rows and the write-CHECK passes.
            await conn.execute("SELECT set_config('app.factory_id', 'DEMO_REST', true)")
            result = await conn.execute(_RESEED_SQL)

    inserted = result.rsplit(" ", 1)[-1] if result else "?"
    print(f"reseed_demo_rest_cost: covered {inserted} new agg_daily_cost row(s) "
          f"(existing rows untouched via ON CONFLICT DO NOTHING)")


def main() -> None:
    ap = argparse.ArgumentParser(
        description="Idempotent DEMO_REST cost reseed (run after agg_daily rebuild)")
    ap.add_argument("--dsn", default=os.getenv("RESEED_DSN"),
                    help="optional asyncpg DSN; defaults to app DB env")
    args = ap.parse_args()
    asyncio.run(_run(args.dsn))


if __name__ == "__main__":
    main()
