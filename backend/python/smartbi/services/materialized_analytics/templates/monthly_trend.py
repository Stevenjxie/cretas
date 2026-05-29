"""MonthlyTrend — daily/weekly/monthly time series of primary measure.

Auto-picks frequency: <= 62 days → daily, <= 400 days → weekly, else monthly.
"""
from __future__ import annotations

from typing import ClassVar

from smartbi.capability.contract import RequiresSpec

from ..compute.base import ComputeBackend
from ..restaurant.action_rec_formatter import format_action_rec
from ..restaurant.schema_helpers import aggregation_for_measure, measure_unit
from ..schema import DataSchema
from .base import AnalysisTemplate, TemplateResult
from .registry import register


@register
class MonthlyTrend(AnalysisTemplate):

    sample_queries = [
        "月度趋势",
        "营收走势",
        "按月增长",
        "营业额变化",
        "月份趋势线",
        "营收最高的月份",
        "哪个月营业额最高",
        "峰值月份",
        "最旺的月份",
        "月度销售排名",
    ]

    requires: ClassVar[RequiresSpec | None] = RequiresSpec(
        all=["date"],
        any=["gross_amount", "net_amount", "revenue"],
        description="月度营收趋势",
    )

    @property
    def code(self) -> str:
        return "monthly_trend"

    @property
    def title(self) -> str:
        return "时间趋势"

    def applies(self, schema: DataSchema) -> bool:
        return schema.time_field is not None and schema.primary_measure is not None

    def compute(self, backend: ComputeBackend, schema: DataSchema) -> TemplateResult:
        time_col = schema.time_field
        measure = schema.primary_measure

        # May 30 2026: intensive measures (星级分/评分/率/客单价) must aggregate
        # with AVG per period, not SUM. SUM produces "星级分 累计 6.23万 分"
        # nonsense; AVG gives the meaningful mean rating per period (~4.8).
        agg = aggregation_for_measure(measure)
        is_avg = agg == "avg"
        unit = measure_unit(measure)

        # Probe daily first; downsample if too many points
        daily = backend.time_series(time_col, measure, "D", agg=agg)
        if not daily:
            return TemplateResult(
                code=self.code, title=self.title, data={},
                applies=False, skip_reason="no valid time values",
            )

        freq_used = "D"
        series = daily
        if len(daily) > 62:
            weekly = backend.time_series(time_col, measure, "W", agg=agg)
            if len(weekly) > 60:
                series = backend.time_series(time_col, measure, "M", agg=agg)
                freq_used = "M"
            else:
                series = weekly
                freq_used = "W"

        # For AVG measures the headline is the mean across periods (an average
        # of averages — directionally correct for a rating trend), NOT a sum.
        if is_avg:
            total = round(sum(r["total"] for r in series) / len(series), 2)
        else:
            total = sum(r["total"] for r in series)
        peak = max(series, key=lambda r: r["total"])
        trough = min(series, key=lambda r: r["total"])

        chart_config = {
            "type": "line",
            "title": {"text": f"{measure} 时间趋势 ({freq_used})", "left": "center"},
            "xAxis": {"type": "category", "data": [r["period"] for r in series]},
            "yAxis": {"type": "value", "name": measure},
            "series": [{
                "name": measure, "type": "line", "smooth": True,
                "data": [r["total"] for r in series],
                "markPoint": {"data": [
                    {"name": "峰", "coord": [peak["period"], peak["total"]]},
                    {"name": "谷", "coord": [trough["period"], trough["total"]]},
                ]},
            }],
            "tooltip": {"trigger": "axis"},
        }

        # Spec §4.3: drive trough vs peak gap into seasonal action plan
        gap_pct = round((peak["total"] - trough["total"]) / peak["total"] * 100, 0) if peak["total"] > 0 else 0
        if gap_pct >= 30:
            action_rec = format_action_rec(
                object_target=f"谷值周期 {trough['period']} (低于峰值 {gap_pct:.0f}%)",
                benefit_range=f"谷值时段定向促销 + 套餐 + 会员唤回可拉高谷值 {measure} 10-25%",
                prerequisite="复盘谷值时段成因 (淡季 / 节假日错峰 / 活动空窗) + 设计针对性方案",
                timeline="本月内",
            )
        else:
            action_rec = format_action_rec(
                object_target=f"峰值 {peak['period']} ({peak['total']:,.0f}) 与谷值 {trough['period']}",
                benefit_range="复刻峰值期运营手段到平日,可缩小波动 10-15%",
                prerequisite="对标峰值时段促销 / 排班 / 物料 + 复盘可复用方案",
                timeline="本月内",
            )
        freq_label = {'D': '日', 'W': '周', 'M': '月'}.get(freq_used, freq_used)
        if is_avg:
            # Rating/rate trend: headline = period average, not 累计/元.
            u = unit or "分"
            kpis = {
                "avg_value": total,
                "peak_period": peak["period"],
                "peak_value": round(peak["total"], 2),
                "trough_period": trough["period"],
                "trough_value": round(trough["total"], 2),
                "period_count": len(series),
                "aggregation": "avg",
                "unit": u,
            }
            insight_text = (
                f"{measure} 平均 {total:.2f} {u},峰值 {peak['period']} "
                f"({peak['total']:.2f} {u}),谷值 {trough['period']} "
                f"({trough['total']:.2f} {u})。按{freq_label}聚合,共 {len(series)} 个周期。 {action_rec}"  # noqa: E501
            )
        else:
            u = unit or "元"
            kpis = {
                "total_revenue": total,
                "peak_period": peak["period"],
                "peak_value": peak["total"],
                "trough_period": trough["period"],
                "trough_value": trough["total"],
                "period_count": len(series),
                "aggregation": "sum",
                "unit": u,
            }
            insight_text = (
                f"{measure} 累计 {total:,.0f} {u},峰值 {peak['period']} "
                f"({peak['total']:,.0f} {u}),谷值 {trough['period']} "
                f"({trough['total']:,.0f} {u})。按{freq_label}聚合,共 {len(series)} 个周期。 {action_rec}"  # noqa: E501
            )
        return TemplateResult(
            code=self.code, title=self.title,
            data={"series": series, "freq": freq_used, "aggregation": agg},
            chart_config=chart_config,
            kpis=kpis,
            insight_text=insight_text,
        )
