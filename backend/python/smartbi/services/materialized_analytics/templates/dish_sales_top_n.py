"""DishSalesTopN — 菜品销量 Top 20 (by quantity + by revenue).

Parses 商品信息 text column row-by-row using item_parser, accumulates
Counter totals across up to 50K rows, then ranks dishes by quantity and
by revenue independently.

Applies only when schema contains a 商品信息 field (restaurant-style data).
"""
from __future__ import annotations

from collections import Counter
from typing import Any, ClassVar, Dict, List

from smartbi.capability.contract import RequiresSpec

from ..compute.base import ComputeBackend
from ..restaurant.action_rec_formatter import format_action_rec
from ..restaurant.dish_classifier import classify_dish
from ..restaurant.dish_name_normalizer import normalize_dish_name
from ..restaurant.item_parser import parse_items
from ..schema import DataSchema, Domain
from .base import AnalysisTemplate, TemplateResult
from .registry import register

_TOP_N = 20
_SAMPLE_ROWS = 500_000

# May 30 2026: staples/surcharge items distort the "畅销菜品" ranking — 米饭/
# 面/打包盒/餐位费 sell in huge volume but are not menu dishes a chef would
# promote. Exclude these inferred categories from the Top-N so the ranking
# surfaces real signature dishes (招牌青花椒鱼 etc.) instead of 米饭.
_EXCLUDED_CATEGORIES = ("主食", "调料/附加")


@register
class DishSalesTopN(AnalysisTemplate):

    sample_queries = [
        "菜品销量 Top 10",
        "最畅销的菜是什么",
        "卖得最好的菜排行",
        "哪个菜最火",
        "爆款菜品有哪些",
        "热销菜品",
        "畅销品",
        "畅销品 Top 5",
        "畅销品排行",
        "卖得最好的菜",
    ]

    requires: ClassVar[RequiresSpec | None] = RequiresSpec(
        all=["store_name", "combo_string"],
        description="按菜品销量 / 销售额排行 (Top 20)",
    )

    @property
    def code(self) -> str:
        return "dish_sales_top_n"

    @property
    def title(self) -> str:
        return "菜品销量 Top 20"

    def applies(self, schema: DataSchema) -> bool:
        field_names = {f.name for f in schema.fields}
        if "商品信息" not in field_names:
            return False
        # Allow restaurant domain or unknown (detected via field presence)
        return schema.domain in (Domain.RESTAURANT, Domain.UNKNOWN)

    def compute(self, backend: ComputeBackend, schema: DataSchema) -> TemplateResult:
        # Access raw polars DataFrame for row-level text parsing
        series = backend._df.get_column("商品信息").head(_SAMPLE_ROWS)

        qty_counter: Counter = Counter()
        rev_counter: Counter = Counter()
        excluded_count = 0

        for raw_value in series.to_list():
            if not raw_value:
                continue
            items = parse_items(str(raw_value))
            for item in items:
                # Apr 24 2026: collapse SKU variants (一吃/二吃/大份/小份/单人份)
                # into the parent dish name so the Top-N ranking treats
                # 招牌青花椒鱼(一吃) and 招牌青花椒鱼(二吃) as one dish.
                name = normalize_dish_name(item["name"])
                # May 30 2026: drop staples (米饭/面/粉) + surcharge items
                # (打包盒/餐位费) — they swamp the畅销ranking but aren't菜品.
                category = classify_dish(name, item.get("is_signature", False))
                if category in _EXCLUDED_CATEGORIES:
                    excluded_count += 1
                    continue
                qty_counter[name] += item["quantity"]
                rev_counter[name] += item["total"]

        if not qty_counter:
            return TemplateResult(
                code=self.code, title=self.title, data={},
                applies=False, skip_reason="no parseable dish items found in 商品信息",
            )

        distinct_dishes = len(qty_counter)

        # Build Top N lists
        top_by_qty: List[Dict[str, Any]] = [
            {"name": name, "quantity": qty, "revenue": rev_counter.get(name, 0.0)}
            for name, qty in qty_counter.most_common(_TOP_N)
        ]
        top_by_revenue: List[Dict[str, Any]] = [
            {"name": name, "revenue": rev, "quantity": qty_counter.get(name, 0.0)}
            for name, rev in rev_counter.most_common(_TOP_N)
        ]

        # KPIs — based on top dish by revenue
        top_name = top_by_revenue[0]["name"]
        top_revenue = top_by_revenue[0]["revenue"]
        top_qty = top_by_revenue[0]["quantity"]

        # ECharts bar chart — Top N by revenue, with qty as second series
        names_rev = [r["name"] for r in top_by_revenue]
        revenues = [round(r["revenue"], 2) for r in top_by_revenue]
        quantities = [round(r["quantity"], 2) for r in top_by_revenue]

        chart_config = {
            "type": "bar",
            "title": {"text": "菜品营业额 Top 20", "left": "center"},
            "xAxis": {
                "type": "category",
                "data": names_rev,
                "axisLabel": {"rotate": 45, "interval": 0},
            },
            "yAxis": [
                {"type": "value", "name": "营业额 (元)", "position": "left"},
                {"type": "value", "name": "销售数量", "position": "right"},
            ],
            "series": [
                {
                    "name": "营业额",
                    "type": "bar",
                    "yAxisIndex": 0,
                    "data": revenues,
                    "label": {"show": False},
                },
                {
                    "name": "销售数量",
                    "type": "bar",
                    "yAxisIndex": 1,
                    "data": quantities,
                    "label": {"show": False},
                },
            ],
            "legend": {"top": 30},
            "tooltip": {"trigger": "axis"},
            "grid": {"left": "3%", "right": "8%", "bottom": "20%", "containLabel": True},
        }

        # K2 / C-rec 8: append spec §4.3 action rec (a对象 b收益区间 c前置 d时间窗)
        action_rec = format_action_rec(
            object_target=top_name,
            benefit_range="加大主推位 + 备货充足后销量再提升 5-10%",
            prerequisite="确认菜单首屏 / 首页排位 + 上架库存满足 1 周",
            timeline="本周内",
        )
        staple_note = (
            f"(已排除米饭/面/打包盒等主食及附加项 {excluded_count} 项)"
            if excluded_count else ""
        )
        insight_text = (
            f"菜品销量 Top 1:{top_name},"
            f"共售出 {top_qty:.0f} 份,"
            f"贡献营业额 {top_revenue:,.0f} 元。"
            f"全量菜单共 {distinct_dishes} 个菜品{staple_note}。 "
            f"{action_rec}"
        )

        return TemplateResult(
            code=self.code,
            title=self.title,
            data={
                "top_by_qty": top_by_qty,
                "top_by_revenue": top_by_revenue,
                "distinct_dishes": distinct_dishes,
                "excluded_staple_items": excluded_count,
            },
            chart_config=chart_config,
            kpis={
                "top_dish_name": top_name,
                "top_dish_qty": top_qty,
                "top_dish_revenue": top_revenue,
                "distinct_dishes": distinct_dishes,
            },
            insight_text=insight_text,
        )
