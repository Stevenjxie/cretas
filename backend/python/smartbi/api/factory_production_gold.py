"""Factory Production Gold read endpoints (Phase 1 of Factory BI).

Reads from smartbi_db.agg_factory_batch_daily (Gold) built by
factory_production_etl.py.  All dates optional — omit for full history.

Endpoints (registered under /api/smartbi/factory prefix in main.py):
    GET /factory/cost-structure     — cost breakdown (material/labor/equip/other)
    GET /factory/yield-trend        — daily/weekly yield rate trend
    GET /factory/product-cost-compare — per-product cost comparison

RBAC:
    Cost fields (all NUMERIC cost columns) visible only to PRICE_VIEW_ROLES.
    Non-price roles get cost fields stripped to null (belt-and-suspenders via
    strip_price_for_role + explicit cost-key sweep, same pattern as
    restaurant_cost_card.py).

RLS:
    All queries set app.factory_id GUC inside a transaction so FORCE ROW LEVEL
    SECURITY on Gold tables is satisfied.

Honest empty-state:
    No Gold rows → success:true, data:[], message "暂无生产数据" (not 500).
    Missing factory context → success:false.
"""
from __future__ import annotations

import logging
from datetime import date
from typing import Any, List, Optional

from fastapi import APIRouter, Query, Request

from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES, strip_price_for_role

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/factory", tags=["Factory Production Gold"])

# Cost field names in our response dicts (for explicit strip in addition to _MONEY_PATTERN)
_COST_KEYS: frozenset[str] = frozenset({
    "totalMaterialCost", "totalLaborCost", "totalEquipmentCost", "totalOtherCost",
    "totalCost", "materialCost", "laborCost", "equipmentCost", "otherCost",
    "costPerUnit",
})


# ── Internal helpers ──────────────────────────────────────────────────────────

def _get_factory_id(request: Request) -> Optional[str]:
    return getattr(request.state, "factory_id", None)


def _get_role(request: Request) -> Optional[str]:
    return getattr(request.state, "role", None)


def _can_view_cost(role: Optional[str]) -> bool:
    return bool(role and role in PRICE_VIEW_ROLES)


def _strip_cost_fields(node: Any) -> None:
    """Depth-first null of cost money keys the shared regex misses."""
    if isinstance(node, dict):
        for k in list(node.keys()):
            if k in _COST_KEYS and not isinstance(node[k], (dict, list)):
                node[k] = None
            else:
                _strip_cost_fields(node[k])
    elif isinstance(node, list):
        for item in node:
            _strip_cost_fields(item)


def _apply_rbac(data: Any, role: Optional[str]) -> Any:
    """Apply RBAC cost stripping if role is not price-view-capable."""
    if _can_view_cost(role):
        return data
    strip_price_for_role(data, role)
    _strip_cost_fields(data)
    return data


async def _query_gold(
    factory_id: str,
    start_date: Optional[date],
    end_date: Optional[date],
    product_type_id: Optional[str] = None,
    all_products_only: bool = False,
):
    """Shared query helper for agg_factory_batch_daily.

    all_products_only=True → only NULL product_type_id rows (daily factory total).
    product_type_id set   → only that product's per-day rows.
    both None             → all rows (per-product).
    """
    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        return None, "db pool unavailable"

    conditions = ["factory_id = $1"]
    params: list = [factory_id]
    idx = 2

    if start_date is not None:
        conditions.append(f"stat_date >= ${idx}")
        params.append(start_date)
        idx += 1

    if end_date is not None:
        conditions.append(f"stat_date <= ${idx}")
        params.append(end_date)
        idx += 1

    if all_products_only:
        conditions.append("product_type_id IS NULL")
    elif product_type_id is not None:
        conditions.append(f"product_type_id = ${idx}")
        params.append(product_type_id)
        idx += 1
    else:
        conditions.append("product_type_id IS NOT NULL")

    where_clause = " AND ".join(conditions)

    try:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)", factory_id
                )
                rows = await conn.fetch(
                    f"""
                    SELECT
                        factory_id, stat_date, product_type_id,
                        batch_count,
                        total_planned_qty, total_actual_qty, total_good_qty,
                        avg_yield_rate,
                        total_material_cost, total_labor_cost,
                        total_equipment_cost, total_other_cost, total_cost,
                        version, updated_at
                    FROM agg_factory_batch_daily
                    WHERE {where_clause}
                    ORDER BY stat_date, product_type_id NULLS FIRST
                    """,
                    *params,
                )
        return rows, None
    except Exception as exc:
        logger.warning("[factory-gold] query failed for %s: %s", factory_id, exc)
        return None, str(exc)


def _row_to_dict(row) -> dict:
    """Convert asyncpg Row to camelCase dict with honest null preservation."""
    return {
        "factoryId":           row["factory_id"],
        "statDate":            row["stat_date"].isoformat() if row["stat_date"] else None,
        "productTypeId":       row["product_type_id"],
        "batchCount":          row["batch_count"],
        "totalPlannedQty":     float(row["total_planned_qty"]) if row["total_planned_qty"] is not None else None,
        "totalActualQty":      float(row["total_actual_qty"])  if row["total_actual_qty"]  is not None else None,
        "totalGoodQty":        float(row["total_good_qty"])    if row["total_good_qty"]    is not None else None,
        "avgYieldRate":        float(row["avg_yield_rate"])    if row["avg_yield_rate"]    is not None else None,
        "totalMaterialCost":   float(row["total_material_cost"])  if row["total_material_cost"]  is not None else None,
        "totalLaborCost":      float(row["total_labor_cost"])     if row["total_labor_cost"]     is not None else None,
        "totalEquipmentCost":  float(row["total_equipment_cost"]) if row["total_equipment_cost"] is not None else None,
        "totalOtherCost":      float(row["total_other_cost"])     if row["total_other_cost"]     is not None else None,
        "totalCost":           float(row["total_cost"])           if row["total_cost"]           is not None else None,
        "version":             row["version"],
        "updatedAt":           row["updated_at"].isoformat() if row["updated_at"] else None,
    }


# ── Endpoints ─────────────────────────────────────────────────────────────────

@router.get("/cost-structure")
async def cost_structure(
    request: Request,
    startDate: Optional[date] = Query(None, description="起始日期 (默认: 全部历史)"),
    endDate:   Optional[date] = Query(None, description="截止日期 (默认: 全部历史)"),
):
    """Factory production cost breakdown by component (material / labor / equipment / other).

    Returns all-product daily rollup rows (product_type_id = NULL) aggregated
    over the requested date range.  Cost fields are null for non-price roles.

    Response:
        {
            success: bool,
            data: {
                rows: [...],            // per-day cost structure
                summary: {
                    totalMaterialCost, totalLaborCost, totalEquipmentCost,
                    totalOtherCost, totalCost,
                    batchCount, totalActualQty
                }
            },
            message: str
        }
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "data": None, "message": "missing factory context"}

    role = _get_role(request)

    rows, err = await _query_gold(factory_id, startDate, endDate, all_products_only=True)
    if err:
        return {"success": False, "data": None, "message": err}
    if not rows:
        return {"success": True, "data": {"rows": [], "summary": {}}, "message": "暂无生产数据"}

    items = [_row_to_dict(r) for r in rows]

    # Summary aggregation (honest null: only sum non-null slices)
    def _sum_field(key: str) -> Optional[float]:
        vals = [item[key] for item in items if item[key] is not None]
        return round(sum(vals), 2) if vals else None

    summary = {
        "totalMaterialCost":  _sum_field("totalMaterialCost"),
        "totalLaborCost":     _sum_field("totalLaborCost"),
        "totalEquipmentCost": _sum_field("totalEquipmentCost"),
        "totalOtherCost":     _sum_field("totalOtherCost"),
        "totalCost":          _sum_field("totalCost"),
        "batchCount":         sum(item["batchCount"] for item in items),
        "totalActualQty":     _sum_field("totalActualQty"),
    }

    data = {"rows": items, "summary": summary}
    _apply_rbac(data, role)
    return {"success": True, "data": data, "message": ""}


@router.get("/yield-trend")
async def yield_trend(
    request: Request,
    startDate:    Optional[date]  = Query(None, description="起始日期 (默认: 全部历史)"),
    endDate:      Optional[date]  = Query(None, description="截止日期 (默认: 全部历史)"),
    productTypeId: Optional[str] = Query(None, description="产品类型 ID (不传=全产品汇总)"),
):
    """Daily yield-rate trend for the factory (or a specific product type).

    Yield = good_qty / actual_qty × 100 (%).
    When productTypeId omitted, returns all-product daily rollup (avg across products).
    Cost fields included but stripped for non-price roles.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "data": None, "message": "missing factory context"}

    role = _get_role(request)

    if productTypeId:
        rows, err = await _query_gold(factory_id, startDate, endDate, product_type_id=productTypeId)
    else:
        rows, err = await _query_gold(factory_id, startDate, endDate, all_products_only=True)

    if err:
        return {"success": False, "data": None, "message": err}
    if not rows:
        return {"success": True, "data": {"trend": []}, "message": "暂无生产数据"}

    trend = [
        {
            "statDate":      r["stat_date"].isoformat() if r["stat_date"] else None,
            "avgYieldRate":  float(r["avg_yield_rate"]) if r["avg_yield_rate"] is not None else None,
            "batchCount":    r["batch_count"],
            "totalActualQty": float(r["total_actual_qty"]) if r["total_actual_qty"] is not None else None,
            "totalGoodQty":   float(r["total_good_qty"])  if r["total_good_qty"]   is not None else None,
            "totalCost":      float(r["total_cost"])      if r["total_cost"]       is not None else None,
        }
        for r in rows
    ]

    data = {"trend": trend, "productTypeId": productTypeId}
    _apply_rbac(data, role)
    return {"success": True, "data": data, "message": ""}


@router.get("/product-cost-compare")
async def product_cost_compare(
    request: Request,
    startDate: Optional[date] = Query(None, description="起始日期 (默认: 全部历史)"),
    endDate:   Optional[date] = Query(None, description="截止日期 (默认: 全部历史)"),
):
    """Per-product-type cost comparison aggregated over the requested period.

    Returns one row per product_type_id with summed costs and average yield.
    Sorted by total_cost DESC (highest cost products first).
    Cost fields stripped for non-price roles.

    Response:
        {
            success: bool,
            data: {
                products: [{
                    productTypeId, batchCount,
                    totalActualQty, totalGoodQty, avgYieldRate,
                    totalMaterialCost, totalLaborCost, totalEquipmentCost,
                    totalOtherCost, totalCost,
                    costPerUnit   // totalCost / totalActualQty (null if qty=0 or costs null)
                }]
            }
        }
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "data": None, "message": "missing factory context"}

    role = _get_role(request)

    rows, err = await _query_gold(factory_id, startDate, endDate)
    if err:
        return {"success": False, "data": None, "message": err}
    if not rows:
        return {"success": True, "data": {"products": []}, "message": "暂无生产数据"}

    # Aggregate across days per product_type_id
    from collections import defaultdict
    agg: dict = defaultdict(lambda: {
        "batchCount": 0,
        "totalPlannedQty": None, "totalActualQty": None, "totalGoodQty": None,
        "yieldRateNumerator": 0.0, "yieldRateDenominator": 0,
        "totalMaterialCost": None, "totalLaborCost": None,
        "totalEquipmentCost": None, "totalOtherCost": None, "totalCost": None,
    })

    def _null_add(a, b):
        """Add two nullable numbers: None+None=None, None+x=x, x+y=x+y."""
        if a is None and b is None:
            return None
        return (a or 0.0) + (b or 0.0)

    for row in rows:
        pid = row["product_type_id"]
        if pid is None:
            continue  # skip all-products rollup rows
        bucket = agg[pid]
        bucket["batchCount"] += row["batch_count"]
        bucket["totalPlannedQty"]    = _null_add(bucket["totalPlannedQty"],    float(row["total_planned_qty"])    if row["total_planned_qty"]    is not None else None)
        bucket["totalActualQty"]     = _null_add(bucket["totalActualQty"],     float(row["total_actual_qty"])     if row["total_actual_qty"]     is not None else None)
        bucket["totalGoodQty"]       = _null_add(bucket["totalGoodQty"],       float(row["total_good_qty"])       if row["total_good_qty"]       is not None else None)
        bucket["totalMaterialCost"]  = _null_add(bucket["totalMaterialCost"],  float(row["total_material_cost"])  if row["total_material_cost"]  is not None else None)
        bucket["totalLaborCost"]     = _null_add(bucket["totalLaborCost"],     float(row["total_labor_cost"])     if row["total_labor_cost"]     is not None else None)
        bucket["totalEquipmentCost"] = _null_add(bucket["totalEquipmentCost"], float(row["total_equipment_cost"]) if row["total_equipment_cost"] is not None else None)
        bucket["totalOtherCost"]     = _null_add(bucket["totalOtherCost"],     float(row["total_other_cost"])     if row["total_other_cost"]     is not None else None)
        bucket["totalCost"]          = _null_add(bucket["totalCost"],          float(row["total_cost"])           if row["total_cost"]           is not None else None)
        # Weighted yield: weight by batch_count
        if row["avg_yield_rate"] is not None:
            bucket["yieldRateNumerator"]   += float(row["avg_yield_rate"]) * row["batch_count"]
            bucket["yieldRateDenominator"] += row["batch_count"]

    products: List[dict] = []
    for pid, bucket in agg.items():
        avg_yield = (
            round(bucket["yieldRateNumerator"] / bucket["yieldRateDenominator"], 2)
            if bucket["yieldRateDenominator"] > 0 else None
        )
        cost_per_unit: Optional[float] = None
        if bucket["totalCost"] is not None and bucket["totalActualQty"] and bucket["totalActualQty"] > 0:
            cost_per_unit = round(bucket["totalCost"] / bucket["totalActualQty"], 4)

        products.append({
            "productTypeId":     pid,
            "batchCount":        bucket["batchCount"],
            "totalActualQty":    round(bucket["totalActualQty"],  2) if bucket["totalActualQty"]  is not None else None,
            "totalGoodQty":      round(bucket["totalGoodQty"],    2) if bucket["totalGoodQty"]    is not None else None,
            "avgYieldRate":      avg_yield,
            "totalMaterialCost": round(bucket["totalMaterialCost"],  2) if bucket["totalMaterialCost"]  is not None else None,
            "totalLaborCost":    round(bucket["totalLaborCost"],     2) if bucket["totalLaborCost"]     is not None else None,
            "totalEquipmentCost": round(bucket["totalEquipmentCost"], 2) if bucket["totalEquipmentCost"] is not None else None,
            "totalOtherCost":    round(bucket["totalOtherCost"],     2) if bucket["totalOtherCost"]     is not None else None,
            "totalCost":         round(bucket["totalCost"],          2) if bucket["totalCost"]          is not None else None,
            "costPerUnit":       cost_per_unit,
        })

    # Sort by totalCost DESC (nulls last)
    products.sort(key=lambda p: p["totalCost"] if p["totalCost"] is not None else -1, reverse=True)

    data = {"products": products}
    _apply_rbac(data, role)
    return {"success": True, "data": data, "message": ""}


@router.get("/process-cost")
async def process_cost(
    request: Request,
    batchId:   Optional[str]  = Query(None, description="生产批次 ID (不传=全厂所有批次)"),
    startDate: Optional[date] = Query(None, description="起始日期 (默认: 全部历史)"),
    endDate:   Optional[date] = Query(None, description="截止日期 (默认: 全部历史)"),
    costOnly:  bool           = Query(False, description="仅返回有成本数据的工序 (cost_source='reported')"),
):
    """Process-level cost and yield for factory production reports.

    Source: fact_production_report (Silver — grain: one row per production_reports YIELD).

    Honesty: ~90% of prod reports have NULL labor_cost / material_cost.
    - costOnly=false (default): returns all reports; cost fields are null when unreported.
    - costOnly=true: filters cost_source = 'reported' rows only (the ~10% with real data).

    Response:
        {
            success: bool,
            data: {
                reports: [
                    {
                        sourcePk, batchId, processOrder, processCategory,
                        reportDate, reportKind,
                        inputQty, outputQty, goodQty, yieldRate,
                        totalWorkMinutes, totalWorkers,
                        laborCost,    // null when not reported
                        materialCost, // null when not reported
                        costSource,   // 'reported' or null
                    }
                ],
                meta: {
                    totalReports, reportsWithCost, costCoverageRate
                }
            }
        }

    Cost fields are stripped to null for non-price roles.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "data": None, "message": "missing factory context"}

    role = _get_role(request)

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "data": None, "message": "db pool unavailable"}

    conditions = ["factory_id = $1"]
    params: list = [factory_id]
    idx = 2

    if batchId is not None:
        conditions.append(f"batch_id = ${idx}")
        params.append(batchId)
        idx += 1

    if startDate is not None:
        conditions.append(f"report_date >= ${idx}")
        params.append(startDate)
        idx += 1

    if endDate is not None:
        conditions.append(f"report_date <= ${idx}")
        params.append(endDate)
        idx += 1

    if costOnly:
        conditions.append("cost_source = 'reported'")

    where_clause = " AND ".join(conditions)

    try:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)", factory_id
                )
                rows = await conn.fetch(
                    f"""
                    SELECT
                        source_pk, batch_id, process_order, process_category,
                        report_date, report_kind,
                        input_qty, output_qty, good_qty, yield_rate,
                        total_work_minutes, total_workers,
                        labor_cost, material_cost, cost_source
                    FROM fact_production_report
                    WHERE {where_clause}
                    ORDER BY report_date, batch_id NULLS LAST, process_order NULLS LAST
                    """,
                    *params,
                )
    except Exception as exc:
        logger.warning("[factory-gold] process-cost query failed for %s: %s", factory_id, exc)
        return {"success": False, "data": None, "message": str(exc)}

    if not rows:
        return {
            "success": True,
            "data": {"reports": [], "meta": {"totalReports": 0, "reportsWithCost": 0, "costCoverageRate": None}},
            "message": "暂无工序报工数据",
        }

    reports = []
    for r in rows:
        reports.append({
            "sourcePk":          r["source_pk"],
            "batchId":           r["batch_id"],
            "processOrder":      r["process_order"],
            "processCategory":   r["process_category"],
            "reportDate":        r["report_date"].isoformat() if r["report_date"] else None,
            "reportKind":        r["report_kind"],
            "inputQty":          float(r["input_qty"])    if r["input_qty"]    is not None else None,
            "outputQty":         float(r["output_qty"])   if r["output_qty"]   is not None else None,
            "goodQty":           float(r["good_qty"])     if r["good_qty"]     is not None else None,
            "yieldRate":         float(r["yield_rate"])   if r["yield_rate"]   is not None else None,
            "totalWorkMinutes":  r["total_work_minutes"],
            "totalWorkers":      r["total_workers"],
            "laborCost":         float(r["labor_cost"])    if r["labor_cost"]    is not None else None,
            "materialCost":      float(r["material_cost"]) if r["material_cost"] is not None else None,
            "costSource":        r["cost_source"],
        })

    total = len(reports)
    with_cost = sum(1 for rep in reports if rep["costSource"] == "reported")
    coverage = round(with_cost / total * 100, 2) if total > 0 else None

    data = {
        "reports": reports,
        "meta": {
            "totalReports":      total,
            "reportsWithCost":   with_cost,
            "costCoverageRate":  coverage,
        },
    }
    _apply_rbac(data, role)
    return {"success": True, "data": data, "message": ""}
