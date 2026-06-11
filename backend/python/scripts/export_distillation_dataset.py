"""Export the distillation training corpus as JSONL, bucketed by business_type.

Pulls (input → strong-teacher-output) pairs from ``smart_bi_distillation_samples``
(written by Fix2 materialization, see llm_materializer._persist_distillation_sample)
and writes one JSONL file per business_type in chat-messages SFT format, ready for
LoRA / distillation when volume thresholds are met (see memory
project_2026_05_31_vertical_model_strategy_verdict: ≥1000-2000 high-quality samples
per business_type, or monthly fallback >5M tokens).

Each JSONL line:
    {"messages": [
        {"role": "system",    "content": <system_prompt>},
        {"role": "user",      "content": <input_text>},
        {"role": "assistant", "content": <teacher_output>}
     ],
     "meta": {"source": ..., "business_type": ..., "factory_id": ...,
              "task_type": ..., "template_codes": ..., "teacher_model": ...,
              "created_at": ...}}

Run on the server (DB env from the app's .env):
    cd /www/wwwroot/cretas/code/backend/python && source venv38/bin/activate
    PYTHONPATH=.:smartbi python scripts/export_distillation_dataset.py --out /tmp/distill_export

Rows are already deduped at write time (UNIQUE input_hash, ON CONFLICT refresh),
so no dedup needed here. NO RLS on the table → reads all factories cross-business.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
from typing import Any, Dict, List, Optional

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("export_distill")


async def _fetch_rows(
    source: Optional[str],
    business_type: Optional[str],
    min_quality: Optional[int],
) -> List[Dict[str, Any]]:
    from smartbi.config import get_pg_pool

    pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("postgres pool unavailable (check DB env)")

    where = [
        "teacher_output IS NOT NULL",
        "input_text IS NOT NULL",
        # P2 eval-freeze exclusion: rows marked eval_frozen=true are part of
        # the held-out eval slice (G3 spot-check asset).  They must NEVER leak
        # into the training set so the eval benchmark stays uncontaminated.
        # We exclude them via: NOT (metadata ? 'eval_frozen') covers the case
        # where the key is absent; the IS DISTINCT FROM 'true' arm covers rows
        # where the key exists but the value is not true (defensive).
        "(metadata IS NULL OR NOT (metadata ? 'eval_frozen')"
        " OR (metadata->>'eval_frozen') IS DISTINCT FROM 'true')",
    ]
    args: List[Any] = []
    if source:
        args.append(source)
        where.append(f"source = ${len(args)}")
    if business_type:
        args.append(business_type)
        where.append(f"business_type = ${len(args)}")
    if min_quality is not None:
        args.append(min_quality)
        # P0-1 fix: NULL rows are EXCLUDED — only rows with quality >= N are
        # exported.  The old "(quality IS NULL OR quality >= N)" let every
        # unscored row leak into the training set, making the quality gate a
        # no-op.  NULL means "not yet scored / polluted bare-query" and must
        # never reach training.
        where.append(f"quality >= ${len(args)}")

    sql = f"""
        SELECT id, source, business_type, factory_id, task_type, template_codes,
               system_prompt, input_text, teacher_model, teacher_output,
               created_at, metadata
        FROM smart_bi_distillation_samples
        WHERE {' AND '.join(where)}
        ORDER BY business_type, created_at
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, *args)
    return [dict(r) for r in rows]


def _to_sft_line(row: Dict[str, Any]) -> Dict[str, Any]:
    messages = []
    if row.get("system_prompt"):
        messages.append({"role": "system", "content": row["system_prompt"]})
    messages.append({"role": "user", "content": row["input_text"]})
    messages.append({"role": "assistant", "content": row["teacher_output"]})
    return {
        "messages": messages,
        "meta": {
            "id": row.get("id"),
            "source": row.get("source"),
            "business_type": row.get("business_type"),
            "factory_id": row.get("factory_id"),
            "task_type": row.get("task_type"),
            "template_codes": row.get("template_codes"),
            "teacher_model": row.get("teacher_model"),
            "created_at": row["created_at"].isoformat() if row.get("created_at") else None,
        },
    }


async def main() -> None:
    ap = argparse.ArgumentParser(description="Export distillation corpus as JSONL by business_type")
    ap.add_argument("--out", default="./distill_export", help="output directory")
    ap.add_argument("--source", default=None, help="filter by source (materialization|fallback|manual)")
    ap.add_argument("--business-type", default=None, help="filter by business_type")
    ap.add_argument("--min-quality", type=int, default=None, help="keep rows with quality >= N (NULL kept)")
    args = ap.parse_args()

    rows = await _fetch_rows(args.source, args.business_type, args.min_quality)
    if not rows:
        logger.info("No samples matched — nothing to export.")
        return

    os.makedirs(args.out, exist_ok=True)
    # Bucket by business_type → one JSONL file each.
    buckets: Dict[str, List[Dict[str, Any]]] = {}
    for r in rows:
        buckets.setdefault(r.get("business_type") or "unknown", []).append(r)

    total = 0
    for bt, brows in sorted(buckets.items()):
        path = os.path.join(args.out, f"dataset_{bt}.jsonl")
        with open(path, "w", encoding="utf-8") as f:
            for r in brows:
                f.write(json.dumps(_to_sft_line(r), ensure_ascii=False) + "\n")
        logger.info(f"  {bt:14s}: {len(brows):6d} samples -> {path}")
        total += len(brows)

    logger.info(f"Exported {total} samples across {len(buckets)} business_type bucket(s) to {args.out}")
    # Readiness hint vs the distillation threshold.
    for bt, brows in sorted(buckets.items()):
        ready = "READY for LoRA" if len(brows) >= 1000 else f"need {1000 - len(brows)} more for 1k threshold"
        logger.info(f"  threshold check [{bt}]: {len(brows)}/1000 — {ready}")


if __name__ == "__main__":
    asyncio.run(main())
