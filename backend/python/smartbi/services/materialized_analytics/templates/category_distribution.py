"""CategoryDistribution — share of primary measure by each dimension.

For each dim, pie chart showing % contribution. Quickly tells user
"品类 A 占 60% 营收" type stories.
"""
from __future__ import annotations

import re as _re
from typing import ClassVar

from smartbi.capability.contract import RequiresSpec

from ..compute.base import ComputeBackend
from ..restaurant.action_rec_formatter import format_action_rec
from ..restaurant.schema_helpers import aggregation_for_measure
from ..schema import DataSchema
from .base import AnalysisTemplate, TemplateResult
from .registry import register

_NUM_LIKE = _re.compile(r'^-?\d+(\.\d+)?$')


def _is_numeric_label(v):
    if isinstance(v, (int, float)):
        return True
    if isinstance(v, str) and _NUM_LIKE.match(v.strip()):
        return True
    return False


def _filter_numeric_rows(rows):
    return [r for r in rows if not _is_numeric_label(r.get('label'))]


@register
class CategoryDistribution(AnalysisTemplate):

    sample_queries = [
        "占比分布",
        "份额结构",
        "类别构成",
        "营收结构",
        "销售分布",
    ]

    # 通用饼图 — 任意维度 × primary_measure 的占比分布. 由 schema 动态决定.
    requires: ClassVar[RequiresSpec | None] = None

    @property
    def code(self) -> str:
        return "category_distribution"

    @property
    def title(self) -> str:
        return "分类占比"

    def applies(self, schema: DataSchema) -> bool:
        return bool(schema.dimensions) and schema.primary_measure is not None

    def compute(self, backend: ComputeBackend, schema: DataSchema) -> TemplateResult:
        measure = schema.primary_measure
        # May 30 2026: a "占比/份额" pie of an intensive measure (星级分/评分/率)
        # is meaningless — you cannot speak of "60% 份额 of a rating". Skip
        # this card for AVG-semantic measures (the trend/ranking cards still
        # show the proper per-dim average).
        if aggregation_for_measure(measure) == "avg":
            return TemplateResult(
                code=self.code, title=self.title, data={},
                applies=False,
                skip_reason=f"占比分布对均值类指标 ({measure}) 无意义,已跳过",
            )
        shares_by_dim = {}

        for dim in schema.dimensions[:4]:
            rows = backend.group_sum(dim, measure)
            rows = _filter_numeric_rows(rows)
            if 2 <= len(rows) <= 15:
                total = sum(r["total"] for r in rows)
                if total > 0:
                    shares_by_dim[dim] = [
                        {"label": r["label"], "total": r["total"],
                         "share": round(r["total"] / total * 100, 2)}
                        for r in rows
                    ]

        if not shares_by_dim:
            return TemplateResult(
                code=self.code, title=self.title, data={},
                applies=False, skip_reason="no dim with 2-15 categories",
            )

        # Primary dim: the one with most balanced distribution (least skewed)
        primary_dim = min(
            shares_by_dim.keys(),
            key=lambda d: shares_by_dim[d][0]["share"],
        )
        primary = shares_by_dim[primary_dim]

        chart_config = {
            "type": "pie",
            "title": {"text": f"{primary_dim} 占比 (按 {measure})", "left": "center"},
            "tooltip": {"trigger": "item", "formatter": "{b}: {c} ({d}%)"},
            "series": [{
                "name": measure, "type": "pie", "radius": "60%",
                "data": [{"name": r["label"], "value": r["total"]} for r in primary],
                "label": {"formatter": "{b}: {d}%"},
            }],
        }

        # Spec §4.3: surface bottom segment for retire / merge consideration
        bottom = primary[-1] if len(primary) >= 3 else None
        if bottom and bottom["share"] < 5.0:
            action_rec = format_action_rec(
                object_target=f"末位「{bottom['label']}」 (占{bottom['share']:.1f}% {measure})",
                benefit_range=f"精简末位 + Top 类目加大投入可提升整体 {measure} 5-10%",
                prerequisite=f"复盘末位 {primary_dim} 历史 + 评估合并 / 退出可行性",
                timeline="本季度内",
            )
        else:
            action_rec = format_action_rec(
                object_target=f"Top 1「{primary[0]['label']}」 (占{primary[0]['share']:.1f}% {measure})",
                benefit_range=f"加大 Top 1 投入可拉高 {measure} 5-10% (前提是不挤压末位资源)",
                prerequisite=f"复盘 Top 1 成功要素 + 跨 {primary_dim} 输出 SOP",
                timeline="本月内",
            )
        return TemplateResult(
            code=self.code, title=self.title,
            data={
                "primary_dim": primary_dim,
                "measure": measure,
                "shares": primary,
                "all_dims": shares_by_dim,
            },
            chart_config=chart_config,
            kpis={
                "top_share_pct": primary[0]["share"],
                "top_label": primary[0]["label"],
                "category_count": len(primary),
            },
            insight_text=(
                f"按 {primary_dim} 分类占比:Top 1 {primary[0]['label']} "
                f"占 {primary[0]['share']}%,共 {len(primary)} 个分类。 {action_rec}"
            ),
        )
