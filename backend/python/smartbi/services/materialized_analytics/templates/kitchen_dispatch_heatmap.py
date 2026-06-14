"""KitchenDispatchHeatmap — 传菜统计报表分析.

Triggered by 传菜统计报表 uploads (format: 桂满陇).
Columns: 门店名称 / 传菜方案 / 菜品名称 / 点菜数量 / 点菜单位 / 结账数量 /
结账单位 / 折前金额 / 折后金额.

Outputs:
  - 总传菜份数 + 总营收
  - Top N 菜品 by 点菜数量
  - 按 传菜方案 分布 (上什 / 冷盘 / 甜品 / ...)
  - 按 门店 concentration
  - 点菜 vs 结账 比例 (wastage / cancel rate indicator)
"""
from __future__ import annotations

from typing import Any, ClassVar, Dict, List

import polars as pl

from smartbi.capability.contract import RequiresSpec

from ..compute.base import ComputeBackend
from ..restaurant.action_rec_formatter import format_action_rec
from ..restaurant.dish_name_normalizer import normalize_dish_name
from ..restaurant.pos_placeholders import POS_PRODUCT_PLACEHOLDERS, filter_placeholder_rows
from ..restaurant.schema_helpers import find_store_col
from ..schema import DataSchema, Domain
from .base import AnalysisTemplate, TemplateResult
from .registry import register

_DISH_CANDIDATES = ("菜品名称", "商品名称", "菜品")
_DISPATCH_PLAN_CANDIDATES = ("传菜方案", "档口", "档口分类")
_ORDER_QTY_CANDIDATES = ("点菜数量", "下单数量")
_CHECKED_QTY_CANDIDATES = ("结账数量", "成交数量")
_AMOUNT_CANDIDATES = ("折后金额", "实收金额", "成交金额", "销售金额", "折前金额")

_TOP_N = 15


def _first(cols, candidates):
    s = set(cols)
    for c in candidates:
        if c in s:
            return c
    return None


@register
class KitchenDispatchHeatmap(AnalysisTemplate):

    sample_queries = [
        "传菜分析",
        "档口出餐情况",
        "后厨分布",
        "厨房业务量",
        "出菜份数统计",
    ]

    # Deferred: applies() requires 传菜方案/档口 + 点菜数量 (kitchen-dispatch
    # specific schema). product_name is canonical, but 传菜方案 and 点菜数量
    # are POS-export columns not yet in ALIAS_TO_ATTR. The deviation from
    # spec (which suggested combo_string + date) reflects actual applies().
    requires: ClassVar[RequiresSpec | None] = None

    @property
    def code(self) -> str:
        return "kitchen_dispatch_heatmap"

    @property
    def title(self) -> str:
        return "传菜/厨房统计分析"

    def applies(self, schema: DataSchema) -> bool:
        if schema.domain not in (Domain.RESTAURANT, Domain.UNKNOWN):
            return False
        names = {f.name for f in schema.fields}
        return (
            _first(names, _DISPATCH_PLAN_CANDIDATES) is not None
            and _first(names, _DISH_CANDIDATES) is not None
            and _first(names, _ORDER_QTY_CANDIDATES) is not None
        )

    def compute(self, backend: ComputeBackend, schema: DataSchema) -> TemplateResult:
        df = backend._df  # type: ignore[attr-defined]
        cols = set(df.columns)

        dish_col = _first(cols, _DISH_CANDIDATES)
        plan_col = _first(cols, _DISPATCH_PLAN_CANDIDATES)
        order_qty_col = _first(cols, _ORDER_QTY_CANDIDATES)
        checked_qty_col = _first(cols, _CHECKED_QTY_CANDIDATES)
        amount_col = _first(cols, _AMOUNT_CANDIDATES)
        store_col = find_store_col(cols)

        def _sum(col):
            if col is None:
                return 0.0  # noqa: E701
            try:
                return float(df.select(
                    pl.col(col).cast(pl.Float64, strict=False).fill_null(0.0).sum()
                ).item() or 0.0)
            except Exception:
                return 0.0

        total_ordered = _sum(order_qty_col)
        total_checked = _sum(checked_qty_col)
        total_amount = _sum(amount_col)
        cancel_rate = (
            round((total_ordered - total_checked) / total_ordered * 100, 2)
            if total_ordered > 0 and checked_qty_col else None
        )

        # Top dishes
        top_dishes: List[Dict[str, Any]] = []
        agg_exprs = [
            pl.col(order_qty_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("ordered"),
        ]
        if checked_qty_col:
            agg_exprs.append(
                pl.col(checked_qty_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("checked")
            )
        if amount_col:
            agg_exprs.append(
                pl.col(amount_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("amount")
            )
        # Apr 24 2026: collapse SKU variants on a normalized dish name so
        # the Top-N treats 招牌青花椒鱼(一吃)/(二吃) as one dish.
        _norm_col = "__dish_norm"
        df_with_norm = df.filter(pl.col(dish_col).is_not_null()).with_columns(
            pl.col(dish_col)
            .cast(pl.Utf8, strict=False)
            .map_elements(normalize_dish_name, return_dtype=pl.Utf8)
            .alias(_norm_col)
        )
        rows = (
            df_with_norm
            .group_by(_norm_col).agg(agg_exprs)
            .sort("ordered", descending=True).head(_TOP_N).to_dicts()
        )
        top_dishes = [
            {
                "dish": str(r.get(_norm_col) or "<空>"),
                "ordered": round(float(r.get("ordered") or 0.0), 2),
                "checked": round(float(r.get("checked") or 0.0), 2) if checked_qty_col else None,
                "amount": round(float(r.get("amount") or 0.0), 2) if amount_col else None,
            }
            for r in rows
        ]
        # Apr 25 2026 D1.C4: drop POS-placeholder rows (打包盒/餐位费/外卖费 etc.)
        # which are system fee items, not real dishes. Without this filter the
        # "Top 1 菜品" KPI sometimes surfaces 打包盒 on takeout-heavy uploads.
        top_dishes = filter_placeholder_rows(top_dishes, name_key="dish",
                                             placeholders=POS_PRODUCT_PLACEHOLDERS)

        # By dispatch plan (档口)
        by_plan: List[Dict[str, Any]] = []
        if plan_col:
            rows = (
                df.filter(pl.col(plan_col).is_not_null())
                .group_by(plan_col).agg([
                    pl.col(order_qty_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("ordered"),
                    pl.len().alias("dish_count"),
                    *([pl.col(amount_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("amount")]
                      if amount_col else []),
                ])
                .sort("ordered", descending=True).to_dicts()
            )
            by_plan = [
                {
                    "plan": str(r.get(plan_col) or "<空>"),
                    "ordered": round(float(r.get("ordered") or 0.0), 2),
                    "dish_count": int(r["dish_count"]),
                    "amount": round(float(r.get("amount") or 0.0), 2) if amount_col else None,
                    "ordered_share_pct": round(float(r.get("ordered") or 0.0) / total_ordered * 100, 2)
                    if total_ordered > 0 else 0.0,
                }
                for r in rows
            ]

        # By store
        by_store: List[Dict[str, Any]] = []
        if store_col:
            rows = (
                df.filter(pl.col(store_col).is_not_null())
                .group_by(store_col).agg([
                    pl.col(order_qty_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("ordered"),
                    *([pl.col(amount_col).cast(pl.Float64, strict=False).fill_null(0.0).sum().alias("amount")]
                      if amount_col else []),
                ])
                .sort("ordered", descending=True).head(10).to_dicts()
            )
            by_store = [
                {
                    "store": str(r.get(store_col) or "<空>"),
                    "ordered": round(float(r.get("ordered") or 0.0), 2),
                    "amount": round(float(r.get("amount") or 0.0), 2) if amount_col else None,
                }
                for r in rows
            ]

        parts = [f"期内传菜合计 {total_ordered:,.0f} 份"]
        if amount_col:
            parts[-1] += f"、营收 {total_amount:,.0f} 元"
        parts[-1] += "。"
        if by_plan:
            top_p = by_plan[0]
            parts.append(f"最大档口:{top_p['plan']} {top_p['ordered']:,.0f} 份 ({top_p['ordered_share_pct']:.1f}%)。")
        if top_dishes:
            top_d = top_dishes[0]
            parts.append(f"Top 菜品:{top_d['dish']} {top_d['ordered']:,.0f} 份。")
        if cancel_rate is not None and cancel_rate > 1:
            parts.append(f"⚠ 点菜未结账比例 {cancel_rate:.1f}%，存在退/改单流失。")
        # Spec §4.3: drive cancel-rate or top-plan into action
        if cancel_rate is not None and cancel_rate > 3:
            action_rec = format_action_rec(
                object_target=f"未结账比例 {cancel_rate:.1f}% (退 / 改单流失)",
                benefit_range="排查改单流程 + 厨房沟通 SOP 可降流失 1-2 个百分点",
                prerequisite="复盘高频改单菜品 + 服务员复述确认 + 后厨听单流程",
                timeline="本周内",
            )
        elif by_plan:
            top_p = by_plan[0]
            action_rec = format_action_rec(
                object_target=f"主力档口「{top_p['plan']}」 ({top_p['ordered']:,.0f} 份 / {top_p['ordered_share_pct']:.1f}%)",
                benefit_range="该档口排班 + 出餐 SOP 优化可降出餐时间 10-20%",
                prerequisite=f"{top_p['plan']}人手按峰值×1.2 + 标准化出餐流程 + 设备检修",
                timeline="本月内",
            )
        elif top_dishes:
            top_d = top_dishes[0]
            action_rec = format_action_rec(
                object_target=f"Top 菜品「{top_d['dish']}」 ({top_d['ordered']:,.0f} 份)",
                benefit_range="主推菜备货 + 出餐速度优化可拉销量 5-10%",
                prerequisite="备货量按峰值×1.2 + 标准化出餐工序",
                timeline="本月内",
            )
        else:
            action_rec = format_action_rec(
                object_target=f"传菜整体 ({total_ordered:,.0f} 份)",
                benefit_range="持续监控档口 / 菜品分布,异常波动立即复盘",
                prerequisite="周期性档口分析 + 出菜速度 SLA",
                timeline="本月内",
            )
        insight_text = " ".join(parts) + " " + action_rec

        chart_config = None
        if top_dishes:
            chart_config = {
                "type": "bar",
                "title": {"text": "Top 菜品传菜量", "left": "center"},
                "tooltip": {"trigger": "axis", "axisPointer": {"type": "shadow"}},
                "xAxis": {
                    "type": "category",
                    "data": [d["dish"] for d in top_dishes],
                    "axisLabel": {"rotate": 45, "interval": 0, "fontSize": 10},
                },
                "yAxis": {"type": "value", "name": "份数"},
                "series": [{
                    "name": "传菜量", "type": "bar",
                    "data": [d["ordered"] for d in top_dishes],
                    "itemStyle": {"color": "#fb8c00"},
                    "label": {"show": True, "position": "top"},
                }],
                "grid": {"left": "3%", "right": "8%", "bottom": "25%", "containLabel": True},
            }

        return TemplateResult(
            code=self.code, title=self.title,
            data={
                "total_ordered": round(total_ordered, 2),
                "total_checked": round(total_checked, 2) if checked_qty_col else None,
                "total_amount": round(total_amount, 2) if amount_col else None,
                "cancel_rate_pct": cancel_rate,
                "top_dishes": top_dishes,
                "by_plan": by_plan,
                "by_store": by_store,
            },
            chart_config=chart_config,
            kpis={
                "total_ordered": round(total_ordered, 2),
                "top_plan": by_plan[0]["plan"] if by_plan else None,
                "top_dish": top_dishes[0]["dish"] if top_dishes else None,
                "cancel_rate_pct": cancel_rate,
            },
            insight_text=insight_text,
        )
