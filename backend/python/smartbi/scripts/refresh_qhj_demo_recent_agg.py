"""Fill the QHJ demo tenant's recent Gold daily-sales gap from a fixed template.

This is deliberately aggregate-only.  It makes recent sales, order-count and
ticket-size comparisons usable without fabricating bill-grain transactions or
mixing synthetic rows into POS details.  The source template is an existing,
continuous 81-day Gold window.  Target rows are marked with a reserved version
so the operation is auditable and safely reversible.

The command is dry-run by default.  Production application requires both
``--apply`` and the exact tenant confirmation value.
"""
from __future__ import annotations

import argparse
import asyncio
from dataclasses import asdict, dataclass
from datetime import date, timedelta
from typing import Any, Dict, Optional


FACTORY_ID = "RES_3101_009"
SOURCE_START = date(2026, 2, 9)
SOURCE_END = date(2026, 4, 30)
TARGET_START = date(2026, 5, 1)
SEED_VERSION = 9_000_721


@dataclass(frozen=True)
class RefreshPlan:
    factory_id: str
    source_start: str
    source_end: str
    target_start: str
    target_end: str
    template_days: int
    source_rows: int
    source_days: int
    target_rows_before: int
    target_days_before: int
    seeded_rows_before: int
    missing_source_days: int


def source_date_for_target(target: date) -> date:
    if target < TARGET_START:
        raise ValueError("target date precedes the approved demo window")
    cycle_days = (SOURCE_END - SOURCE_START).days + 1
    offset = (target - TARGET_START).days % cycle_days
    return SOURCE_START + timedelta(days=offset)


def validate_target_end(target_end: date) -> None:
    latest_allowed = date.today() - timedelta(days=1)
    if target_end < TARGET_START:
        raise ValueError("target end precedes the approved demo window")
    if target_end > latest_allowed:
        raise ValueError(
            f"target end {target_end} exceeds latest complete day {latest_allowed}"
        )


async def build_plan(conn: Any, target_end: date) -> RefreshPlan:
    validate_target_end(target_end)
    await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY_ID)
    counts = await conn.fetchrow(
        """
        SELECT
          (SELECT COUNT(*) FROM agg_daily
            WHERE factory_id=$1 AND date BETWEEN $2 AND $3) AS source_rows,
          (SELECT COUNT(DISTINCT date) FROM agg_daily
            WHERE factory_id=$1 AND date BETWEEN $2 AND $3) AS source_days,
          (SELECT COUNT(*) FROM agg_daily
            WHERE factory_id=$1 AND date BETWEEN $4 AND $5) AS target_rows,
          (SELECT COUNT(DISTINCT date) FROM agg_daily
            WHERE factory_id=$1 AND date BETWEEN $4 AND $5) AS target_days,
          (SELECT COUNT(*) FROM agg_daily
            WHERE factory_id=$1 AND version=$6) AS seeded_rows,
          (SELECT COUNT(*)
             FROM generate_series($2::date, $3::date, interval '1 day') d
            WHERE NOT EXISTS (
                  SELECT 1 FROM agg_daily a
                   WHERE a.factory_id=$1 AND a.date=d::date
            )) AS missing_source_days
        """,
        FACTORY_ID,
        SOURCE_START,
        SOURCE_END,
        TARGET_START,
        target_end,
        SEED_VERSION,
    )
    cycle_days = (SOURCE_END - SOURCE_START).days + 1
    return RefreshPlan(
        factory_id=FACTORY_ID,
        source_start=SOURCE_START.isoformat(),
        source_end=SOURCE_END.isoformat(),
        target_start=TARGET_START.isoformat(),
        target_end=target_end.isoformat(),
        template_days=cycle_days,
        source_rows=int(counts["source_rows"]),
        source_days=int(counts["source_days"]),
        target_rows_before=int(counts["target_rows"]),
        target_days_before=int(counts["target_days"]),
        seeded_rows_before=int(counts["seeded_rows"]),
        missing_source_days=int(counts["missing_source_days"]),
    )


async def apply_refresh(conn: Any, plan: RefreshPlan) -> Dict[str, int]:
    if plan.missing_source_days:
        raise RuntimeError("source template is not calendar-continuous")
    if plan.source_days != plan.template_days or plan.source_rows <= 0:
        raise RuntimeError("source template coverage is incomplete")

    result = await conn.execute(
        """
        WITH target_days AS (
          SELECT day::date AS target_date,
                 $4::date + MOD((day::date - $2::date), $5::int) AS source_date
            FROM generate_series($2::date, $3::date, interval '1 day') day
        )
        INSERT INTO agg_daily (
          factory_id, date, store_id,
          gross_amount, discount_amount, net_amount, actual_receive,
          bill_count, customer_count, item_count,
          version, computed_at
        )
        SELECT $1, td.target_date, source.store_id,
               source.gross_amount, source.discount_amount,
               source.net_amount, source.actual_receive,
               source.bill_count, source.customer_count, source.item_count,
               $6, NOW()
          FROM target_days td
          JOIN agg_daily source
            ON source.factory_id=$1 AND source.date=td.source_date
        ON CONFLICT (factory_id, date, store_id) DO NOTHING
        """,
        FACTORY_ID,
        TARGET_START,
        date.fromisoformat(plan.target_end),
        SOURCE_START,
        plan.template_days,
        SEED_VERSION,
    )
    inserted = int(result.rsplit(" ", 1)[-1])
    verification = await conn.fetchrow(
        """
        SELECT COUNT(*) AS rows,
               COUNT(DISTINCT date) AS days,
               MIN(date) AS min_date,
               MAX(date) AS max_date,
               COUNT(*) FILTER (WHERE version=$4) AS seeded_rows
          FROM agg_daily
         WHERE factory_id=$1 AND date BETWEEN $2 AND $3
        """,
        FACTORY_ID,
        TARGET_START,
        date.fromisoformat(plan.target_end),
        SEED_VERSION,
    )
    expected_days = (date.fromisoformat(plan.target_end) - TARGET_START).days + 1
    if int(verification["days"]) != expected_days:
        raise RuntimeError(
            f"target coverage is incomplete: {verification['days']}/{expected_days} days"
        )
    return {
        "inserted_rows": inserted,
        "target_rows_after": int(verification["rows"]),
        "target_days_after": int(verification["days"]),
        "seeded_rows_after": int(verification["seeded_rows"]),
    }


async def rollback_refresh(conn: Any) -> Dict[str, int]:
    await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY_ID)
    result = await conn.execute(
        "DELETE FROM agg_daily WHERE factory_id=$1 AND version=$2",
        FACTORY_ID,
        SEED_VERSION,
    )
    return {"deleted_rows": int(result.rsplit(" ", 1)[-1])}


async def run(args: argparse.Namespace) -> None:
    if args.confirm != FACTORY_ID and (args.apply or args.rollback):
        raise RuntimeError(f"state change requires --confirm {FACTORY_ID}")
    target_end = date.fromisoformat(args.end) if args.end else date.today() - timedelta(days=1)

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
                print({"mode": "dry-run", **asdict(plan)})
                return
            result = await apply_refresh(conn, plan)
            print({"mode": "apply", **asdict(plan), **result})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true")
    mode.add_argument("--rollback", action="store_true")
    parser.add_argument("--confirm", default="")
    parser.add_argument("--end", help="last complete target date; defaults to yesterday")
    args = parser.parse_args()
    asyncio.run(run(args))


if __name__ == "__main__":
    main()
