"""SHA256-keyed narrative cache for LLM insight responses, plus a semantic
fallback layer (2026-07-10) for natural paraphrases.

Table: narrative_cache (PK: factory_id, question_hash).

Hash composition:
    SHA256(normalized_q + "|" + start_date + "|" + end_date + "|" + factory_id)

- `normalized_q` = question lowered + stripped + collapsed whitespace
- `start_date`/`end_date` = ISO YYYY-MM-DD strings (or empty for non-range)
- `factory_id` in the hash is belt-and-suspenders — the PK already
  includes factory_id, but hashing it in makes cross-tenant collision
  impossible even if someone forgets to bind factory_id in the query

Invalidation:
- Time: expires_at < now() (24h default TTL)
- Upload event: `invalidate_on_upload(factory_id)` wipes ALL cache rows
  for that factory. Simple > precise; Agent-layer insights span
  multi-month ranges and tracing which hash references a given upload
  is not worth the complexity.

Cleanup:
- `prune_expired()` deletes rows where expires_at < now(). Caller is
  responsible for scheduling (not baked in here — defer to a
  background task/cron layer).

Semantic fallback (2026-07-10, `get_semantic`)
-----------------------------------------------
The exact-hash path above misses a natural paraphrase — "外送跟到店哪个强"
vs "外卖堂食哪个赚钱" hash to completely different SHA256 keys even though
they'd produce the same FactBook + answer. `get_semantic` adds a pgvector
cosine-similarity lookup on TOP of (never instead of) the exact-hash path:
synthesis_engine.py still tries the exact hash first (cheapest, zero
false-positive risk) and only falls to `get_semantic` on a miss.

⛔ Grounding guards (do NOT weaken — see migration
V20260710_03__narrative_cache_semantic.sql for the schema rationale):
  - similarity threshold 0.90 (stricter than template_rag's 0.85 routing
    hint — this serves a FULL answer, not just a routing signal)
  - `window_key` (the resolved date range) and `plan_key` (the sorted set of
    triggered plan_dimensions()) are HARD SQL WHERE filters, not just part
    of the similarity score. A high-similarity match across a different
    window or a different dimension set is NEVER served — e.g. "堂食赚钱吗"
    (finance+channel) must never serve for "堂食忙吗" (channel only), and
    "这两月" must never serve for "上周" even with near-identical wording.
  - The served answer was already grounded/reconciled by FactReconciler
    when it was first written (post-`reconcile()`, pre-`put()`), so numbers
    stay safe to replay within the same window+plan.
"""
from __future__ import annotations

import hashlib
import json
import logging
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

import asyncpg

logger = logging.getLogger(__name__)


DEFAULT_TTL_HOURS = 24
# Calibrated 2026-07-10 against gte-base-zh: genuine paraphrases score 0.78-0.86
# ("这两个月净利多少" vs "这两个月净赚多少利润率" = 0.863), while different-intent
# questions sit at 0.48-0.55 — a wide gap. 0.90 (initial guess) missed every
# paraphrase → cache never semantically hit. 0.80 catches paraphrases and stays
# far above the different-intent band. The window_key + plan_key HARD filters do
# the real safety work (cross-dimension / cross-window never served regardless of
# similarity); this threshold only screens within a same-plan+window candidate set.
SEMANTIC_MIN_SIMILARITY = 0.80


def _emb_literal(emb: List[float]) -> str:
    """pgvector literal format `[0.1,0.2,...]` — mirrors
    template_embedding_index._emb_literal (same convention project-wide)."""
    return f"[{','.join(str(x) for x in emb)}]"


def compute_question_hash(
    question: str,
    start_date: Optional[str],
    end_date: Optional[str],
    factory_id: str,
) -> str:
    """Deterministic SHA256 over normalized components.

    Inputs are joined with `|` (never appears in dates; rare in
    questions — and even if a user's question contains `|`, the
    separator-free alternative risks collisions between e.g. Q="ab" date="c"
    and Q="a" date="bc").
    """
    normalized_q = " ".join((question or "").strip().lower().split())
    parts = [
        normalized_q,
        (start_date or "").strip(),
        (end_date or "").strip(),
        (factory_id or "").strip(),
    ]
    raw = "|".join(parts).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


class NarrativeCacheService:
    """Thin wrapper over narrative_cache table.

    Stateless — pool connections carry RLS context via
    set_pg_connection_tenant setup callback.
    """

    def __init__(self, pool: asyncpg.Pool):
        self._pool = pool

    async def get(
        self, factory_id: str, question_hash: str
    ) -> Optional[Dict[str, Any]]:
        """Return cached answer if present AND not expired, else None.

        Apr 27 2026 (F12 fix): RLS GUC for tenant_isolation on narrative_cache.
        """
        async with self._pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)",
                    factory_id,
                )
                row = await conn.fetchrow(
                    """
                    SELECT answer, chart_config, tokens, created_at, expires_at
                      FROM narrative_cache
                     WHERE factory_id    = $1
                       AND question_hash = $2
                       AND expires_at    > NOW()
                    """,
                    factory_id, question_hash,
                )
        if row is None:
            return None
        # asyncpg returns JSONB as a string unless a codec is registered
        # on the pool. Parse inline here so callers always see a dict.
        chart = row["chart_config"]
        if isinstance(chart, str):
            chart = json.loads(chart)
        return {
            "answer": row["answer"],
            "chart_config": chart,
            "tokens": int(row["tokens"]),
            "created_at": row["created_at"],
            "expires_at": row["expires_at"],
        }

    async def put(
        self,
        factory_id: str,
        question_hash: str,
        answer: str,
        chart_config: Optional[Dict[str, Any]],
        tokens: int,
        ttl_hours: int = DEFAULT_TTL_HOURS,
        *,
        question_embedding: Optional[List[float]] = None,
        window_key: Optional[str] = None,
        plan_key: Optional[str] = None,
    ) -> None:
        """Upsert a cache entry. Existing row for the same key is replaced.

        `question_embedding`/`window_key`/`plan_key` (2026-07-10, semantic
        fallback) are optional — omitting them (the default) writes a
        NULL-embedding row, byte-identical to pre-semantic-cache behavior
        (exact-hash-only, invisible to get_semantic's partial-index scan).
        """
        expires_at = datetime.now(timezone.utc) + timedelta(hours=ttl_hours)
        # asyncpg maps None → NULL, dict → jsonb via explicit cast.
        chart_json = json.dumps(chart_config) if chart_config is not None else None
        emb_literal = _emb_literal(question_embedding) if question_embedding is not None else None
        async with self._pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)",
                    factory_id,
                )
                await conn.execute(
                    """
                    INSERT INTO narrative_cache
                        (factory_id, question_hash, answer, chart_config,
                         tokens, expires_at, question_embedding, window_key,
                         plan_key)
                    VALUES ($1, $2, $3, $4::jsonb, $5, $6, $7::vector, $8, $9)
                    ON CONFLICT (factory_id, question_hash) DO UPDATE
                        SET answer             = EXCLUDED.answer,
                            chart_config       = EXCLUDED.chart_config,
                            tokens             = EXCLUDED.tokens,
                            expires_at         = EXCLUDED.expires_at,
                            created_at         = NOW(),
                            question_embedding = EXCLUDED.question_embedding,
                            window_key         = EXCLUDED.window_key,
                            plan_key           = EXCLUDED.plan_key
                    """,
                    factory_id, question_hash, answer, chart_json,
                    int(tokens), expires_at, emb_literal, window_key, plan_key,
                )

    async def get_semantic(
        self,
        factory_id: str,
        question_embedding: List[float],
        window_key: str,
        plan_key: str,
        *,
        min_similarity: float = SEMANTIC_MIN_SIMILARITY,
    ) -> Optional[Dict[str, Any]]:
        """Cosine-similarity fallback for a natural paraphrase that missed
        the exact-hash lookup. Caller (synthesis_engine) tries `get()` first
        and only calls this on a miss.

        ⛔ window_key + plan_key are HARD equality filters in the SQL WHERE
        clause, not folded into the similarity score — a paraphrase about a
        DIFFERENT date window or a DIFFERENT set of triggered plan
        dimensions is never served, no matter how similar the wording (see
        module docstring + V20260710_03 migration for why). Returns None
        (never raises) on any DB error, no embedding-model rows, or a
        best-match similarity below `min_similarity` — callers fall through
        to full synthesis exactly like a cache miss.
        """
        emb_literal = _emb_literal(question_embedding)
        try:
            async with self._pool.acquire() as conn:
                async with conn.transaction():
                    await conn.execute(
                        "SELECT set_config('app.factory_id', $1, false)",
                        factory_id,
                    )
                    row = await conn.fetchrow(
                        """
                        SELECT answer, chart_config, tokens,
                               1 - (question_embedding <=> $2::vector) AS sim
                          FROM narrative_cache
                         WHERE factory_id         = $1
                           AND window_key         = $3
                           AND plan_key           = $4
                           AND expires_at         > NOW()
                           AND question_embedding IS NOT NULL
                         ORDER BY question_embedding <=> $2::vector ASC
                         LIMIT 1
                        """,
                        factory_id, emb_literal, window_key, plan_key,
                    )
        except Exception as e:
            logger.warning("[narrative-cache] get_semantic failed: %s", e)
            return None
        if row is None:
            return None
        sim = float(row["sim"])
        if sim < min_similarity:
            return None
        chart = row["chart_config"]
        if isinstance(chart, str):
            chart = json.loads(chart)
        return {
            "answer": row["answer"],
            "chart_config": chart,
            "tokens": int(row["tokens"]),
            "similarity": sim,
        }

    async def invalidate_on_upload(self, factory_id: str) -> int:
        """Delete all cache rows for this factory. Returns row count.

        Called when a new Excel upload lands (data has changed; any
        cached insights are potentially stale). Coarse but correct.

        F11.1 fix: tenant_isolation RLS policy filters by app.factory_id GUC.
        Without SET, DELETE finds 0 rows when caller is outside per-request
        context (cron / upload event handler).
        """
        async with self._pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)",
                    factory_id,
                )
                result = await conn.execute(
                    "DELETE FROM narrative_cache WHERE factory_id = $1",
                    factory_id,
                )
        # asyncpg returns "DELETE <n>"; parse count defensively.
        try:
            return int(result.rsplit(" ", 1)[-1])
        except ValueError:
            return 0

    async def prune_expired(self) -> int:
        """Delete all expired rows (any factory). For scheduled cleanup."""
        async with self._pool.acquire() as conn:
            result = await conn.execute(
                "DELETE FROM narrative_cache WHERE expires_at <= NOW()"
            )
        try:
            return int(result.rsplit(" ", 1)[-1])
        except ValueError:
            return 0
