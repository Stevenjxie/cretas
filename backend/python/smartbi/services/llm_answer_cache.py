"""LLM-answer cache for repeat queries — v4 B2-B (Apr 26 2026).

Background: S4 v4 audit showed 87% follow-ups go through LLM (~12s). Many are
repeat queries within 24h. Caching the LLM answer keyed by (factory_id +
normalized_query + upload_id) lets repeat callers get the answer in ~200ms.

Distinct from:
- ChatSession (parent answer summary for follow-up CONTEXT injection — different scope)
- materialized template cache (template-level, pre-computed at upload time)
- InsightCache (in-process LRU, lost on restart)

This is a SHA256-keyed PG-persistent cache surviving restarts. TTL 24h
matching narrative_cache pattern. Skipped when v2 conv memory has parent
context (different turn = different answer expected).
"""
from __future__ import annotations

import hashlib
import json as _json
import logging
from typing import Any, Optional

import asyncpg

logger = logging.getLogger(__name__)


def compute_cache_key(factory_id: str, normalized_q: str, upload_id: Optional[int]) -> str:
    """Deterministic SHA256 over normalized inputs.

    Returns 64-char hex (full SHA256). Inputs joined with '|' which is rare
    in queries; even if user query contains '|', the hash is collision-free
    because all 3 components are required matches.
    """
    parts = [
        (factory_id or "").strip(),
        (normalized_q or "").strip().lower(),  # lowered to match more variations
        str(upload_id or 0),
    ]
    raw = "|".join(parts).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


class LlmAnswerCache:
    """Async service for smart_bi_llm_answer_cache.

    Failures are logged and swallowed — cache is best-effort, never break
    the user's request flow.
    """

    def __init__(self, pool: asyncpg.Pool):
        self._pool = pool

    async def get(
        self, factory_id: str, normalized_q: str, upload_id: Optional[int]
    ) -> Optional[dict[str, Any]]:
        """Lookup. Returns dict {answer_text, charts, warning} or None.

        Returns None when:
        - Inputs incomplete (no factory_id or no query)
        - Entry not found / expired
        - DB error

        Apr 27 2026 (F11 fix): SET app.factory_id GUC in transaction so the
        RLS tenant_isolation policy on smart_bi_llm_answer_cache evaluates
        truthy. Without this, smartbi_user's RLS check fails silently — get
        returns None on existing rows, set is rejected with "new row violates
        row-level security policy". Cache had 0 entries in prod+test for
        months because writes silently failed.
        """
        from smartbi.services.smartbi_metrics import LLM_CACHE_LOOKUP, LLM_CACHE_HIT_AGE
        if not factory_id or not normalized_q:
            LLM_CACHE_LOOKUP.labels(outcome='miss').inc()
            return None
        key = compute_cache_key(factory_id, normalized_q, upload_id)
        try:
            async with self._pool.acquire() as conn:
                async with conn.transaction():
                    await conn.execute(
                        "SELECT set_config('app.factory_id', $1, false)",
                        factory_id,
                    )
                    row = await conn.fetchrow(
                        """
                        SELECT answer_text, charts_json, warning, hit_count
                        FROM smart_bi_llm_answer_cache
                        WHERE cache_key = $1
                          AND expires_at > NOW()
                        """,
                        key,
                    )
                    if not row:
                        LLM_CACHE_LOOKUP.labels(outcome='miss').inc()
                        return None
                    # Bump hit_count + last_hit_at — fire-and-forget UPDATE
                    await conn.execute(
                        """
                        UPDATE smart_bi_llm_answer_cache
                        SET hit_count = hit_count + 1,
                            last_hit_at = NOW()
                        WHERE cache_key = $1
                        """,
                        key,
                    )
                    LLM_CACHE_LOOKUP.labels(outcome='hit').inc()
                    LLM_CACHE_HIT_AGE.observe(row['hit_count'] + 1)
                    return {
                        "answer_text": row["answer_text"],
                        "charts": (
                            _json.loads(row["charts_json"]) if row["charts_json"] else []
                        ) if isinstance(row["charts_json"], str) else (row["charts_json"] or []),
                        "warning": row["warning"],
                        "hit_count": row["hit_count"] + 1,  # post-increment value
                    }
        except Exception as e:
            LLM_CACHE_LOOKUP.labels(outcome='error').inc()
            logger.warning(f"[llm-answer-cache] get failed (non-fatal): {e}")
            return None

    async def set(
        self,
        factory_id: str,
        normalized_q: str,
        upload_id: Optional[int],
        answer_text: str,
        charts: Optional[list] = None,
        warning: Optional[str] = None,
    ) -> None:
        """Write a new cache entry. ON CONFLICT updates the answer + resets TTL."""
        from smartbi.services.smartbi_metrics import LLM_CACHE_WRITE
        if not factory_id or not normalized_q or not answer_text:
            LLM_CACHE_WRITE.labels(outcome='skipped_short').inc()
            return
        # Skip caching very short answers (likely error/silent-timeout msgs).
        if len(answer_text.strip()) < 30:
            LLM_CACHE_WRITE.labels(outcome='skipped_short').inc()
            return
        key = compute_cache_key(factory_id, normalized_q, upload_id)
        try:
            async with self._pool.acquire() as conn:
                # Apr 27 2026 (F11 fix): set GUC for RLS tenant_isolation
                # policy (factory_id = current_setting('app.factory_id')).
                # Without GUC, INSERT fails with "new row violates row-level
                # security policy" — silently swallowed by the except below.
                async with conn.transaction():
                    await conn.execute(
                        "SELECT set_config('app.factory_id', $1, false)",
                        factory_id,
                    )
                    await conn.execute(
                        """
                        INSERT INTO smart_bi_llm_answer_cache (
                            cache_key, factory_id, upload_id, normalized_q,
                            answer_text, charts_json, warning, expires_at
                        )
                        VALUES (
                            $1, $2, $3, $4, $5, $6::jsonb, $7,
                            NOW() + INTERVAL '24 hours'
                        )
                        ON CONFLICT (cache_key) DO UPDATE SET
                            answer_text = EXCLUDED.answer_text,
                            charts_json = EXCLUDED.charts_json,
                            warning     = EXCLUDED.warning,
                            expires_at  = NOW() + INTERVAL '24 hours'
                        """,
                        key, factory_id, upload_id, normalized_q,
                        answer_text,
                        _json.dumps(charts or [], ensure_ascii=False),
                        warning,
                    )
                LLM_CACHE_WRITE.labels(outcome='ok').inc()
        except Exception as e:
            LLM_CACHE_WRITE.labels(outcome='error').inc()
            logger.warning(f"[llm-answer-cache] set failed (non-fatal): {e}")

    async def invalidate_on_upload(self, factory_id: str) -> int:
        """Wipe all cache entries for a factory when they upload new data.

        Simple > precise: rather than tracking which entries depend on which
        upload, just nuke the factory. New cache builds in 24h naturally.

        F11.1 fix: tenant_isolation RLS policy filters by app.factory_id GUC.
        Without SET, DELETE finds 0 rows when caller is outside per-request
        context (cron / upload event handler).
        """
        try:
            async with self._pool.acquire() as conn:
                async with conn.transaction():
                    await conn.execute(
                        "SELECT set_config('app.factory_id', $1, false)",
                        factory_id,
                    )
                    result = await conn.execute(
                        "DELETE FROM smart_bi_llm_answer_cache WHERE factory_id = $1",
                        factory_id,
                    )
                if isinstance(result, str) and result.startswith("DELETE "):
                    return int(result.split()[1])
                return 0
        except Exception as e:
            logger.warning(f"[llm-answer-cache] invalidate failed: {e}")
            return 0

    async def prune_expired(self) -> int:
        """Delete expired rows. Caller schedules (cron)."""
        try:
            async with self._pool.acquire() as conn:
                result = await conn.execute(
                    "DELETE FROM smart_bi_llm_answer_cache WHERE expires_at < NOW()"
                )
                if isinstance(result, str) and result.startswith("DELETE "):
                    return int(result.split()[1])
                return 0
        except Exception as e:
            logger.warning(f"[llm-answer-cache] prune failed: {e}")
            return 0
