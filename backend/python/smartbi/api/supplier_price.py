"""G7 取数自动化 Tier A — 供应商进价 gold REST API.

Routes (registered under prefix="/api/smartbi" in main.py):
- POST /gold/supplier-price/batch-upsert  → upsert confirmed delivery lines
- GET  /gold/supplier-price/trend         → price trend for one ingredient (G4)
- GET  /gold/supplier-price/latest        → latest price per ingredient (G5 cost card)

Tenant-scoped: factory_id from JWT (auth middleware request.state) or from
X-Factory-Id header on internal Java→Python calls (X-Internal-Secret path).

RBAC: unit_price / line_amount 是金额, 对非 price-view 角色剥零 (复用 _rbac_strip)。
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Body, HTTPException, Query, Request

from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES, strip_price_for_role

logger = logging.getLogger(__name__)
router = APIRouter(tags=["SupplierPrice"])


def _get_factory_id(request: Request) -> Optional[str]:
    return getattr(request.state, "factory_id", None)


def _get_role(request: Request) -> Optional[str]:
    return getattr(request.state, "role", None)


def _get_auth_method(request: Request) -> Optional[str]:
    return getattr(request.state, "auth_method", None)


def _require_internal_write(request: Request) -> None:
    if _get_auth_method(request) != "internal":
        raise HTTPException(
            status_code=403,
            detail="供应商进价 gold 写入仅允许 Java 后端内部同步调用",
        )


def _apply_rbac_strip(data: Any, role: Optional[str]) -> Any:
    """Null monetary fields (unit_price/line_amount) for non price-view roles."""
    if role and role in PRICE_VIEW_ROLES:
        return data
    if data is None:
        return data
    strip_price_for_role(data, role)
    return data


@router.post("/gold/supplier-price/batch-upsert")
async def batch_upsert(
    request: Request,
    body: Dict[str, Any] = Body(...),
) -> Dict[str, Any]:
    """Bulk-insert confirmed delivery-note lines into agg_supplier_price.

    Body: {factoryId, noteId, lines: [{ingredientName, normalizedName,
           rawMaterialTypeId, supplierId, supplierName, deliveryDate,
           unitPrice, quantity, unit, lineAmount}]}

    factory_id 优先取请求上下文 (X-Factory-Id / JWT); 缺失才回落 body.factoryId
    (内部 Java 调用都带 X-Factory-Id, 但保留 body 兼容)。
    """
    _require_internal_write(request)
    ctx_factory = _get_factory_id(request)
    body_factory = body.get("factoryId")
    factory_id = ctx_factory or body_factory
    if not factory_id:
        return {"success": False, "message": "missing factory context"}
    # 安全: 若两者都在且不一致, 拒绝 (防跨租户写)。
    if ctx_factory and body_factory and ctx_factory != body_factory:
        return {"success": False,
                "message": f"factory mismatch: ctx={ctx_factory} body={body_factory}"}

    note_id = body.get("noteId")
    lines = body.get("lines") or []
    if not isinstance(lines, list):
        return {"success": False, "message": "lines must be a list"}

    # Map camelCase body → snake_case ETL row keys.
    from datetime import datetime
    rows: List[dict] = []
    for ln in lines:
        ddate = ln.get("deliveryDate")
        if isinstance(ddate, str) and ddate:
            try:
                ddate = datetime.strptime(ddate, "%Y-%m-%d").date()
            except ValueError:
                ddate = None
        rows.append({
            "ingredient_name": ln.get("ingredientName"),
            "normalized_name": ln.get("normalizedName"),
            "raw_material_type_id": ln.get("rawMaterialTypeId"),
            "supplier_id": ln.get("supplierId"),
            "supplier_name": ln.get("supplierName"),
            "delivery_date": ddate,
            "unit_price": ln.get("unitPrice"),
            "quantity": ln.get("quantity"),
            "unit": ln.get("unit"),
            "line_amount": ln.get("lineAmount"),
            "source_note_id": note_id,
        })

    from smartbi.config import get_pg_pool
    from smartbi.gold.supplier_price_etl import upsert_supplier_price_batch
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "smartbi_db pool unavailable"}

    try:
        count = await upsert_supplier_price_batch(pool, factory_id, rows)
    except ValueError as e:
        return {"success": False, "message": str(e)}
    except Exception as e:
        logger.exception("[supplier-price] batch-upsert failed for %s", factory_id)
        return {"success": False, "message": f"upsert failed: {e}"}

    return {"success": True, "data": {"count": count}}


@router.get("/gold/supplier-price/trend")
async def price_trend(
    request: Request,
    ingredient: str = Query(..., description="食材名称 (normalized match)"),
    days: int = Query(90, ge=1, le=730),
) -> Dict[str, Any]:
    """进价趋势 — [{deliveryDate, unitPrice, supplierName, quantity}] ASC by date."""
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    from smartbi.config import get_pg_pool
    from smartbi.gold.supplier_price_etl import get_supplier_price_trend
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "db pool unavailable"}

    try:
        data = await get_supplier_price_trend(pool, factory_id, ingredient, days=days)
    except ValueError as e:
        return {"success": False, "message": str(e)}
    except Exception as e:
        logger.exception("[supplier-price] trend failed for %s", factory_id)
        return {"success": False, "message": f"query failed: {e}"}

    _apply_rbac_strip(data, _get_role(request))
    return {"success": True, "data": data}


@router.get("/gold/supplier-price/latest")
async def latest_prices(request: Request) -> Dict[str, Any]:
    """最新进价 — latest unit_price per ingredient (G5 cost card)."""
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    from smartbi.config import get_pg_pool
    from smartbi.gold.supplier_price_etl import get_latest_ingredient_prices
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "db pool unavailable"}

    try:
        data = await get_latest_ingredient_prices(pool, factory_id)
    except ValueError as e:
        return {"success": False, "message": str(e)}
    except Exception as e:
        logger.exception("[supplier-price] latest failed for %s", factory_id)
        return {"success": False, "message": f"query failed: {e}"}

    _apply_rbac_strip(data, _get_role(request))
    return {"success": True, "data": data}
