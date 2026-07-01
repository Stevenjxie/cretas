from __future__ import annotations

"""Multi-store comparison section — ranks restaurant chain stores + anomalies.


Wraps ``MultiStoreComparator.compare()`` for chain-level analysis. Requires
a POS DataFrame containing a 门店名称 column with ≥2 unique stores. Otherwise
returns SKIPPED gracefully (single-store factories don't need this section).

Supported params (all optional, with sensible Chinese-POS defaults):
  - ``pos_df``: pandas DataFrame
  - ``store_col`` (default "门店名称")
  - ``revenue_col`` (default "实收")
  - ``product_col`` (default "商品名称")
  - ``quantity_col`` (default "数量")
  - ``reviews``: optional list[dict] of dianping reviews for rating overlay
"""

import time
from typing import Any

from smartbi.services.restaurant.sections.base import (
    AbstractSectionHandler,
    SectionRequest,
    SectionResponse,
)


class MultiStoreComparisonHandler(AbstractSectionHandler):
    """Wraps :class:`MultiStoreComparator` for chain-level store ranking + anomalies."""
    section_name = "multi_store_comparison"

    def __init__(self) -> None:
        self._comparator = None

    def _get_comparator(self):
        """Lazy-build the stateless comparator."""
        if self._comparator is None:
            from smartbi.services.restaurant.multi_store_comparator import (
                MultiStoreComparator,
            )
            self._comparator = MultiStoreComparator()
        return self._comparator

    def compute(self, request: SectionRequest, context: dict[str, Any]) -> SectionResponse:
        started = time.time()

        # NOTE: explicit `is None` checks — pandas DataFrames raise ValueError
        # on `or` / truthiness.
        pos_df = context.get("pos_df")
        if pos_df is None:
            pos_df = request.params.get("pos_df")
        if pos_df is None:
            return self.skipped(request, "未提供 POS DataFrame", started)

        if not hasattr(pos_df, "columns"):
            return self.skipped(request, "pos_df 不是 DataFrame (缺 columns 属性)", started)

        store_col = request.params.get("store_col", "门店名称")
        revenue_col = request.params.get("revenue_col", "实收")
        product_col = request.params.get("product_col", "商品名称")
        quantity_col = request.params.get("quantity_col", "数量")

        if revenue_col not in pos_df.columns:
            for alias in ("实收额", "实收金额", "销售金额", "营业额"):
                if alias in pos_df.columns:
                    revenue_col = alias
                    break

        if store_col not in pos_df.columns:
            return self.skipped(request, f"POS 缺列 {store_col!r}", started)
        if revenue_col not in pos_df.columns:
            return self.skipped(request, f"POS 缺列 {revenue_col!r}", started)
        if product_col not in pos_df.columns:
            return self.skipped(request, f"POS 缺列 {product_col!r}", started)

        unique_stores = int(pos_df[store_col].dropna().nunique())
        if unique_stores < 2:
            return self.skipped(
                request,
                f"POS 仅含 {unique_stores} 个门店, 多店对比需要 ≥2 个",
                started,
            )

        # reviews can come from request.params (standalone) or context (batch)
        reviews = request.params.get("reviews")
        if reviews is None:
            reviews = context.get("reviews")

        try:
            report = self._get_comparator().compare(
                pos_df=pos_df,
                revenue_col=revenue_col,
                product_col=product_col,
                store_col=store_col,
                quantity_col=quantity_col,
                reviews=reviews,
            )
        except Exception as e:
            return self.skipped(request, f"multi_store 对比失败: {e}", started)

        if hasattr(report, "to_dict"):
            data = report.to_dict()
        elif isinstance(report, dict):
            data = report
        else:
            return self.skipped(
                request,
                f"multi_store 返回非 dict 类型: {type(report).__name__}",
                started,
            )

        return self.ok(request, data=data, started=started)
