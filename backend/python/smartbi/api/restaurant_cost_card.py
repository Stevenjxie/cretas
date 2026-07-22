"""#57 成本卡/出菜反推 — 菜品食材成本读 API (gold 缓存)。

GET /api/smartbi/restaurant/dish-cost-card/{product_id}
    读 agg_restaurant_product_cost (gold 缓存, by factory_id + product_source_pk)
    返回 food_cost / ingredient_count / has_price_data / staleness。

权威成本卡 (逐料拆解 + 毛利率) 由 Java REST 实时算
(GET /api/mobile/{factoryId}/restaurant/dishes/{productTypeId}/cost-card)。
本 Python 端点提供 gold 缓存的快速食材成本读 (sub-100ms, dashboard / SmartBI 用),
不含逐料明细。

RBAC: 非金额角色 (PRICE_VIEW_ROLES 之外) → foodCost null, 计数/标志保留。
诚实空态: 无缓存行 → success:true, data:null, message "暂无成本缓存" (不是 500)。
缺 factory context → success:false。
"""
from __future__ import annotations

import logging
from typing import Any, Optional

from fastapi import APIRouter, Request

from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES, strip_price_for_role

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/restaurant", tags=["RestaurantCostCard"])


# camelCase 金额键 (通用 _MONEY_PATTERN 不含 'cost' token, 显式列出)。
_COST_MONEY_KEYS: frozenset[str] = frozenset({"foodCost"})


def _get_factory_id(request: Request) -> Optional[str]:
    return getattr(request.state, "factory_id", None)


def _get_role(request: Request) -> Optional[str]:
    return getattr(request.state, "role", None)


def _strip_cost_amounts(node: Any) -> None:
    """Depth-first null of cost money keys the shared regex misses."""
    if isinstance(node, dict):
        for k, v in list(node.items()):
            if k in _COST_MONEY_KEYS and not isinstance(v, (dict, list)):
                node[k] = None
            else:
                _strip_cost_amounts(v)
    elif isinstance(node, list):
        for item in node:
            _strip_cost_amounts(item)


def _apply_rbac_strip(data: Any, role: Optional[str]) -> Any:
    if role and role in PRICE_VIEW_ROLES:
        return data
    if data is None:
        return data
    strip_price_for_role(data, role)
    _strip_cost_amounts(data)
    return data


@router.get("/dish-cost-card/{product_id}")
async def dish_cost_card(product_id: str, request: Request) -> dict[str, Any]:
    """Gold-cached food cost for one dish (by product_source_pk = product_types.id)."""
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "data": None, "message": "missing factory context"}

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "data": None, "message": "db pool unavailable"}

    try:
        async with pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
            row = await conn.fetchrow(
                """
                SELECT product_source_pk,
                       food_cost::float           AS food_cost,
                       ingredient_count,
                       has_price_data,
                       computed_at,
                       last_recipe_updated_at
                  FROM agg_restaurant_product_cost
                 WHERE factory_id = $1 AND product_source_pk = $2
                """,
                factory_id, product_id,
            )
    except Exception as e:
        logger.exception("[dish-cost-card] query failed")
        return {"success": False, "data": None, "message": f"query failed: {e}"}

    if row is None:
        return {
            "success": True,
            "data": None,
            "message": "暂无成本缓存，请先在「配方管理」配置该菜品配方，或调用 Java 实时成本卡接口。",
        }

    # stale = 配方比缓存新 (last_recipe_updated_at > computed_at)
    stale = False
    if row["last_recipe_updated_at"] is not None and row["computed_at"] is not None:
        stale = row["last_recipe_updated_at"] > row["computed_at"]

    data = {
        "productSourcePk": row["product_source_pk"],
        "foodCost": row["food_cost"],
        "ingredientCount": row["ingredient_count"],
        # NOTE: 用 dataComplete (而非 hasPriceData/costComplete) 命名 — 后两者含
        # "price"/"cost" 会被 _rbac_strip._MONEY_PATTERN 误剥成 null。这是个布尔标志
        # (全部食材都已配单价 → True), 非金额, 不应受价权门控。
        "dataComplete": row["has_price_data"],
        "stale": stale,
        "computedAt": row["computed_at"].isoformat() if row["computed_at"] is not None else None,
        "lastRecipeUpdatedAt": (
            row["last_recipe_updated_at"].isoformat()
            if row["last_recipe_updated_at"] is not None else None
        ),
    }
    _apply_rbac_strip(data, _get_role(request))

    return {"success": True, "data": data, "message": "ok"}
