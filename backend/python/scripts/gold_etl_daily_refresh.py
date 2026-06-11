"""Gold ETL daily refresh orchestrator.

Runs three gold ETL pipelines for every active tenant discovered in cretas_db:
  1. restaurant_ops_etl.run_full_etl_with_retry
     (cretas_db → smartbi_db restaurant ops gold)
  2. factory_production_etl.run_factory_etl_with_retry
     (cretas_db → smartbi_db factory production gold)
  3. supplier_price_ingest_etl.run_supplier_price_ingest
     (cretas_db material_batches → smartbi_db agg_supplier_price)

Each ETL is isolated per-tenant: one failure does not abort the others.
A summary of stats + errors is printed at the end.

Usage (CLI):
    # All discovered tenants:
    cd backend/python
    python -m scripts.gold_etl_daily_refresh

    # Specific factory IDs only:
    python -m scripts.gold_etl_daily_refresh --factories F001,F006

    # Dry-run — list tenants + ETLs, do not execute:
    python -m scripts.gold_etl_daily_refresh --dry-run

Systemd:
    Managed by cretas-gold-etl-refresh.service / .timer (scripts/systemd/).
    Runs daily at 03:30 CST. TimeoutStartSec=3600.

Environment (via EnvironmentFile=/www/wwwroot/cretas/.env.prod):
    FOOD_KB_POSTGRES_HOST / _PORT / _DB / _USER / _PASSWORD  → cretas_pool
    POSTGRES_HOST / _PORT / _DB / _USER / _PASSWORD          → smartbi_pool

⚠️  .env.distill reference: The systemd service uses EnvironmentFile pointing
    to .env.prod (not .env.distill). Confirm .env.prod contains both sets of
    DSN vars (cretas_db via FOOD_KB_POSTGRES_* and smartbi_db via POSTGRES_*).
    .env.distill is the LLM-distillation env; these ETLs do not call LLM.
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set

# ---------------------------------------------------------------------------
# Path bootstrap — must come before any smartbi imports
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.normpath(os.path.join(_SCRIPT_DIR, ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
)
logger = logging.getLogger("gold_etl_daily_refresh")

# ---------------------------------------------------------------------------
# Module-level imports of pool factories and ETL entry points.
# Imported here (not inside async functions) so that tests can patch them
# via patch("scripts.gold_etl_daily_refresh.<name>", ...).
# ---------------------------------------------------------------------------
from smartbi.config import get_cretas_pool, get_pg_pool  # noqa: E402
from smartbi.gold.restaurant_ops_etl import run_full_etl_with_retry as _restaurant_ops_etl  # noqa: E402
from smartbi.gold.factory_production_etl import run_factory_etl_with_retry as _factory_production_etl  # noqa: E402
from smartbi.gold.supplier_price_ingest_etl import run_supplier_price_ingest as _supplier_price_etl  # noqa: E402

# ---------------------------------------------------------------------------
# Tenant discovery queries
# ---------------------------------------------------------------------------

_DISCOVERY_SQL = """
SELECT DISTINCT factory_id FROM production_batches WHERE deleted_at IS NULL
UNION
SELECT DISTINCT factory_id FROM recipes WHERE deleted_at IS NULL
UNION
SELECT DISTINCT factory_id FROM material_batches WHERE deleted_at IS NULL
ORDER BY 1
"""

# Subset discovery: factory_id must appear in production_batches to run
# factory_production_etl (prevents pointless queries for restaurant-only tenants).
_HAS_PRODUCTION_SQL = """
SELECT DISTINCT factory_id FROM production_batches WHERE deleted_at IS NULL
"""

# Subset discovery: factory_id must appear in material_batches to run
# supplier_price_ingest (prevents zero-row runs for tenants without batches).
_HAS_MATERIAL_BATCHES_SQL = """
SELECT DISTINCT factory_id FROM material_batches WHERE deleted_at IS NULL
"""


# ---------------------------------------------------------------------------
# Per-ETL result
# ---------------------------------------------------------------------------

@dataclass
class EtlRunResult:
    factory_id: str
    etl_name: str
    ok: bool
    duration_s: float
    stats: Any = None          # EtlStats / FactoryEtlStats / dict
    error: Optional[str] = None


@dataclass
class RefreshSummary:
    results: List[EtlRunResult] = field(default_factory=list)

    @property
    def total(self) -> int:
        return len(self.results)

    @property
    def ok_count(self) -> int:
        return sum(1 for r in self.results if r.ok)

    @property
    def error_count(self) -> int:
        return sum(1 for r in self.results if not r.ok)

    @property
    def errors(self) -> List[EtlRunResult]:
        return [r for r in self.results if not r.ok]


# ---------------------------------------------------------------------------
# Tenant discovery
# ---------------------------------------------------------------------------

async def discover_factories(cretas_pool) -> List[str]:
    """Return sorted list of active factory_ids from cretas_db."""
    async with cretas_pool.acquire() as conn:
        rows = await conn.fetch(_DISCOVERY_SQL)
    return [row["factory_id"] for row in rows]


async def _factory_subsets(cretas_pool) -> tuple[Set[str], Set[str]]:
    """Return (has_production, has_material_batches) as sets of factory_ids."""
    async with cretas_pool.acquire() as conn:
        prod_rows = await conn.fetch(_HAS_PRODUCTION_SQL)
        mat_rows = await conn.fetch(_HAS_MATERIAL_BATCHES_SQL)
    has_production = {r["factory_id"] for r in prod_rows}
    has_material = {r["factory_id"] for r in mat_rows}
    return has_production, has_material


# ---------------------------------------------------------------------------
# Individual ETL runners (each isolated in try/except)
# ---------------------------------------------------------------------------

async def _run_restaurant_ops(
    cretas_pool,
    smartbi_pool,
    factory_id: str,
) -> EtlRunResult:
    t0 = time.monotonic()
    try:
        stats = await _restaurant_ops_etl(cretas_pool, smartbi_pool, factory_id)
        return EtlRunResult(
            factory_id=factory_id,
            etl_name="restaurant_ops",
            ok=True,
            duration_s=time.monotonic() - t0,
            stats=stats,
        )
    except Exception as exc:
        logger.error(
            "[restaurant_ops] %s FAILED: %s", factory_id, exc, exc_info=True
        )
        return EtlRunResult(
            factory_id=factory_id,
            etl_name="restaurant_ops",
            ok=False,
            duration_s=time.monotonic() - t0,
            error=str(exc),
        )


async def _run_factory_production(
    cretas_pool,
    smartbi_pool,
    factory_id: str,
) -> EtlRunResult:
    t0 = time.monotonic()
    try:
        stats = await _factory_production_etl(cretas_pool, smartbi_pool, factory_id)
        return EtlRunResult(
            factory_id=factory_id,
            etl_name="factory_production",
            ok=True,
            duration_s=time.monotonic() - t0,
            stats=stats,
        )
    except Exception as exc:
        logger.error(
            "[factory_production] %s FAILED: %s", factory_id, exc, exc_info=True
        )
        return EtlRunResult(
            factory_id=factory_id,
            etl_name="factory_production",
            ok=False,
            duration_s=time.monotonic() - t0,
            error=str(exc),
        )


async def _run_supplier_price(
    cretas_pool,
    smartbi_pool,
    factory_id: str,
) -> EtlRunResult:
    t0 = time.monotonic()
    try:
        stats = await _supplier_price_etl(cretas_pool, smartbi_pool, factory_id)
        return EtlRunResult(
            factory_id=factory_id,
            etl_name="supplier_price",
            ok=True,
            duration_s=time.monotonic() - t0,
            stats=stats,
        )
    except Exception as exc:
        logger.error(
            "[supplier_price] %s FAILED: %s", factory_id, exc, exc_info=True
        )
        return EtlRunResult(
            factory_id=factory_id,
            etl_name="supplier_price",
            ok=False,
            duration_s=time.monotonic() - t0,
            error=str(exc),
        )


# ---------------------------------------------------------------------------
# Main orchestration
# ---------------------------------------------------------------------------

async def run_refresh(
    factory_ids: Optional[List[str]] = None,
    dry_run: bool = False,
) -> RefreshSummary:
    """Orchestrate all three ETLs across all (or specified) active tenants.

    Args:
        factory_ids: explicit list to restrict run; None = discover from DB.
        dry_run: if True, print plan but do not acquire pools or run ETLs.
    """
    # get_cretas_pool / get_pg_pool are imported at module level (not locally)
    # so tests can patch them via monkeypatch.setattr(mod, "get_cretas_pool", ...)
    # Do NOT re-import them here — a local `from ... import` would shadow the
    # module-level name and defeat patching.

    summary = RefreshSummary()

    if dry_run:
        logger.info("[dry-run] Would acquire cretas_pool + smartbi_pool")
        if factory_ids:
            logger.info("[dry-run] Explicit factories: %s", factory_ids)
            all_factories = factory_ids
            has_production = set(factory_ids)
            has_material = set(factory_ids)
        else:
            logger.info("[dry-run] Would query cretas_db for active factory_ids")
            all_factories = ["<discovered from DB>"]
            has_production = set(all_factories)
            has_material = set(all_factories)

        logger.info("[dry-run] Plan:")
        for fid in all_factories:
            etls = ["restaurant_ops"]
            if fid in has_production:
                etls.append("factory_production")
            if fid in has_material:
                etls.append("supplier_price")
            logger.info("  %s -> %s", fid, ", ".join(etls))
        return summary

    # get_cretas_pool / get_pg_pool are imported at module level so tests can
    # patch them via patch("scripts.gold_etl_daily_refresh.get_cretas_pool", ...)
    cretas_pool = await get_cretas_pool()
    smartbi_pool = await get_pg_pool()

    if cretas_pool is None:
        raise RuntimeError(
            "cretas_pool is None — check FOOD_KB_POSTGRES_* env vars "
            "(FOOD_KB_POSTGRES_HOST/PORT/DB/USER/PASSWORD)"
        )
    if smartbi_pool is None:
        raise RuntimeError(
            "smartbi_pool is None — check POSTGRES_* env vars "
            "(POSTGRES_HOST/PORT/DB/USER/PASSWORD, POSTGRES_ENABLED=true)"
        )

    # Tenant discovery
    if factory_ids:
        all_factories = list(factory_ids)
        has_production, has_material = await _factory_subsets(cretas_pool)
        # Restrict subsets to explicitly requested factories
        has_production = has_production & set(all_factories)
        has_material = has_material & set(all_factories)
    else:
        all_factories = await discover_factories(cretas_pool)
        has_production, has_material = await _factory_subsets(cretas_pool)

    if not all_factories:
        logger.warning("No active factory_ids found in cretas_db — nothing to do.")
        return summary

    logger.info(
        "Gold ETL refresh: %d tenant(s): %s",
        len(all_factories),
        ", ".join(all_factories),
    )

    run_start = time.monotonic()

    for factory_id in all_factories:
        logger.info("--- %s ---", factory_id)

        # 1. restaurant_ops — run for all tenants
        r1 = await _run_restaurant_ops(cretas_pool, smartbi_pool, factory_id)
        summary.results.append(r1)
        _log_result(r1)

        # 2. factory_production — only for tenants with production_batches
        if factory_id in has_production:
            r2 = await _run_factory_production(cretas_pool, smartbi_pool, factory_id)
            summary.results.append(r2)
            _log_result(r2)
        else:
            logger.info(
                "[factory_production] %s skipped — no production_batches", factory_id
            )

        # 3. supplier_price — only for tenants with material_batches
        if factory_id in has_material:
            r3 = await _run_supplier_price(cretas_pool, smartbi_pool, factory_id)
            summary.results.append(r3)
            _log_result(r3)
        else:
            logger.info(
                "[supplier_price] %s skipped — no material_batches", factory_id
            )

    total_s = time.monotonic() - run_start
    _print_summary(summary, total_s)
    return summary


def _log_result(r: EtlRunResult) -> None:
    status = "OK" if r.ok else "FAIL"
    logger.info(
        "[%s] %s %s (%.1fs) %s",
        r.etl_name,
        r.factory_id,
        status,
        r.duration_s,
        r.error or "",
    )


def _print_summary(summary: RefreshSummary, total_s: float) -> None:
    print("\n" + "=" * 70)
    print(f"  Gold ETL Refresh Summary  |  total={total_s:.1f}s")
    print("=" * 70)
    print(f"  Runs:    {summary.total}")
    print(f"  OK:      {summary.ok_count}")
    print(f"  Errors:  {summary.error_count}")
    if summary.errors:
        print()
        print("  FAILED:")
        for r in summary.errors:
            print(f"    [{r.etl_name}] {r.factory_id}: {r.error}")
    print("=" * 70 + "\n")


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

async def main() -> None:
    parser = argparse.ArgumentParser(
        description="Daily gold ETL refresh for all active tenants.",
    )
    parser.add_argument(
        "--factories",
        type=str,
        default=None,
        help=(
            "Comma-separated list of factory IDs to refresh "
            "(default: auto-discover from cretas_db)"
        ),
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=False,
        help="Print plan without executing ETLs or acquiring DB pools.",
    )
    args = parser.parse_args()

    factory_ids: Optional[List[str]] = None
    if args.factories:
        factory_ids = [f.strip() for f in args.factories.split(",") if f.strip()]

    summary = await run_refresh(factory_ids=factory_ids, dry_run=args.dry_run)

    if summary.error_count > 0:
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
