from __future__ import annotations

"""Menu engineering section — Kasavana-Smith 4-quadrant classification.


Part of P3 Task 3.2. Wraps the P3.1 MenuEngineeringAnalyzer as a
FastAPI section endpoint. Classifies menu items into Star / Cash Cow /
Puzzle / Dog quadrants by popularity × profitability, returns the
full classification + recommendations.

Inputs (via context):
  pos_df (pd.DataFrame, required): POS sales data with columns
    product_col / quantity_col / revenue_col (defaults: 商品名称 / 数量 / 实收额)
  sku_food_costs (dict[str, float], required): per-dish food cost lookup.
    Populated by the orchestrator from SKU form data (BOM Layer 2+).
    Missing → SKIPPED (analyzer can't compute margins without costs).

Params (optional overrides):
  product_col, quantity_col, revenue_col — column name overrides
"""

import time
from typing import Any

from smartbi.services.restaurant.menu_engineering import MenuEngineeringAnalyzer
from smartbi.services.restaurant.sections.base import (
    AbstractSectionHandler,
    SectionRequest,
    SectionResponse,
)


class MenuEngineeringHandler(AbstractSectionHandler):
    """Kasavana-Smith 4-quadrant menu analyzer section."""

    section_name = "menu_engineering"

    def __init__(self) -> None:
        self._analyzer = MenuEngineeringAnalyzer()

    def compute(self, request: SectionRequest, context: dict[str, Any]) -> SectionResponse:
        started = time.time()

        # Prefer context (batch mode), fall back to params (standalone mode)
        pos_df = context.get("pos_df")
        if pos_df is None:
            pos_df = request.params.get("pos_df")
        if pos_df is None:
            return self.skipped(
                request,
                "未提供 POS DataFrame (需要 pos_df via context 或 params)",
                started,
            )

        # SKU food costs come from BOM Layer 2 data — check context first
        sku_costs = context.get("sku_food_costs") or request.params.get("sku_food_costs")
        if not sku_costs:
            return self.skipped(
                request,
                "缺少 SKU 食材成本数据 (sku_food_costs) — 需要上传 SKU form 或启用 BOM Layer 2+",
                started,
            )

        name_col = request.params.get("product_col", "商品名称")
        qty_col = request.params.get("quantity_col", "数量")
        revenue_col = request.params.get("revenue_col", "实收")

        if not hasattr(pos_df, "columns"):
            return self.skipped(
                request,
                "pos_df 不是 DataFrame (缺 columns 属性)",
                started,
            )

        if revenue_col not in pos_df.columns:
            for alias in ("实收额", "实收金额", "销售金额", "营业额"):
                if alias in pos_df.columns:
                    revenue_col = alias
                    break

        missing_cols = [c for c in (name_col, qty_col, revenue_col) if c not in pos_df.columns]
        if missing_cols:
            return self.skipped(
                request,
                f"POS 缺少必需列: {missing_cols}",
                started,
            )

        # Aggregate POS by dish name
        aggregated = (
            pos_df.groupby(name_col, as_index=False)
            .agg({qty_col: "sum", revenue_col: "sum"})
            .rename(columns={name_col: "name", qty_col: "sold_qty", revenue_col: "revenue"})
        )

        # Attach food_cost per dish from sku_costs lookup; fall back to 40% of revenue
        # when a dish isn't in the SKU table (matches P3.1 analyzer default).
        aggregated["food_cost"] = aggregated.apply(
            lambda r: sku_costs.get(r["name"], r["revenue"] * 0.4),
            axis=1,
        )

        try:
            report = self._analyzer.analyze(aggregated)
        except Exception as e:
            return self.skipped(
                request,
                f"菜品工程分析失败: {e}",
                started,
            )

        return self.ok(request, data=report.to_dict(), started=started)
