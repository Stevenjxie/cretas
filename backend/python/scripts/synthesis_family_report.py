"""Comprehensive-synthesis demand-signal report (G1 Piece 2).

Reads the ``source='synthesis'`` rows that ``ComprehensiveSynthesisEngine``
now captures (G1 Piece 1) and reports, per intent family
(attribution | write | query), how much real demand exists and which exact
questions recur. This is the evidence for "which deterministic decomposition
to write next" — the same demand-driven signal the ops flywheel gets from
``restaurant_intent_promotion.family_breakdown``, here applied to the
synthesis path (which the ops promotion aggregator does not cover).

Nothing is auto-promoted: the report only surfaces the demand. A human reads
it and decides which attribution shape is hot enough to justify hand-writing
its deterministic producer (numeric decomposition is never LLM-generalized —
see synthesis_engine docstring).

Usage:
    cd /www/wwwroot/cretas/code/backend/python && source venv38/bin/activate
    PYTHONPATH=.:smartbi python -m scripts.synthesis_family_report
    # or with an explicit DSN:
    PYTHONPATH=.:smartbi python -m scripts.synthesis_family_report --dsn postgresql://...
    # only shapes seen >= N times:
    PYTHONPATH=.:smartbi python -m scripts.synthesis_family_report --min-count 2

READ-ONLY — never modifies the database. smart_bi_distillation_samples has no
RLS (cross-tenant corpus), so no per-tenant GUC is needed.
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

# Standalone-script bootstrap: the import chain touches smartbi/services/* and
# smartbi/gold/*, which resolve `from services.X` / `from smartbi.X` the way
# main.py sets up. Insert both the python root and smartbi/ so this runs whether
# invoked as `-m scripts.synthesis_family_report` or directly.
_PYTHON_ROOT = Path(__file__).resolve().parent.parent
for _p in (str(_PYTHON_ROOT), str(_PYTHON_ROOT / "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from smartbi.gold.restaurant_intent_promotion import (  # noqa: E402
    classify_question_family,
    family_breakdown,
)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("synthesis_family_report")


_REPORT_SQL = """
    SELECT COALESCE(metadata->>'query', input_text)      AS query,
           metadata->>'question_family'                  AS family,
           business_type                                 AS business_type,
           COUNT(*)                                       AS occurrence_count,
           MAX(created_at)                                AS last_seen
      FROM smart_bi_distillation_samples
     WHERE source = 'synthesis'
     GROUP BY 1, 2, 3
     ORDER BY occurrence_count DESC, last_seen DESC
"""


def _build_candidates(rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """One candidate per distinct captured question, shaped for family_breakdown.

    family falls back to classify_question_family(query) when a legacy row has
    no stored question_family — mirrors restaurant_intent_promotion behaviour.
    """
    valid = ("attribution", "write", "query")
    out: List[Dict[str, Any]] = []
    for r in rows:
        fam = r.get("family") or classify_question_family(r.get("query"))
        # Clamp to the known family set — family_breakdown() indexes a dict
        # pre-seeded with only these three and would KeyError on a stray value
        # (e.g. a legacy row or a future 4th family). Unknown → "query".
        if fam not in valid:
            fam = "query"
        out.append({
            "query": (r.get("query") or "").strip(),
            "family": fam,
            "occurrence_count": int(r.get("occurrence_count") or 0),
            "business_type": r.get("business_type"),
            "last_seen": r.get("last_seen"),
        })
    return out


def _render(candidates: List[Dict[str, Any]], *, min_count: int) -> None:
    total_snapshots = sum(c["occurrence_count"] for c in candidates)
    distinct_shapes = len(candidates)

    # Distinct-shape distribution across families (reuses the ops flywheel fn).
    shape_breakdown = family_breakdown(candidates)
    # Snapshot count per family (see caveat below — this is NOT ask-frequency).
    snap: Dict[str, int] = {"attribution": 0, "write": 0, "query": 0}
    for c in candidates:
        snap[c["family"]] = snap.get(c["family"], 0) + c["occurrence_count"]

    print(f"\nSynthesis demand report — {distinct_shapes} distinct question shape(s), "
          f"{total_snapshots} captured snapshot(s)\n")
    print("NOTE: 'snapshots' = distinct (question, data-snapshot) captures, NOT ask-frequency.")
    print("Same question repeated within 24h / over unchanged data dedups to 1 (narrative_cache")
    print("+ input_hash ON CONFLICT); the count grows ~1 per day-with-changed-data. So a hot")
    print("question can still read 1. Use DISTINCT SHAPES (not snapshots) as the primary signal;")
    print("for true ask-frequency, add a per-ask event log.\n")
    print("Per-family (distinct shapes / captured snapshots):")
    for fam in ("attribution", "write", "query"):
        print(f"  {fam:<12} shapes={shape_breakdown.get(fam, 0):<4} snapshots={snap.get(fam, 0)}")

    # Actionable signal: distinct attribution/write shapes are the candidate
    # decompositions to hand-write. query-family shapes are already served by
    # deterministic resolvers, so they are not decomposition candidates.
    to_write = [c for c in candidates
                if c["family"] in ("attribution", "write")
                and c["occurrence_count"] >= min_count]
    to_write.sort(key=lambda c: c["occurrence_count"], reverse=True)
    print(f"\nCandidate decompositions to hand-write (attribution/write shapes"
          f"{'' if min_count <= 1 else f', snapshots>={min_count}'}, "
          f"{len(to_write)} distinct, most-captured first):")
    if not to_write:
        print("  (none yet — no attribution/write synthesis questions captured)")
    for c in to_write[:20]:
        q = c["query"][:60].replace("\n", " ")
        print(f"  [{c['family']:<11}] snapshots={c['occurrence_count']:<3} {q}")
    print()


async def _run(dsn: Optional[str], *, min_count: int) -> None:
    if dsn:
        import asyncpg
        pool = await asyncpg.create_pool(dsn, min_size=1, max_size=2)
    else:
        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()

    if pool is None:
        raise RuntimeError("Postgres pool unavailable (check DB env / --dsn)")

    async with pool.acquire() as conn:
        rows = await conn.fetch(_REPORT_SQL)

    records = [dict(r) for r in rows]
    if not records:
        print("No source='synthesis' rows yet — the synthesis path has not been "
              "asked (or captured) anything. Ask a comprehensive question first.")
        return

    _render(_build_candidates(records), min_count=min_count)


def main() -> None:
    ap = argparse.ArgumentParser(
        description="Comprehensive-synthesis demand-signal report (read-only)")
    ap.add_argument("--dsn", default=os.getenv("SYNTHESIS_REPORT_DSN"),
                    help="optional asyncpg DSN; defaults to app DB env")
    ap.add_argument("--min-count", type=int, default=1,
                    help="only list shapes with at least this many captured snapshots "
                         "(default 1 = all; note snapshots != ask-frequency, see report NOTE)")
    args = ap.parse_args()
    asyncio.run(_run(args.dsn, min_count=args.min_count))


if __name__ == "__main__":
    main()
