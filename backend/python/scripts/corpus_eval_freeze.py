"""Freeze a held-out eval slice per (source, business_type) bucket — P2 / G3 asset.

Selects N quality>=4 rows per bucket and marks them as eval_frozen in metadata:
    metadata = metadata || '{"eval_frozen": true}'

Frozen rows are:
  - EXCLUDED from the training export (export_distillation_dataset.py respects this).
  - STABLE across runs (idempotent: already-frozen rows are counted but never
    re-selected or re-written).
  - Intended for human spot-checks (G3 protocol) and per-bucket eval scoring.

GREEN criterion context (spec §4):
  Buckets need ≥1000 quality>=4 training rows PLUS a stable eval slice that
  never bleeds into training.  This script creates the eval side; the export
  script excludes the frozen rows.

Usage:
    # Dry-run — print what would be frozen, no DB writes:
    cd backend/python
    python -m scripts.corpus_eval_freeze --dry-run

    # Real run with default 30 per bucket:
    python -m scripts.corpus_eval_freeze

    # Custom slice size:
    python -m scripts.corpus_eval_freeze --n 50

    # Custom DSN:
    python -m scripts.corpus_eval_freeze --dsn postgresql://user:pass@host/db --n 30
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
from collections import defaultdict
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Path bootstrap — works whether run as ``python scripts/corpus_eval_freeze.py``
# or ``python -m scripts.corpus_eval_freeze`` from backend/python.
# ---------------------------------------------------------------------------
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.normpath(os.path.join(_SCRIPT_DIR, ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("corpus_eval_freeze")

# ---------------------------------------------------------------------------
# SQL
# ---------------------------------------------------------------------------

# Fetch quality>=4 rows NOT already frozen, ordered by id for determinism.
# The LIMIT is applied per-bucket in Python to keep logic transparent.
_FETCH_SQL = """
    SELECT id, source, business_type
    FROM smart_bi_distillation_samples
    WHERE quality >= 4
      AND (metadata IS NULL OR NOT (metadata ? 'eval_frozen'))
    ORDER BY source, business_type, id
"""

# Fetch already-frozen rows to count them (for idempotency reporting).
_COUNT_FROZEN_SQL = """
    SELECT source, business_type, COUNT(*) AS cnt
    FROM smart_bi_distillation_samples
    WHERE metadata ? 'eval_frozen'
      AND (metadata->>'eval_frozen')::boolean = true
    GROUP BY source, business_type
"""

# Freeze: merge eval_frozen=true into metadata.  Uses jsonb || operator so
# other metadata keys are preserved.
_FREEZE_SQL = """
    UPDATE smart_bi_distillation_samples
    SET metadata = COALESCE(metadata, '{}'::jsonb) || '{"eval_frozen": true}'::jsonb
    WHERE id = ANY($1::int[])
"""


# ---------------------------------------------------------------------------
# Pool helpers
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
            logger.warning("Could not load settings for pool DSN: %s", exc)
            return None

    if not effective_dsn:
        logger.warning("No DSN available — pool will be None.")
        return None

    try:
        pool = await asyncpg.create_pool(effective_dsn, min_size=1, max_size=3)
        logger.info("asyncpg pool created (DSN prefix: %s...)", effective_dsn[:30])
        return pool
    except Exception as exc:
        logger.error("Failed to create asyncpg pool: %s", exc)
        return None


# ---------------------------------------------------------------------------
# Core freeze logic
# ---------------------------------------------------------------------------

def _group_by_bucket(
    rows: List[Dict[str, Any]],
) -> Dict[Tuple[str, str], List[int]]:
    """Group row IDs by (source, business_type) bucket."""
    buckets: Dict[Tuple[str, str], List[int]] = defaultdict(list)
    for row in rows:
        key = (
            row.get("source") or "unknown",
            row.get("business_type") or "unknown",
        )
        buckets[key].append(row["id"])
    return dict(buckets)


async def run_freeze(
    n: int = 30,
    dry_run: bool = False,
    dsn: Optional[str] = None,
) -> Dict[str, Any]:
    """Select and freeze eval slices.

    Args:
        n: Target number of eval rows per bucket.
        dry_run: If True, compute selections but perform no DB writes.
        dsn: Optional asyncpg DSN override.

    Returns:
        Summary dict with per-bucket results.
    """
    pool = await _make_pool(dsn) if not dry_run else None

    # --- Fetch candidates (not yet frozen, quality>=4) ---
    if pool is not None:
        async with pool.acquire() as conn:
            raw_candidates = await conn.fetch(_FETCH_SQL)
            raw_already = await conn.fetch(_COUNT_FROZEN_SQL)
    else:
        # dry-run or pool unavailable: try a read-only pool for census
        ro_pool = await _make_pool(dsn)
        if ro_pool is not None:
            async with ro_pool.acquire() as conn:
                raw_candidates = await conn.fetch(_FETCH_SQL)
                raw_already = await conn.fetch(_COUNT_FROZEN_SQL)
            await ro_pool.close()
        else:
            raw_candidates = []
            raw_already = []

    candidate_rows = [dict(r) for r in raw_candidates]
    already_frozen: Dict[Tuple[str, str], int] = {
        (r["source"], r["business_type"]): r["cnt"]
        for r in raw_already
    }

    # --- Group candidates by bucket ---
    bucket_candidates = _group_by_bucket(candidate_rows)

    # --- Determine which IDs to freeze per bucket ---
    to_freeze: Dict[Tuple[str, str], List[int]] = {}
    summary_lines: List[str] = []

    all_buckets = set(bucket_candidates.keys()) | set(already_frozen.keys())

    for bucket in sorted(all_buckets):
        src, bt = bucket
        already = already_frozen.get(bucket, 0)
        candidates = bucket_candidates.get(bucket, [])
        need = max(0, n - already)
        selected = candidates[:need]
        to_freeze[bucket] = selected

        status = "SKIP (already at cap)" if need == 0 else f"freeze {len(selected)}"
        line = (
            f"  bucket ({src}, {bt}): already={already}  candidates={len(candidates)}"
            f"  need={need}  selected={len(selected)}  → {status}"
        )
        summary_lines.append(line)
        logger.info(line)

    # Flatten all IDs to freeze
    all_ids: List[int] = []
    for ids in to_freeze.values():
        all_ids.extend(ids)

    total_new = len(all_ids)
    total_already = sum(already_frozen.values())

    if dry_run:
        print(f"\nDRY-RUN: would freeze {total_new} new rows across "
              f"{len(all_buckets)} bucket(s) "
              f"({total_already} already frozen, target N={n}).")
        print("No DB writes performed.")
        return {
            "dry_run": True,
            "n_target": n,
            "n_new": total_new,
            "n_already": total_already,
            "buckets": len(all_buckets),
            "per_bucket": {f"{k[0]}/{k[1]}": len(v) for k, v in to_freeze.items()},
        }

    # --- Apply freeze in batches ---
    if pool is not None and all_ids:
        async with pool.acquire() as conn:
            await conn.execute(_FREEZE_SQL, all_ids)
        logger.info("Froze %d row(s) in smart_bi_distillation_samples.", total_new)
    elif not all_ids:
        logger.info("Nothing to freeze — all buckets already at target N=%d.", n)

    print(f"\nEVAL FREEZE COMPLETE")
    print(f"  target N per bucket : {n}")
    print(f"  already frozen      : {total_already}")
    print(f"  newly frozen        : {total_new}")
    print(f"  buckets processed   : {len(all_buckets)}")

    if pool is not None:
        try:
            await pool.close()
        except Exception:
            pass

    return {
        "dry_run": False,
        "n_target": n,
        "n_new": total_new,
        "n_already": total_already,
        "buckets": len(all_buckets),
        "per_bucket": {f"{k[0]}/{k[1]}": len(v) for k, v in to_freeze.items()},
    }


# ---------------------------------------------------------------------------
# CLI entry-point
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Freeze eval slice per (source, business_type) bucket — G3 asset.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "--n", type=int, default=30,
        help="Target eval rows per bucket (default: 30). Already-frozen rows count toward N.",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Show what would be frozen without writing to DB.",
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

    asyncio.run(run_freeze(
        n=args.n,
        dry_run=args.dry_run,
        dsn=args.dsn or None,
    ))


if __name__ == "__main__":
    main()
