"""MonthlyAnomaly — 月度异常波动检测 (pre-aggregated then detect).

Regular anomaly_detection template runs on row-level data and gets drowned
out — a -33% monthly dip averages out at row level. This template first
aggregates to monthly totals, then flags outlier months using two signals:
  1. MoM delta magnitude: |Δ%| ≥ 20% is "significant"; ≥ 30% is "alert"
  2. z-score across 12 months: |z| ≥ 1.5 flags outliers vs trend

Reports: each anomalous month with root-cause hints (if dimension-split is
available).
"""
from __future__ import annotations

import statistics
from typing import Any, ClassVar, Dict, List, Optional

import polars as pl

from smartbi.capability.contract import RequiresSpec

from ..compute.base import ComputeBackend
from ..restaurant.action_rec_formatter import format_action_rec
from ..restaurant.schema_helpers import (
    find_date_col, find_gross_revenue_col, find_net_revenue_col,
    preferred_revenue_col,
)
from ..schema import DataSchema, Domain
from .base import AnalysisTemplate, TemplateResult
from .registry import register

# _META_LABELS 从 polars_backend 导入 —— 这里曾经各抄一份且抄漏了
# (2026-08-10 实测: 本文件当时只有 4 个词, 权威有 12 个),
# 认不出的汇总行会被当成明细算进去。判据: 这种判据只能有一处定义。
from smartbi.services.materialized_analytics.compute.polars_backend import _META_LABELS  # noqa: E402
_MOM_SIGNIFICANT = 20.0     # |Δ%| ≥ 20% = noteworthy
_MOM_ALERT = 30.0           # |Δ%| ≥ 30% = alert
_Z_OUTLIER = 2.0            # |z| ≥ 2.0 vs 12-month mean (standard 95% outlier threshold).
# Previous 1.5 was too loose — qhj 2025-08 +8.3% MoM flagged
# even though MoM was below significant threshold.


@register
class MonthlyAnomaly(AnalysisTemplate):

    sample_queries = [
        "哪个月营业额异常",
        "营收暴跌月份",
        "月度波动检测",
        "异常月识别",
        "月份突变",
    ]

    # applies() requires date + (net_amount OR gross_amount OR primary_measure).
    # The schema-helpers fallback to primary_measure isn't expressible in
    # RequiresSpec, but date + a revenue measure covers the common path.
    requires: ClassVar[RequiresSpec | None] = RequiresSpec(
        all=["date"],
        any=["net_amount", "gross_amount"],
        description="月度异常检测 — 需 date + 营收口径(net_amount 或 gross_amount)",
    )

    @property
    def code(self) -> str:
        return "monthly_anomaly"

    @property
    def title(self) -> str:
        return "月度异常波动检测"

    def applies(self, schema: DataSchema) -> bool:
        if schema.domain not in (Domain.RESTAURANT, Domain.UNKNOWN):
            return False
        names = {f.name for f in schema.fields}
        has_date = find_date_col(names) is not None
        has_rev = (
            find_net_revenue_col(names) is not None
            or find_gross_revenue_col(names) is not None
            or schema.primary_measure is not None
        )
        return has_date and has_rev

    def compute(self, backend: ComputeBackend, schema: DataSchema) -> TemplateResult:
        df = backend._df  # type: ignore[attr-defined]
        cols = set(df.columns)

        date_col = find_date_col(cols)
        rev_col = preferred_revenue_col(cols, schema.primary_measure)
        if date_col is None or rev_col is None:
            return TemplateResult(
                code=self.code, title=self.title, data={},
                applies=False, skip_reason="missing date or revenue col",
            )

        # C-rec 7 (Apr 25 2026): label revenue basis so AI / FE can't pass
        # off gross-side numbers as net (Q14 bug: "monthly peak ¥3.19M" was
        # gross 应收, real net 实收 was ¥2.29M, customer made wrong decisions).
        net_candidate = find_net_revenue_col(cols)
        gross_candidate = find_gross_revenue_col(cols)
        if rev_col == net_candidate:
            revenue_basis = "净"  # 实收 / 折后 / 到账
            revenue_basis_label = "[净/实收口径]"
        elif rev_col == gross_candidate:
            revenue_basis = "毛"  # 应收 / 折前 / 含税
            revenue_basis_label = "[毛/应收口径]"
        else:
            revenue_basis = "未知"
            revenue_basis_label = f"[按{rev_col}统计]"

        # Drop meta rows
        df = df.filter(
            ~pl.col(date_col).cast(pl.Utf8, strict=False).fill_null("").is_in(list(_META_LABELS))
        )

        # Aggregate by yyyy-mm
        monthly_df = (
            df.with_columns(
                pl.col(date_col).cast(pl.Utf8, strict=False).str.slice(0, 7).alias("_ym")
            )
            .filter(pl.col("_ym").is_not_null() & (pl.col("_ym") != ""))
            .group_by("_ym")
            .agg([
                pl.col(rev_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("revenue"),
                pl.len().alias("orders"),
            ])
            .sort("_ym")
        )
        monthly_rows = monthly_df.to_dicts()

        if len(monthly_rows) < 3:
            return TemplateResult(
                code=self.code, title=self.title, data={},
                applies=False, skip_reason=f"only {len(monthly_rows)} months; need ≥3 for anomaly detection",
            )

        revenues = [float(r["revenue"] or 0.0) for r in monthly_rows]
        mean = statistics.mean(revenues)
        stdev = statistics.stdev(revenues) if len(revenues) >= 2 else 0.0

        # Enrich each month with MoM delta + z-score + anomaly flags
        months: List[Dict[str, Any]] = []
        prev_rev: Optional[float] = None
        for r in monthly_rows:
            rev = float(r["revenue"] or 0.0)
            mom_pct = ((rev - prev_rev) / prev_rev * 100) if prev_rev and prev_rev != 0 else None
            z = (rev - mean) / stdev if stdev > 0 else 0.0

            flags: List[str] = []
            if mom_pct is not None:
                if abs(mom_pct) >= _MOM_ALERT:
                    flags.append(f"🚨 MoM {mom_pct:+.1f}%")
                elif abs(mom_pct) >= _MOM_SIGNIFICANT:
                    flags.append(f"⚠️ MoM {mom_pct:+.1f}%")
            if abs(z) >= _Z_OUTLIER:
                flags.append(f"📊 离群 z={z:+.2f}")

            months.append({
                "month": str(r.get("_ym") or ""),
                "revenue": round(rev, 2),
                "orders": int(r["orders"]),
                "mom_delta_pct": round(mom_pct, 2) if mom_pct is not None else None,
                "z_score": round(z, 2),
                "flags": flags,
                "is_anomaly": bool(flags),
            })
            prev_rev = rev

        anomalies = [m for m in months if m["is_anomaly"]]
        # Most severe anomaly
        if anomalies:
            worst = max(anomalies, key=lambda m: abs(m.get("mom_delta_pct") or 0))
        else:
            worst = None

        # Peak / trough
        peak = max(months, key=lambda m: m["revenue"])
        trough = min(months, key=lambda m: m["revenue"])

        # Insight
        parts: List[str] = []
        parts.append(f"营收口径：{revenue_basis_label}（{rev_col}）。")
        parts.append(f"期内 {len(months)} 个月,月均 ¥{mean:,.0f}{revenue_basis_label}。")
        parts.append(
            f"峰值 {peak['month']} ¥{peak['revenue']:,.0f}{revenue_basis_label},"
            f"谷值 {trough['month']} ¥{trough['revenue']:,.0f}{revenue_basis_label}。"
        )
        if anomalies:
            anomaly_months = ", ".join(f"{m['month']}({m['mom_delta_pct']:+.1f}%)" for m in anomalies)
            parts.append(f"🚨 发现 {len(anomalies)} 个异常月:{anomaly_months}（按月度营收MoM[{revenue_basis}]统计）。")
            if worst and worst.get("mom_delta_pct"):
                delta = worst["mom_delta_pct"]
                if delta < 0:
                    parts.append(f"最大跌幅 {worst['month']} {delta:+.1f}%。")
                    # K2 / C-rec 8: spec §4.3 action rec for跌幅
                    parts.append(format_action_rec(
                        object_target=f"{worst['month']} 营收跌幅 ({delta:+.1f}%)",
                        benefit_range=f"找出根因后干预可挽回 5-15% 营收 (¥{abs(worst['revenue'] - mean):,.0f})",
                        prerequisite="拉取该月按渠道/门店/品类拆分 + 对比促销日历 / 节假日 / 关店",
                        timeline="本周内",
                    ))
                else:
                    parts.append(f"最大涨幅 {worst['month']} {delta:+.1f}%。")
                    # K2 / C-rec 8: spec §4.3 action rec for涨幅
                    parts.append(format_action_rec(
                        object_target=f"{worst['month']} 营收涨幅 ({delta:+.1f}%) 成功因素",
                        benefit_range=f"复制至其他月份可拉高均值 3-8% (¥{(worst['revenue'] - mean) * 0.5:,.0f}/月)",
                        prerequisite="复盘该月促销/节庆/新品/客流来源 + 沉淀 SOP",
                        timeline="近 30 天",
                    ))
        else:
            parts.append("✅ 期内无月度异常波动。")
            # K2 / C-rec 8: 无异常时也给可执行建议 (避免空 rec)
            parts.append(format_action_rec(
                object_target=f"稳定期 (峰值 {peak['month']}, 谷值 {trough['month']})",
                benefit_range=f"在峰值月复制成功因素到谷值月可拉平 5-10% (¥{(peak['revenue'] - trough['revenue']) * 0.3:,.0f})",
                prerequisite=f"对比 {peak['month']} vs {trough['month']} 客流/促销/天气数据",
                timeline="本月内",
            ))
        insight_text = " ".join(parts)

        # ECharts: bar chart with colors by anomaly
        data_with_color = []
        for m in months:
            if any("🚨" in f for f in m["flags"]):
                color = "#d32f2f"  # red — alert
            elif any("⚠️" in f for f in m["flags"]):
                color = "#ff9800"  # orange — significant
            elif any("📊" in f for f in m["flags"]):
                color = "#fbc02d"  # yellow — outlier
            else:
                color = "#1e88e5"  # blue — normal
            data_with_color.append({"value": m["revenue"], "itemStyle": {"color": color}})

        chart_config = {
            "type": "bar",
            "title": {"text": "月度营业额 + 异常标记", "left": "center"},
            "tooltip": {
                "trigger": "axis", "axisPointer": {"type": "shadow"},
                "formatter": "{b}: ¥{c}",
            },
            "xAxis": {"type": "category", "data": [m["month"] for m in months]},
            "yAxis": {"type": "value", "name": "营业额 (元)"},
            "series": [{
                "name": "营业额",
                "type": "bar",
                "data": data_with_color,
                "label": {"show": True, "position": "top", "formatter": "{c}"},
            }],
            "grid": {"left": "3%", "right": "8%", "bottom": "10%", "containLabel": True},
        }

        return TemplateResult(
            code=self.code,
            title=self.title,
            data={
                "months": months,
                "anomalies": anomalies,
                "peak": peak,
                "trough": trough,
                "mean_revenue": round(mean, 2),
                "stdev_revenue": round(stdev, 2),
                # C-rec 7: explicit basis labels for downstream LLM/FE
                "revenue_basis": revenue_basis,           # "毛" / "净" / "未知"
                "revenue_column": rev_col,
                "revenue_basis_label": revenue_basis_label,  # "[毛/应收口径]" 等
            },
            chart_config=chart_config,
            kpis={
                "anomaly_count": len(anomalies),
                "worst_month": worst["month"] if worst else None,
                "worst_mom_delta_pct": worst.get("mom_delta_pct") if worst else None,
                "peak_month": peak["month"],
                "trough_month": trough["month"],
                # C-rec 7: surface basis label so AI prompt + cards inherit it
                "revenue_basis": revenue_basis,
                "revenue_basis_label": revenue_basis_label,
            },
            insight_text=insight_text,
        )
