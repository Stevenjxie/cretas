"""CapabilityCalculator — 数据织网 Sub-Project A.

Per 数据织网/02-A-能力驱动渲染.md §3.1. Aggregates a factory's
COMPLETED uploads' field_definitions, maps via ALIAS_TO_ATTR, returns
the canonical-field set the factory currently has.

Defenses (all live in get_capabilities + _fetch_capabilities):
- Cross-tenant guard via tenant_ctx.get_factory_id (raises 403)
- 5-min in-process TTL cache (per-process; multi-worker → see spec §3.4)
- Inflight Future dedup (3 concurrent get_capabilities → 1 SQL call)
- invalidation_gen token: invalidate() during inflight bumps gen, the
  inflight result then refuses to write to cache (race defeat, spec O5)
- transaction-scoped set_config('app.factory_id', $1, false) inside an
  explicit conn.transaction() — GUC clears at commit, no leak to next
  pool acquirer (spec C4)
- merge_status column lazy memo: B-stage column may not exist yet;
  one-shot information_schema check, NOT in hot path (spec S-v3-1)
- merge_status filter NOT LIKE 'MERGED_INTO_%' AND != 'SUPERSEDED'
  (matches dynamic id pattern, not literal 'MERGED_INTO_OTHER'; spec
  C-v3-1)
"""
from __future__ import annotations
import asyncio
import time
from typing import Optional
import asyncpg
from fastapi import HTTPException
from smartbi.canonical.aliases import to_canonical
from smartbi.tenant_ctx import get_factory_id


class CapabilityCalculator:
    """聚合 factory 所有 upload 的 field_definitions, 返回 canonical 字段集合.

    Concurrency model:
    - Per-process in-memory dict cache (current prod uvicorn worker=1, OK)
    - 5 分钟 TTL + event-driven invalidate
    - Inflight Promise dedup 防 stampede
    - invalidation_gen token defeats race: invalidate() 跑时旧 inflight 还没结束,
      旧结果不会覆盖 cache.

    多 worker scale 时切 Redis (见 spec §3.4).
    """

    def __init__(self, pool: asyncpg.Pool):
        self._pool = pool
        self._cache: dict[str, tuple[set[str], float]] = {}
        self._cache_ttl = 300  # 5 分钟
        self._inflight: dict[str, asyncio.Future] = {}
        self._invalidation_gen: dict[str, int] = {}
        # merge_status column 存在性 lazy memo (一次性, 不在 hot path)
        self._has_merge_status: Optional[bool] = None

    async def _check_merge_status_column(self) -> bool:
        """检测 merge_status 列是否存在 (B 上线后存在). lazy memo 一次. spec §3.1 S-v3-1."""
        if self._has_merge_status is not None:
            return self._has_merge_status
        async with self._pool.acquire() as conn:
            self._has_merge_status = await conn.fetchval("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'smart_bi_pg_excel_uploads'
                      AND column_name = 'merge_status'
                )
            """)
        return self._has_merge_status

    async def get_capabilities(self, factory_id: str) -> set[str]:
        """返回 factory 当前可用的 canonical 字段集合."""
        # Defensive: ctx factory_id must match (防 IDOR)
        ctx_fid = get_factory_id()
        if ctx_fid and ctx_fid not in (factory_id, "__internal__"):
            raise HTTPException(status_code=403, detail="Cross-tenant access denied")

        # Cache hit
        cached = self._cache.get(factory_id)
        if cached and time.time() - cached[1] < self._cache_ttl:
            return cached[0]

        # Inflight dedup
        if factory_id in self._inflight:
            return await self._inflight[factory_id]

        # invalidation_gen race defeat: record gen at start, only write to cache if unchanged
        gen_at_start = self._invalidation_gen.get(factory_id, 0)
        future: asyncio.Future = asyncio.Future()
        self._inflight[factory_id] = future
        try:
            result = await self._fetch_capabilities(factory_id)
            if self._invalidation_gen.get(factory_id, 0) == gen_at_start:
                self._cache[factory_id] = (result, time.time())
            future.set_result(result)
            return result
        except Exception as e:
            future.set_exception(e)
            raise
        finally:
            self._inflight.pop(factory_id, None)

    async def _fetch_capabilities(self, factory_id: str) -> set[str]:
        """transaction-scoped set_config + 显式 transaction 包裹.
        merge_status 过滤改 NOT LIKE 'MERGED_INTO_%' 模式 (匹配动态 id),
        column 存在性 memo 不在 hot path. spec §3.1 C-v3-1 + S-v3-1.
        """
        has_merge_status = await self._check_merge_status_column()

        merge_filter = (
            "AND (u.merge_status IS NULL OR (u.merge_status != 'SUPERSEDED' AND u.merge_status NOT LIKE 'MERGED_INTO_%'))"  # noqa: E501
            if has_merge_status
            else ""
        )

        async with self._pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)",
                    factory_id
                )
                rows = await conn.fetch(f"""
                    SELECT DISTINCT fd.original_name
                      FROM smart_bi_pg_field_definitions fd
                      JOIN smart_bi_pg_excel_uploads u ON fd.upload_id = u.id
                     WHERE u.factory_id = $1
                       AND u.upload_status = 'COMPLETED'
                       {merge_filter}
                """, factory_id)

        canonical_fields: set[str] = set()
        for row in rows:
            canonical = to_canonical(row["original_name"])
            if canonical:
                canonical_fields.add(canonical)
        return canonical_fields

    def invalidate(self, factory_id: str) -> None:
        """上传完成后调用, 失效缓存. 同时 bump invalidation_gen token. spec §3.1 O5."""
        self._cache.pop(factory_id, None)
        self._invalidation_gen[factory_id] = self._invalidation_gen.get(factory_id, 0) + 1
