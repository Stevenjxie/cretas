"""Roll DEMO_REST's agg_daily forward from its own POS grain.

DEMO_REST's Python restaurant answers (sales summary, day comparisons) read
``agg_daily`` directly, but its seed migration only wrote synthetic aggregates
up to the migration date.  The tenant's ``fact_pos_transaction`` grain is
seeded far ahead, so recent calendar days ("yesterday", "the day before")
exist at grain level while the aggregate the resolvers read stays stale and
every recent-day question collapses to a truthful-but-useless no-data answer.

This command aggregates DEMO_REST's own ``fact_pos_transaction`` rows into
``agg_daily`` for every date up to the requested end (never beyond yesterday).
Existing aggregate rows are left untouched (``ON CONFLICT DO NOTHING``), new
rows carry a reserved seed version so the operation is auditable and fully
reversible.  Scoped to the demo tenant only; real tenant data is never read
or written.

Dry-run by default.  Applying or rolling back requires both ``--apply`` /
``--rollback`` and the exact tenant confirmation value.
"""
from __future__ import annotations

import argparse
import asyncio
from datetime import date, timedelta
from typing import Any, Dict


FACTORY_ID = "DEMO_REST"
SEED_VERSION = 9_000_722


def validate_target_end(target_end: date) -> None:
    latest_allowed = date.today() - timedelta(days=1)
    if target_end > latest_allowed:
        raise ValueError(
            f"target end {target_end} exceeds latest complete day {latest_allowed}"
        )


async def build_plan(conn: Any, target_end: date) -> Dict[str, Any]:
    validate_target_end(target_end)
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", FACTORY_ID)
    counts = await conn.fetchrow(
        """
        SELECT
          (SELECT COUNT(*) FROM fact_pos_transaction
            WHERE factory_id=$1 AND date <= $2) AS fact_rows,
          (SELECT MAX(date) FROM fact_pos_transaction
            WHERE factory_id=$1 AND date <= $2) AS fact_max_date,
          (SELECT COUNT(*) FROM agg_daily WHERE factory_id=$1) AS agg_rows,
          (SELECT MAX(date) FROM agg_daily WHERE factory_id=$1) AS agg_max_date,
          (SELECT COUNT(*) FROM agg_daily
            WHERE factory_id=$1 AND version=$3) AS seeded_rows,
          (SELECT COUNT(DISTINCT t.date) FROM fact_pos_transaction t
            WHERE t.factory_id=$1 AND t.date <= $2
              AND NOT EXISTS (
                    SELECT 1 FROM agg_daily a
                     WHERE a.factory_id=$1 AND a.date=t.date
              )) AS missing_agg_days
        """,
        FACTORY_ID, target_end, SEED_VERSION,
    )
    return {
        "factory_id": FACTORY_ID,
        "target_end": target_end.isoformat(),
        "fact_rows": int(counts["fact_rows"]),
        "fact_max_date": str(counts["fact_max_date"]),
        "agg_rows_before": int(counts["agg_rows"]),
        "agg_max_date_before": str(counts["agg_max_date"]),
        "seeded_rows_before": int(counts["seeded_rows"]),
        "missing_agg_days": int(counts["missing_agg_days"]),
    }


async def apply_refresh(conn: Any, target_end: date) -> Dict[str, Any]:
    result = await conn.execute(
        """
        INSERT INTO agg_daily (
          factory_id, date, store_id,
          gross_amount, discount_amount, net_amount, actual_receive,
          bill_count, customer_count, item_count, version, computed_at
        )
        SELECT t.factory_id, t.date, t.store_id,
               SUM(COALESCE(t.gross_amount, 0)),
               SUM(COALESCE(t.discount_amount, 0)),
               SUM(COALESCE(t.net_amount, 0)),
               SUM(COALESCE(t.actual_receive, t.net_amount, 0)),
               COUNT(*)::int,
               SUM(COALESCE(t.customer_count, 0))::int,
               SUM(COALESCE(t.item_count, 0))::int,
               $2, NOW()
          FROM fact_pos_transaction t
         WHERE t.factory_id = $1 AND t.date <= $3
         GROUP BY t.factory_id, t.date, t.store_id
        ON CONFLICT (factory_id, date, store_id) DO NOTHING
        """,
        FACTORY_ID, SEED_VERSION, target_end,
    )
    inserted = int(result.rsplit(" ", 1)[-1])
    if inserted:
        # Fresh rows shift the planner's row estimates; without new stats the
        # store-margin anchor scans have regressed from seconds to minutes.
        await conn.execute("ANALYZE agg_daily")
    verification = await conn.fetchrow(
        """
        SELECT COUNT(*) AS rows, MAX(date) AS max_date,
               COUNT(*) FILTER (WHERE version=$2) AS seeded_rows
          FROM agg_daily WHERE factory_id=$1
        """,
        FACTORY_ID, SEED_VERSION,
    )
    max_after = verification["max_date"]
    if max_after is None or max_after < target_end:
        raise RuntimeError(
            f"agg_daily coverage still ends at {max_after}, expected {target_end}"
        )
    return {
        "inserted_rows": inserted,
        "agg_rows_after": int(verification["rows"]),
        "agg_max_date_after": str(max_after),
        "seeded_rows_after": int(verification["seeded_rows"]),
    }


async def rollback_refresh(conn: Any) -> Dict[str, int]:
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", FACTORY_ID)
    result = await conn.execute(
        "DELETE FROM agg_daily WHERE factory_id=$1 AND version=$2",
        FACTORY_ID, SEED_VERSION,
    )
    return {"deleted_rows": int(result.rsplit(" ", 1)[-1])}


async def run(args: argparse.Namespace) -> None:
    if args.confirm != FACTORY_ID and (args.apply or args.rollback):
        raise RuntimeError(f"state change requires --confirm {FACTORY_ID}")
    target_end = (
        date.fromisoformat(args.end) if args.end else date.today() - timedelta(days=1)
    )

    from smartbi.config import get_pg_pool

    pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("SmartBI Postgres pool is unavailable")
    async with pool.acquire() as conn:
        async with conn.transaction():
            if args.rollback:
                result = await rollback_refresh(conn)
                print({"mode": "rollback", **result})
                return
            plan = await build_plan(conn, target_end)
            if not args.apply:
                print({"mode": "dry-run", **plan})
                return
            result = await apply_refresh(conn, target_end)
            print({"mode": "apply", **plan, **result})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write the aggregates")
    parser.add_argument("--rollback", action="store_true",
                        help="delete rows previously written by this command")
    parser.add_argument("--confirm", default="",
                        help=f"must equal {FACTORY_ID} for --apply/--rollback")
    parser.add_argument("--end", default="",
                        help="last date to aggregate (default: yesterday)")
    args = parser.parse_args()
    asyncio.run(run(args))


if __name__ == "__main__":
    main()
