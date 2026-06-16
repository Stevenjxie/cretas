"""LLM-fallback query logger.

Fire-and-forget write of each user query that went through the LLM fallback
path (i.e. no template matched). Feeds Phase 2 pattern clustering and
Phase 3 RAG retrieval.

Fail-safe: if local embedding fails or DB is down, the logger logs a
warning and swallows the exception. The user's chat answer must not be
blocked by logging.

Public API:
    await log_fallback(pool, payload) -> Optional[int]  # returns row id
    await update_feedback(pool, log_id, value, comment) -> bool
"""
from __future__ import annotations

import json as _json
import logging
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

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
) -> Optional[int]:
    """Log a template cache-hit answer so the user can 👍/👎 it.

    Lighter-weight than log_fallback: no embedding (templates already have
    their own sample-query embeddings), no agg_meta, no history. Just enough
    to tie feedback back to a (query, template) pair.
    """
    answer_trunc = (answer or "")[:4000]
    sql = """
        INSERT INTO smart_bi_llm_fallback_log (
            query, factory_id, upload_id, answer,
            source, template_code, total_wall_ms, llm_wall_ms
        ) VALUES (
            $1, $2, $3, $4, 'template', $5, $6, 0
        )
        RETURNING id
    """
    try:
        async with pool.acquire() as conn:
            row_id = await conn.fetchval(
                sql, query, factory_id, upload_id, answer_trunc,
                template_code, total_wall_ms,
            )
        logger.debug(f"[template-log] wrote id={row_id} template={template_code}")
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
