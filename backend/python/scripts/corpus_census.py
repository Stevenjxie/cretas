"""Distillation-corpus per-bucket census.

Reports the health of every (source, business_type, task_type) bucket in
``smart_bi_distillation_samples`` so the team can see at a glance which buckets
are GREEN (ready for training) vs YELLOW/RED (needs more organic samples or a
quality sweep).

Output (per bucket):
  - total rows
  - organic vs synthetic breakdown (metadata.seeded / source_kind)
  - quality distribution: count by quality value (5/4/3/NULL)
  - GAP to GREEN threshold (≥1000 rows with quality ≥ 4)

GREEN criteria (spec §4):
  ≥1000 quality≥4 rows + synthetic ≤ 50% of total + (G3/G4 are offline checks)

Usage:
    cd /www/wwwroot/cretas/code/backend/python && source venv38/bin/activate
    PYTHONPATH=.:smartbi python -m scripts.corpus_census
    # or with a custom DSN:
    PYTHONPATH=.:smartbi python -m scripts.corpus_census --dsn postgresql://...

The script is READ-ONLY — it never modifies the database.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
from collections import defaultdict
from typing import Any, Dict, List, Optional, Tuple

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("corpus_census")

# GREEN threshold: ≥ this many quality≥4 rows per bucket
GREEN_THRESHOLD = 1000
# Synthetic cap: synthetic rows must be ≤ this fraction of total
SYNTHETIC_CAP = 0.50


# ---------------------------------------------------------------------------
# SQL
# ---------------------------------------------------------------------------

_CENSUS_SQL = """
    SELECT
        source,
        business_type,
        task_type,
        quality,
        metadata
    FROM smart_bi_distillation_samples
    ORDER BY source, business_type, task_type
"""


# ---------------------------------------------------------------------------
# Per-row helpers
# ---------------------------------------------------------------------------

def _is_synthetic(metadata: Any) -> bool:
    """Return True if the row is synthetic / seeded (not organic).

    Checks metadata.seeded (bool) or metadata.source_kind == 'synthetic'.
    """
    if not metadata:
        return False
    if isinstance(metadata, str):
        try:
            metadata = json.loads(metadata)
        except Exception:
            return False
    if not isinstance(metadata, dict):
        return False
    if metadata.get("seeded") is True:
        return True
    sk = metadata.get("source_kind", "")
    return isinstance(sk, str) and sk.lower() == "synthetic"


# ---------------------------------------------------------------------------
# Census aggregation
# ---------------------------------------------------------------------------

class BucketStats:
    """Aggregated stats for one (source, business_type, task_type) bucket."""

    def __init__(self):
        self.total: int = 0
        self.synthetic: int = 0
        self.quality_dist: Dict[Optional[int], int] = defaultdict(int)

    def add(self, quality: Optional[int], is_syn: bool) -> None:
        self.total += 1
        if is_syn:
            self.synthetic += 1
        self.quality_dist[quality] += 1

    @property
    def organic(self) -> int:
        return self.total - self.synthetic

    @property
    def quality_ge4(self) -> int:
        return sum(v for k, v in self.quality_dist.items() if k is not None and k >= 4)

    @property
    def quality_null(self) -> int:
        return self.quality_dist.get(None, 0)

    @property
    def synthetic_pct(self) -> float:
        return (self.synthetic / self.total * 100) if self.total else 0.0

    @property
    def gap_to_green(self) -> int:
        return max(0, GREEN_THRESHOLD - self.quality_ge4)

    def status(self) -> str:
        if self.total == 0:
            return "EMPTY"
        if (
            self.quality_ge4 >= GREEN_THRESHOLD
            and self.synthetic_pct <= SYNTHETIC_CAP * 100
        ):
            return "GREEN"
        if self.quality_ge4 >= GREEN_THRESHOLD // 2:
            return "YELLOW"
        return "RED"


def _aggregate(rows: List[Dict[str, Any]]) -> Dict[Tuple[str, str, str], BucketStats]:
    buckets: Dict[Tuple[str, str, str], BucketStats] = defaultdict(BucketStats)
    for row in rows:
        key = (
            row.get("source") or "unknown",
            row.get("business_type") or "unknown",
            row.get("task_type") or "unknown",
        )
        quality = row.get("quality")
        meta = row.get("metadata")
        buckets[key].add(quality, _is_synthetic(meta))
    return dict(buckets)


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

def _render(buckets: Dict[Tuple[str, str, str], BucketStats]) -> None:
    # Column widths
    w_src = max((len(k[0]) for k in buckets), default=6) + 2
    w_bt = max((len(k[1]) for k in buckets), default=8) + 2
    w_tt = max((len(k[2]) for k in buckets), default=6) + 2

    header = (
        f"{'source':<{w_src}} {'business_type':<{w_bt}} {'task_type':<{w_tt}}"
        f"  {'total':>6}  {'organic':>7}  {'synth':>5}  {'q5':>4}  {'q4':>4}"
        f"  {'q3':>4}  {'qNULL':>5}  {'q>=4':>6}  {'gap':>6}  status"
    )
    sep = "-" * len(header)
    print(sep)
    print(header)
    print(sep)

    for key in sorted(buckets.keys()):
        src, bt, tt = key
        s = buckets[key]
        q5 = s.quality_dist.get(5, 0)
        q4 = s.quality_dist.get(4, 0)
        q3 = s.quality_dist.get(3, 0)
        qn = s.quality_null
        status = s.status()
        status_icon = {"GREEN": "✅ GREEN", "YELLOW": "⚠️ YELLOW", "RED": "❌ RED", "EMPTY": "— EMPTY"}.get(status, status)
        print(
            f"{src:<{w_src}} {bt:<{w_bt}} {tt:<{w_tt}}"
            f"  {s.total:>6}  {s.organic:>7}  {s.synthetic:>5}"
            f"  {q5:>4}  {q4:>4}  {q3:>4}  {qn:>5}"
            f"  {s.quality_ge4:>6}  {s.gap_to_green:>6}  {status_icon}"
        )
    print(sep)
    print(f"GREEN threshold = {GREEN_THRESHOLD} quality≥4 rows per bucket")
    print(f"Synthetic cap   = {int(SYNTHETIC_CAP*100)}% of total")
    print(sep)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

async def _run(dsn: Optional[str]) -> None:
    if dsn:
        import asyncpg
        pool = await asyncpg.create_pool(dsn, min_size=1, max_size=2)
    else:
        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()

    if pool is None:
        raise RuntimeError("Postgres pool unavailable (check DB env / --dsn)")

    async with pool.acquire() as conn:
        rows = await conn.fetch(_CENSUS_SQL)

    records = [dict(r) for r in rows]
    if not records:
        print("smart_bi_distillation_samples is empty — no buckets to report.")
        return

    buckets = _aggregate(records)
    print(f"\nCorpus census — {len(records)} total rows across {len(buckets)} bucket(s)\n")
    _render(buckets)

    # Summary line
    green_count = sum(1 for s in buckets.values() if s.status() == "GREEN")
    print(f"\nSummary: {green_count}/{len(buckets)} bucket(s) GREEN (ready for training)\n")


def main() -> None:
    ap = argparse.ArgumentParser(description="Distillation corpus per-bucket census (read-only)")
    ap.add_argument("--dsn", default=None, help="optional asyncpg DSN; defaults to app DB env")
    args = ap.parse_args()
    asyncio.run(_run(args.dsn))


if __name__ == "__main__":
    main()
