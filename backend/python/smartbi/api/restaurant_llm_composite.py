"""Sprint 11 MealClaw Response — composite LLM endpoint.

Merges 3 underlying restaurant endpoints into a single, LLM-friendly response:
- restaurant_completeness  (data completeness sentinel — Cretas differentiation #1)
- restaurant_ops_gold      (top ingredients / cost concentration)
- restaurant_ops_gold etl  (table freshness signal, optional)

Returns the strict 5-field whitelist via ``restaurant_llm_formatter.to_llm_format``.
Path B fallback (30d → 365d) is applied to top-ingredients per the PM decision
2026-05-22 (see dispatch log §3) because the cretas business tables are empty
for the recent 30d window (audit §6.2 finding); SmartBI Gold's 646K+ POS rows
for 2025 serve as the historical baseline.

URL: GET /api/smartbi/restaurant/llm-composite

Query:
- factory_id   — required, drives RLS + tenant scoping
- month        — optional, "YYYY-MM" for window labeling. Defaults to current month.
- days         — optional window override (default 30). Path B may widen to 365.

Response (strict whitelist):
    {
      "summary": str,
      "topItems": [...],
      "recommendations": [...],
      "evidence": { dataWindow: {requested, actual, fallback, fallbackReason}, source },
      "dataAvailable": { overall, posData, wastageData, consumptionData }
    }
"""
from __future__ import annotations

import asyncio
import logging
from datetime import date
from typing import Any, Dict, Optional

from fastapi import APIRouter, HTTPException, Query, Request

logger = logging.getLogger(__name__)
router = APIRouter(tags=["RestaurantLlmComposite"])

# Admin-tier roles that may query any factory (mirrors restaurant_completeness)
_ADMIN_ROLES = {"factory_super_admin", "platform_admin", "internal", "admin"}


def _get_request_factory(request: Request) -> str:
    return getattr(request.state, "factory_id", None) or ""


def _get_request_role(request: Request) -> Optional[str]:
    return getattr(request.state, "role", None)


@router.get("/restaurant/llm-composite")
async def restaurant_llm_composite(
    request: Request,
    factory_id: str = Query(..., description="Factory ID (tenant scope)"),
    month: Optional[str] = Query(None, description='"YYYY-MM" for summary text; defaults to current'),
    days: int = Query(30, ge=1, le=365, description="Rolling window for top items"),
    kpi_kind: str = Query("requisition_cost", description="requisition_qty | requisition_cost | wastage_cost"),
) -> Dict[str, Any]:
    """Composite restaurant overview, strict-whitelist response for LLM consumption."""
    # ── Auth gate (mirrors restaurant_completeness) ──────────────────────────
    role = _get_request_role(request)
    jwt_factory_id = _get_request_factory(request)
    if role is None:
        raise HTTPException(status_code=401, detail="未登录，请先认证")

    factory_id = (factory_id or "").strip()
    if not factory_id:
        raise HTTPException(status_code=400, detail="factory_id 不能为空")
    if len(factory_id) > 50:
        raise HTTPException(status_code=400, detail="factory_id 长度不能超过 50 字符")

    is_admin = role in _ADMIN_ROLES
    if not is_admin and jwt_factory_id != factory_id:
        raise HTTPException(
            status_code=403,
            detail=f"无权查询工厂 {factory_id!r} 的数据 (当前工厂 {jwt_factory_id!r})",
        )

    # Default month label for summary
    if not month:
        today = date.today()
        month = f"{today.year:04d}-{today.month:02d}"

    # ── Fetch underlying data concurrently ───────────────────────────────────
    # Pools / handlers are imported lazily to avoid circular imports at boot.
    raw_completeness: Dict[str, Any] = {}
    raw_top_items: Dict[str, Any] = {"success": False, "data": []}
    actual_days = days
    fallback = False

    async def _fetch_completeness() -> Dict[str, Any]:
        try:
            from smartbi.api.restaurant_completeness import (
                _factory_age_days,
                _fetch_module_stats,
                _coverage_window_days,
                _build_module_response,
                _EXPECTED_MODULE_IDS,
            )
            from smartbi.config import get_cretas_pool, get_pg_pool

            smartbi_pool = await get_pg_pool()
            cretas_pool = await get_cretas_pool()
            if smartbi_pool is None or cretas_pool is None:
                logger.warning("[llm-composite] completeness pools unavailable")
                return {}

            age_days = await _factory_age_days(factory_id, smartbi_pool, cretas_pool)
            window_days = _coverage_window_days(age_days)
            stats = await _fetch_module_stats(
                factory_id, window_days, cretas_pool, smartbi_pool
            )
            modules = [
                _build_module_response(
                    mid, stats.get(mid, {"count": 0, "last_updated": None}), window_days
                )
                for mid in _EXPECTED_MODULE_IDS
            ]
            overall = int(sum(m["coverage"] for m in modules) / len(modules))
            return {
                "factoryId": factory_id,
                "factoryAgeDays": age_days,
                "modules": modules,
                "overallCompleteness": overall,
            }
        except Exception as exc:
            logger.warning(f"[llm-composite] completeness fetch failed: {exc}")
            return {}

    async def _fetch_top_items(window_days: int):
        """Query agg_restaurant_daily_ops (aggregated by ETL)."""
        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()
        if pool is None:
            return None
        async with pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )
            return await conn.fetch(
                """
                SELECT i.ingredient_id, i.name, i.category, i.unit,
                       SUM(a.value_num)::NUMERIC(18,4) AS total_value
                  FROM agg_restaurant_daily_ops a
                  JOIN dim_ingredient i ON a.dim_value_id = i.ingredient_id
                 WHERE a.factory_id = $1
                   AND a.kpi_kind = $2
                   AND a.date >= CURRENT_DATE - ($3::int)
                 GROUP BY i.ingredient_id, i.name, i.category, i.unit
                 ORDER BY total_value DESC NULLS LAST
                 LIMIT 10
                """,
                factory_id, kpi_kind, window_days,
            )

    async def _fetch_top_pos_items(window_days: int):
        """Sprint 11 Round 2 Path B2 — POS-level fallback.

        When agg_restaurant_daily_ops is empty (ETL not run for this factory),
        query the raw fact_pos_item table directly. Per Round 1 evidence:
        RES_3101_009 has 646K+ POS rows in fact_pos_item but 0 rows in
        agg_restaurant_daily_ops, causing the original Path B fallback to
        return empty topItems despite advertising "365d historical data".

        Returns top-10 dishes by revenue from fact_pos_item.
        """
        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()
        if pool is None:
            return None
        async with pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )
            return await conn.fetch(
                """
                SELECT p.product_id, p.name, p.category, NULL::text AS unit,
                       SUM(i.amount)::NUMERIC(18,4) AS total_value
                  FROM fact_pos_item i
                  JOIN fact_pos_transaction t ON t.id = i.transaction_id
                  JOIN dim_product p ON p.product_id = i.product_id
                 WHERE i.factory_id = $1 AND t.factory_id = $1 AND p.factory_id = $1
                   AND t.date >= CURRENT_DATE - ($2::int)
                 GROUP BY p.product_id, p.name, p.category
                 ORDER BY total_value DESC NULLS LAST
                 LIMIT 10
                """,
                factory_id, window_days,
            )

    async def _fetch_top_items_with_path_b() -> Dict[str, Any]:
        """Path B + Path B2 fallback chain.

        1. Try aggregated 30d query (preferred — pre-aggregated, fast)
        2. Path B: widen to 365d on agg table
        3. Path B2 (Sprint 11 Round 2): when 365d agg ALSO empty, fall back to
           raw fact_pos_item (POS source) at 365d. This handles the case where
           ETL hasn't yet populated agg_restaurant_daily_ops for the factory
           but raw POS data is available.
        """
        nonlocal actual_days, fallback
        try:
            rows = await _fetch_top_items(days)
            if rows is None:
                return {"success": False, "data": [], "message": "db pool unavailable"}
            # Path B: widen 30d → 365d when initial window is empty
            from smartbi.services.restaurant_llm_formatter import (
                FALLBACK_WINDOW_DAYS,
            )
            if len(rows) == 0 and days < FALLBACK_WINDOW_DAYS:
                logger.info(
                    "[llm-composite] Path B fallback %dd → %dd for factory=%s",
                    days, FALLBACK_WINDOW_DAYS, factory_id,
                )
                rows = await _fetch_top_items(FALLBACK_WINDOW_DAYS)
                if rows is None:
                    rows = []
                actual_days = FALLBACK_WINDOW_DAYS
                fallback = True

                # Path B2 (Sprint 11 Round 2 fix): when agg table is ALSO empty
                # at 365d, fall back to raw POS source. This is the actual fix
                # for the Round 1 P1 finding where Round 1 found
                # fallback=true but topItems=[] despite 646K+ POS rows present.
                if len(rows) == 0:
                    logger.info(
                        "[llm-composite] Path B2 POS fallback (agg empty 365d) for factory=%s",
                        factory_id,
                    )
                    try:
                        pos_rows = await _fetch_top_pos_items(FALLBACK_WINDOW_DAYS)
                        if pos_rows:
                            items = [
                                {
                                    "ingredient_id": f"product:{r['product_id']}",
                                    "name": r["name"],
                                    "category": r["category"],
                                    "unit": r["unit"],
                                    "value": float(r["total_value"]) if r["total_value"] is not None else 0.0,
                                    "rank": idx + 1,
                                }
                                for idx, r in enumerate(pos_rows)
                            ]
                            logger.info(
                                "[llm-composite] Path B2 returned %d POS items for factory=%s",
                                len(items), factory_id,
                            )
                            # Note: _pathB2 stripped by WHITELIST in formatter — kept
                            # in upstream raw dict for evidence-builder source labeling.
                            return {"success": True, "data": items, "_pathB2": True}
                    except Exception as pos_exc:
                        logger.warning(
                            "[llm-composite] Path B2 POS fallback failed for factory=%s: %s",
                            factory_id, pos_exc,
                        )
                        # Fall through to empty agg result rather than 500.

            items = [
                {
                    "ingredient_id": r["ingredient_id"],
                    "name": r["name"],
                    "category": r["category"],
                    "unit": r["unit"],
                    "value": float(r["total_value"]) if r["total_value"] is not None else 0.0,
                    "rank": idx + 1,
                }
                for idx, r in enumerate(rows)
            ]
            return {"success": True, "data": items}
        except Exception as exc:
            logger.warning(f"[llm-composite] top-items fetch failed: {exc}")
            return {"success": False, "data": [], "message": str(exc)}

    raw_completeness, raw_top_items = await asyncio.gather(
        _fetch_completeness(),
        _fetch_top_items_with_path_b(),
    )

    # ── Merge into a single LLM-friendly response ────────────────────────────
    from smartbi.services.restaurant_llm_formatter import (
        _build_evidence,
        _build_recommendations,
        _build_summary,
        _check_data_available,
        _extract_top_items,
        WHITELIST,
    )

    # Compose a "merged" raw dict the formatter helpers can read.
    merged: Dict[str, Any] = {
        "success": True,
        # top-ingredients shape used by _extract_top_items
        "data": raw_top_items.get("data", []) or [],
    }
    # Surface completeness modules so _build_recommendations / dataAvailable see
    # per-module signals
    if raw_completeness:
        merged["modules"] = raw_completeness.get("modules", [])
        merged["overallCompleteness"] = raw_completeness.get(
            "overallCompleteness", 0
        )

    data_available = _check_data_available(merged)
    # Override with completeness-derived availability when we have it (more accurate)
    if raw_completeness:
        # _check_data_available already inspects modules; this is a no-op safety
        pass

    # Sprint 11 Round 2: when Path B2 (POS fallback) fired, the raw POS source
    # IS available — surface posData=True so the LLM understands historical data
    # is present even though aggregation hasn't run.
    used_path_b2 = bool(raw_top_items.get("_pathB2"))
    if used_path_b2:
        data_available["posData"] = True
        # overall reflects whether ANY data is now available
        data_available["overall"] = True

    summary_text = _build_summary(
        merged,
        requested_days=days,
        actual_days=actual_days,
        fallback=fallback,
        data_available=data_available,
        factory_id=factory_id,
    )
    # Prefix month label to make the summary self-describing
    summary_text = f"{factory_id} {month} 餐饮经营 AI 概览: {summary_text}"
    # Sprint 11 Round 2: when Path B2 fired, append explicit source note so the
    # LLM does not pretend the data is from the agg layer.
    if used_path_b2:
        summary_text = (
            f"{summary_text} (注: 数据源 fact_pos_item 原始 POS, "
            f"agg_restaurant_daily_ops ETL 尚未为本工厂运行)"
        )

    # Source label reflects which path served the data.
    source_label = "restaurant-llm-composite-pos-source" if used_path_b2 \
        else "restaurant-llm-composite"

    body = {
        "summary": summary_text,
        "topItems": _extract_top_items(merged),
        "recommendations": _build_recommendations(
            merged, data_available, fallback, days
        ),
        "evidence": _build_evidence(
            merged, days, actual_days, fallback, source_label=source_label
        ),
        "dataAvailable": data_available,
    }

    # Defensive: enforce whitelist
    return {k: v for k, v in body.items() if k in WHITELIST}
