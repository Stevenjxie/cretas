"""Dimension resolver — turn raw name+attrs into a dim_* surrogate id.

Week 2 Day 3 of Unified Data Layer v1 spec (§2.1).

Pattern
-------
Every call:
  1. Check per-instance cache keyed by the dim's natural key. Hit → return
     cached id instantly (no DB round trip — critical for batch inserts
     where the same store_name repeats across hundreds of bills).
  2. Miss → INSERT ... ON CONFLICT (factory_id, natural_key) DO UPDATE SET
     updated_at = NOW() RETURNING <id>. DO UPDATE (not DO NOTHING) is
     load-bearing: DO NOTHING returns no row on conflict, forcing a
     follow-up SELECT which loses concurrency safety. DO UPDATE touches
     updated_at and always returns the id.
  3. Store in cache and return.

Tenant safety
-------------
This service assumes `tenant_ctx.get_factory_id()` is set upstream (by
JWT middleware or explicit `set_factory_id`). The asyncpg pool's `setup`
callback (Week 1 Day 1) applies it to every acquired connection as the
session's `app.factory_id`, and RLS enforces the tenant boundary on the
INSERT's WITH CHECK clause. If the caller's constructor `factory_id`
arg disagrees with the tenant context, the INSERT fails (by design —
wrong-tenant inserts should never succeed).

Lifecycle
---------
One DimResolver per upload / batch. Per-instance cache makes sense only
for the duration of a single normalizer run — beyond that, dim rows may
have been updated by concurrent uploads and the cached (name → id)
mapping is still valid (ids never change), but stale dim metadata should
be re-fetched from DB if needed.
"""
from __future__ import annotations

import logging
from typing import Dict, Optional, Tuple

import asyncpg

logger = logging.getLogger(__name__)


# Shape of the SQL for each dim — centralized so the caller doesn't
# hand-build UPSERT queries. `key_cols` is the UNIQUE natural key; the
# RETURNING column is the table's surrogate id.

_STORE_UPSERT_SQL = """
INSERT INTO dim_store (factory_id, name, brand, city, province, region)
VALUES ($1, $2, $3, $4, $5, $6)
ON CONFLICT (factory_id, name)
  DO UPDATE SET updated_at = NOW(),
                brand    = COALESCE(EXCLUDED.brand,    dim_store.brand),
                city     = COALESCE(EXCLUDED.city,     dim_store.city),
                province = COALESCE(EXCLUDED.province, dim_store.province),
                region   = COALESCE(EXCLUDED.region,   dim_store.region)
RETURNING store_id
"""

_PRODUCT_UPSERT_SQL = """
INSERT INTO dim_product (factory_id, name, normalized_name, category, sub_category, sku_code)
VALUES ($1, $2, $3, $4, $5, $6)
ON CONFLICT (factory_id, normalized_name)
  DO UPDATE SET updated_at   = NOW(),
                category     = COALESCE(EXCLUDED.category,     dim_product.category),
                sub_category = COALESCE(EXCLUDED.sub_category, dim_product.sub_category),
                sku_code     = COALESCE(EXCLUDED.sku_code,     dim_product.sku_code)
RETURNING product_id
"""

_STAFF_UPSERT_SQL = """
INSERT INTO dim_staff (factory_id, name, role, store_id)
VALUES ($1, $2, $3, $4)
ON CONFLICT (factory_id, name, store_id)
  DO UPDATE SET updated_at = NOW(),
                role       = COALESCE(EXCLUDED.role, dim_staff.role)
RETURNING staff_id
"""

_CHANNEL_UPSERT_SQL = """
INSERT INTO dim_payment_channel (factory_id, name, category)
VALUES ($1, $2, $3)
ON CONFLICT (factory_id, name)
  DO UPDATE SET updated_at = NOW(),
                category   = COALESCE(EXCLUDED.category, dim_payment_channel.category)
RETURNING channel_id
"""

_COST_CATEGORY_UPSERT_SQL = """
INSERT INTO dim_cost_category (factory_id, name, cost_type, is_fixed)
VALUES ($1, $2, $3, $4)
ON CONFLICT (factory_id, name)
  DO UPDATE SET updated_at  = NOW(),
                cost_type   = EXCLUDED.cost_type,
                is_fixed    = EXCLUDED.is_fixed
RETURNING category_id
"""

_DISCOUNT_UPSERT_SQL = """
INSERT INTO dim_discount (
    factory_id, name, discount_type, platform,
    face_value, actual_price, start_date, end_date, parsed_ok
)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
ON CONFLICT (factory_id, name)
  DO UPDATE SET updated_at     = NOW(),
                discount_type  = COALESCE(EXCLUDED.discount_type,  dim_discount.discount_type),
                platform       = COALESCE(EXCLUDED.platform,       dim_discount.platform),
                face_value     = COALESCE(EXCLUDED.face_value,     dim_discount.face_value),
                actual_price   = COALESCE(EXCLUDED.actual_price,   dim_discount.actual_price),
                start_date     = COALESCE(EXCLUDED.start_date,     dim_discount.start_date),
                end_date       = COALESCE(EXCLUDED.end_date,       dim_discount.end_date),
                parsed_ok      = EXCLUDED.parsed_ok OR dim_discount.parsed_ok
RETURNING discount_id
"""


class DimResolver:
    """Per-upload dimension resolver. NOT thread-safe (use one per task)."""

    def __init__(self, pool: asyncpg.Pool, factory_id: str):
        if not factory_id:
            raise ValueError("factory_id required")
        self.pool = pool
        self.factory_id = factory_id
        # Separate cache per dim type to avoid cross-collision on short names.
        self._store_cache: Dict[str, int] = {}
        self._product_cache: Dict[str, int] = {}
        self._staff_cache: Dict[Tuple[str, Optional[int]], int] = {}
        self._channel_cache: Dict[str, int] = {}
        self._discount_cache: Dict[str, int] = {}
        self._cost_category_cache: Dict[str, int] = {}

    # ── Store ────────────────────────────────────────────────

    async def resolve_store(
        self,
        name: str,
        *,
        brand: Optional[str] = None,
        city: Optional[str] = None,
        province: Optional[str] = None,
        region: Optional[str] = None,
    ) -> int:
        # Whitespace normalization (Task B4): 二维火 POS CSV exports have
        # trailing/leading whitespace on store names; without .strip() the
        # dim_store unique constraint creates duplicates. Closed-store prefix
        # (（闭店）) is BUSINESS data and is preserved — not noise.
        name = (name or "").strip()
        if not name:
            raise ValueError("store name required")
        cached = self._store_cache.get(name)
        if cached is not None:
            return cached
        async with self.pool.acquire() as conn:
            sid = await conn.fetchval(
                _STORE_UPSERT_SQL,
                self.factory_id, name, brand, city, province, region,
            )
        self._store_cache[name] = sid
        return sid

    # ── Product ──────────────────────────────────────────────

    async def resolve_product(
        self,
        name: str,
        normalized_name: str,
        *,
        category: Optional[str] = None,
        sub_category: Optional[str] = None,
        sku_code: Optional[str] = None,
    ) -> int:
        if not name or not normalized_name:
            raise ValueError("name and normalized_name required")
        cached = self._product_cache.get(normalized_name)
        if cached is not None:
            return cached
        async with self.pool.acquire() as conn:
            pid = await conn.fetchval(
                _PRODUCT_UPSERT_SQL,
                self.factory_id, name, normalized_name,
                category, sub_category, sku_code,
            )
        self._product_cache[normalized_name] = pid
        return pid

    # ── Staff ────────────────────────────────────────────────

    async def resolve_staff(
        self,
        name: str,
        *,
        role: Optional[str] = None,
        store_id: Optional[int] = None,
    ) -> int:
        if not name:
            raise ValueError("staff name required")
        key = (name, store_id)
        cached = self._staff_cache.get(key)
        if cached is not None:
            return cached
        async with self.pool.acquire() as conn:
            sid = await conn.fetchval(
                _STAFF_UPSERT_SQL,
                self.factory_id, name, role, store_id,
            )
        self._staff_cache[key] = sid
        return sid

    # ── Payment channel ──────────────────────────────────────

    async def resolve_payment_channel(
        self,
        name: str,
        *,
        category: Optional[str] = None,
    ) -> int:
        if not name:
            raise ValueError("channel name required")
        cached = self._channel_cache.get(name)
        if cached is not None:
            return cached
        async with self.pool.acquire() as conn:
            cid = await conn.fetchval(
                _CHANNEL_UPSERT_SQL,
                self.factory_id, name, category,
            )
        self._channel_cache[name] = cid
        return cid

    # ── Discount ─────────────────────────────────────────────

    async def resolve_discount(
        self,
        name: str,
        *,
        discount_type: Optional[str] = None,
        platform: Optional[str] = None,
        face_value: Optional[float] = None,
        actual_price: Optional[float] = None,
        start_date=None,
        end_date=None,
        parsed_ok: bool = False,
    ) -> int:
        if not name:
            raise ValueError("discount name required")
        cached = self._discount_cache.get(name)
        if cached is not None:
            return cached
        async with self.pool.acquire() as conn:
            did = await conn.fetchval(
                _DISCOUNT_UPSERT_SQL,
                self.factory_id, name, discount_type, platform,
                face_value, actual_price, start_date, end_date, parsed_ok,
            )
        self._discount_cache[name] = did
        return did

    # ── Cost category ────────────────────────────────────────

    async def resolve_cost_category(
        self,
        name: str,
        cost_type: str,
        *,
        is_fixed: bool = False,
    ) -> int:
        """Upsert a cost category. cost_type must be one of
        material/labor/overhead/other — enforced by DB CHECK."""
        if not name:
            raise ValueError("cost category name required")
        if cost_type not in ("material", "labor", "overhead", "other"):
            raise ValueError(
                f"cost_type must be material/labor/overhead/other, got {cost_type!r}"
            )
        cached = self._cost_category_cache.get(name)
        if cached is not None:
            return cached
        async with self.pool.acquire() as conn:
            cid = await conn.fetchval(
                _COST_CATEGORY_UPSERT_SQL,
                self.factory_id, name, cost_type, is_fixed,
            )
        self._cost_category_cache[name] = cid
        return cid

    # ── Introspection (for tests / logging) ──────────────────

    def cache_stats(self) -> Dict[str, int]:
        return {
            "store": len(self._store_cache),
            "product": len(self._product_cache),
            "staff": len(self._staff_cache),
            "channel": len(self._channel_cache),
            "discount": len(self._discount_cache),
            "cost_category": len(self._cost_category_cache),
        }


def clear_cache(factory_id: str) -> None:
    """No-op: DimResolver caches are per-instance and get GC'd when the upload task
    completes. Called by concurrency.with_factory_serialization for protocol parity
    with future module-level caches (e.g., embedding cache shared across resolvers).
    """
