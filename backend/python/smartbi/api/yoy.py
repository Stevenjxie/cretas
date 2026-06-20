from __future__ import annotations
"""
YoY (Year-over-Year) Comparison API

Provides endpoints for comparing financial data across different upload periods.
Aligns rows by financial keywords and computes YoY growth rates.
"""
import logging
from typing import Any, Dict, List, Optional

from fastapi import APIRouter
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)
router = APIRouter(tags=["YoY Comparison"])


class YoYRequest(BaseModel):
    """Request for YoY comparison"""
    upload_id: int = Field(..., description="Current period upload ID")
    compare_upload_id: Optional[int] = Field(None, description="Previous period upload ID (auto-detect if omitted)")
    factory_id: str = Field(default="F001", description="Factory ID")


class ComparisonItem(BaseModel):
    """Single row comparison result"""
    label: str
    current_value: float
    previous_value: float
    yoy_growth: Optional[float] = None  # percentage
    category: str = "other"  # revenue, cost, profit, expense, other


class YoYResponse(BaseModel):
    """YoY comparison response"""
    success: bool
    current_upload_id: int
    compare_upload_id: Optional[int] = None
    current_period: Optional[str] = None
    compare_period: Optional[str] = None
    comparison: List[ComparisonItem] = []
    summary: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


# Financial keyword categories for row matching
FINANCIAL_KEYWORDS = {
    "revenue": ["营业收入", "主营业务收入", "收入合计", "Revenue"],
    "cost": ["营业成本", "主营业务成本", "成本合计"],
    "gross_profit": ["毛利润", "毛利", "Gross Profit"],
    "net_profit": ["净利润", "利润总额", "Net Profit"],
    "expense": ["销售费用", "管理费用", "财务费用", "研发费用"],
}


def _classify_row(label: str) -> str:
    """Classify a financial row by its label"""
    for category, keywords in FINANCIAL_KEYWORDS.items():
        if any(kw in label for kw in keywords):
            return category
    return "other"


def _extract_period_from_columns(columns: List[str]) -> Optional[str]:
    """Extract data period (YYYY-MM) from date-like column names"""
    import re
    for col in columns:
        m = re.match(r'^(\d{4})-(\d{2})', str(col))
        if m:
            return f"{m.group(1)}-{m.group(2)}"
    return None


def _sum_numeric_values(row: Dict[str, Any], exclude_keys: set) -> float:
    """Sum all numeric values in a row, excluding specified keys"""
    total = 0.0
    for key, val in row.items():
        if key in exclude_keys:
            continue
        try:
            num = float(val) if val is not None else 0.0
            if not (num != num):  # NaN check
                total += num
        except (ValueError, TypeError):
            continue
    return total


@router.post("/yoy-comparison", response_model=YoYResponse)
async def yoy_comparison(request: YoYRequest):
    """
    Compare two uploads for Year-over-Year analysis.

    Matches financial rows by keyword and computes growth rates.
    If compare_upload_id is not provided, attempts to find the most recent
    previous upload with a similar sheet structure.
    """
    try:
        from smartbi.config import get_settings
        settings = get_settings()

        if not settings.postgres_enabled:
            return YoYResponse(
                success=False,
                current_upload_id=request.upload_id,
                error="PostgreSQL not enabled. YoY comparison requires database access."
            )

        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()
        if not pool:
            return YoYResponse(
                success=False, current_upload_id=request.upload_id,
                error="Database pool not available"
            )

        async with pool.acquire() as conn:
            # Explicitly set tenant GUC — asyncpg pool `setup` runs only on
            # connection creation (not per-acquire), so the GUC may be stale.
            # smart_bi_pg_excel_uploads has FORCE ROW LEVEL SECURITY; without
            # the correct factory_id the SELECT returns 0 rows silently.
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)",
                request.factory_id
            )

            # 1. Load current upload metadata
            current_meta = await conn.fetchrow(
                "SELECT id, file_name, sheet_name, created_at FROM smart_bi_pg_excel_uploads WHERE id = $1",
                request.upload_id
            )
            if not current_meta:
                return YoYResponse(
                    success=False,
                    current_upload_id=request.upload_id,
                    error=f"Upload {request.upload_id} not found"
                )

            # 2. Find comparison upload
            compare_id = request.compare_upload_id
            if not compare_id:
                candidates = await conn.fetch(
                    """SELECT id, created_at FROM smart_bi_pg_excel_uploads
                       WHERE factory_id = $1 AND sheet_name = $2 AND id != $3
                       ORDER BY created_at DESC LIMIT 5""",
                    request.factory_id, current_meta['sheet_name'], request.upload_id
                )
                if candidates:
                    compare_id = candidates[0]['id']

            if not compare_id:
                return YoYResponse(
                    success=False,
                    current_upload_id=request.upload_id,
                    error="No comparable upload found. Please specify compare_upload_id."
                )

            # 3. Load both datasets
            current_rows = await conn.fetch(
                "SELECT row_data FROM smart_bi_dynamic_data WHERE upload_id = $1 ORDER BY id",
                request.upload_id
            )
            compare_rows = await conn.fetch(
                "SELECT row_data FROM smart_bi_dynamic_data WHERE upload_id = $1 ORDER BY id",
                compare_id
            )

            if not current_rows or not compare_rows:
                return YoYResponse(
                    success=False,
                    current_upload_id=request.upload_id,
                    compare_upload_id=compare_id,
                    error="One or both uploads have no data rows"
                )

            # 4. Parse JSONB row_data
            import json
            current_data = [json.loads(r['row_data']) if isinstance(r['row_data'], str) else r['row_data'] for r in current_rows]  # noqa: E501
            compare_data = [json.loads(r['row_data']) if isinstance(r['row_data'], str) else r['row_data'] for r in compare_rows]  # noqa: E501

            # 5. Detect label field (first text column)
            label_field = None
            if current_data:
                first_row = current_data[0]
                for key, val in first_row.items():
                    if isinstance(val, str) and val.strip():
                        label_field = key
                        break

            if not label_field:
                return YoYResponse(
                    success=False,
                    current_upload_id=request.upload_id,
                    compare_upload_id=compare_id,
                    error="Cannot detect label field in upload data"
                )

            # 6. Build label -> values maps
            exclude_keys = {label_field}
            current_map: Dict[str, float] = {}
            compare_map: Dict[str, float] = {}

            for row in current_data:
                label = str(row.get(label_field, '')).strip()
                if label:
                    current_map[label] = _sum_numeric_values(row, exclude_keys)

            for row in compare_data:
                label = str(row.get(label_field, '')).strip()
                if label:
                    compare_map[label] = _sum_numeric_values(row, exclude_keys)

            # 7. Match and compute YoY
            comparison: List[ComparisonItem] = []
            all_labels = list(dict.fromkeys(list(current_map.keys()) + list(compare_map.keys())))

            for label in all_labels:
                curr = current_map.get(label, 0.0)
                prev = compare_map.get(label, 0.0)
                yoy = ((curr - prev) / abs(prev) * 100) if prev != 0 else None
                category = _classify_row(label)

                comparison.append(ComparisonItem(
                    label=label,
                    current_value=round(curr, 2),
                    previous_value=round(prev, 2),
                    yoy_growth=round(yoy, 2) if yoy is not None else None,
                    category=category
                ))

            # 8. Summary metrics
            rev_item = next((c for c in comparison if c.category == "revenue"), None)
            np_item = next((c for c in comparison if c.category == "net_profit"), None)
            summary = {}
            if rev_item:
                summary["revenue_current"] = rev_item.current_value
                summary["revenue_previous"] = rev_item.previous_value
                summary["revenue_yoy"] = rev_item.yoy_growth
            if np_item:
                summary["net_profit_current"] = np_item.current_value
                summary["net_profit_previous"] = np_item.previous_value
                summary["net_profit_yoy"] = np_item.yoy_growth

            current_cols = list(current_data[0].keys()) if current_data else []
            compare_cols = list(compare_data[0].keys()) if compare_data else []

            return YoYResponse(
                success=True,
                current_upload_id=request.upload_id,
                compare_upload_id=compare_id,
                current_period=_extract_period_from_columns(current_cols),
                compare_period=_extract_period_from_columns(compare_cols),
                comparison=comparison,
                summary=summary
            )

    except Exception as e:
        logger.error(f"YoY comparison failed: {e}", exc_info=True)
        return YoYResponse(
            success=False,
            current_upload_id=request.upload_id,
            error="处理失败，请稍后重试"
        )
