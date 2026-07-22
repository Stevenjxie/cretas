"""Restaurant module completeness API (餐饮 Phase A Task 2.1).

Per spec v2 §2.2:

  GET /completeness?factoryId=F001

Returns a structured view of how complete the restaurant data is across 6
operational modules.  Each module reports: record count, last-updated
timestamp, coverage score (0–100), and actionable missing-data hints.

Auth
----
Factory users may query their own factory (factory_id from JWT must match
query param).  Admin-tier roles (factory_super_admin / platform_admin) may
query any factory.

Cache
-----
Module-level dict keyed by factoryId, 5-minute TTL.  Not shared across
workers (Phase A simplification; replace with Redis for multi-worker prod).

Pool acquisition
----------------
Pools are acquired lazily inside the endpoint to avoid circular imports at
module-load time.  Both pools are app-lifetime singletons from smartbi.config:
  - SmartBI pool → ``smartbi.config.get_pg_pool``
  - Cretas (Java app DB) pool → ``smartbi.config.get_cretas_pool``

Each of the 6 module SQL queries is wrapped in its own try/except so a
missing table (e.g. restaurant_reviews not yet migrated) returns count=0
instead of aborting the whole endpoint.
"""
from __future__ import annotations

import asyncio
import logging
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Tuple

from fastapi import APIRouter, HTTPException, Query, Request

logger = logging.getLogger(__name__)

router = APIRouter()

# ---------------------------------------------------------------------------
# Module catalogue
# ---------------------------------------------------------------------------

#: Expected module IDs (exported so tests can reference without hard-coding).
_EXPECTED_MODULE_IDS: List[str] = [
    "pos_sales",
    "menu_recipe",
    "requisition",
    "wastage",
    "stocktaking",
    "review",
]

# Human-readable names for each module
_MODULE_NAMES: Dict[str, str] = {
    "pos_sales": "POS 销售数据",
    "menu_recipe": "菜品配方",
    "requisition": "领料记录",
    "wastage": "损耗记录",
    "stocktaking": "盘点记录",
    "review": "顾客评价",
}

# Default missing-data hints per module
_MODULE_HINTS: Dict[str, List[str]] = {
    "pos_sales": ["上传 POS 导出 Excel 文件", "确认文件名包含「销售」关键字"],
    "menu_recipe": ["在「配方管理」页面录入标准配方", "为每个菜品关联原料清单"],
    "requisition": ["在「领料管理」页面创建领料申请", "领料单需包含日期和数量"],
    "wastage": ["在「损耗记录」页面录入日常损耗数据", "建议每日录入"],
    "stocktaking": ["在「盘点管理」页面创建盘点记录", "建议每周盘点一次"],
    "review": ["上传顾客评价数据文件", "或通过评价来源配置自动采集"],
}

# Admin-tier roles that may query any factory
_ADMIN_ROLES = {"factory_super_admin", "platform_admin", "internal", "admin"}

# ---------------------------------------------------------------------------
# Module-level cache: {factoryId: (timestamp_float, response_dict)}
# ---------------------------------------------------------------------------

_CACHE_TTL_S: int = 300  # 5 minutes
_cache: Dict[str, Tuple[float, Dict[str, Any]]] = {}


# ---------------------------------------------------------------------------
# Pure helpers (no I/O — easy to unit-test)
# ---------------------------------------------------------------------------

def _coverage_window_days(age_days: int) -> int:
    """Return the window (days) to use for coverage calculation.

    Clamps to [1, 30]:  new factories use a shorter window so they aren't
    penalised for having no data before they existed.

    >>> _coverage_window_days(5)
    5
    >>> _coverage_window_days(30)
    30
    >>> _coverage_window_days(100)
    30
    >>> _coverage_window_days(0)
    1
    """
    return min(30, max(1, age_days))


def _coverage_score(count: int, window_days: int) -> int:
    """Map record count → 0-100 coverage score.

    Heuristic: 1 record per day in the window = 100%.  Capped at 100.
    An empty module always returns 0.
    """
    if count <= 0 or window_days <= 0:
        return 0
    score = int(count * 100 / window_days)
    return min(100, score)


# ---------------------------------------------------------------------------
# Async DB helpers
# ---------------------------------------------------------------------------

async def _factory_age_days(factory_id: str, smartbi_pool, cretas_pool) -> int:
    """Return the age of the factory in days based on its earliest data signal.

    Tries multiple sources in order:
      1. smart_bi_pg_excel_uploads.MIN(created_at) — uploads
      2. cretas_db material_requisitions / wastage_records / stocktaking_records
         MIN(created_at) — manual data entries (R_XMX edge case fix)
      3. Fall back to 1 if no data anywhere.

    Returns the EARLIEST date across all signals so factoryAge reflects
    when the factory truly started, not just when they started uploading.
    """
    # All datetimes normalized to UTC-aware before min() comparison —
    # asyncpg returns naive datetime for TIMESTAMP (no time zone) and aware
    # for TIMESTAMPTZ; mixed comparison raises TypeError.
    from datetime import date as _date

    def _to_utc_aware(val):
        """Normalize date / naive datetime / aware datetime → UTC-aware datetime."""
        if isinstance(val, _date) and not isinstance(val, datetime):
            return datetime.combine(val, datetime.min.time(), tzinfo=timezone.utc)
        if val.tzinfo is None:
            return val.replace(tzinfo=timezone.utc)
        return val.astimezone(timezone.utc)

    candidates = []
    try:
        async with smartbi_pool.acquire() as conn:
            earliest = await conn.fetchval(
                "SELECT MIN(created_at) FROM smart_bi_pg_excel_uploads"
                " WHERE factory_id = $1",
                factory_id,
            )
            if earliest is not None:
                candidates.append(_to_utc_aware(earliest))
    except Exception as exc:
        logger.debug(f"[completeness] uploads age check failed: {exc}")

    # Fallback: cretas_db data tables (may have data without corresponding upload).
    # Use BUSINESS date columns (requisition_date / wastage_date / stocktaking_date)
    # not created_at — to match the date columns used by the module filters
    # (_requisition / _wastage / _stocktaking). R_XMX edge case: created_at=4 days
    # ago but requisition_date=15 days ago → window=4 filtered out all data.
    # Aligning factoryAge with business dates fixes this mismatch.
    if cretas_pool is not None:
        for table, date_col in [
            ('material_requisitions', 'requisition_date'),
            ('wastage_records', 'wastage_date'),
            ('stocktaking_records', 'stocktaking_date'),
            ('recipes', 'created_at'),  # recipes has no business date column; created_at OK
        ]:
            try:
                async with cretas_pool.acquire() as conn:
                    val = await conn.fetchval(
                        f"SELECT MIN({date_col}) FROM {table}"
                        f" WHERE factory_id = $1 AND deleted_at IS NULL",
                        factory_id,
                    )
                    if val is not None:
                        candidates.append(_to_utc_aware(val))
            except Exception as exc:
                logger.debug(f"[completeness] {table} age check failed: {exc}")

    if not candidates:
        return 1
    # All candidates are UTC-aware now — safe to compare
    earliest = min(candidates)
    now = datetime.now(timezone.utc)
    delta = now - earliest
    return max(1, delta.days)


async def _fetch_module_stats(
    factory_id: str, window_days: int, cretas_pool, smartbi_pool
) -> Dict[str, Dict[str, Any]]:
    """Run 6 parallel SQL queries and return per-module raw stats.

    Each stat dict has keys:
      - count (int): records in the coverage window (or total if no date col)
      - last_updated (str | None): ISO-8601 of most recent record

    Any query that fails returns {"count": 0, "last_updated": None} so the
    rest of the response is unaffected.
    """
    stats: Dict[str, Dict[str, Any]] = {}

    async def _safe(coro, module_id: str) -> None:
        try:
            stats[module_id] = await coro
        except Exception as exc:
            logger.warning(
                f"[completeness] module={module_id} factory={factory_id} error: {exc}"
            )
            stats[module_id] = {"count": 0, "last_updated": None}

    # ── 1. POS sales: prefer real gold/raw sales rows, then uploads ────────
    async def _pos_sales() -> Dict[str, Any]:
        try:
            async with smartbi_pool.acquire() as conn:
                async with conn.transaction():
                    await conn.execute(
                        "SELECT set_config('app.factory_id', $1, false)", factory_id
                    )
                    row = await conn.fetchrow(
                        """
                        SELECT
                            COUNT(*)              AS cnt,
                            MAX(date)::text       AS last_upd
                        FROM agg_daily
                        WHERE factory_id = $1
                          AND date >= CURRENT_DATE - ($2 || ' days')::interval
                        """,
                        factory_id,
                        str(window_days),
                    )
            if row and (row["cnt"] or 0) > 0:
                return {"count": row["cnt"] or 0, "last_updated": row["last_upd"]}
        except Exception as exc:
            logger.debug(f"[completeness] pos_sales agg_daily fallback: {exc}")

        try:
            async with cretas_pool.acquire() as conn:
                row = await conn.fetchrow(
                    """
                    SELECT
                        COUNT(*)                AS cnt,
                        MAX(order_date)::text   AS last_upd
                    FROM smart_bi_sales_data
                    WHERE factory_id = $1
                      AND order_date >= CURRENT_DATE - ($2 || ' days')::interval
                    """,
                    factory_id,
                    str(window_days),
                )
            if row and (row["cnt"] or 0) > 0:
                return {"count": row["cnt"] or 0, "last_updated": row["last_upd"]}
        except Exception as exc:
            logger.debug(f"[completeness] pos_sales raw sales fallback: {exc}")

        try:
            async with smartbi_pool.acquire() as conn:
                row = await conn.fetchrow(
                    """
                    SELECT
                        COUNT(*)                         AS cnt,
                        MAX(created_at)::text            AS last_upd
                    FROM smart_bi_pg_excel_uploads
                    WHERE factory_id = $1
                      AND (
                          file_name ILIKE '%销售%'
                          OR file_name ILIKE '%pos%'
                          OR file_name ILIKE '%营业%'
                          OR file_name ILIKE '%收入%'
                      )
                      AND created_at >= NOW() - ($2 || ' days')::interval
                    """,
                    factory_id,
                    str(window_days),
                )
            return {"count": row["cnt"] or 0, "last_updated": row["last_upd"]}
        except Exception as exc:
            logger.debug(f"[completeness] pos_sales fallback: {exc}")
            return {"count": 0, "last_updated": None}

    # ── 2. Menu/recipe: recipes table in cretas_db ─────────────────────────
    async def _menu_recipe() -> Dict[str, Any]:
        try:
            async with cretas_pool.acquire() as conn:
                row = await conn.fetchrow(
                    """
                    SELECT
                        COUNT(*)              AS cnt,
                        MAX(created_at)::text AS last_upd
                    FROM recipes
                    WHERE factory_id = $1
                      AND deleted_at IS NULL
                    """,
                    factory_id,
                )
            return {"count": row["cnt"] or 0, "last_updated": row["last_upd"]}
        except Exception as exc:
            logger.debug(f"[completeness] menu_recipe fallback: {exc}")
            return {"count": 0, "last_updated": None}

    # ── 3. Requisition: material_requisitions in cretas_db ─────────────────
    async def _requisition() -> Dict[str, Any]:
        try:
            async with cretas_pool.acquire() as conn:
                row = await conn.fetchrow(
                    """
                    SELECT
                        COUNT(*)                           AS cnt,
                        MAX(requisition_date)::text        AS last_upd
                    FROM material_requisitions
                    WHERE factory_id = $1
                      AND deleted_at IS NULL
                      AND requisition_date >= CURRENT_DATE - ($2 || ' days')::interval
                    """,
                    factory_id,
                    str(window_days),
                )
            return {"count": row["cnt"] or 0, "last_updated": row["last_upd"]}
        except Exception as exc:
            logger.debug(f"[completeness] requisition fallback: {exc}")
            return {"count": 0, "last_updated": None}

    # ── 4. Wastage: wastage_records in cretas_db ───────────────────────────
    async def _wastage() -> Dict[str, Any]:
        try:
            async with cretas_pool.acquire() as conn:
                row = await conn.fetchrow(
                    """
                    SELECT
                        COUNT(*)                  AS cnt,
                        MAX(wastage_date)::text    AS last_upd
                    FROM wastage_records
                    WHERE factory_id = $1
                      AND deleted_at IS NULL
                      AND wastage_date >= CURRENT_DATE - ($2 || ' days')::interval
                    """,
                    factory_id,
                    str(window_days),
                )
            return {"count": row["cnt"] or 0, "last_updated": row["last_upd"]}
        except Exception as exc:
            logger.debug(f"[completeness] wastage fallback: {exc}")
            return {"count": 0, "last_updated": None}

    # ── 5. Stocktaking: stocktaking_records in cretas_db ──────────────────
    async def _stocktaking() -> Dict[str, Any]:
        try:
            async with cretas_pool.acquire() as conn:
                row = await conn.fetchrow(
                    """
                    SELECT
                        COUNT(*)                      AS cnt,
                        MAX(stocktaking_date)::text    AS last_upd
                    FROM stocktaking_records
                    WHERE factory_id = $1
                      AND deleted_at IS NULL
                      AND stocktaking_date >= CURRENT_DATE - ($2 || ' days')::interval
                    """,
                    factory_id,
                    str(window_days),
                )
            return {"count": row["cnt"] or 0, "last_updated": row["last_upd"]}
        except Exception as exc:
            logger.debug(f"[completeness] stocktaking fallback: {exc}")
            return {"count": 0, "last_updated": None}

    # ── 6. Reviews: restaurant_reviews in smartbi_db ───────────────────────
    async def _review() -> Dict[str, Any]:
        # restaurant_reviews has FORCE RLS (V20260502_04 migration). Must set
        # app.factory_id GUC inside conn.transaction() else silently 0 rows.
        try:
            async with smartbi_pool.acquire() as conn:
                async with conn.transaction():
                    await conn.execute(
                        "SELECT set_config('app.factory_id', $1, false)", factory_id
                    )
                    row = await conn.fetchrow(
                        """
                        SELECT
                            COUNT(*)                  AS cnt,
                            MAX(review_time)::text    AS last_upd
                        FROM restaurant_reviews
                        WHERE factory_id = $1
                          AND review_time >= NOW() - ($2 || ' days')::interval
                        """,
                        factory_id,
                        str(window_days),
                    )
            if row and (row["cnt"] or 0) > 0:
                return {"count": row["cnt"] or 0, "last_updated": row["last_upd"]}
        except Exception as exc:
            logger.debug(f"[completeness] review fallback: {exc}")

        # Gold review analytics are currently served from smart_bi_dynamic_data
        # (大众点评 upload). Use upload created_at for freshness because review
        # business dates can be historical while the demo corpus is newly seeded.
        try:
            async with smartbi_pool.acquire() as conn:
                async with conn.transaction():
                    await conn.execute(
                        "SELECT set_config('app.factory_id', $1, false)", factory_id
                    )
                    row = await conn.fetchrow(
                        """
                        SELECT
                            COUNT(*)                  AS cnt,
                            MAX(created_at)::text     AS last_upd
                        FROM smart_bi_dynamic_data
                        WHERE factory_id = $1
                          AND created_at >= NOW() - ($2 || ' days')::interval
                          AND (
                              row_data ? '评价ID'
                              OR row_data ? '星级分'
                              OR row_data ? '评价时间'
                          )
                        """,
                        factory_id,
                        str(window_days),
                    )
            return {"count": row["cnt"] or 0, "last_updated": row["last_upd"]}
        except Exception as exc:
            logger.debug(f"[completeness] review dynamic fallback: {exc}")
            return {"count": 0, "last_updated": None}

    # Run all 6 concurrently
    await asyncio.gather(
        _safe(_pos_sales(), "pos_sales"),
        _safe(_menu_recipe(), "menu_recipe"),
        _safe(_requisition(), "requisition"),
        _safe(_wastage(), "wastage"),
        _safe(_stocktaking(), "stocktaking"),
        _safe(_review(), "review"),
    )
    return stats


def _build_module_response(
    module_id: str,
    stat: Dict[str, Any],
    window_days: int,
) -> Dict[str, Any]:
    """Build a single module entry for the API response."""
    count = stat.get("count", 0)
    last_updated = stat.get("last_updated")
    has_data = count > 0
    coverage = _coverage_score(count, window_days)

    # Only include hints when coverage is below threshold
    hints: List[str] = []
    if coverage < 50:
        hints = _MODULE_HINTS.get(module_id, [])

    return {
        "id": module_id,
        "name": _MODULE_NAMES.get(module_id, module_id),
        "hasData": has_data,
        "recordCount": count,
        "lastUpdated": last_updated,
        "coverage": coverage,
        "missingHints": hints,
        "windowDays": window_days,
    }


# ---------------------------------------------------------------------------
# Endpoint
# ---------------------------------------------------------------------------

@router.get("/completeness")
async def get_completeness(
    request: Request,
    factoryId: str = Query(..., description="Factory ID to query completeness for"),
    format: str = Query("default", description="`default` | `llm` (Sprint 11 LLM wrapper)"),
) -> Dict[str, Any]:
    """Return completeness metrics for all 6 restaurant data modules.

    Auth:
    - Any authenticated user may query their own factory.
    - Admin-tier roles may query any factory.
    - Unauthenticated requests → 401.
    - Non-admin querying another factory → 403.

    Returns:
    {
        "factoryId": str,
        "modules": [ {id, name, hasData, recordCount, lastUpdated, coverage, missingHints, windowDays} ],
        "overallCompleteness": int,   # mean of 6 module coverage scores
        "cachedAt": str               # ISO-8601 timestamp
    }
    """
    # ── Auth checks ──────────────────────────────────────────────────────────
    role = getattr(request.state, "role", None)
    jwt_factory_id = getattr(request.state, "factory_id", None) or ""

    if role is None:
        raise HTTPException(status_code=401, detail="未登录，请先认证")

    if not factoryId or not factoryId.strip():
        raise HTTPException(
            status_code=400,
            detail="factoryId 不能为空",
        )
    factoryId = factoryId.strip()
    if len(factoryId) > 50:
        raise HTTPException(
            status_code=400,
            detail=f"factoryId 长度不能超过 50 字符 (收到 {len(factoryId)} 字符)",
        )

    is_admin = role in _ADMIN_ROLES
    if not is_admin and jwt_factory_id != factoryId:
        raise HTTPException(
            status_code=403,
            detail=f"无权查询工厂 {factoryId!r} 的数据 (当前工厂 {jwt_factory_id!r})",
        )

    # ── Cache check ──────────────────────────────────────────────────────────
    now_ts = time.monotonic()
    cached = _cache.get(factoryId)
    if cached is not None:
        cached_at_ts, cached_body = cached
        if now_ts - cached_at_ts < _CACHE_TTL_S:
            return _maybe_llm_wrap(cached_body, format, factoryId)

    # ── Acquire pools lazily (avoid circular import from main) ───────────────
    try:
        from smartbi.config import get_pg_pool, get_cretas_pool

        smartbi_pool = await get_pg_pool()
        if smartbi_pool is None:
            raise RuntimeError("SmartBI pool unavailable — check POSTGRES_URL setting")

        cretas_pool = await get_cretas_pool()
        if cretas_pool is None:
            raise RuntimeError("Cretas pool unavailable — check FOOD_KB_DB_URL / food_kb_postgres_* settings")
    except Exception as exc:
        logger.error(f"[completeness] pool acquisition failed: {exc}")
        raise HTTPException(
            status_code=503,
            detail=f"数据库连接失败: {exc}",
        )

    # ── Factory age → coverage window ────────────────────────────────────
    age_days = await _factory_age_days(factoryId, smartbi_pool, cretas_pool)
    window_days = _coverage_window_days(age_days)

    # ── Fetch all 6 module stats ─────────────────────────────────────────
    stats = await _fetch_module_stats(factoryId, window_days, cretas_pool, smartbi_pool)

    # ── Build response ───────────────────────────────────────────────────
    modules = [
        _build_module_response(mod_id, stats.get(mod_id, {"count": 0, "last_updated": None}), window_days)
        for mod_id in _EXPECTED_MODULE_IDS
    ]

    overall = int(sum(m["coverage"] for m in modules) / len(modules))
    cached_at = datetime.now(timezone.utc).isoformat()

    body: Dict[str, Any] = {
        "factoryId": factoryId,
        # Phase A: factoryName == factoryId, factoryType hardcoded RESTAURANT.
        # Phase B: lookup factories table for real factoryName + type.
        "factoryName": factoryId,
        "factoryType": "RESTAURANT",
        "factoryAgeDays": age_days,
        "modules": modules,
        "overallCompleteness": overall,
        "cachedAt": cached_at,
    }

    # ── Store in cache ────────────────────────────────────────────────────
    _cache[factoryId] = (now_ts, body)

    return _maybe_llm_wrap(body, format, factoryId)


# ---------------------------------------------------------------------------
# Sprint 11 MealClaw Response — LLM wrapper hook (Path B)
# ---------------------------------------------------------------------------

def _maybe_llm_wrap(body: Dict[str, Any], format: str, factory_id: str) -> Dict[str, Any]:
    """If ``format=llm``, transform the response into the strict 5-field whitelist.

    The completeness endpoint already returns a derived shape, so no Path B
    windowing is needed here (window is computed from factoryAgeDays). When
    overall coverage is 0 the LLM wrapper will still surface that via
    `dataAvailable.overall=False` and a recommendation list driven by per-module
    `missingHints`.
    """
    if format != "llm":
        return body

    from smartbi.services.restaurant_llm_formatter import to_llm_format
    # window_days is derived per-module above; surface the first module's window
    # (all modules share the same window).
    first_window = 30
    if body.get("modules"):
        first_window = int(body["modules"][0].get("windowDays") or 30)

    return to_llm_format(
        body,
        requested_days=first_window,
        actual_days=first_window,
        fallback=False,
        factory_id=factory_id,
        source_label="restaurant-completeness",
    )
