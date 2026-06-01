"""Shared distillation-corpus capture helper.

Every LLM "teacher pair" (a strong-model answer to a structured/user input) is
training material for the future vertical-model distillation. Originally only
the materialization path (``llm_materializer._persist_distillation_sample``)
wrote into ``smart_bi_distillation_samples``; the high-volume LIVE flows (AI
Q&A answers in ``chat.py`` and 经营驾驶舱 report insights in
``agent/orchestrator.py``) leaked their teacher pairs.

This module extracts the materializer's persistence logic into one reusable,
fire-and-forget helper so every capture site shares IDENTICAL hash / INSERT /
dedup behavior.

HARD RULE -- Fire-and-forget contract (per the original
``_persist_distillation_sample`` docstring): training-data capture is
best-effort. It MUST NEVER raise — any DB / pool / serialization error is
swallowed with a warning so it can never affect the user-facing answer or the
calling flow.

Dedup / hash behavior (faithful extraction of the original — see report):
``input_hash = sha256(input_text)``. The table has a UNIQUE index on
``input_hash`` (``uq_distill_input_hash``); ON CONFLICT refreshes the existing
row's teacher_output / model / template_codes / metadata instead of stacking
near-duplicate samples. This matches the materializer exactly (its
``_get_cached_teacher_output`` content-hash cache looks the row up by
``sha256(prompt)`` where ``prompt == input_text`` — so the hash MUST stay
``sha256(input_text)`` or that cache would silently miss).
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

# Same env kill-switch the materializer used — set to "0" to disable all
# distillation capture without a redeploy.
_CAPTURE_ENV = "SMARTBI_DISTILL_CAPTURE"

_INSERT_SQL = """
    INSERT INTO smart_bi_distillation_samples (
        source, business_type, factory_id, task_type, template_codes,
        system_prompt, input_text, teacher_model, teacher_output,
        input_hash, quality, metadata
    ) VALUES (
        $1, $2, $3, $4, $5,
        $6, $7, $8, $9,
        $10, $11, $12::jsonb
    )
    ON CONFLICT (input_hash) DO UPDATE SET
        teacher_output = EXCLUDED.teacher_output,
        teacher_model  = EXCLUDED.teacher_model,
        template_codes = EXCLUDED.template_codes,
        metadata       = EXCLUDED.metadata,
        created_at     = NOW()
"""


def compute_input_hash(input_text: str) -> str:
    """sha256(input_text) — faithful to the materializer's hash so the
    content-hash teacher-output cache stays consistent. Stable: same input →
    same hash."""
    return hashlib.sha256((input_text or "").encode("utf-8")).hexdigest()


async def persist_distillation_sample(
    pool,
    source: str,
    task_type: str,
    input_text: str,
    teacher_output: str,
    *,
    business_type: str = "unknown",
    factory_id: Optional[str] = None,
    system_prompt: Optional[str] = None,
    teacher_model: Optional[str] = None,
    template_codes: Optional[List[str]] = None,
    quality: Optional[int] = None,
    metadata: Optional[Dict[str, Any]] = None,
) -> None:
    """Write one distillation training sample. Fire-and-forget, NEVER raises.

    Persists the (input_text → teacher_output) pair into
    ``smart_bi_distillation_samples``. Idempotent on ``input_hash`` —
    re-capturing the same input refreshes the row's teacher_output instead of
    stacking near-duplicate samples.

    Args:
        pool: an asyncpg pool (or anything with ``.acquire()``). If ``None``,
            returns quietly (caller could not obtain a pool).
        source: capture origin, e.g. ``"materialization"`` / ``"chat_qa"`` /
            ``"agent_insight"`` (table column ``source``, VARCHAR(32)).
        task_type: ``"insights"`` / ``"qa"`` / ``"analysis"`` … (VARCHAR(32)).
        input_text: the structured / user prompt fed to the teacher (NOT NULL).
        teacher_output: the teacher model's raw response (NOT NULL).
        business_type: 业态分桶 (restaurant/factory/unknown). Defaults
            ``"unknown"``; ``None`` is also normalized to ``"unknown"`` since
            the column is NOT NULL.
        factory_id: optional tenant id tag.
        system_prompt: the teacher's system role, if readily available.
        teacher_model: e.g. ``"qwen3-max"``, if readily known.
        template_codes: applicable template codes (materialization); stored as a
            comma-joined string. ``None`` → NULL.
        quality: optional curation score (nullable).
        metadata: optional dict, JSON-encoded into the jsonb column. ``None`` →
            NULL.
    """
    if os.getenv(_CAPTURE_ENV, "1") == "0":
        return
    if pool is None:
        return
    # Guard the NOT NULL columns: never attempt an INSERT we know will fail.
    if not input_text or not teacher_output:
        return
    try:
        input_hash = compute_input_hash(input_text)
        codes_str = ",".join(template_codes) if template_codes else None
        metadata_json = (
            json.dumps(metadata, ensure_ascii=False) if metadata is not None else None
        )
        async with pool.acquire() as conn:
            await conn.execute(
                _INSERT_SQL,
                source,
                (business_type or "unknown"),
                factory_id,
                task_type,
                codes_str,
                system_prompt,
                input_text,
                teacher_model,
                teacher_output,
                input_hash,
                quality,
                metadata_json,
            )
        logger.debug(
            "[distill] captured sample (source=%s, task_type=%s, "
            "business_type=%s, factory=%s)",
            source, task_type, business_type, factory_id,
        )
    except Exception as e:
        # Training-data capture is best-effort. Never propagate.
        logger.warning("[distill] sample capture failed (non-blocking): %s", e)
