"""BusinessOverviewSummary — 营业概况报表头部 KPI + 门店排名.

营业概况报表 is a POS-exported DAY-level aggregate (每行 = 一家门店的一天),
very different from qhj 订单销售明细 (order-level). Columns seen in 桂满陇:
  日期 / 门店名称 / 门店编码 / 营业额 / 折扣额 / 实收额 /
  订单数 / 单均消费 / 客流量 / 人均消费 / 翻台率 / 会员充值金额 / ...

This template produces the at-a-glance monthly summary a restaurant manager
actually wants: headline totals + daily trend + top 门店 ranking. It does NOT
re-derive 单均消费/人均消费 from raw orders (those came pre-computed in the
export); it surfaces them as-is.

Applies when schema has 营业额 + 订单数 columns and a row count >= 30 (i.e.,
enough store-days to be an "overview", not a one-off upload). Self-filters
out qhj order-level data because qhj has 营业额 but no 订单数 as a column.
"""
from __future__ import annotations

from typing import Any, ClassVar, Dict, List, Optional

import polars as pl

from smartbi.capability.contract import RequiresSpec

from ..compute.base import ComputeBackend
from ..restaurant import industry_benchmarks as bench
from ..restaurant.action_rec_formatter import format_action_rec
from ..restaurant.schema_helpers import (
    find_customer_col, find_date_col, find_store_col,
)
from ..schema import DataSchema, Domain
from .base import AnalysisTemplate, TemplateResult
from .registry import register

_REVENUE_CANDIDATES = ("营业额", "实收额", "实收", "销售额")
_ORDER_COUNT_CANDIDATES = ("订单数", "单数", "笔数", "桌数")
_AVG_PER_ORDER_CANDIDATES = ("单均消费", "客单价", "单均")
_AVG_PER_CUSTOMER_CANDIDATES = ("人均消费", "实际人均", "客均")
_TURN_RATE_CANDIDATES = ("翻台率",)
_DISCOUNT_CANDIDATES = ("折扣额", "优惠额", "代金券优惠")

_MIN_ROWS = 30
_TOP_N_STORES = 10

# Summary-row labels that aggregate POS exports embed inline. These will
# double-count against the day-level totals if included, so filter at start
# of compute(). Same pattern as PolarsBackend._META_LABELS.
# _META_LABELS 从 polars_backend 导入 —— 这里曾经各抄一份且抄漏了
# (2026-08-10 实测: 本文件当时只有 7 个词, 权威有 12 个),
# 认不出的汇总行会被当成明细算进去。判据: 这种判据只能有一处定义。
from smartbi.services.materialized_analytics.compute.polars_backend import _META_LABELS  # noqa: E402
@register
class BusinessOverviewSummary(AnalysisTemplate):

    sample_queries = [
        "营业概况",
        "月度汇总",
        "门店日报",
        "营业汇总报表",
        "经营概览",
        "门店业绩汇总",
    ]

    # applies() gates on 营业额(gross_amount) + 订单数 + ≥30 rows. 订单数 is
    # day-aggregated (POS pre-aggregate report), no canonical equivalent yet
    # in ALIAS_TO_ATTR. We surface the date + revenue dependency since those
    # ARE canonical; the 订单数 implicit dependency is documented but not
    # enforced (per spec — defer to None would be too strict).
    requires: ClassVar[RequiresSpec | None] = RequiresSpec(
        all=["date"],
        any=["gross_amount", "net_amount"],
        description="营业概况报表 — 需 date + 营收口径(gross_amount/net_amount)；额外依赖 订单数(POS 预聚合,未 canonical)",
    )

    @property
    def code(self) -> str:
        return "business_overview_summary"

    @property
    def title(self) -> str:
        return "营业概况汇总 (门店 × 天)"

    def applies(self, schema: DataSchema) -> bool:
        if schema.domain not in (Domain.RESTAURANT, Domain.UNKNOWN):
            return False
        names = {f.name for f in schema.fields}
        has_revenue = any(c in names for c in _REVENUE_CANDIDATES)
        has_order_count = any(c in names for c in _ORDER_COUNT_CANDIDATES)
        # 订单数 as a COLUMN (not as a count of rows) signals this is an
        # aggregate-level report, not an order-level export. Self-excludes qhj.
        return has_revenue and has_order_count and schema.row_count >= _MIN_ROWS

    def compute(self, backend: ComputeBackend, schema: DataSchema) -> TemplateResult:
        df = backend._df  # type: ignore[attr-defined]
        cols = set(df.columns)

        rev_col = next((c for c in _REVENUE_CANDIDATES if c in cols), None)
        order_col = next((c for c in _ORDER_COUNT_CANDIDATES if c in cols), None)
        avg_order_col = next((c for c in _AVG_PER_ORDER_CANDIDATES if c in cols), None)
        avg_cust_col = next((c for c in _AVG_PER_CUSTOMER_CANDIDATES if c in cols), None)
        turn_col = next((c for c in _TURN_RATE_CANDIDATES if c in cols), None)
        discount_col = next((c for c in _DISCOUNT_CANDIDATES if c in cols), None)
        cust_col = find_customer_col(cols)
        date_col = find_date_col(cols)
        store_col = find_store_col(cols)

        if rev_col is None or order_col is None:
            return TemplateResult(
                code=self.code, title=self.title, data={},
                applies=False, skip_reason="missing 营业额 or 订单数 column",
            )

        # Drop 合计/总计/小计 summary rows that POS exports embed. Without this
        # a "合计" row gets double-counted with daily rows, inflating totals
        # ~2x, and also shows up as "peak_day" in the trend chart.
        meta_filter = pl.lit(True)
        for mc in (date_col, store_col):
            if mc is not None:
                meta_filter = meta_filter & ~(
                    pl.col(mc).cast(pl.Utf8, strict=False).fill_null("").is_in(
                        list(_META_LABELS)
                    )
                )
        df = df.filter(meta_filter)

        def _sum(col: Optional[str]) -> float:
            if col is None:
                return 0.0
            try:
                return float(df.select(
                    pl.col(col).cast(pl.Float64, strict=False).fill_null(0.0).sum()
                ).item() or 0.0)
            except Exception:
                return 0.0

        def _avg(col: Optional[str]) -> Optional[float]:
            if col is None:
                return None
            try:
                v = df.select(
                    pl.col(col).cast(pl.Float64, strict=False).mean()
                ).item()
                return float(v) if v is not None else None
            except Exception:
                return None

        total_revenue = _sum(rev_col)
        total_orders = _sum(order_col)
        total_customers = _sum(cust_col) if cust_col else 0.0
        total_discount = _sum(discount_col) if discount_col else 0.0

        # Derived averages (reconstruct from totals — more accurate than
        # averaging the per-day per-store pre-computed avg columns, which
        # would double-average). Fall back to column avg if derivation fails.
        derived_avg_per_order = round(total_revenue / total_orders, 2) if total_orders > 0 else None
        derived_avg_per_customer = (
            round(total_revenue / total_customers, 2) if total_customers > 0 else None
        )
        avg_turn_rate = _avg(turn_col)  # simple mean of daily turn rates
        fallback_avg_per_order = _avg(avg_order_col)
        fallback_avg_per_customer = _avg(avg_cust_col)

        avg_per_order = derived_avg_per_order or fallback_avg_per_order
        avg_per_customer = derived_avg_per_customer or fallback_avg_per_customer

        # Per-store ranking (Top N by 营业额)
        by_store: List[Dict[str, Any]] = []
        if store_col:
            agg_exprs = [
                pl.col(rev_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("revenue"),
                pl.col(order_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("orders"),
            ]
            if cust_col:
                agg_exprs.append(
                    pl.col(cust_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("customers")
                )
            rows = (
                df.filter(pl.col(store_col).is_not_null())
                .group_by(store_col)
                .agg(agg_exprs)
                .sort("revenue", descending=True)
                .head(_TOP_N_STORES)
                .to_dicts()
            )
            for r in rows:
                rev = float(r["revenue"] or 0.0)
                orders = float(r["orders"] or 0.0)
                cust = float(r.get("customers") or 0.0) if cust_col else 0.0
                share = round(rev / total_revenue * 100, 2) if total_revenue > 0 else 0.0
                by_store.append({
                    "store": str(r.get(store_col) or "<空>"),
                    "revenue": round(rev, 2),
                    "orders": int(orders),
                    "customers": int(cust) if cust_col else None,
                    "avg_per_order": round(rev / orders, 2) if orders > 0 else None,
                    "avg_per_customer": round(rev / cust, 2) if cust > 0 else None,
                    "share_pct": share,
                })

        # Daily trend
        by_date: List[Dict[str, Any]] = []
        if date_col:
            rows = (
                df.filter(pl.col(date_col).is_not_null())
                .with_columns(
                    pl.col(date_col).cast(pl.Utf8, strict=False).str.slice(0, 10).alias("_day")
                )
                .group_by("_day")
                .agg([
                    pl.col(rev_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("revenue"),
                    pl.col(order_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("orders"),
                ])
                .sort("_day")
                .to_dicts()
            )
            by_date = [
                {
                    "date": str(r.get("_day") or ""),
                    "revenue": round(float(r["revenue"] or 0.0), 2),
                    "orders": int(float(r["orders"] or 0.0)),
                }
                for r in rows
            ]

        # Peak day / bottom day
        peak_day = max(by_date, key=lambda d: d["revenue"]) if by_date else None
        low_day = min(by_date, key=lambda d: d["revenue"]) if by_date else None

        # Build insight
        parts: List[str] = []
        parts.append(
            f"期内合计 营业额 {total_revenue:,.0f} 元、订单 {int(total_orders):,} 单"
        )
        if total_customers > 0:
            parts[-1] += f"、客流 {int(total_customers):,} 人"
        parts[-1] += "。"
        if avg_per_order is not None:
            parts.append(f"单均 {avg_per_order:.0f} 元")
            if avg_per_customer is not None:
                parts[-1] += f"，人均 {avg_per_customer:.0f} 元"
            parts[-1] += "。"
        if by_store:
            top_s = by_store[0]
            parts.append(
                f"最大门店:{top_s['store']}（{top_s['revenue']:,.0f} 元，"
                f"占 {top_s['share_pct']:.1f}%）。"
            )
        if peak_day and low_day and peak_day != low_day:
            parts.append(
                f"最高日 {peak_day['date']}（{peak_day['revenue']:,.0f} 元）"
                f"、最低日 {low_day['date']}（{low_day['revenue']:,.0f} 元）。"
            )
        if avg_turn_rate is not None:
            parts.append(f" 平均翻台率 {avg_turn_rate:.2f}。")
        if total_discount > 0 and total_revenue > 0:
            discount_pct = round(total_discount / total_revenue * 100, 2)
            parts.append(f" 折扣/优惠累计 {total_discount:,.0f} 元 (占营业额 {discount_pct:.1f}%)。")
        parts.append(bench.industry_footer_by_context("business_overview"))
        # Spec §4.3: drive Top vs bottom store gap into SOP transfer
        if len(by_store) >= 3:
            top_s = by_store[0]
            bottom_s = by_store[-1]
            gap_pct = round((top_s["revenue"] - bottom_s["revenue"]) / top_s["revenue"] * 100, 0) \
                if top_s["revenue"] > 0 else 0
            action_rec = format_action_rec(
                object_target=f"末位「{bottom_s['store']}」 (与 Top「{top_s['store']}」差距 {gap_pct:.0f}%)",
                benefit_range="复制 Top 门店 SOP + 客流引导可拉高末位营业额 10-25%",
                prerequisite=f"对标走访{top_s['store']} + 末位店店长 KPI 重设 + 公司层面调度支持",
                timeline="本季度内",
            )
        else:
            action_rec = format_action_rec(
                object_target=f"全 {len(by_store) or 1} 家门店运营",
                benefit_range="持续监控翻台率 / 客单 / 折扣占比,异常波动 5%+ 立即复盘",
                prerequisite="周期性同比 / 环比对标 + 行业基准对照",
                timeline="本月内",
            )
        insight_text = " ".join(parts) + " " + action_rec

        # ECharts bar — store ranking
        chart_config = None
        if by_store:
            chart_config = {
                "type": "bar",
                "title": {"text": "门店营业额 Top", "left": "center"},
                "tooltip": {"trigger": "axis", "axisPointer": {"type": "shadow"}},
                "xAxis": {
                    "type": "category",
                    "data": [s["store"] for s in by_store],
                    "axisLabel": {"rotate": 45, "interval": 0, "fontSize": 10},
                },
                "yAxis": {"type": "value", "name": "营业额 (元)"},
                "series": [{
                    "name": "营业额",
                    "type": "bar",
                    "data": [s["revenue"] for s in by_store],
                    "itemStyle": {"color": "#1e88e5"},
                    "label": {"show": True, "position": "top"},
                }],
                "grid": {"left": "3%", "right": "8%", "bottom": "25%", "containLabel": True},
            }

        return TemplateResult(
            code=self.code,
            title=self.title,
            data={
                "total_revenue": round(total_revenue, 2),
                "total_orders": int(total_orders),
                "total_customers": int(total_customers) if cust_col else None,
                "total_discount": round(total_discount, 2) if discount_col else None,
                "avg_per_order": avg_per_order,
                "avg_per_customer": avg_per_customer,
                "avg_turn_rate": round(avg_turn_rate, 4) if avg_turn_rate is not None else None,
                "by_store": by_store,
                "by_date": by_date,
                "peak_day": peak_day,
                "low_day": low_day,
                "columns_used": {
                    "revenue": rev_col, "orders": order_col, "customers": cust_col,
                    "date": date_col, "store": store_col,
                    "turn_rate": turn_col, "discount": discount_col,
                },
            },
            chart_config=chart_config,
            kpis={
                "total_revenue": round(total_revenue, 2),
                "total_orders": int(total_orders),
                "total_customers": int(total_customers) if cust_col else None,
                "avg_per_order": avg_per_order,
                "avg_per_customer": avg_per_customer,
                "top_store": by_store[0]["store"] if by_store else None,
                "peak_date": peak_day["date"] if peak_day else None,
            },
            insight_text=insight_text,
        )
