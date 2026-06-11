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

# Count total quality>=4 rows per bucket (frozen + unfrozen) — needed for proportional cap.
_TOTAL_QUALITY_SQL = """
    SELECT source, business_type, COUNT(*) AS cnt
    FROM smart_bi_distillation_samples
    WHERE quality >= 4
    GROUP BY source, business_type
"""

# Unfreeze: remove eval_frozen key from metadata (for --rebalance).
# newest-first: we unfreeze the rows with the highest id first (most recently frozen).
_FROZEN_IDS_SQL = """
    SELECT id, source, business_type
    FROM smart_bi_distillation_samples
    WHERE metadata ? 'eval_frozen'
      AND (metadata->>'eval_frozen')::boolean = true
    ORDER BY source, business_type, id DESC
"""

_UNFREEZE_SQL = """
    UPDATE smart_bi_distillation_samples
    SET metadata = metadata - 'eval_frozen'
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


import math as _math


async def run_freeze(
    n: int = 30,
    dry_run: bool = False,
    dsn: Optional[str] = None,
    max_frac: float = 0.30,
) -> Dict[str, Any]:
    """Select and freeze eval slices — proportional cap variant.

    The effective target per bucket is:
        cap = min(n, floor(total_quality_ge4_in_bucket * max_frac))

    This prevents small buckets from being 100% frozen (no training rows left).
    Default max_frac=0.30 means at most 30% of a bucket's quality≥4 rows are
    frozen as eval, regardless of --n.

    Args:
        n: Hard upper bound on eval rows per bucket (default: 30).
        dry_run: If True, compute selections but perform no DB writes.
        dsn: Optional asyncpg DSN override.
        max_frac: Max fraction of total quality≥4 rows per bucket to freeze (default: 0.30).

    Returns:
        Summary dict with per-bucket results.
    """
    pool = await _make_pool(dsn) if not dry_run else None

    # --- Fetch candidates (not yet frozen, quality>=4) ---
    if pool is not None:
        async with pool.acquire() as conn:
            raw_candidates = await conn.fetch(_FETCH_SQL)
            raw_already = await conn.fetch(_COUNT_FROZEN_SQL)
            raw_totals = await conn.fetch(_TOTAL_QUALITY_SQL)
    else:
        # dry-run or pool unavailable: try a read-only pool for census
        ro_pool = await _make_pool(dsn)
        if ro_pool is not None:
            async with ro_pool.acquire() as conn:
                raw_candidates = await conn.fetch(_FETCH_SQL)
                raw_already = await conn.fetch(_COUNT_FROZEN_SQL)
                raw_totals = await conn.fetch(_TOTAL_QUALITY_SQL)
            await ro_pool.close()
        else:
            raw_candidates = []
            raw_already = []
            raw_totals = []

    candidate_rows = [dict(r) for r in raw_candidates]
    already_frozen: Dict[Tuple[str, str], int] = {
        (r["source"], r["business_type"]): r["cnt"]
        for r in raw_already
    }
    bucket_totals: Dict[Tuple[str, str], int] = {
        (r["source"], r["business_type"]): r["cnt"]
        for r in raw_totals
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
        total_in_bucket = bucket_totals.get(bucket, already + len(candidates))
        # Proportional cap: never freeze more than max_frac of the bucket
        proportional_cap = _math.floor(total_in_bucket * max_frac)
        effective_target = min(n, proportional_cap)
        need = max(0, effective_target - already)
        selected = candidates[:need]
        to_freeze[bucket] = selected

        if need == 0:
            status = "SKIP (already at cap)"
        else:
            status = f"freeze {len(selected)} (cap={effective_target}, frac={max_frac:.0%} of {total_in_bucket})"
        line = (
            f"  bucket ({src}, {bt}): total={total_in_bucket}  already={already}  "
            f"candidates={len(candidates)}  need={need}  selected={len(selected)}  → {status}"
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
              f"({total_already} already frozen, target N={n}, max_frac={max_frac:.0%}).")
        print("No DB writes performed.")
        return {
            "dry_run": True,
            "n_target": n,
            "max_frac": max_frac,
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
        logger.info("Nothing to freeze — all buckets already at proportional cap.")

    print(f"\nEVAL FREEZE COMPLETE")
    print(f"  target N per bucket : {n}  (max_frac={max_frac:.0%})")
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
        "max_frac": max_frac,
        "n_new": total_new,
        "n_already": total_already,
        "buckets": len(all_buckets),
        "per_bucket": {f"{k[0]}/{k[1]}": len(v) for k, v in to_freeze.items()},
    }


async def run_rebalance(
    dry_run: bool = False,
    dsn: Optional[str] = None,
    max_frac: float = 0.30,
) -> Dict[str, Any]:
    """Unfreeze over-capped eval rows — idempotent.

    For any bucket where frozen_count > floor(total_quality_ge4 * max_frac),
    unfreeze the excess (newest-first by id) down to the cap.

    This releases eval-frozen rows back into the training pool for small buckets
    that were frozen before proportional capping was introduced (e.g. chat_qa/factory
    26/26 → all 26 frozen, cap=7 → unfreeze 19).

    Args:
        dry_run: If True, compute what would be unfrozen but do not write.
        dsn: Optional asyncpg DSN override.
        max_frac: Maximum fraction of quality≥4 rows to keep frozen per bucket.

    Returns:
        Summary dict.
    """
    pool = await _make_pool(dsn) if not dry_run else None

    if pool is not None:
        async with pool.acquire() as conn:
            raw_frozen = await conn.fetch(_FROZEN_IDS_SQL)
            raw_totals = await conn.fetch(_TOTAL_QUALITY_SQL)
    else:
        ro_pool = await _make_pool(dsn)
        if ro_pool is not None:
            async with ro_pool.acquire() as conn:
                raw_frozen = await conn.fetch(_FROZEN_IDS_SQL)
                raw_totals = await conn.fetch(_TOTAL_QUALITY_SQL)
            await ro_pool.close()
        else:
            raw_frozen = []
            raw_totals = []

    bucket_totals: Dict[Tuple[str, str], int] = {
        (r["source"], r["business_type"]): r["cnt"]
        for r in raw_totals
    }

    # Group frozen IDs by bucket (already newest-first due to ORDER BY id DESC)
    frozen_by_bucket: Dict[Tuple[str, str], List[int]] = defaultdict(list)
    for r in raw_frozen:
        key = (r["source"], r["business_type"])
        frozen_by_bucket[key].append(r["id"])

    to_unfreeze: List[int] = []
    summary: Dict[str, Any] = {}

    for bucket in sorted(set(frozen_by_bucket.keys()) | set(bucket_totals.keys())):
        frozen_ids = frozen_by_bucket.get(bucket, [])
        total = bucket_totals.get(bucket, len(frozen_ids))
        cap = _math.floor(total * max_frac)
        excess = len(frozen_ids) - cap
        if excess > 0:
            # Unfreeze the newest `excess` rows (already sorted newest-first)
            unfreeze_ids = frozen_ids[:excess]
            to_unfreeze.extend(unfreeze_ids)
            src, bt = bucket
            line = (
                f"  bucket ({src}, {bt}): total={total}  frozen={len(frozen_ids)}  "
                f"cap={cap}  excess={excess}  → unfreeze {len(unfreeze_ids)}"
            )
            logger.info(line)
            summary[f"{src}/{bt}"] = {"unfrozen": len(unfreeze_ids), "new_frozen": cap}
        else:
            src, bt = bucket
            logger.debug(
                "  bucket (%s, %s): frozen=%d cap=%d — within cap, no action",
                src, bt, len(frozen_ids), cap,
            )

    if dry_run:
        print(f"\nDRY-RUN --rebalance: would unfreeze {len(to_unfreeze)} rows "
              f"across {len(summary)} over-capped bucket(s) (max_frac={max_frac:.0%}).")
        print("No DB writes performed.")
        return {
            "dry_run": True,
            "max_frac": max_frac,
            "n_unfrozen": len(to_unfreeze),
            "over_capped_buckets": len(summary),
            "per_bucket": summary,
        }

    if pool is not None and to_unfreeze:
        async with pool.acquire() as conn:
            await conn.execute(_UNFREEZE_SQL, to_unfreeze)
        logger.info("Unfroze %d over-capped row(s).", len(to_unfreeze))
    elif not to_unfreeze:
        logger.info("All buckets within proportional cap (max_frac=%.0f%%) — nothing to unfreeze.", max_frac * 100)

    if pool is not None:
        try:
            await pool.close()
        except Exception:
            pass

    print(f"\nREBALANCE COMPLETE: unfroze {len(to_unfreeze)} row(s), "
          f"{len(summary)} bucket(s) adjusted (max_frac={max_frac:.0%}).")
    return {
        "dry_run": False,
        "max_frac": max_frac,
        "n_unfrozen": len(to_unfreeze),
        "over_capped_buckets": len(summary),
        "per_bucket": summary,
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
        help="Hard upper bound on eval rows per bucket (default: 30). "
             "Actual cap = min(N, floor(total_in_bucket * max-frac)).",
    )
    parser.add_argument(
        "--max-frac", type=float, default=0.30,
        help="Max fraction of quality≥4 rows in each bucket to freeze as eval "
             "(default: 0.30 = 30%%). Prevents small buckets from being 100%% frozen.",
    )
    parser.add_argument(
        "--rebalance", action="store_true",
        help="Unfreeze over-capped rows (newest-first) down to max-frac. "
             "Idempotent. Run after changing max-frac or after bucket size grows.",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Show what would be frozen/unfrozen without writing to DB.",
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

    if args.rebalance:
        asyncio.run(run_rebalance(
            dry_run=args.dry_run,
            dsn=args.dsn or None,
            max_frac=args.max_frac,
        ))
    else:
        asyncio.run(run_freeze(
            n=args.n,
            dry_run=args.dry_run,
            dsn=args.dsn or None,
            max_frac=args.max_frac,
        ))


if __name__ == "__main__":
    main()
