"""
Customer RFM Analysis API

Calculates Recency, Frequency, Monetary scores from sales_orders
and segments customers into actionable groups.

Fool-proof-design Rule 5: when source data is absent (未接入), return
connected=false with a plain-language message pointing to next action.
No fake/demo data under any circumstances.
"""
from __future__ import annotations

import logging
from datetime import datetime
from typing import Optional

from fastapi import APIRouter, Query
from fastapi.responses import JSONResponse

router = APIRouter()
logger = logging.getLogger(__name__)

# ── response shapes ──────────────────────────────────────────────────────────

_NOT_CONNECTED = {
    "success": True,
    "data": {
        "connected": False,
        "reason": "客户复购数据未接入 — 需导入会员订单数据",
        "segments": [],
        "totalCustomers": 0,
    },
    "message": "复购/RFM数据未接入 — 还没有会员订单数据，接入后可看到客户分层和复购分析",
}


def _get_db_connection():
    """Get database connection from smartbi config."""
    try:
        from smartbi.database.connection import get_connection, is_postgres_enabled
        if is_postgres_enabled():
            return get_connection()
    except Exception as e:
        logger.warning(f"DB connection failed: {e}")
    return None


SEGMENT_DEFINITIONS = [
    {"name": "高价值客户", "name_en": "Champions", "r_min": 4, "f_min": 4, "m_min": 4,
     "color": "#10B981", "icon": "crown", "suggestion": "维护关系，提供VIP专属优惠和优先服务"},
    {"name": "忠实客户", "name_en": "Loyal", "r_min": 3, "f_min": 3, "m_min": 3,
     "color": "#3B82F6", "icon": "account-heart", "suggestion": "交叉销售，提升客单价，推荐高价值产品"},
    {"name": "潜力客户", "name_en": "Potential", "r_min": 3, "f_min": 1, "m_min": 1,
     "color": "#F59E0B", "icon": "trending-up", "suggestion": "引导复购，培养忠诚度，提供首购优惠"},
    {"name": "风险客户", "name_en": "At Risk", "r_min": 1, "f_min": 3, "m_min": 3,
     "color": "#EF4444", "icon": "alert", "suggestion": "立即挽回，发送专属优惠券和关怀短信"},
    {"name": "沉睡客户", "name_en": "Hibernating", "r_min": 1, "f_min": 1, "m_min": 2,
     "color": "#8B5CF6", "icon": "sleep", "suggestion": "激活唤醒，推送新品信息和限时促销"},
    {"name": "流失客户", "name_en": "Lost", "r_min": 1, "f_min": 1, "m_min": 1,
     "color": "#6B7280", "icon": "account-off", "suggestion": "分析流失原因，低成本渠道触达"},
]


@router.get("/rfm-analysis")
async def get_rfm_analysis(
    factory_id: Optional[str] = Query(None, alias="factoryId"),
):
    """
    Calculate RFM segmentation for all customers.
    Returns connected=false (not fake data) when source table is empty.
    """
    try:
        conn = _get_db_connection()
        if conn:
            return _compute_rfm_from_db(conn, factory_id)
    except Exception as e:
        logger.warning(f"RFM DB computation failed: {e}")

    # DB unavailable — honest not-connected response, no fake data
    return JSONResponse(content=_NOT_CONNECTED)


def _compute_rfm_from_db(conn, factory_id: Optional[str]):
    """Compute RFM from sales_orders table.
    Returns connected=false when 0 rows, real segments when data exists.
    """
    import pandas as pd

    conditions = ["so.deleted_at IS NULL"]
    params: tuple = ()
    if factory_id:
        conditions.append("so.factory_id = %s")
        params = (factory_id,)

    where_clause = "WHERE " + " AND ".join(conditions)

    query = f"""
    SELECT
        so.customer_id,
        c.name as customer_name,
        MAX(so.order_date) as last_order_date,
        COUNT(DISTINCT so.id) as order_count,
        COALESCE(SUM(so.total_amount), 0) as total_amount
    FROM sales_orders so
    LEFT JOIN customers c ON so.customer_id = c.id
    {where_clause}
    GROUP BY so.customer_id, c.name
    HAVING COUNT(DISTINCT so.id) > 0
    """

    cursor = None
    try:
        cursor = conn.cursor()
        cursor.execute(query, params)
        rows = cursor.fetchall()
        columns = [desc[0] for desc in cursor.description]

        if not rows:
            # Source table present but zero customers — honest empty, no fake data
            return JSONResponse(content=_NOT_CONNECTED)

        df = pd.DataFrame(rows, columns=columns)
        today = datetime.now()

        # Calculate R, F, M raw values
        df["recency_days"] = df["last_order_date"].apply(
            lambda d: (today - d).days if d else 999
        )
        df["frequency"] = df["order_count"]
        df["monetary"] = df["total_amount"].astype(float)

        # Score 1-5 using quantiles
        for col, score_col in [
            ("recency_days", "r_score"),
            ("frequency", "f_score"),
            ("monetary", "m_score"),
        ]:
            try:
                df[score_col] = pd.qcut(
                    df[col],
                    5,
                    labels=[5, 4, 3, 2, 1] if col == "recency_days" else [1, 2, 3, 4, 5],
                    duplicates="drop",
                ).astype(int)
            except ValueError:
                df[score_col] = 3

        # Segment customers (higher-priority segments consume rows first)
        segments = []
        for seg_def in SEGMENT_DEFINITIONS:
            mask = (
                (df["r_score"] >= seg_def["r_min"]) &
                (df["f_score"] >= seg_def["f_min"]) &
                (df["m_score"] >= seg_def["m_min"])
            )
            seg_customers = df[mask]
            df = df[~mask]

            segments.append({
                "name": seg_def["name"],
                "nameEn": seg_def["name_en"],
                "count": len(seg_customers),
                "percentage": round(len(seg_customers) / max(len(rows), 1) * 100, 1),
                "avgRecency": round(seg_customers["recency_days"].mean(), 0) if len(seg_customers) > 0 else 0,
                "avgFrequency": round(seg_customers["frequency"].mean(), 1) if len(seg_customers) > 0 else 0,
                "avgMonetary": round(seg_customers["monetary"].mean(), 0) if len(seg_customers) > 0 else 0,
                "color": seg_def["color"],
                "icon": seg_def["icon"],
                "suggestion": seg_def["suggestion"],
            })

        # Add any remaining un-segmented rows to Lost
        if len(df) > 0:
            segments[-1]["count"] += len(df)
            segments[-1]["percentage"] = round(
                segments[-1]["count"] / max(len(rows), 1) * 100, 1
            )

        total = sum(s["count"] for s in segments)
        return JSONResponse(content={
            "success": True,
            "data": {
                "connected": True,
                "segments": segments,
                "totalCustomers": total,
                "analysisDate": today.strftime("%Y-%m-%d"),
            },
            "message": "RFM分析完成",
        })

    except Exception as e:
        logger.error(f"RFM SQL error: {e}")
        # On any error, return honest not-connected, no fake data
        return JSONResponse(content=_NOT_CONNECTED)
    finally:
        if cursor:
            try:
                cursor.close()
            except Exception:
                pass
