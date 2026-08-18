"""LLM-fallback query logger.

Fire-and-forget write of each user query that went through the LLM fallback
path (i.e. no template matched). Feeds Phase 2 pattern clustering and
Phase 3 RAG retrieval.

Fail-safe: if local embedding fails or DB is down, the logger logs a
warning and swallows the exception. The user's chat answer must not be
blocked by logging.

Public API:
    await log_fallback(pool, payload) -> Optional[int]  # returns row id
    await log_template_hit(...) -> Optional[int]         # returns row id
    await update_feedback(pool, log_id, value, comment) -> bool
    await update_history(pool, log_id, history) -> None
    await run_capture_with_history(capture_coro, pool, history) -> Optional[int]
"""
from __future__ import annotations

import json as _json
import logging
from dataclasses import dataclass
from typing import Any, Awaitable, Dict, List, Optional, Sequence

logger = logging.getLogger(__name__)


@dataclass
class LlmFallbackLogPayload:
    query: str
    factory_id: Optional[str]
    upload_id: Optional[int]
    answer: str
    agg_meta: Dict[str, Any]
    history: Optional[List[Dict[str, str]]]
    total_wall_ms: int
    llm_wall_ms: int


# Lazy-imported to keep food_kb/services/embedding optional at startup.
# If food_kb isn't available, logging still works (embedding = None).
async def get_embedding(text: str) -> Optional[List[float]]:
    """Call local gRPC embedding-service via food_kb's cached client.

    Returns 768-dim float list, or None on failure.
    """
    try:
        from food_kb.services.embedding import get_embedding as _real_get_embedding
        return await _real_get_embedding(text)
    except Exception as e:
        logger.warning(f"[fallback-log] embedding call failed: {e}")
        return None


async def log_fallback(pool, payload: LlmFallbackLogPayload) -> Optional[int]:
    """Write one fallback log row. Returns generated row id, or None on failure.

    The caller should `asyncio.create_task(log_fallback(...))` and not await it
    on the SSE hot path — logging latency shouldn't block the user-facing
    response.
    """
    # 1. Embed the query (best-effort)
    emb = await get_embedding(payload.query)

    # 2. Clamp answer length to bound row size
    answer = (payload.answer or "")[:4000]

    # 3. JSON-encode structured fields for JSONB columns
    agg_meta_json = _json.dumps(payload.agg_meta or {}, ensure_ascii=False, default=str)
    history_json = (
        _json.dumps(payload.history, ensure_ascii=False, default=str)
        if payload.history else None
    )

    # 4. pgvector wants a literal string like "[0.1, 0.2, ...]"
    embedding_literal = f"[{','.join(str(x) for x in emb)}]" if emb else None

    sql = """
        INSERT INTO smart_bi_llm_fallback_log (
            query, factory_id, upload_id, answer, agg_meta,
            query_embedding, history, total_wall_ms, llm_wall_ms
        ) VALUES (
            $1, $2, $3, $4, $5::jsonb,
            $6::vector, $7::jsonb, $8, $9
        )
        RETURNING id
    """
    try:
        async with pool.acquire() as conn:
            row_id = await conn.fetchval(
                sql,
                payload.query, payload.factory_id, payload.upload_id, answer,
                agg_meta_json, embedding_literal, history_json,
                payload.total_wall_ms, payload.llm_wall_ms,
            )
        logger.debug(f"[fallback-log] wrote id={row_id} (embedding={'yes' if emb else 'no'})")
        return row_id
    except Exception as e:
        logger.warning(f"[fallback-log] insert failed: {e}")
        return None


async def log_template_hit(
    pool,
    query: str,
    factory_id: Optional[str],
    upload_id: Optional[int],
    template_code: str,
    answer: str,
    total_wall_ms: int,
    agg_meta: Optional[Dict[str, Any]] = None,
) -> Optional[int]:
    """Log a template cache-hit answer so the user can 👍/👎 it.

    Lighter-weight than log_fallback: no agg_meta shape assumptions, no
    history (a caller with conversation history in scope attaches it
    afterward via `run_capture_with_history` -- see that function's
    docstring for why this stays a separate step instead of a new
    parameter here). Just enough to tie feedback back to a (query,
    template) pair.

    🔴 2026-08-18 (飞轮 A 第 10 项): this is the ONLY write path production
    restaurant traffic goes through (`restaurant_intent.log_intent_capture`
    / `log_intent_miss` both call this, never `log_fallback`) -- confirmed
    by prod probe: `smart_bi_llm_fallback_log` had query_embedding=0/11958
    while `log_fallback`'s sibling table `smart_bi_template_embeddings` is
    a DIFFERENT table entirely (that one embeds the reviewed SAMPLE_QUERIES
    corpus, not live traffic). So embedding here -- not adding it to
    `log_fallback` -- is what actually closes the gap for
    `/admin/fallback-log/by-similarity` and any future promotion-candidate
    clustering. Best-effort, same fail-open contract as `log_fallback`'s
    embedding: a failed/unavailable embedding service degrades this to
    exactly today's behaviour (NULL column), never blocks the write.
    """
    answer_trunc = (answer or "")[:4000]
    agg_meta_json = _json.dumps(agg_meta or {}, ensure_ascii=False, default=str)
    emb = await get_embedding(query)
    embedding_literal = f"[{','.join(str(x) for x in emb)}]" if emb else None
    sql = """
        INSERT INTO smart_bi_llm_fallback_log (
            query, factory_id, upload_id, answer,
            agg_meta, source, template_code, total_wall_ms, llm_wall_ms,
            query_embedding
        ) VALUES (
            $1, $2, $3, $4, $5::jsonb, 'template', $6, $7, 0, $8::vector
        )
        RETURNING id
    """
    try:
        async with pool.acquire() as conn:
            row_id = await conn.fetchval(
                sql, query, factory_id, upload_id, answer_trunc,
                agg_meta_json, template_code, total_wall_ms, embedding_literal,
            )
        logger.debug(
            f"[template-log] wrote id={row_id} template={template_code} "
            f"embedding={'yes' if emb else 'no'}"
        )
        return row_id
    except Exception as e:
        logger.warning(f"[template-log] insert failed: {e}")
        return None


async def update_feedback(
    pool, log_id: int, value: int, comment: Optional[str],
    factory_id: Optional[str] = None,
) -> bool:
    """Update user feedback on a logged fallback. value ∈ {-1, 0, 1}.

    If factory_id is provided, the UPDATE is scoped to rows owned by that
    factory — prevents cross-tenant writes when the endpoint is called by
    a non-admin user. Returns True only when exactly one row is affected.

    Returns True when exactly one row updated, False when no row matched
    (wrong id or wrong factory). Raises on DB errors so the caller can
    return 500 instead of 404 for those cases.
    """
    if value not in (-1, 0, 1):
        raise ValueError(f"feedback value must be -1/0/1, got {value}")
    if factory_id is not None:
        sql = """
            UPDATE smart_bi_llm_fallback_log
            SET user_feedback = $1, feedback_comment = $2
            WHERE id = $3 AND factory_id = $4
        """
        args = (value, comment, log_id, factory_id)
    else:
        sql = """
            UPDATE smart_bi_llm_fallback_log
            SET user_feedback = $1, feedback_comment = $2
            WHERE id = $3
        """
        args = (value, comment, log_id)
    async with pool.acquire() as conn:
        result = await conn.execute(sql, *args)
    # asyncpg returns "UPDATE 1" when exactly one row was updated,
    # "UPDATE 0" when WHERE matched no rows.
    if not result.upper().startswith("UPDATE"):
        return False
    try:
        affected = int(result.split()[-1])
    except (ValueError, IndexError):
        affected = 0
    return affected >= 1


# ─── History attachment (飞轮 A 第 10 项, 2026-08-18) ──────────────────────
#
# Why this is a separate id-scoped UPDATE and not a `history` parameter on
# `log_template_hit`: `log_template_hit` is called from
# `restaurant_intent.log_intent_capture` / `log_intent_miss` -- neither of
# those functions receives conversation history today, and this task is
# explicitly scoped to NOT touch restaurant_intent.py (another line owns
# it). The caller that DOES have history in scope is
# `restaurant_intent_service.tiered_answer`, which already calls
# `log_intent_capture` as a fire-and-forget `asyncio.create_task`. Wrapping
# that same coroutine with `run_capture_with_history` lets the history
# column get filled without widening `log_intent_capture`'s signature.
#
# 🔴 Sequenced, not a second independent fire-and-forget task: awaiting the
# capture first and then UPDATE-by-id (the exact row `log_intent_capture`
# just returned) avoids racing the INSERT. A second `create_task` that
# instead matched on `(factory_id, trim(query))` -- the pattern
# `restaurant_feedback.py` uses -- would be safe there only because a human
# clicking 👍/👎 fires long after the INSERT already committed; here both
# writes would start in the same beat, so an "most recent matching row"
# UPDATE could legitimately grab a DIFFERENT row (e.g. the same question
# asked twice in quick succession, or the INSERT simply not committed yet).

def _cap_history(history: Optional[Sequence[Dict[str, Any]]]) -> Optional[List[Dict[str, Any]]]:
    """Same window as the deterministic-layer prompt build
    (`restaurant_intent_service`'s `conversation_history=list(history or
    [])[-20:]`) -- storing exactly what the model actually saw, not a
    separately-invented cap, is the point: this column exists so a human
    reviewing a promotion candidate can see the context the answer was
    actually produced under."""
    if not history:
        return None
    capped = [h for h in list(history)[-20:] if isinstance(h, dict)]
    return capped or None


async def update_history(pool, log_id: int, history: Optional[Sequence[Dict[str, Any]]]) -> bool:
    """Best-effort UPDATE of the `history` column for an already-written row.

    No-op (returns False, does not touch the DB) when `history` is empty --
    mirrors `log_fallback`'s own "history JSONB NULL on first-turn" contract
    instead of writing an empty array that would read as "server saw a
    history but it was blank"."""
    capped = _cap_history(history)
    if not capped:
        return False
    history_json = _json.dumps(capped, ensure_ascii=False, default=str)
    async with pool.acquire() as conn:
        result = await conn.execute(
            "UPDATE smart_bi_llm_fallback_log SET history = $1::jsonb WHERE id = $2",
            history_json, log_id,
        )
    return result.upper().startswith("UPDATE") and result.split()[-1] != "0"


async def run_capture_with_history(
    capture_coro: Awaitable[Optional[int]],
    pool,
    history: Optional[Sequence[Dict[str, Any]]],
) -> Optional[int]:
    """Await a fire-and-forget capture coroutine (e.g.
    `restaurant_intent.log_intent_capture(...)`), then best-effort attach
    `history` to the row it just wrote via `update_history`.

    Callers keep scheduling this the same way they scheduled the bare
    capture coroutine before -- `asyncio.create_task(run_capture_with_history(
    log_intent_capture(...), pool, history))` -- so this changes nothing
    about when/whether the capture itself fires, only adds one more
    best-effort step chained after it in the same task. A failure attaching
    history is logged and swallowed, same fail-open contract as the rest of
    this module: logging must never surface as a user-visible error.
    """
    log_id = await capture_coro
    if log_id:
        try:
            await update_history(pool, log_id, history)
        except Exception as exc:
            logger.warning(f"[fallback-log] attach history failed for id={log_id}: {exc}")
    return log_id
