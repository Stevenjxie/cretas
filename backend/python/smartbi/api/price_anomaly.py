"""Wave2 价格异常威慑引擎 — REST API.

Routes (registered under prefix="/api/smartbi" in main.py):
- GET  /gold/price-anomaly/detect  → 检测同类物料相邻采购单价异常 (latest vs trailing-N avg)
- POST /gold/price-anomaly/ack     → 录入供应商解释/确认 (威慑非处罚, 幂等)
- GET  /gold/price-anomaly/acks    → 已确认异常列表 (报警去重 + 留痕)

Tenant-scoped: factory_id from JWT (request.state) or X-Factory-Id on internal
Java→Python calls. RBAC: 绝对金额字段 (oldPrice/newPrice/trailingAvg) 对非
price-view 角色剥零; deltaPct (率) + riskLevel/direction (威慑信号) 保留可见。

防呆 (fool-proof):
- Rule 3: ack reason_code 是 dropdown (SEASONAL/MARKET_RISE/SPEC_CHANGE/OTHER), OTHER 必填备注。
- Rule 4: ack 幂等 (同一异常只确认一次, ON CONFLICT 更新解释)。
- 报警 message 含上下文 (品名 + 供应商 + 新旧价) 便于威慑追责。
"""
from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Body, HTTPException, Query, Request

from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES, strip_price_for_role

logger = logging.getLogger(__name__)
router = APIRouter(tags=["PriceAnomaly"])

_ACK_WRITE_ROLES: frozenset[str] = frozenset({
    "factory_super_admin",
    "platform_admin",
    "permission_admin",
    "restaurant_manager",
})

# 绝对金额 camelCase keys not matched by the shared _MONEY_PATTERN. deltaPct is a
# RATE (conveys trend/deterrence without leaking the amount) so it stays visible.
_EXTRA_MONEY_KEYS: frozenset[str] = frozenset({"trailingAvg"})


def _get_factory_id(request: Request) -> Optional[str]:
    return getattr(request.state, "factory_id", None)


def _get_role(request: Request) -> Optional[str]:
    return getattr(request.state, "role", None)


def _get_username(request: Request) -> Optional[str]:
    return getattr(request.state, "username", None)


def _require_ack_write_role(request: Request) -> None:
    role = _get_role(request)
    if role not in _ACK_WRITE_ROLES:
        raise HTTPException(
            status_code=403,
            detail=(
                "无权记录价格异常解释；请由店长/老板账号处理，"
                "仓管现场涨价说明请在送货单验收入库页填写。"
            ),
        )


def _strip_extras(node: Any) -> None:
    if isinstance(node, dict):
        for k, v in list(node.items()):
            if k in _EXTRA_MONEY_KEYS and not isinstance(v, (dict, list)):
                node[k] = None
            else:
                _strip_extras(v)
    elif isinstance(node, list):
        for item in node:
            _strip_extras(item)


def _apply_rbac_strip(data: Any, role: Optional[str]) -> Any:
    """Null absolute monetary fields for roles outside PRICE_VIEW_ROLES."""
    if role and role in PRICE_VIEW_ROLES:
        return data
    if data is None:
        return data
    strip_price_for_role(data, role)
    _strip_extras(data)
    return data


def _parse_date(s: Any):
    if not s:
        return None
    if isinstance(s, str):
        try:
            return datetime.strptime(s, "%Y-%m-%d").date()
        except ValueError:
            return None
    return s


@router.get("/gold/price-anomaly/detect")
async def detect(
    request: Request,
    trailing_n: int = Query(3, ge=1, le=20, description="count 模式: trailing 均价窗口 N"),
    epsilon_pct: float = Query(5.0, ge=0.0, le=100.0, description="异常容限 ε (百分比)"),
    baseline_mode: str = Query(
        "count", pattern="^(count|days)$",
        description="基准模式: count=trailing-N 次均价 (legacy); days=N 天移动均价 (邓总锁定 90 天)"),
    window_days: int = Query(
        90, ge=1, le=365, description="days 模式: 移动均价窗口天数 (邓总锁定 90)"),
) -> Dict[str, Any]:
    """检测同类物料相邻采购单价异常。

    返回 list (HIGH risk 优先, 然后 |delta| 大优先):
      {normalizedName, ingredientName, supplierId, supplierName, unit,
       anomalyDeliveryDate, oldPrice, newPrice, trailingAvg, deltaPct,
       direction, consecutiveAnomalyCount, riskLevel}

    baseline_mode='days' + window_days=90 = 邓总锁定的"自身 90 天移动均价"基准
    (供应商涨价预警)。复用 #53 检测内核 (同 agg_supplier_price 历史 + ack/累计高风险),
    不分叉新 detector。
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    from smartbi.config import get_pg_pool
    import smartbi.gold.price_anomaly as mod
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "smartbi_db pool unavailable"}

    try:
        data = await mod.detect_price_anomalies(
            pool, factory_id, trailing_n=trailing_n, epsilon_pct=epsilon_pct,
            baseline_mode=baseline_mode, window_days=window_days,
        )
    except ValueError as e:
        return {"success": False, "message": str(e)}
    except Exception as e:
        logger.exception("[price-anomaly] detect failed for %s", factory_id)
        return {"success": False, "message": f"detect failed: {e}"}

    _apply_rbac_strip(data, _get_role(request))
    return {"success": True, "data": data}


@router.post("/gold/price-anomaly/ack")
async def ack(
    request: Request,
    body: Dict[str, Any] = Body(...),
) -> Dict[str, Any]:
    """录入供应商对一条价格异常的解释/确认 (威慑非处罚)。

    Body (camelCase): {normalizedName, ingredientName, supplierId, supplierName,
      anomalyDeliveryDate (YYYY-MM-DD), oldPrice, newPrice, deltaPct,
      reasonCode (SEASONAL|MARKET_RISE|SPEC_CHANGE|OTHER), reasonNote}

    acknowledged_by 取自 JWT username (审计留痕)。幂等 (ON CONFLICT 更新解释)。
    """
    _require_ack_write_role(request)
    ctx_factory = _get_factory_id(request)
    body_factory = body.get("factoryId")
    factory_id = ctx_factory or body_factory
    if not factory_id:
        return {"success": False, "message": "missing factory context"}
    if ctx_factory and body_factory and ctx_factory != body_factory:
        return {"success": False,
                "message": f"factory mismatch: ctx={ctx_factory} body={body_factory}"}

    from smartbi.config import get_pg_pool
    import smartbi.gold.price_anomaly as mod
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "smartbi_db pool unavailable"}

    try:
        ack_id = await mod.record_anomaly_ack(
            pool, factory_id,
            normalized_name=body.get("normalizedName"),
            ingredient_name=body.get("ingredientName"),
            supplier_id=body.get("supplierId"),
            supplier_name=body.get("supplierName"),
            anomaly_delivery_date=_parse_date(body.get("anomalyDeliveryDate")),
            old_price=body.get("oldPrice"),
            new_price=body.get("newPrice"),
            delta_pct=body.get("deltaPct"),
            reason_code=body.get("reasonCode") or "",
            reason_note=body.get("reasonNote"),
            acknowledged_by=_get_username(request),
        )
    except ValueError as e:
        return {"success": False, "message": str(e)}
    except Exception as e:
        logger.exception("[price-anomaly] ack failed for %s", factory_id)
        return {"success": False, "message": f"ack failed: {e}"}

    return {"success": True, "data": {"id": ack_id},
            "message": "解释已记录, 该异常已确认"}


@router.get("/gold/price-anomaly/acks")
async def acks(
    request: Request,
    ingredient: Optional[str] = Query(None, description="按食材归一名过滤 (可选)"),
) -> Dict[str, Any]:
    """已确认异常列表 (报警去重 + 留痕展示)。"""
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    from smartbi.config import get_pg_pool
    import smartbi.gold.price_anomaly as mod
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "smartbi_db pool unavailable"}

    norm = None
    if ingredient:
        # mirror the detection/ETL normalization so the filter matches.
        norm = " ".join(str(ingredient).lower().strip().split())

    try:
        data: List[dict] = await mod.list_anomaly_acks(pool, factory_id, normalized_name=norm)
    except ValueError as e:
        return {"success": False, "message": str(e)}
    except Exception as e:
        logger.exception("[price-anomaly] acks list failed for %s", factory_id)
        return {"success": False, "message": f"query failed: {e}"}

    _apply_rbac_strip(data, _get_role(request))
    return {"success": True, "data": data}
