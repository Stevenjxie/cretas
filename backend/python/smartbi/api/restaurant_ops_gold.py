"""Restaurant daily ops Gold read API + ETL trigger endpoint.

Plan C Phase 3. Exposes:
- POST /api/smartbi/restaurant-ops/etl         → trigger ETL run for current factory
- GET  /api/smartbi/restaurant-ops/top-ingredients → Top N ingredients by qty or cost
- GET  /api/smartbi/restaurant-ops/daily-trend    → trend series for kpi_kind

All endpoints are tenant-scoped (factory_id from JWT via auth middleware) and
read from Gold agg tables for sub-100ms response times.

Sprint 11 MealClaw Response (2026-05-22):
- Added `?format=llm` query param on /etl + /top-ingredients (default off,
  backward-compatible). When format=llm, returns the strict 5-field whitelist
  shape from smartbi.services.restaurant_llm_formatter — used by the AI Chat
  layer to feed clean context into the LLM without metadata leaks.
- Path B windowing: top-ingredients auto-fallbacks 30d → 365d when empty, with
  a fallback flag in the LLM `evidence` block. Existing JSON shape unchanged
  for non-LLM callers.
"""
from __future__ import annotations

import logging
from typing import Any, Dict, Optional

from fastapi import APIRouter, Query, Request

from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES, strip_price_for_role

logger = logging.getLogger(__name__)
router = APIRouter(tags=["RestaurantOpsGold"])


# Restaurant-ops money keys not matched by the shared ``_MONEY_PATTERN``. The
# shared regex catches ``revenue`` / ``revenueMedian`` / ``medianRevenue`` (all
# contain the substring 'revenue') but NOT the camelCase ``avgTicket`` (客单价,
# 元/单) — unambiguously monetary, so strip it here as a thin local extension.
# Mirror of gold_reads._GOLD_EXTRA_MONEY_KEYS (which added weekdayAvg/weekendAvg
# for the same reason on the /trend-bundle shape).
_RESTAURANT_OPS_EXTRA_MONEY_KEYS: frozenset[str] = frozenset({
    "avgTicket",
})


def _get_factory_id(request: Request) -> Optional[str]:
    """Extract factory_id set by auth middleware."""
    return getattr(request.state, "factory_id", None)


def _get_role(request: Request) -> Optional[str]:
    """Role string set by JWTAuthMiddleware (auth_middleware.py).

    Mirrors gold_reads._get_role — ``_get_factory_id`` above reads
    ``request.state.factory_id`` from the same middleware, so the role lives at
    ``request.state.role``.
    """
    return getattr(request.state, "role", None)


def _strip_ops_extras(node: Any) -> None:
    """Depth-first null of restaurant-ops money keys the shared regex misses."""
    if isinstance(node, dict):
        for k, v in list(node.items()):
            if k in _RESTAURANT_OPS_EXTRA_MONEY_KEYS and not isinstance(v, (dict, list)):
                node[k] = None
            else:
                _strip_ops_extras(v)
    elif isinstance(node, list):
        for item in node:
            _strip_ops_extras(item)


def _apply_rbac_strip(data: Any, role: Optional[str]) -> Any:
    """Null monetary fields in ``data`` for roles outside PRICE_VIEW_ROLES.

    Walks the inner ``data`` payload (NOT the ``{success, data}`` envelope) so
    the standard wrapper keys survive. Whitelisted roles get the raw payload.
    Returns the same ``data`` reference for caller convenience.
    """
    if role and role in PRICE_VIEW_ROLES:
        return data
    if data is None:
        return data
    strip_price_for_role(data, role)
    _strip_ops_extras(data)
    return data


@router.post("/restaurant-ops/etl")
async def trigger_etl(
    request: Request,
    format: str = Query("default", description="`default` | `llm` (Sprint 11 LLM wrapper)"),
) -> Dict[str, Any]:
    """Run the full ETL pipeline for the current tenant.

    Intended for admin / on-demand refresh. Production should run a cron job
    that iterates over active factories and calls the underlying function
    directly; this endpoint is for dev + manual triggers.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    try:
        import asyncpg
        from config import get_settings
        from smartbi.config import get_pg_pool
        from smartbi.gold.restaurant_ops_etl import run_full_etl

        smartbi_pool = await get_pg_pool()
        if smartbi_pool is None:
            return {"success": False, "message": "smartbi_db pool unavailable"}

        settings = get_settings()
        cretas_pool = await asyncpg.create_pool(
            settings.food_kb_db_url, min_size=1, max_size=3, command_timeout=60,
        )
        try:
            stats = await run_full_etl(cretas_pool, smartbi_pool, factory_id)
        finally:
            await cretas_pool.close()

        raw_response = {
            "success": len(stats.errors) == 0,
            "data": {
                "dim_ingredient_upserted": stats.dim_ingredient_upserted,
                "fact_requisition_upserted": stats.fact_requisition_upserted,
                "fact_wastage_upserted": stats.fact_wastage_upserted,
                "fact_recipe_upserted": stats.fact_recipe_upserted,
                "fact_stocktaking_upserted": stats.fact_stocktaking_upserted,
                "agg_daily_ops_upserted": stats.agg_daily_ops_upserted,
                "agg_daily_totals_upserted": stats.agg_daily_totals_upserted,
                "agg_product_cost_upserted": stats.agg_product_cost_upserted,
                "errors": stats.errors,
            },
        }
        if format == "llm":
            from smartbi.services.restaurant_llm_formatter import to_llm_format
            return to_llm_format(
                raw_response,
                requested_days=1,  # ETL is a point-in-time op, not a windowed query
                actual_days=1,
                fallback=False,
                factory_id=factory_id,
                source_label="restaurant-ops-etl",
            )
        return raw_response
    except Exception as e:
        logger.exception("[etl-endpoint] failed for %s", factory_id)
        err = {"success": False, "message": f"ETL failed: {e}"}
        if format == "llm":
            from smartbi.services.restaurant_llm_formatter import to_llm_format
            return to_llm_format(
                err, requested_days=1, actual_days=1, fallback=False,
                factory_id=factory_id, source_label="restaurant-ops-etl",
            )
        return err


@router.get("/restaurant-ops/top-ingredients")
async def top_ingredients(
    request: Request,
    kpi_kind: str = Query("requisition_cost", description="requisition_qty | requisition_cost | wastage_cost"),
    top_n: int = Query(10, ge=1, le=100),
    days: int = Query(30, ge=1, le=365, description="Rolling window"),
    format: str = Query("default", description="`default` | `llm` (Sprint 11 LLM wrapper)"),
) -> Dict[str, Any]:
    """Top N ingredients by a given KPI in the last N days.

    Returns [{ingredient_id, name, category, value, rank}].

    Sprint 11 MealClaw Response — Path B:
    When format=llm and the requested window returns zero rows, the endpoint
    automatically widens to 365d (SmartBI Gold has 646K+ rows of 2025 POS data
    per BI subagent #1) and surfaces the fallback explicitly via the LLM
    `evidence` block.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        err = {"success": False, "message": "missing factory context"}
        if format == "llm":
            from smartbi.services.restaurant_llm_formatter import to_llm_format
            return to_llm_format(
                err, requested_days=days, actual_days=days,
                fallback=False, factory_id=factory_id,
            )
        return err

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        err = {"success": False, "message": "db pool unavailable"}
        if format == "llm":
            from smartbi.services.restaurant_llm_formatter import to_llm_format
            return to_llm_format(
                err, requested_days=days, actual_days=days,
                fallback=False, factory_id=factory_id,
            )
        return err

    async def _query(window_days: int):
        async with pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
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
                 LIMIT $4
                """,
                factory_id, kpi_kind, window_days, top_n,
            )

    rows = await _query(days)
    actual_days = days
    fallback = False

    # Path B: when LLM-facing AND the requested window has no data, widen to 365d
    # to surface the SmartBI Gold historical baseline. Default (non-LLM) callers
    # see the original empty result so dashboards don't silently rewrite the
    # window the user asked for.
    from smartbi.services.restaurant_llm_formatter import (
        FALLBACK_WINDOW_DAYS,
    )
    if format == "llm" and len(rows) == 0 and days < FALLBACK_WINDOW_DAYS:
        logger.info(
            "[top-ingredients] Path B fallback %dd → %dd for factory=%s kpi=%s",
            days, FALLBACK_WINDOW_DAYS, factory_id, kpi_kind,
        )
        rows = await _query(FALLBACK_WINDOW_DAYS)
        actual_days = FALLBACK_WINDOW_DAYS
        fallback = True

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
    raw_response = {"success": True, "data": items}

    if format == "llm":
        from smartbi.services.restaurant_llm_formatter import to_llm_format
        return to_llm_format(
            raw_response,
            requested_days=days,
            actual_days=actual_days,
            fallback=fallback,
            factory_id=factory_id,
            source_label="smartbi-gold-top-ingredients",
        )
    return raw_response


@router.get("/restaurant-ops/daily-trend")
async def daily_trend(
    request: Request,
    kpi_kind: str = Query("requisition_cost"),
    days: int = Query(30, ge=1, le=365),
) -> Dict[str, Any]:
    """Daily time series of a scalar KPI.

    Returns [{date, value}]. Dates with no data are skipped (not zero-filled —
    callers that need continuous series should zero-fill themselves).
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "db pool unavailable"}

    # Map friendly kpi_kind to totals column name
    col_map = {
        "requisition_qty":   "requisition_qty_total",
        "requisition_cost":  "requisition_cost_total",
        "requisition_count": "requisition_count",
        "wastage_qty":       "wastage_qty_total",
        "wastage_cost":      "wastage_cost_total",
        "stocktaking_shortage": "stocktaking_shortage_total",
    }
    col = col_map.get(kpi_kind)
    if not col:
        return {"success": False, "message": f"unsupported kpi_kind: {kpi_kind}"}

    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        # Safe to interpolate col since it's from a whitelist
        rows = await conn.fetch(
            f"""
            SELECT date, {col} AS value
              FROM agg_restaurant_daily_totals
             WHERE factory_id = $1
               AND date >= CURRENT_DATE - ($2::int)
             ORDER BY date
            """,
            factory_id, days,
        )
    items = [
        {"date": r["date"].isoformat(), "value": float(r["value"]) if r["value"] is not None else 0.0}
        for r in rows
    ]
    return {"success": True, "data": items}


@router.get("/restaurant-ops/gross-margin")
async def gross_margin(request: Request, days: int = Query(30, ge=1, le=365)) -> Dict[str, Any]:
    """Per-dish gross margin breakdown for the dedicated dashboard page.

    Reuses resolve_gross_margin but returns the full per-dish list (not just top N)
    in a structured format the FE page can sort/filter/export.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    from smartbi.config import get_pg_pool
    from smartbi.gold.restaurant_ops_router import resolve_gross_margin
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "db pool unavailable"}

    try:
        ans = await resolve_gross_margin(pool, factory_id, days=days, top_n=500)  # noqa: F841
    except Exception as e:
        logger.exception("[gross-margin] resolver failed")
        return {"success": False, "message": f"compute failed: {e}"}

    # Resolver returns enriched[] via .answer_text string; to avoid re-parsing,
    # recompute the per-dish dict here directly from the same join logic (thin wrapper).
    # For cleanliness: extend resolver to return structured rows.
    # For now: call back into the DB to build the structured list:
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        pos_rows = await conn.fetch(
            """
            SELECT p.name AS dish_name, p.normalized_name,
                   SUM(i.qty)::float AS qty,
                   SUM(i.amount)::float AS revenue,
                   COUNT(DISTINCT i.transaction_id)::int AS bills
              FROM fact_pos_item i
              JOIN fact_pos_transaction t ON t.id = i.transaction_id
              JOIN dim_product p ON p.product_id = i.product_id
             WHERE i.factory_id = $1 AND t.factory_id = $1 AND p.factory_id = $1
               AND t.date >= CURRENT_DATE - ($2::int)
             GROUP BY p.name, p.normalized_name
             ORDER BY revenue DESC NULLS LAST
            """,
            factory_id, days,
        )

    # Name match + alias fallback — see restaurant_ops_router.resolve_gross_margin for details.
    # P1-5 also loads excluded dish list to drop noise from analysis.
    normalized_names = list({r["normalized_name"] for r in pos_rows})
    cretas_map: Dict[str, str] = {}
    excluded_set: set = set()
    if normalized_names:
        try:
            import asyncpg as _asyncpg
            from config import get_settings as _get_settings
            cretas_url = _get_settings().food_kb_db_url
            cretas = await _asyncpg.connect(cretas_url)
            try:
                name_rows = await cretas.fetch(
                    "SELECT id, name FROM product_types WHERE factory_id = $1 AND name = ANY($2::text[])",
                    factory_id, normalized_names,
                )
                for r in name_rows:
                    cretas_map[r["name"]] = r["id"]
                # P0-2 alias fallback
                unmapped = [n for n in normalized_names if n not in cretas_map]
                if unmapped:
                    try:
                        alias_rows = await cretas.fetch(
                            """SELECT pos_name, product_type_id FROM dim_product_alias
                                WHERE factory_id = $1 AND pos_name = ANY($2::text[])""",
                            factory_id, unmapped,
                        )
                        for r in alias_rows:
                            cretas_map[r["pos_name"]] = r["product_type_id"]
                    except Exception as e:
                        if "does not exist" not in str(e):
                            logger.warning(f"[gross-margin] alias lookup failed: {e}")
                # P1-5 excluded dishes (noise — packaging / utensil / ads)
                try:
                    ex_rows = await cretas.fetch(
                        "SELECT pos_name FROM dim_product_excluded WHERE factory_id = $1",
                        factory_id,
                    )
                    excluded_set = {r["pos_name"] for r in ex_rows}
                except Exception as e:
                    if "does not exist" not in str(e):
                        logger.warning(f"[gross-margin] excluded lookup failed: {e}")
            finally:
                await cretas.close()
        except Exception as e:
            logger.warning(f"[gross-margin] cretas lookup failed: {e}")

    # Filter out excluded dishes from pos_rows before margin calc
    if excluded_set:
        pos_rows = [r for r in pos_rows if r["dish_name"] not in excluded_set]

    cost_map: Dict[str, float] = {}
    if cretas_map:
        async with pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
            cost_rows = await conn.fetch(
                """
                SELECT product_source_pk, food_cost::float AS food_cost
                  FROM agg_restaurant_product_cost
                 WHERE factory_id = $1 AND product_source_pk = ANY($2::text[])
                """,
                factory_id, list(cretas_map.values()),
            )
            cost_map = {r["product_source_pk"]: r["food_cost"] for r in cost_rows}

    # P2-7 加速 E: industry default cost ratio fallback for dishes without recipes.
    # Customer sees "估算毛利" (灰色 tag) instantly after upload; recipes overwrite later.
    INDUSTRY_DEFAULT_COST_RATIO = {
        "RESTAURANT_CHUAN": 0.35,    # 川菜
        "RESTAURANT_HOTPOT": 0.28,   # 火锅
        "RESTAURANT_FASTFOOD": 0.25,  # 快餐
        "RESTAURANT_WESTERN": 0.30,  # 西餐
        "RESTAURANT_NOODLES": 0.30,  # 面食
        "RESTAURANT_JAPANESE": 0.40,  # 日料 (食材贵)
        "RESTAURANT_CANTONESE": 0.32,
        "DEFAULT": 0.32,
    }
    industry_cost_ratio = INDUSTRY_DEFAULT_COST_RATIO["DEFAULT"]

    dishes = []
    total_rev_all = 0.0         # all dishes (for display "总营收")
    total_rev_with_cost = 0.0   # dishes with recipes (for avgRate denominator)
    total_profit = 0.0
    total_rev_estimated = 0.0   # 估算 (无配方) 部分营收
    total_profit_estimated = 0.0  # 估算 profit
    for r in pos_rows:
        source_pk = cretas_map.get(r["normalized_name"])
        food_cost = cost_map.get(source_pk, 0) if source_pk else 0
        total_cost = food_cost * r["qty"]
        gp = r["revenue"] - total_cost
        rate = gp / r["revenue"] if r["revenue"] > 0 else 0
        has_cost = food_cost > 0
        # 加速 E: when no recipe, use industry default to give estimated margin
        if not has_cost:
            est_cost_total = r["revenue"] * industry_cost_ratio
            est_gp = r["revenue"] - est_cost_total
            est_rate = 1.0 - industry_cost_ratio  # noqa: F841
            total_rev_estimated += r["revenue"]
            total_profit_estimated += est_gp
        dishes.append({
            "name": r["dish_name"],
            "qty": r["qty"],
            "revenue": r["revenue"],
            "foodCostUnit": food_cost,
            "totalCost": total_cost if has_cost else round(r["revenue"] * industry_cost_ratio, 2),
            "grossProfit": gp if has_cost else round(r["revenue"] * (1 - industry_cost_ratio), 2),
            "marginRate": rate if has_cost else (1 - industry_cost_ratio),
            "bills": r["bills"],
            "hasCost": has_cost,
            "isEstimated": not has_cost,
        })
        total_rev_all += r["revenue"]
        if has_cost:
            total_rev_with_cost += r["revenue"]
            total_profit += gp

    # avgRate 用 "只算有配方菜" 分母, 避免 403 无配方菜稀释真实毛利率.
    avg_rate = total_profit / total_rev_with_cost if total_rev_with_cost > 0 else 0
    dishes_with_cost = sum(1 for d in dishes if d["hasCost"])
    coverage_revenue = total_rev_with_cost / total_rev_all if total_rev_all > 0 else 0

    # 加速 E: 估算 totals 包含无配方菜 (按行业默认成本率), 客户立即看全菜估算毛利
    total_profit_combined = total_profit + total_profit_estimated  # 精确 + 估算
    avg_rate_combined = total_profit_combined / total_rev_all if total_rev_all > 0 else 0

    data = {
        "windowDays": days,
        "totalRevenue": total_rev_all,
        "totalRevenueWithCost": total_rev_with_cost,
        "totalProfit": total_profit,
        "avgRate": avg_rate,
        # 加速 E 新字段: 精确+估算 合并版 (FE 可选切换显示)
        "totalProfitWithEstimated": total_profit_combined,
        "avgRateWithEstimated": avg_rate_combined,
        "industryDefaultCostRatio": industry_cost_ratio,
        "coverage": {
            "dishCount": dishes_with_cost,
            "totalDishCount": len(dishes),
            "revenueRatio": coverage_revenue,
        },
        "dishes": dishes,
    }
    # RBAC: revenue / profit / cost 等金额字段对非 price-view 角色剥零 (pre-existing leak fix)。
    # 比率 (avgRate / marginRate) 与计数 (dishCount) 非绝对金额, 但共享 _MONEY_PATTERN 会
    # 过度匹配含 cost/revenue 子串的计数键 (如 dishCount 不含; revenueRatio/dishesWithCost 含) —
    # 过度剥零是保守安全行为 (counts 非泄露), 不影响金额脱敏正确性。
    _apply_rbac_strip(data, _get_role(request))
    return {"success": True, "data": data}


def _parse_opt_date(s: Optional[str], field: str) -> Optional[Any]:
    """Parse an optional YYYY-MM-DD query param → date | None.

    None / empty → None (= all-history on that bound). Bad format → None too
    (honest empty window rather than 500); callers that need strict parsing
    use the gold_reads._parse_range path.
    """
    if not s:
        return None
    from datetime import datetime as _dt
    try:
        return _dt.strptime(s, "%Y-%m-%d").date()
    except ValueError:
        logger.warning("[restaurant-ops] bad %s=%r, treating as open bound", field, s)
        return None


@router.get("/restaurant-ops/menu-quadrant")
async def menu_quadrant_endpoint(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
) -> Dict[str, Any]:
    """菜品四象限 (收入模式) — classify dishes by sales volume × revenue.

    Returns {items:[{name, qty, revenue, quadrant}], qtyMedian, revenueMedian}.
    Dates optional (省略 → 全部历史). Honest empty: no dishes → items=[].
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    from smartbi.config import get_pg_pool
    from smartbi.gold.queries import menu_quadrant as _menu_quadrant
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "db pool unavailable"}

    start = _parse_opt_date(start_date, "start_date")
    end = _parse_opt_date(end_date, "end_date")
    try:
        data = await _menu_quadrant(pool, factory_id, (start, end))
    except Exception as e:
        logger.exception("[menu-quadrant] failed for %s", factory_id)
        return {"success": False, "message": f"compute failed: {e}"}
    # RBAC: revenue / revenueMedian 等金额字段对非 price-view 角色剥零。
    # qty / quadrant / name / qtyMedian 非金额, 保留。
    _apply_rbac_strip(data, _get_role(request))
    return {"success": True, "data": data}


@router.get("/restaurant-ops/store-comparison")
async def store_comparison_endpoint(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
) -> Dict[str, Any]:
    """门店对比 — per-store revenue / 单量 / 客单价 / 折扣率 + 低于中位营收的弱店列表。

    Returns {stores:[{name, revenue, orderCount, avgTicket, discountPct}],
    medianRevenue, weakStores:[name,...]}. Dates optional (省略 → 全部历史)。
    Honest empty: no stores → stores=[]。
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    from smartbi.config import get_pg_pool
    from smartbi.gold.queries import store_comparison as _store_comparison
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "db pool unavailable"}

    start = _parse_opt_date(start_date, "start_date")
    end = _parse_opt_date(end_date, "end_date")
    try:
        data = await _store_comparison(pool, factory_id, (start, end))
    except Exception as e:
        logger.exception("[store-comparison] failed for %s", factory_id)
        return {"success": False, "message": f"compute failed: {e}"}
    # RBAC: revenue / medianRevenue / avgTicket 等绝对金额字段对非 price-view 角色剥零。
    # orderCount / name / weakStores(门店名) 非金额, 保留。
    # discountPct (折扣率) 是百分比"率"非绝对金额 — 与本文件 store-margin 的 marginRate /
    # 共享 _rbac_strip 的 changeRate/completionRate 同属"率"类, 按既有约定保留可见
    # (它只暴露"折掉营业额的百分之几", 不泄露折扣额/营业额本身的绝对值)。整页前端已
    # 门控 canViewPrice, 此处仅 belt-and-suspenders。_MONEY_PATTERN 不匹配 'discountPct'
    # (不含任何金额 token), 因此 strip 天然不会误剥它。
    _apply_rbac_strip(data, _get_role(request))
    return {"success": True, "data": data}


@router.get("/restaurant-ops/store-margin")
async def store_margin(request: Request, days: int = Query(30, ge=1, le=365)) -> Dict[str, Any]:
    """Per-store margin breakdown for store-comparison page."""
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}
    from smartbi.config import get_pg_pool
    from smartbi.gold.restaurant_ops_router import resolve_store_margin
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "db pool unavailable"}
    try:
        # Forward the caller's role: resolve_store_margin gates margin visibility on
        # role ∈ PRICE_VIEW_ROLES (which includes factory_super_admin). Without this,
        # role defaulted to None → bool(None) False → EVERY caller (incl. super-admin)
        # got the masked {"rbac_masked": True} payload, so 门店对比's 毛利率 column was
        # silently blank for authorized roles.
        ans = await resolve_store_margin(pool, factory_id, days=days, top_n=100,
                                         role=_get_role(request))
    except Exception as e:
        logger.exception("[store-margin] failed")
        return {"success": False, "message": str(e)}
    # RBAC: totalRevenue / totalProfit / stores[].revenue / grossProfit 等金额对非
    # price-view 角色剥零 (pre-existing leak fix)。marginRate (比率) 保留;
    # dishesWithCost (计数) 因含 'cost' 子串会被保守剥零, 不影响金额脱敏正确性。
    data = ans.meta
    _apply_rbac_strip(data, _get_role(request))
    return {"success": True, "data": data}


@router.get("/restaurant-ops/summary")
async def summary(request: Request, days: int = Query(30, ge=1, le=365)) -> Dict[str, Any]:
    """Combined summary for a factory — total requisition cost, top 5 ingredients, etc.

    Single endpoint used by Dashboard strip + AIQuery fast-path.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "db pool unavailable"}

    # Apr 24 2026: graceful fallback if aggregation tables not yet provisioned
    # for this factory (Plan C Silver 层 migration pending). Return empty totals
    # so FE sees 0 KPI instead of 500 error.
    import asyncpg as _asyncpg
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        try:
            totals = await conn.fetchrow(
                """
                SELECT
                    COALESCE(SUM(requisition_count), 0)::int             AS total_requisitions,
                    COALESCE(SUM(requisition_qty_total), 0)::float       AS total_req_qty,
                    COALESCE(SUM(requisition_cost_total), 0)::float      AS total_req_cost,
                    COALESCE(SUM(wastage_count), 0)::int                 AS total_wastage,
                    COALESCE(SUM(wastage_qty_total), 0)::float           AS total_wastage_qty,
                    COALESCE(SUM(wastage_cost_total), 0)::float          AS total_wastage_cost,
                    COALESCE(SUM(stocktaking_count), 0)::int             AS total_stocktaking,
                    COALESCE(SUM(stocktaking_shortage_total), 0)::float  AS total_shortage,
                    COALESCE(SUM(stocktaking_surplus_total), 0)::float   AS total_surplus,
                    COUNT(DISTINCT date)                                 AS active_days
                  FROM agg_restaurant_daily_totals
                 WHERE factory_id = $1
                   AND date >= CURRENT_DATE - ($2::int)
                """,
                factory_id, days,
            )
            top5 = await conn.fetch(
                """
                SELECT i.name, i.category,
                       SUM(a.value_num)::float AS cost
                  FROM agg_restaurant_daily_ops a
                  JOIN dim_ingredient i ON a.dim_value_id = i.ingredient_id
                 WHERE a.factory_id = $1 AND a.kpi_kind = 'requisition_cost'
                   AND a.date >= CURRENT_DATE - ($2::int)
                 GROUP BY i.name, i.category
                 ORDER BY cost DESC NULLS LAST
                 LIMIT 5
                """,
                factory_id, days,
            )
        except _asyncpg.exceptions.UndefinedTableError as e:
            logger.warning(f"[summary] agg table not yet provisioned for {factory_id}: {e}")
            empty_data = {
                "window_days": days,
                "totals": {},
                "top5_ingredients": [],
                "margin": {
                    "total_pos_revenue": 0.0, "total_gross_profit": 0.0,
                    "avg_margin_rate": 0.0,
                    "dish_count_with_cost": 0, "total_dish_count": 0,
                },
                "etl_pending": True,
            }
            # RBAC strip even on empty payload (idempotent; nulls the 0.0 money fields).
            _apply_rbac_strip(empty_data, _get_role(request))
            return {"success": True, "data": empty_data}

    # Apr 24 Plan C Phase 7+: compute gross margin totals by reusing the
    # resolver. Wraps the same POS × food_cost join already used by AI query.
    # Returns zeros gracefully if POS or recipe data is missing.
    margin_totals = {
        "total_pos_revenue": 0.0,
        "total_gross_profit": 0.0,
        "avg_margin_rate": 0.0,
        "dish_count_with_cost": 0,
        "total_dish_count": 0,
    }
    try:
        from smartbi.gold.restaurant_ops_router import resolve_gross_margin
        margin_ans = await resolve_gross_margin(pool, factory_id, days=days, top_n=100)
        if margin_ans.kpis:
            for kpi in margin_ans.kpis:
                if kpi["title"] == "总营收":
                    margin_totals["total_pos_revenue"] = float(kpi["rawValue"]) if kpi["rawValue"] else 0.0
                elif kpi["title"] == "总毛利":
                    margin_totals["total_gross_profit"] = float(kpi["rawValue"]) if kpi["rawValue"] else 0.0
                elif kpi["title"] == "平均毛利率":
                    margin_totals["avg_margin_rate"] = float(kpi["rawValue"]) if kpi["rawValue"] else 0.0
        meta = margin_ans.meta or {}
        margin_totals["total_dish_count"] = meta.get("total_dishes", 0)
        margin_totals["dish_count_with_cost"] = (
            meta.get("total_dishes", 0) - meta.get("missing_cost_count", 0)
        )
    except Exception as e:
        logger.warning(f"[summary] gross margin compute failed: {e}")

    data = {
        "window_days": days,
        "totals": dict(totals) if totals else {},
        "top5_ingredients": [
            {"name": r["name"], "category": r["category"], "cost": r["cost"]}
            for r in top5
        ],
        "margin": margin_totals,
    }
    # RBAC: totals.*_cost / top5_ingredients[].cost / margin.total_pos_revenue /
    # total_gross_profit 等金额字段对非 price-view 角色剥零 (pre-existing leak fix)。
    # _qty / _count / *_rate 等非绝对金额保留 (avg_margin_rate 是比率)。
    _apply_rbac_strip(data, _get_role(request))
    return {"success": True, "data": data}
