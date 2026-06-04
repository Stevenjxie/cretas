"""#56 价值可视化回馈回路 — 价值快照 API。

GET  /api/smartbi/restaurant-value/value-summary — 返回最新快照 {month, annual} 两口径 (D3)。
POST /api/smartbi/restaurant-value/refresh        — 触发当前工厂当前期间重算。

RBAC (per spec §前端/RN 呈现 + _rbac_strip): 非金额角色 (PRICE_VIEW_ROLES 之外)
金额字段 null, count 保留。前端直调 Python (D4: 139 Nginx 反代 47:8083)。

诚实空态: 无快照 → success:true, data:null, message "暂无价值快照" (不是 500)。
缺 factory context → success:false。
"""
from __future__ import annotations

import logging
from typing import Any, Optional

from fastapi import APIRouter, Query, Request
from pydantic import BaseModel

from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES, strip_price_for_role
from smartbi.services.restaurant.value_snapshot_service import get_value_summary

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/restaurant-value", tags=["RestaurantValue"])


def _get_factory_id(request: Request) -> Optional[str]:
    return getattr(request.state, "factory_id", None)


def _get_role(request: Request) -> Optional[str]:
    return getattr(request.state, "role", None)


def _apply_rbac_strip(data: Any, role: Optional[str]) -> Any:
    """非金额角色: 金额字段 null, count 保留。

    _MONEY_PATTERN 不匹配 total/laborRigidity/shrinkageVariance/foodCostSavings/
    discountSavings 等 camelCase 价值键 (不含通用 money token), 故用本地显式 key 集。
    criticalCount / diagnosisCount / rxActionCount 是计数, 保留。
    """
    if role and role in PRICE_VIEW_ROLES:
        return data
    if data is None:
        return data
    strip_price_for_role(data, role)  # belt-and-suspenders (通用金额键)
    _strip_value_amounts(data)        # 本地价值金额键
    return data


# 价值快照里的绝对金额键 (通用 _MONEY_PATTERN 漏匹配的 camelCase)。
_VALUE_MONEY_KEYS: frozenset[str] = frozenset({
    "total",
    "laborRigidity",
    "shrinkageVariance",
    "foodCostSavings",
    "discountSavings",
})


def _strip_value_amounts(node: Any) -> None:
    """Depth-first null of value-snapshot money keys (kind/count/amount in signals)。"""
    if isinstance(node, dict):
        for k, v in list(node.items()):
            if k in _VALUE_MONEY_KEYS and not isinstance(v, (dict, list)):
                node[k] = None
            elif k == "signalSources" and isinstance(v, list):
                # signal_sources 每项的 amount 也是金额 → null
                for item in v:
                    if isinstance(item, dict) and "amount" in item and not isinstance(item["amount"], (dict, list)):
                        item["amount"] = None
            else:
                _strip_value_amounts(v)
    elif isinstance(node, list):
        for item in node:
            _strip_value_amounts(item)


@router.get("/value-summary")
async def value_summary(
    request: Request,
    period_month: Optional[str] = Query(None, description="YYYY-MM; 省略=最新快照"),
    store_id: Optional[str] = Query(None, description="门店 ID; 省略=全店汇总"),
) -> dict[str, Any]:
    """返回最新价值快照 (月度 + 年化两口径, D3)。"""
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "data": None, "message": "缺少工厂上下文 (factory context)"}

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "data": None, "message": "数据库连接不可用"}

    try:
        # API 层: middleware 已设 GUC, 但价值快照走 smartbi_db 池, 显式设以确保 RLS。
        summary = await get_value_summary(
            pool, factory_id, period_month, store_id, set_tenant_guc=True
        )
    except Exception as e:
        logger.exception("[value-summary] failed for %s", factory_id)
        return {"success": False, "data": None, "message": f"读取价值快照失败: {e}"}

    if summary is None:
        # 诚实空态: 正常返回 (不是 500), 前端显"暂无价值快照 [前往上传]"。
        return {
            "success": True,
            "data": None,
            "message": "暂无价值快照 — 上传月度经营数据后将自动生成",
        }

    _apply_rbac_strip(summary, _get_role(request))
    return {"success": True, "data": summary, "message": "ok"}


class RefreshRequest(BaseModel):
    periodMonth: Optional[str] = None   # YYYY-MM; 省略 = 上月
    storeId: Optional[str] = None


@router.post("/refresh")
async def refresh(request: Request, body: RefreshRequest = RefreshRequest()) -> dict[str, Any]:
    """触发当前工厂指定期间重算价值快照 (admin/手动刷新)。"""
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "data": None, "message": "缺少工厂上下文 (factory context)"}

    try:
        result = await _recompute_snapshot(
            factory_id, period_month=body.periodMonth, store_id=body.storeId
        )
    except Exception as e:
        logger.exception("[value-refresh] failed for %s", factory_id)
        return {"success": False, "data": None, "message": f"重算失败: {e}"}

    return {
        "success": bool(result.get("success")),
        "data": {"totalMonth": result.get("totalMonth"), "totalAnnual": result.get("totalAnnual")},
        "message": result.get("message", "ok"),
    }


async def _recompute_snapshot(
    factory_id: str,
    period_month: Optional[str] = None,
    store_id: Optional[str] = None,
) -> dict[str, Any]:
    """重算并 upsert 一个工厂/期间的价值快照。

    委托给 value_refresh_pipeline (hook / cron / API 共用)。当前期间默认上月。
    """
    from smartbi.services.restaurant.value_refresh_pipeline import refresh_snapshot_for_factory

    return await refresh_snapshot_for_factory(
        factory_id, period_month=period_month, store_id=store_id, sub_sector="火锅"
    )
