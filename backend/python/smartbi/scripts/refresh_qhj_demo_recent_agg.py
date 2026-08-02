"""Roll an allowlisted demo tenant's recent Gold sales from a fixed template.

This stage is deliberately aggregate-only.  It gives the downstream demo POS
roller an explicit revenue / bill-count target without reading a real tenant or
inventing a second business shape.  Each demo tenant owns a fixed, verified,
continuous 81-day template and a distinct reserved version marker.  Target rows
are therefore deterministic, auditable, idempotent and safely reversible.

``RES_3101_009`` remains the backwards-compatible default.  ``DEMO_REST`` is
allowed only so its stopped 2026-07-31 seed can enter the same aggregate -> POS
transaction -> dish-item loop; it is a maintained fallback/regression tenant,
not a primary user-facing demo data source.

The command is dry-run by default.  Production application requires both
``--apply`` and the exact tenant confirmation value.
"""
from __future__ import annotations

import argparse
import asyncio
from dataclasses import asdict, dataclass
from datetime import date, timedelta
from typing import Any, Dict


@dataclass(frozen=True)
class DemoRefreshConfig:
    factory_id: str
    source_start: date
    source_end: date
    target_start: date
    seed_version: int


FACTORY_ID = "RES_3101_009"  # backwards-compatible CLI/function default
DEMO_REST_FACTORY_ID = "DEMO_REST"
SOURCE_START = date(2026, 2, 9)
SOURCE_END = date(2026, 4, 30)
TARGET_START = date(2026, 5, 1)
SEED_VERSION = 9_000_721
DEMO_REST_TARGET_START = date(2026, 8, 1)
DEMO_REST_SEED_VERSION = 9_000_728

CONFIG_BY_FACTORY = {
    FACTORY_ID: DemoRefreshConfig(
        factory_id=FACTORY_ID,
        source_start=SOURCE_START,
        source_end=SOURCE_END,
        target_start=TARGET_START,
        seed_version=SEED_VERSION,
    ),
    DEMO_REST_FACTORY_ID: DemoRefreshConfig(
        factory_id=DEMO_REST_FACTORY_ID,
        # Production read-only probe 2026-08-02: DEMO_REST agg_daily is
        # calendar-continuous from 2025-01-01 through 2026-07-31.  Reuse the
        # same fixed 81-day calendar window as the RES demo, but clone only
        # DEMO_REST's own rows; never read across tenants.
        source_start=SOURCE_START,
        source_end=SOURCE_END,
        target_start=DEMO_REST_TARGET_START,
        seed_version=DEMO_REST_SEED_VERSION,
    ),
}
ALLOWED_FACTORIES = tuple(CONFIG_BY_FACTORY)


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
    seed_version: int


def config_for(factory_id: str) -> DemoRefreshConfig:
    try:
        return CONFIG_BY_FACTORY[factory_id]
    except KeyError as exc:
        raise RuntimeError(f"--factory must be one of {ALLOWED_FACTORIES}") from exc


def source_date_for_target(target: date, factory_id: str = FACTORY_ID) -> date:
    config = config_for(factory_id)
    if target < config.target_start:
        raise ValueError("target date precedes the approved demo window")
    cycle_days = (config.source_end - config.source_start).days + 1
    offset = (target - config.target_start).days % cycle_days
    return config.source_start + timedelta(days=offset)


def validate_target_end(target_end: date, factory_id: str = FACTORY_ID) -> None:
    config = config_for(factory_id)
    latest_allowed = date.today() - timedelta(days=1)
    if target_end < config.target_start:
        raise ValueError("target end precedes the approved demo window")
    if target_end > latest_allowed:
        raise ValueError(
            f"target end {target_end} exceeds latest complete day {latest_allowed}"
        )


async def build_plan(
    conn: Any,
    target_end: date,
    factory_id: str = FACTORY_ID,
) -> RefreshPlan:
    config = config_for(factory_id)
    validate_target_end(target_end, factory_id)
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
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
        factory_id,
        config.source_start,
        config.source_end,
        config.target_start,
        target_end,
        config.seed_version,
    )
    cycle_days = (config.source_end - config.source_start).days + 1
    return RefreshPlan(
        factory_id=factory_id,
        source_start=config.source_start.isoformat(),
        source_end=config.source_end.isoformat(),
        target_start=config.target_start.isoformat(),
        target_end=target_end.isoformat(),
        template_days=cycle_days,
        source_rows=int(counts["source_rows"]),
        source_days=int(counts["source_days"]),
        target_rows_before=int(counts["target_rows"]),
        target_days_before=int(counts["target_days"]),
        seeded_rows_before=int(counts["seeded_rows"]),
        missing_source_days=int(counts["missing_source_days"]),
        seed_version=config.seed_version,
    )


async def apply_refresh(conn: Any, plan: RefreshPlan) -> Dict[str, int]:
    config = config_for(plan.factory_id)
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
        plan.factory_id,
        config.target_start,
        date.fromisoformat(plan.target_end),
        config.source_start,
        plan.template_days,
        config.seed_version,
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
        plan.factory_id,
        config.target_start,
        date.fromisoformat(plan.target_end),
        config.seed_version,
    )
    expected_days = (
        date.fromisoformat(plan.target_end) - config.target_start
    ).days + 1
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


async def rollback_refresh(
    conn: Any,
    factory_id: str = FACTORY_ID,
) -> Dict[str, int]:
    config = config_for(factory_id)
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
    result = await conn.execute(
        "DELETE FROM agg_daily WHERE factory_id=$1 AND version=$2",
        factory_id,
        config.seed_version,
    )
    return {"deleted_rows": int(result.rsplit(" ", 1)[-1])}


async def run(args: argparse.Namespace) -> None:
    # Preserve programmatic callers built before the CLI gained --factory.
    factory_id = getattr(args, "factory", FACTORY_ID)
    config_for(factory_id)
    if args.confirm != factory_id and (args.apply or args.rollback):
        raise RuntimeError(f"state change requires --confirm {factory_id}")
    target_end = date.fromisoformat(args.end) if args.end else date.today() - timedelta(days=1)

    from smartbi.config import get_pg_pool

    pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("SmartBI Postgres pool is unavailable")
    async with pool.acquire() as conn:
        async with conn.transaction():
            if args.rollback:
                result = await rollback_refresh(conn, factory_id)
                print({"mode": "rollback", "factory_id": factory_id, **result})
                return
            plan = await build_plan(conn, target_end, factory_id)
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
    parser.add_argument(
        "--factory",
        default=FACTORY_ID,
        choices=list(ALLOWED_FACTORIES),
        help=f"demo tenant to refresh (default: {FACTORY_ID})",
    )
    parser.add_argument("--confirm", default="")
    parser.add_argument("--end", help="last complete target date; defaults to yesterday")
    args = parser.parse_args()
    asyncio.run(run(args))


if __name__ == "__main__":
    main()
