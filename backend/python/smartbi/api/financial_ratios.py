"""
Financial Ratios Analysis API

Computes key financial ratios from SmartBI finance data:
- Profitability: ROE, ROA, Gross Margin, Net Margin
- Liquidity: Current, Quick, Cash ratios
- Efficiency: Inventory/AR/Asset turnover
- Leverage: Debt ratio, Interest coverage, Equity multiplier
"""
from __future__ import annotations

import logging
from datetime import datetime
from typing import Optional

from fastapi import APIRouter, Query
from fastapi.responses import JSONResponse

router = APIRouter()
logger = logging.getLogger(__name__)


def _get_db_connection():
    """Get database connection from smartbi config."""
    try:
        from smartbi.database.connection import get_connection, is_postgres_enabled
        if is_postgres_enabled():
            return get_connection()
    except Exception as e:
        logger.warning(f"DB connection failed: {e}")
    return None


def _safe_ratio(
    numerator: Optional[float],
    denominator: Optional[float],
    scale: float = 1.0,
) -> Optional[float]:
    """Return None when a ratio cannot be supported by source data."""
    if numerator is None or denominator is None or denominator == 0:
        return None
    return round(numerator / denominator * scale, 2)


def _status(value: Optional[float], benchmark: float, higher_is_better: bool = True) -> str:
    if value is None:
        return "unavailable"
    if higher_is_better:
        if value >= benchmark * 1.1:
            return "good"
        elif value >= benchmark * 0.8:
            return "warning"
        return "danger"
    else:
        if value <= benchmark * 0.9:
            return "good"
        elif value <= benchmark * 1.2:
            return "warning"
        return "danger"


@router.get("/financial-ratios")
async def get_financial_ratios(
    factory_id: Optional[str] = Query(None, alias="factoryId"),
    start_date: Optional[str] = Query(None, alias="startDate"),
    end_date: Optional[str] = Query(None, alias="endDate"),
):
    """
    Compute financial ratios from finance data.
    Missing source metrics stay unavailable; demo values are never substituted.
    """
    if not factory_id:
        return JSONResponse(
            status_code=400,
            content={"success": False, "data": None, "message": "缺少 factoryId，无法计算财务比率"},
        )

    try:
        conn = _get_db_connection()
        if conn is None:
            return JSONResponse(
                status_code=503,
                content={"success": False, "data": None, "message": "财务数据库不可用，未生成财务比率"},
            )

        result = _compute_ratios_from_db(conn, factory_id, start_date, end_date)
        if result is None:
            return JSONResponse(
                status_code=422,
                content={
                    "success": False,
                    "data": None,
                    "message": "当前期间没有足够的真实财务指标，未生成财务比率",
                },
            )
        return JSONResponse(content={"success": True, "data": result, "message": "财务比率分析完成"})
    except Exception as e:
        logger.exception("Ratio computation failed")
        return JSONResponse(
            status_code=500,
            content={"success": False, "data": None, "message": f"财务比率计算失败：{type(e).__name__}"},
        )


def _compute_ratios_from_db(conn, factory_id: str, start_date: Optional[str], end_date: Optional[str]):
    """Compute only ratios supported by the real smart_bi_finance_data schema."""
    cursor = None
    try:
        cursor = conn.cursor()
        query = """
        SELECT
            SUM(CASE WHEN record_type = 'REVENUE' THEN actual_amount END) as revenue,
            SUM(
                CASE WHEN record_type = 'COST'
                     THEN COALESCE(NULLIF(actual_amount, 0), total_cost, actual_amount)
                END
            ) as cost
        FROM smart_bi_finance_data
        WHERE factory_id = %s AND deleted_at IS NULL
        """
        params = [factory_id]
        if start_date:
            query += " AND record_date >= %s"
            params.append(start_date)
        if end_date:
            query += " AND record_date <= %s"
            params.append(end_date)

        cursor.execute(query, params)
        row = cursor.fetchone()

        if not row or all(value is None for value in row):
            return None

        data = {
            "revenue": float(row[0]) if row[0] is not None else None,
            "cost": float(row[1]) if row[1] is not None else None,
            # The current table has no governed balance-sheet/net-profit fields.
            "net_profit": None,
            "total_assets": None,
            "total_equity": None,
            "total_liabilities": None,
            "current_assets": None,
            "current_liabilities": None,
            "inventory": None,
            "cash": None,
        }
        gross_profit = (
            data["revenue"] - data["cost"]
            if data["revenue"] is not None and data["cost"] is not None
            else None
        )

        categories = [
            {
                "title": "盈利能力", "icon": "trending-up", "color": "#10B981",
                "ratios": [
                    {"name": "ROE (净资产收益率)", "value": _safe_ratio(data["net_profit"], data["total_equity"], 100),
                     "unit": "%", "benchmark": 12.0, "status": "", "description": "每单位净资产创造的净利润"},
                    {"name": "ROA (总资产收益率)", "value": _safe_ratio(data["net_profit"], data["total_assets"], 100),
                     "unit": "%", "benchmark": 6.5, "status": "", "description": "每单位总资产创造的净利润"},
                    {"name": "毛利率", "value": _safe_ratio(gross_profit, data["revenue"], 100),
                     "unit": "%", "benchmark": 30.0, "status": "", "description": "销售毛利占销售收入的比率"},
                    {"name": "净利率", "value": _safe_ratio(data["net_profit"], data["revenue"], 100),
                     "unit": "%", "benchmark": 8.0, "status": "", "description": "净利润占销售收入的比率"},
                ]
            },
            {
                "title": "流动性", "icon": "water", "color": "#3B82F6",
                "ratios": [
                    {"name": "流动比率", "value": _safe_ratio(data["current_assets"], data["current_liabilities"]),
                     "unit": "", "benchmark": 2.0, "status": "", "description": "流动资产/流动负债"},
                    {"name": "速动比率", "value": _safe_ratio(
                        data["current_assets"] - data["inventory"]
                        if data["current_assets"] is not None and data["inventory"] is not None
                        else None,
                        data["current_liabilities"],
                    ), "unit": "", "benchmark": 1.0, "status": "", "description": "(流动资产-存货)/流动负债"},
                    {"name": "现金比率", "value": _safe_ratio(data["cash"], data["current_liabilities"]),
                     "unit": "", "benchmark": 0.5, "status": "", "description": "现金及等价物/流动负债"},
                ]
            },
            {
                "title": "运营效率", "icon": "cog-sync", "color": "#F59E0B",
                "ratios": [
                    {"name": "存货周转率", "value": _safe_ratio(data["cost"], data["inventory"]),
                     "unit": "次", "benchmark": 6.0, "status": "", "description": "年销售成本/平均存货"},
                    {"name": "总资产周转率", "value": _safe_ratio(data["revenue"], data["total_assets"]),
                     "unit": "次", "benchmark": 1.2, "status": "", "description": "年销售收入/平均总资产"},
                ]
            },
            {
                "title": "偿债能力", "icon": "shield-account", "color": "#EF4444",
                "ratios": [
                    {"name": "资产负债率", "value": _safe_ratio(data["total_liabilities"], data["total_assets"], 100),
                     "unit": "%", "benchmark": 50.0, "status": "", "description": "总负债/总资产"},
                    {"name": "权益乘数", "value": _safe_ratio(data["total_assets"], data["total_equity"]),
                     "unit": "", "benchmark": 2.0, "status": "", "description": "总资产/净资产"},
                ]
            },
        ]

        for category in categories:
            for ratio in category["ratios"]:
                higher = ratio["name"] != "资产负债率"
                ratio["status"] = _status(ratio["value"], ratio["benchmark"], higher)
                ratio["available"] = ratio["value"] is not None
                if ratio["value"] is None:
                    ratio["unavailableReason"] = "缺少计算该比率所需的真实财务指标"

        return {"categories": categories, "analysisDate": datetime.now().strftime("%Y-%m-%d")}

    except Exception as e:
        logger.error(f"Ratio SQL error: {e}")
        raise
    finally:
        try:
            cursor.close()
        except Exception:
            pass
