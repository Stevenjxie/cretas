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

数据主权门控 (training-consent foundation, 2026-06-11):
  - SHARED 导出 (默认, 按 business_type 分桶):
      * 合成行 (SEED_R/SEED_F / metadata.source_kind='synthetic_seed') → **无条件**收入。
      * 真实行 → 仅当其 factory_id 的 training_consent.consent_scope='shared_anon' 时收入,
        且**必经 anonymize_for_shared 脱敏** (数值分桶 + 实体伪名化, 保留 %/形状)。
      * consent='none'/'private' 或无授权行的真实租户 → **排除** (default-deny)。
  - PRIVATE 导出 (--factory F001): 仅当该工厂 consent_scope='private' 时导出其**原始**行
    (不脱敏), 输出 dataset_private_{factory}.jsonl; 否则拒绝并给出清晰提示。
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
from typing import Any, Dict, List, Optional

# Path bootstrap — importing smartbi.services.corpus_anonymize triggers smartbi/services/__init__
# which uses bare `from services.X import ...`; mirror main.py so it resolves to smartbi/services/X.
_PYTHON_ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("export_distill")


async def _fetch_rows(
    source: Optional[str],
    business_type: Optional[str],
    min_quality: Optional[int],
    pool=None,
    factory_id: Optional[str] = None,
) -> List[Dict[str, Any]]:
    if pool is None:
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
    if factory_id:
        args.append(factory_id)
        where.append(f"factory_id = ${len(args)}")

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


# Sentinel factory IDs marking synthetic seed corpus (see seed_chart_insight_corpus.py).
_SENTINEL_FACTORIES = frozenset({"SEED_R", "SEED_F"})


def _is_synthetic(row: Dict[str, Any]) -> bool:
    """Synthetic-seed rows are UNCONDITIONALLY usable for shared training — they
    belong to no real tenant. Detection mirrors corpus_census / seeder tagging:
    metadata.source_kind == 'synthetic_seed' / metadata.seeded == true / factory
    id in SEED_R/SEED_F."""
    if (row.get("factory_id") or "") in _SENTINEL_FACTORIES:
        return True
    meta = row.get("metadata")
    if isinstance(meta, str):
        try:
            meta = json.loads(meta)
        except (ValueError, TypeError):
            meta = None
    if isinstance(meta, dict):
        sk = meta.get("source_kind")
        if isinstance(sk, str) and sk.lower() in ("synthetic_seed", "synthetic"):
            return True
        if meta.get("seeded") is True:
            return True
    return False


async def _load_consent(pool) -> Dict[str, str]:
    """Return {factory_id: consent_scope} from training_consent.

    Missing factory = no row = default-deny ('none'). If the table does not
    exist yet (migration not applied), returns {} → every real tenant is
    treated as 'none' (still default-deny, never a fail-open).
    """
    sql = "SELECT factory_id, consent_scope FROM training_consent"
    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch(sql)
        return {r["factory_id"]: r["consent_scope"] for r in rows}
    except Exception as e:  # table absent / permission → treat as no consent
        logger.warning(
            "training_consent unreadable (%s) — treating ALL real tenants as "
            "'none' (default-deny). Apply V20261001_01 migration if unexpected.",
            e,
        )
        return {}


def _consent_of(row: Dict[str, Any], consent: Dict[str, str]) -> str:
    """consent_scope for a row's tenant; absent factory → 'none' (default-deny)."""
    return consent.get(row.get("factory_id") or "", "none")


def _to_sft_line(
    row: Dict[str, Any],
    *,
    input_text: Optional[str] = None,
    teacher_output: Optional[str] = None,
    consent_class: str = "synthetic",
) -> Dict[str, Any]:
    """Build one SFT JSONL line.

    ``input_text`` / ``teacher_output`` override the row's raw values (used to
    inject anonymized text for shared_anon real rows). ``consent_class`` tags
    how the row qualified ('synthetic' | 'shared_anon' | 'private') for audit.
    """
    in_txt = input_text if input_text is not None else row["input_text"]
    out_txt = teacher_output if teacher_output is not None else row["teacher_output"]
    messages = []
    if row.get("system_prompt"):
        messages.append({"role": "system", "content": row["system_prompt"]})
    messages.append({"role": "user", "content": in_txt})
    messages.append({"role": "assistant", "content": out_txt})
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
            "consent_class": consent_class,
            "anonymized": consent_class == "shared_anon",
        },
    }


def gate_shared_rows(
    rows: List[Dict[str, Any]],
    consent: Dict[str, str],
) -> List[Dict[str, Any]]:
    """Consent-gate rows for the SHARED (cross-tenant) export.

    Returns list of (row, input_text, teacher_output, consent_class) tuples as
    dicts ready for _to_sft_line. Synthetic → unconditional raw. Real
    shared_anon → anonymized. Real none/private → excluded (default-deny).

    Returned items: {"row", "input_text", "teacher_output", "consent_class"}.
    """
    from smartbi.services.corpus_anonymize import anonymize_for_shared

    out: List[Dict[str, Any]] = []
    for r in rows:
        if _is_synthetic(r):
            out.append({"row": r, "input_text": None, "teacher_output": None,
                        "consent_class": "synthetic"})
            continue
        scope = _consent_of(r, consent)
        if scope == "shared_anon":
            anon_in, anon_out = anonymize_for_shared(
                r.get("input_text") or "",
                r.get("teacher_output") or "",
                r.get("business_type"),
            )
            out.append({"row": r, "input_text": anon_in, "teacher_output": anon_out,
                        "consent_class": "shared_anon"})
        # scope in ('none', 'private') → real row NOT in shared export (default-deny)
    return out


async def _export_private(args, pool) -> None:
    """PRIVATE single-tenant export: raw rows for one factory, ONLY if it has
    consent_scope='private'. Else refuse with a clear message."""
    consent = await _load_consent(pool)
    scope = consent.get(args.factory, "none")
    if scope != "private":
        logger.error(
            "REFUSED: factory %s has consent_scope=%r — private raw export "
            "requires 'private'. Authorize first:\n"
            "  python -m scripts.set_training_consent --factory %s --scope private --by <name>",
            args.factory, scope, args.factory,
        )
        return

    rows = await _fetch_rows(args.source, args.business_type, args.min_quality,
                             pool=pool, factory_id=args.factory)
    if not rows:
        logger.info("No samples for factory %s — nothing to export.", args.factory)
        return

    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, f"dataset_private_{args.factory}.jsonl")
    with open(path, "w", encoding="utf-8") as f:
        for r in rows:
            # RAW — private model uses the tenant's own exact data, no anonymization.
            f.write(json.dumps(_to_sft_line(r, consent_class="private"),
                                ensure_ascii=False) + "\n")
    logger.info(
        "PRIVATE export: %d RAW samples (factory %s, consent=private) -> %s",
        len(rows), args.factory, path,
    )


async def main() -> None:
    ap = argparse.ArgumentParser(description="Export distillation corpus as JSONL by business_type")
    ap.add_argument("--out", default="./distill_export", help="output directory")
    ap.add_argument("--source", default=None, help="filter by source (materialization|fallback|manual)")
    ap.add_argument("--business-type", default=None, help="filter by business_type")
    ap.add_argument("--min-quality", type=int, default=None, help="keep rows with quality >= N (NULL kept)")
    ap.add_argument("--factory", default=None,
                    help="PRIVATE mode: export ONLY this factory's RAW rows, "
                         "requires its consent_scope='private'")
    args = ap.parse_args()

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        raise RuntimeError("postgres pool unavailable (check DB env)")

    # PRIVATE single-tenant path.
    if args.factory:
        await _export_private(args, pool)
        return

    # SHARED (default) path: consent-gated, by business_type.
    consent = await _load_consent(pool)
    rows = await _fetch_rows(args.source, args.business_type, args.min_quality, pool=pool)
    if not rows:
        logger.info("No samples matched — nothing to export.")
        return

    gated = gate_shared_rows(rows, consent)
    excluded = len(rows) - len(gated)
    if not gated:
        logger.info(
            "No rows passed consent-gate (default-deny). %d real rows excluded "
            "(no 'shared_anon' consent); no synthetic rows present.", excluded,
        )
        return

    os.makedirs(args.out, exist_ok=True)
    # Bucket by business_type → one JSONL file each.
    buckets: Dict[str, List[Dict[str, Any]]] = {}
    for item in gated:
        bt = item["row"].get("business_type") or "unknown"
        buckets.setdefault(bt, []).append(item)

    total = 0
    n_synth = sum(1 for i in gated if i["consent_class"] == "synthetic")
    n_anon = sum(1 for i in gated if i["consent_class"] == "shared_anon")
    for bt, items in sorted(buckets.items()):
        path = os.path.join(args.out, f"dataset_{bt}.jsonl")
        with open(path, "w", encoding="utf-8") as f:
            for it in items:
                line = _to_sft_line(
                    it["row"],
                    input_text=it["input_text"],
                    teacher_output=it["teacher_output"],
                    consent_class=it["consent_class"],
                )
                f.write(json.dumps(line, ensure_ascii=False) + "\n")
        logger.info(f"  {bt:14s}: {len(items):6d} samples -> {path}")
        total += len(items)

    logger.info(
        "SHARED export: %d samples across %d bucket(s) (%d synthetic + %d "
        "shared_anon; %d real rows excluded by default-deny) to %s",
        total, len(buckets), n_synth, n_anon, excluded, args.out,
    )
    # Readiness hint vs the distillation threshold.
    for bt, items in sorted(buckets.items()):
        ready = "READY for LoRA" if len(items) >= 1000 else f"need {1000 - len(items)} more for 1k threshold"
        logger.info(f"  threshold check [{bt}]: {len(items)}/1000 — {ready}")


if __name__ == "__main__":
    asyncio.run(main())
