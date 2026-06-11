"""Backfill teacher_model in smart_bi_distillation_samples for chart_insight rows.

Existing rows seeded / captured while CHART_INSIGHT_LLM_SLOT=REVIEW was set recorded
'chart' (the slot enum name) instead of the real model name (e.g. 'qwen3-max').
This script updates those rows to the correct value.

Best-effort heuristic: rows with source='chart_insight' and teacher_model='chart'
were produced during corpus seeding, which runs with CHART_INSIGHT_LLM_SLOT=REVIEW.
Update them to the value of --correct-model (default: 'qwen3-max').

Usage:
    cd backend/python
    # Dry-run — see what would change:
    PYTHONPATH=.:smartbi python scripts/backfill_teacher_model.py --dry-run

    # Apply with the correct model name (default qwen3-max):
    PYTHONPATH=.:smartbi python scripts/backfill_teacher_model.py

    # Explicit DSN:
    PYTHONPATH=.:smartbi python scripts/backfill_teacher_model.py \
        --dsn postgresql://user:pass@host/db --correct-model qwen3-max

The script is idempotent: re-running after a successful backfill will find 0 rows to update.
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from typing import Optional

# ---------------------------------------------------------------------------
# Path bootstrap — mirrors export_distillation_dataset.py
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.normpath(os.path.join(_SCRIPT_DIR, ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("backfill_teacher_model")

# ---------------------------------------------------------------------------
# SQL
# ---------------------------------------------------------------------------

# Count rows that need backfilling
_COUNT_SQL = """
    SELECT COUNT(*)
    FROM smart_bi_distillation_samples
    WHERE source = 'chart_insight'
      AND teacher_model = $1
"""

# Update rows
_UPDATE_SQL = """
    UPDATE smart_bi_distillation_samples
    SET teacher_model = $2
    WHERE source = 'chart_insight'
      AND teacher_model = $1
"""


# ---------------------------------------------------------------------------
# Pool helper
# ---------------------------------------------------------------------------

async def _make_pool(dsn: Optional[str]):
    """Create asyncpg pool from explicit DSN or app settings."""
    import asyncpg  # noqa: PLC0415

    effective_dsn = dsn
    if not effective_dsn:
        try:
            from smartbi.config import get_settings  # noqa: PLC0415
            effective_dsn = get_settings().postgres_url
        except Exception as exc:
            logger.warning("Could not load app settings for DSN: %s", exc)
            return None

    if not effective_dsn:
        logger.error(
            "No DSN available. Pass --dsn or set SMARTBI_PROD_DSN / DB env vars."
        )
        return None

    try:
        pool = await asyncpg.create_pool(effective_dsn, min_size=1, max_size=3)
        return pool
    except Exception as exc:
        logger.error("Failed to create asyncpg pool: %s", exc)
        return None


# ---------------------------------------------------------------------------
# Core backfill
# ---------------------------------------------------------------------------

async def run_backfill(
    wrong_value: str = "chart",
    correct_value: str = "qwen3-max",
    dry_run: bool = False,
    dsn: Optional[str] = None,
) -> dict:
    """Update chart_insight rows whose teacher_model is wrong_value → correct_value.

    Args:
        wrong_value: The stale teacher_model value to replace (default: 'chart').
        correct_value: The correct model name to write (default: 'qwen3-max').
        dry_run: If True, count affected rows but do not update.
        dsn: Optional asyncpg DSN override.

    Returns:
        Summary dict with n_found, n_updated.
    """
    pool = await _make_pool(dsn)
    if pool is None:
        logger.error("Pool unavailable — cannot run backfill.")
        return {"error": "pool_unavailable", "n_found": 0, "n_updated": 0}

    try:
        async with pool.acquire() as conn:
            n_found = await conn.fetchval(_COUNT_SQL, wrong_value)

        if n_found == 0:
            logger.info(
                "No rows found with source='chart_insight' AND teacher_model=%r — "
                "nothing to backfill (already clean or wrong --wrong-model).",
                wrong_value,
            )
            return {"dry_run": dry_run, "n_found": 0, "n_updated": 0}

        logger.info(
            "Found %d chart_insight row(s) with teacher_model=%r → will update to %r.",
            n_found, wrong_value, correct_value,
        )

        if dry_run:
            print(
                f"\nDRY-RUN: would update {n_found} row(s) "
                f"teacher_model: {wrong_value!r} → {correct_value!r}."
            )
            print("No DB writes performed.")
            return {"dry_run": True, "n_found": n_found, "n_updated": 0}

        async with pool.acquire() as conn:
            status = await conn.execute(_UPDATE_SQL, wrong_value, correct_value)
        # asyncpg returns "UPDATE N" string
        logger.info("DB result: %s", status)
        print(
            f"\nBACKFILL COMPLETE: {n_found} row(s) updated "
            f"teacher_model: {wrong_value!r} → {correct_value!r}."
        )
        return {"dry_run": False, "n_found": n_found, "n_updated": n_found}

    finally:
        try:
            await pool.close()
        except Exception:
            pass


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Backfill teacher_model in smart_bi_distillation_samples for chart_insight rows. "
            "Fixes rows where CHART_INSIGHT_LLM_SLOT=REVIEW was active during seeding but "
            "the slot name ('chart') was recorded instead of the real model name."
        )
    )
    parser.add_argument(
        "--wrong-model", default="chart",
        help="Stale teacher_model value to replace (default: 'chart').",
    )
    parser.add_argument(
        "--correct-model", default="qwen3-max",
        help="Correct model name to write (default: 'qwen3-max'). "
             "Matches the model behind SLOT.REVIEW in the aliyun_b/c quota.",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Count affected rows but do not update DB.",
    )
    parser.add_argument(
        "--dsn", default=os.environ.get("SMARTBI_PROD_DSN", ""),
        help="asyncpg DSN override (default: SMARTBI_PROD_DSN env or app settings).",
    )
    parser.add_argument(
        "--log-level", default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )

    asyncio.run(run_backfill(
        wrong_value=args.wrong_model,
        correct_value=args.correct_model,
        dry_run=args.dry_run,
        dsn=args.dsn or None,
    ))


if __name__ == "__main__":
    main()
