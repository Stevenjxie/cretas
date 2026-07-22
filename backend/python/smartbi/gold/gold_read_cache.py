"""SHA256-keyed result cache for the Gold read endpoints.

Table: gold_read_cache (PK: factory_id, cache_key). Migration
V20260910_01__gold_read_cache.sql.

Why
---
qhj 餐饮 analytics 默认聚合"全部历史"(WS1 Task 1 让日期可空)。全历史聚合每次扫
全表偏重, 故对成形的 Gold read response 做缓存。

Key composition
---------------
    SHA256(endpoint | start | end | role | params_json)

- `endpoint` — logical endpoint name (e.g. "finance-summary")
- `start` / `end` — ISO date strings or "" (None → "" so all-history has a
  stable, distinct key from any concrete range)
- `role` — caller's RBAC role; IN the key so a price-stripped vs raw payload
  never cross-leak between roles
- `params_json` — `json.dumps(params, sort_keys=True)` so dict ordering is
  irrelevant

Invalidation
------------
- Time: `expires_at < now()` (6h default TTL)
- Upload event: callers should wipe the factory's rows when new data lands.
  `invalidate(factory_id)` does that (coarse but correct — mirrors
  narrative_cache.invalidate_on_upload).

RLS
---
Stateless over an asyncpg pool. get/put/invalidate set the `app.factory_id`
GUC inside a transaction (mirrors NarrativeCacheService) so the
tenant_isolation RLS policy admits the row. `prune_expired` relies on the
allow_prune_expired DELETE policy and needs no tenant context.
"""
from __future__ import annotations

import hashlib
import json
import logging
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, Optional

import asyncpg

logger = logging.getLogger(__name__)


DEFAULT_TTL_HOURS = 6


def compute_cache_key(
    endpoint: str,
    start: Optional[str],
    end: Optional[str],
    role: Optional[str],
    params: Dict[str, Any],
) -> str:
    """Deterministic SHA256 hexdigest over normalized components.

    Parts are joined with `|`. `params` is serialized with `sort_keys=True`
    so the key is independent of dict insertion order. None role/dates
    collapse to empty strings (stable + distinct from concrete values).
    """
    params_json = json.dumps(params or {}, sort_keys=True, ensure_ascii=False)
    parts = [
        (endpoint or "").strip(),
        (start or "").strip(),
        (end or "").strip(),
        (role or "").strip(),
        params_json,
    ]
    raw = "|".join(parts).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


class GoldReadCache:
    """Thin wrapper over the gold_read_cache table.

    Stateless — connections carry RLS context via the GUC set inside each
    transaction (mirrors smartbi.agent.narrative_cache.NarrativeCacheService).
    """

    def __init__(self, pool: asyncpg.Pool):
        self._pool = pool

    async def get(
        self, factory_id: str, cache_key: str
    ) -> Optional[Dict[str, Any]]:
        """Return cached payload if present AND not expired, else None."""
        async with self._pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)",
                    factory_id,
                )
                row = await conn.fetchrow(
                    """
                    SELECT payload
                      FROM gold_read_cache
                     WHERE factory_id = $1
                       AND cache_key  = $2
                       AND expires_at > NOW()
                    """,
                    factory_id, cache_key,
                )
        if row is None:
            return None
        payload = row["payload"]
        # asyncpg returns JSONB as a string unless a codec is registered on
        # the pool. Parse inline so callers always see a dict.
        if isinstance(payload, str):
            payload = json.loads(payload)
        return payload

    async def put(
        self,
        factory_id: str,
        cache_key: str,
        payload: Dict[str, Any],
        ttl_hours: int = DEFAULT_TTL_HOURS,
    ) -> None:
        """Upsert a cache entry. Existing row for the same key is replaced."""
        expires_at = datetime.now(timezone.utc) + timedelta(hours=ttl_hours)
        payload_json = json.dumps(payload, ensure_ascii=False)
        async with self._pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)",
                    factory_id,
                )
                await conn.execute(
                    """
                    INSERT INTO gold_read_cache
                        (factory_id, cache_key, payload, expires_at)
                    VALUES ($1, $2, $3::jsonb, $4)
                    ON CONFLICT (factory_id, cache_key) DO UPDATE
                        SET payload    = EXCLUDED.payload,
                            expires_at = EXCLUDED.expires_at,
                            created_at = NOW()
                    """,
                    factory_id, cache_key, payload_json, expires_at,
                )

    async def invalidate(self, factory_id: str) -> int:
        """Delete all cache rows for this factory. Returns row count.

        Called when new data lands for the tenant (any cached Gold reads
        are potentially stale). Coarse but correct — mirrors
        narrative_cache.invalidate_on_upload.
        """
        async with self._pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)",
                    factory_id,
                )
                result = await conn.execute(
                    "DELETE FROM gold_read_cache WHERE factory_id = $1",
                    factory_id,
                )
        try:
            return int(result.rsplit(" ", 1)[-1])
        except ValueError:
            return 0

    async def prune_expired(self) -> int:
        """Delete all expired rows (any factory). For scheduled cleanup."""
        async with self._pool.acquire() as conn:
            result = await conn.execute(
                "DELETE FROM gold_read_cache WHERE expires_at <= NOW()"
            )
        try:
            return int(result.rsplit(" ", 1)[-1])
        except ValueError:
            return 0
