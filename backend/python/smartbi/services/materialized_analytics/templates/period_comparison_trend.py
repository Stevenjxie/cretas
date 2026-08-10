"""PeriodComparisonTrend — 跨周期对比 (同一上传内多期数据).

Triggered when an upload's data spans ≥2 distinct months (via 营业日期 /
交易日期 / 日期). Groups rows by yyyy-mm and reports M/M deltas on key
metrics (营业额 / 订单数 / 客流量 / 单均), plus DoD delta for the two
latest days.

Separate from monthly_trend which is a generic time-series; this is
specifically about PERIOD-OVER-PERIOD comparison with explicit Δ % callouts.
Helpful for "上个月 vs 本月 营业额涨了多少" type questions.
"""
from __future__ import annotations

from typing import Any, ClassVar, Dict, List, Optional

import polars as pl

from smartbi.capability.contract import RequiresSpec

from ..compute.base import ComputeBackend
from ..restaurant import industry_benchmarks as bench
from ..restaurant.action_rec_formatter import format_action_rec
from ..restaurant.schema_helpers import (
    find_customer_col, find_date_col, find_gross_revenue_col, find_net_revenue_col,
)
from ..schema import DataSchema, Domain
from .base import AnalysisTemplate, TemplateResult
from .registry import register

_ORDER_COUNT_CANDIDATES = ("订单数", "单数", "笔数", "桌数")
# _META_LABELS 从 polars_backend 导入 —— 这里曾经各抄一份且抄漏了
# (2026-08-10 实测: 本文件当时只有 4 个词, 权威有 12 个),
# 认不出的汇总行会被当成明细算进去。判据: 这种判据只能有一处定义。
from smartbi.services.materialized_analytics.compute.polars_backend import _META_LABELS  # noqa: E402
def _first(cols, candidates):
    s = set(cols)
    for c in candidates:
        if c in s:
            return c
    return None


def _pct_delta(prev: float, curr: float) -> Optional[float]:
    if prev is None or curr is None or prev == 0:
        return None
    return round((curr - prev) / abs(prev) * 100, 2)


@register
class PeriodComparisonTrend(AnalysisTemplate):

    sample_queries = [
        "月度营业额对比",
        "环比分析",
        "同比涨跌",
        "近期趋势",
        "月度对比",
    ]

    # applies() requires date + (gross_amount OR net_amount).
    requires: ClassVar[RequiresSpec | None] = RequiresSpec(
        all=["date"],
        any=["gross_amount", "net_amount"],
        description="周期对比趋势 — 需 date + 营收口径(gross_amount 或 net_amount)",
    )

    @property
    def code(self) -> str:
        return "period_comparison_trend"

    @property
    def title(self) -> str:
        return "周期对比趋势 (月度/近期)"

    def applies(self, schema: DataSchema) -> bool:
        if schema.domain not in (Domain.RESTAURANT, Domain.UNKNOWN):
            return False
        names = {f.name for f in schema.fields}
        return find_date_col(names) is not None and (
            find_gross_revenue_col(names) is not None
            or find_net_revenue_col(names) is not None
        )

    def compute(self, backend: ComputeBackend, schema: DataSchema) -> TemplateResult:
        df = backend._df  # type: ignore[attr-defined]
        cols = set(df.columns)
        date_col = find_date_col(cols)
        rev_col = find_gross_revenue_col(cols) or find_net_revenue_col(cols)
        order_col = _first(cols, _ORDER_COUNT_CANDIDATES)
        cust_col = find_customer_col(cols)
        if date_col is None or rev_col is None:
            return TemplateResult(code=self.code, title=self.title, data={},
                                  applies=False, skip_reason="missing date or revenue col")

        # Meta-row filter (合计/总计/...)
        df = df.filter(
            ~pl.col(date_col).cast(pl.Utf8, strict=False).fill_null("").is_in(list(_META_LABELS))
        )

        # Add yyyy-mm and yyyy-mm-dd columns
        df = df.with_columns([
            pl.col(date_col).cast(pl.Utf8, strict=False).str.slice(0, 7).alias("_ym"),
            pl.col(date_col).cast(pl.Utf8, strict=False).str.slice(0, 10).alias("_ymd"),
        ])

        # ── Monthly aggregates ──
        month_agg_exprs = [
            pl.col(rev_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("revenue"),
        ]
        if order_col:
            month_agg_exprs.append(
                pl.col(order_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("orders")
            )
        else:
            month_agg_exprs.append(pl.len().alias("orders"))  # row count as proxy
        if cust_col:
            month_agg_exprs.append(
                pl.col(cust_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("customers")
            )

        months_df = (
            df.filter(pl.col("_ym").is_not_null() & (pl.col("_ym") != ""))
            .group_by("_ym").agg(month_agg_exprs).sort("_ym")
        )
        months = months_df.to_dicts()

        if len(months) < 2:
            return TemplateResult(code=self.code, title=self.title, data={},
                                  applies=False, skip_reason=f"only {len(months)} month(s) of data; need >=2 for comparison")  # noqa: E501

        # Enrich each month with deltas vs previous
        monthly: List[Dict[str, Any]] = []
        prev: Optional[Dict[str, Any]] = None
        for m in months:
            rev = float(m.get("revenue") or 0.0)
            orders = float(m.get("orders") or 0.0)
            cust = float(m.get("customers") or 0.0) if cust_col else None
            avg_order = round(rev / orders, 2) if orders > 0 else None
            avg_cust = round(rev / cust, 2) if cust and cust > 0 else None
            row = {
                "month": str(m.get("_ym") or ""),
                "revenue": round(rev, 2),
                "orders": int(orders),
                "customers": int(cust) if cust is not None else None,
                "avg_per_order": avg_order,
                "avg_per_customer": avg_cust,
            }
            if prev:
                row["revenue_delta_pct"] = _pct_delta(prev["revenue"], rev)
                row["orders_delta_pct"] = _pct_delta(prev["orders"], orders)
                if cust is not None and prev.get("customers") is not None:
                    row["customers_delta_pct"] = _pct_delta(prev["customers"], cust)
            monthly.append(row)
            prev = {"revenue": rev, "orders": orders, "customers": cust}

        # ── Daily aggregates for last-7 / DoD comparison ──
        days_df = (
            df.filter(pl.col("_ymd").is_not_null() & (pl.col("_ymd") != ""))
            .group_by("_ymd").agg([
                pl.col(rev_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("revenue"),
            ]).sort("_ymd")
        )
        daily = days_df.to_dicts()
        last_7: List[Dict[str, Any]] = []
        for r in daily[-7:]:
            last_7.append({
                "date": str(r.get("_ymd") or ""),
                "revenue": round(float(r.get("revenue") or 0.0), 2),
            })

        dod_delta_pct = None
        if len(daily) >= 2:
            prev_d = float(daily[-2].get("revenue") or 0.0)
            curr_d = float(daily[-1].get("revenue") or 0.0)
            dod_delta_pct = _pct_delta(prev_d, curr_d)

        # Build insight
        latest = monthly[-1]
        parts = [
            f"期内覆盖 {len(monthly)} 个月，最新 {latest['month']} 营业额 {latest['revenue']:,.0f} 元"
        ]
        if latest.get("revenue_delta_pct") is not None:
            sign = "↑" if latest["revenue_delta_pct"] >= 0 else "↓"
            parts[-1] += f" ({sign}{abs(latest['revenue_delta_pct']):.1f}% 环比)"
        parts[-1] += "。"
        if len(monthly) >= 3:
            # Check overall trend direction
            deltas = [m.get("revenue_delta_pct") for m in monthly[1:] if m.get("revenue_delta_pct") is not None]
            if deltas and all(d > 0 for d in deltas):
                parts.append("整体月度连续上涨 ↑。")
            elif deltas and all(d < 0 for d in deltas):
                parts.append("整体月度连续下滑 ↓，需干预。")
        if dod_delta_pct is not None:
            sign = "↑" if dod_delta_pct >= 0 else "↓"
            parts.append(f"最后两天 DoD {sign}{abs(dod_delta_pct):.1f}%。")
        # Compare to industry dine-in avg price trend
        if latest.get("revenue_delta_pct") is not None:
            local = latest["revenue_delta_pct"]
            industry = bench.DINE_IN_AVG_PRICE_YOY_PCT_2024
            if local > industry + 5:  # outperform industry by >5 points
                parts.append(f"🎯 远超行业同期 ({industry:+.1f}%),表现优异。")
            elif local < industry - 5:
                parts.append(f"⚠ 落后行业同期 ({industry:+.1f}%),需关注。")

        # K2 / C-rec 8: append spec §4.3 action rec (a对象 b收益区间 c前置 d时间窗)
        latest_delta = latest.get("revenue_delta_pct")
        if latest_delta is not None and latest_delta < -10.0:
            # Sharp drop — diagnose & remediate
            action_rec = format_action_rec(
                object_target=f"{latest['month']} 营收下滑 ({latest_delta:+.1f}%)",
                benefit_range="找到 2-3 个核心原因后干预可挽回 50-80% 跌幅",
                prerequisite="按渠道/门店/品类拆分 + 对比促销日历 / 客流/天气数据",
                timeline="本周内",
            )
        elif latest_delta is not None and latest_delta > 10.0:
            # Sharp rise — replicate
            action_rec = format_action_rec(
                object_target=f"{latest['month']} 营收涨幅 ({latest_delta:+.1f}%) 复盘",
                benefit_range=f"提炼成功 SOP 复制到下月预计可保住 60-80% 涨幅 (¥{latest['revenue'] * 0.06:,.0f})",
                prerequisite="复盘该月促销/新品/客流来源 + 销售团队访谈",
                timeline="近 30 天",
            )
        elif dod_delta_pct is not None and dod_delta_pct < -20.0:
            action_rec = format_action_rec(
                object_target=f"近 2 日 DoD 跌幅 ({dod_delta_pct:+.1f}%)",
                benefit_range="48 小时内介入可避免短期客流流失 5-15%",
                prerequisite="检查门店开门状态 / 促销节奏 / 平台评分突变",
                timeline="本周内",
            )
        else:
            action_rec = format_action_rec(
                object_target=f"周期趋势平稳 ({len(monthly)} 个月)",
                benefit_range="设定下月营收目标比上月 +3-5% 的渐进增长",
                prerequisite="拉取上月销售/客流明细 + 制定 1-2 个新促销节点",
                timeline="本月内",
            )
        parts.append(action_rec)

        parts.append(bench.industry_footer_by_context("revenue"))
        insight_text = " ".join(parts)

        # ECharts — month bars with delta labels
        chart_config = {
            "type": "bar",
            "title": {"text": "月度营业额对比", "left": "center"},
            "tooltip": {"trigger": "axis", "axisPointer": {"type": "shadow"}},
            "xAxis": {"type": "category", "data": [m["month"] for m in monthly]},
            "yAxis": {"type": "value", "name": "营业额 (元)"},
            "series": [{
                "name": "营业额", "type": "bar",
                "data": [m["revenue"] for m in monthly],
                "label": {"show": True, "position": "top", "formatter": "{c}"},
                "itemStyle": {"color": "#1e88e5"},
            }],
            "grid": {"left": "3%", "right": "8%", "bottom": "10%", "containLabel": True},
        }

        return TemplateResult(
            code=self.code, title=self.title,
            data={
                "monthly": monthly,
                "last_7_days": last_7,
                "month_count": len(monthly),
                "dod_delta_pct": dod_delta_pct,
                "columns_used": {"date": date_col, "revenue": rev_col, "orders": order_col, "customers": cust_col},
            },
            chart_config=chart_config,
            kpis={
                "month_count": len(monthly),
                "latest_month": latest["month"],
                "latest_revenue": latest["revenue"],
                "latest_mom_delta_pct": latest.get("revenue_delta_pct"),
                "dod_delta_pct": dod_delta_pct,
            },
            insight_text=insight_text,
        )
