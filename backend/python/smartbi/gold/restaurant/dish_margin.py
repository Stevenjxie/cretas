"""每道菜的毛利口径 —— **唯一的结构化出数处**。

⛔ 本模块是从 `smartbi/api/restaurant_ops_gold.py` 的 `/restaurant-ops/gross-margin`
处理函数里**原样搬出来**的, 不是新写的一份。搬的理由写在那个处理函数自己的注释里:

    "Resolver returns enriched[] via .answer_text string; to avoid re-parsing,
     recompute the per-dish dict here directly from the same join logic (thin wrapper).
     For cleanliness: extend resolver to return structured rows.
     For now: call back into the DB to build the structured list"

也就是说它当时就知道自己是第二份实现, 只是没地方放。发现层的毛利规则需要同一份
per-dish 数字, 再抄一遍就是第三份。

🔴 「每道菜的食材成本」这个口径在仓里**已经有 5 处承载**:
    restaurant_cost_card.py / restaurant_ops_gold.py / restaurant_targets_p1.py /
    restaurant_finance_etl.py / resolve_gross_margin
而 `FindingProvider` 的接口注释明写「⛔ 实现禁止新写口径 SQL」。所以规则不自己 join,
调本函数。

⚠️ 本函数**不做 RBAC 脱敏**。脱敏是出口的事(端点用 `_apply_rbac_strip`, 按调用者
角色决定), 而发现层的规则需要**未脱敏的真实数字**才能判定 —— 把脱敏塞进这里,
规则会拿到一堆 0 然后判出「全店都是亏本菜」。谁把数字给到人, 谁负责脱敏。

⚠️ `agg_restaurant_product_cost.food_cost` 是**当前快照, 不随时间变化**
(建表注释原话: `NOT time-varying (unit prices change over time; we snapshot "current")`,
每周重算)。所以拿本函数的输出做「本期 vs 上期」的毛利率对比是**假话** ——
动的只有销量结构和售价, 成本那一侧根本没动。要做环比必须显式声明只比了哪一侧。
"""
from __future__ import annotations

import logging
from typing import Any, Dict

from smartbi.gold.restaurant.restaurant_cost_mapping import merge_cost_product_mapping
from smartbi.gold.restaurant.provenance import (
    ESTIMATED as PROV_ESTIMATED,
    MEASURED as PROV_MEASURED,
    qualifier as provenance_qualifier,
)

logger = logging.getLogger(__name__)

#: 估算依据的措辞。⛔ 只此一处 —— 这句话要出现在店长眼前, 不能两条路各写各的。
_ESTIMATION_BASIS = "行业默认成本率 {pct:.0f}%"


async def compute_dish_margins(
    pool, factory_id: str, *, days: int = 30,
) -> Dict[str, Any]:
    """按菜品算毛利, 返回 `/restaurant-ops/gross-margin` 的 `data` 结构。

    返回的键与该端点**逐字一致**(dishes / coverage / menuEngineering / totals...),
    因为它就是从那里搬来的; 端点现在调本函数, 响应形状不变。
    """
    # Resolver returns enriched[] via .answer_text string; to avoid re-parsing,
    # recompute the per-dish dict here directly from the same join logic (thin wrapper).
    # For cleanliness: extend resolver to return structured rows.
    # For now: call back into the DB to build the structured list:
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        pos_rows = await conn.fetch(
            """
            SELECT p.name AS dish_name, p.normalized_name,
                   SUM(i.qty)::float AS qty,
                   SUM(i.amount)::float AS revenue,
                   COUNT(DISTINCT i.transaction_id)::int AS bills
              FROM fact_pos_item i
              JOIN fact_pos_transaction t ON t.id = i.transaction_id
              JOIN dim_product p ON p.product_id = i.product_id
             WHERE i.factory_id = $1 AND t.factory_id = $1 AND p.factory_id = $1
               AND t.date >= CURRENT_DATE - ($2::int)
             GROUP BY p.name, p.normalized_name
             ORDER BY revenue DESC NULLS LAST
            """,
            factory_id, days,
        )

    # Name match + alias fallback — see restaurant_ops_router.resolve_gross_margin for details.
    # P1-5 also loads excluded dish list to drop noise from analysis.
    normalized_names = list({r["normalized_name"] for r in pos_rows})
    cretas_map: Dict[str, str] = {}
    excluded_set: set = set()
    if normalized_names:
        try:
            import asyncpg as _asyncpg
            from config import get_settings as _get_settings
            cretas_url = _get_settings().food_kb_db_url
            cretas = await _asyncpg.connect(cretas_url)
            try:
                name_rows = await cretas.fetch(
                    "SELECT id, name FROM product_types WHERE factory_id = $1 AND name = ANY($2::text[])",
                    factory_id, normalized_names,
                )
                for r in name_rows:
                    cretas_map[r["name"]] = r["id"]
                # P0-2 alias fallback
                unmapped = [n for n in normalized_names if n not in cretas_map]
                if unmapped:
                    try:
                        alias_rows = await cretas.fetch(
                            """SELECT pos_name, product_type_id FROM dim_product_alias
                                WHERE factory_id = $1 AND pos_name = ANY($2::text[])""",
                            factory_id, unmapped,
                        )
                        for r in alias_rows:
                            cretas_map[r["pos_name"]] = r["product_type_id"]
                    except Exception as e:
                        if "does not exist" not in str(e):
                            logger.warning(f"[gross-margin] alias lookup failed: {e}")
                # P1-5 excluded dishes (noise — packaging / utensil / ads)
                try:
                    ex_rows = await cretas.fetch(
                        "SELECT pos_name FROM dim_product_excluded WHERE factory_id = $1",
                        factory_id,
                    )
                    excluded_set = {r["pos_name"] for r in ex_rows}
                except Exception as e:
                    if "does not exist" not in str(e):
                        logger.warning(f"[gross-margin] excluded lookup failed: {e}")
            finally:
                await cretas.close()
        except Exception as e:
            logger.warning(f"[gross-margin] cretas lookup failed: {e}")

    cretas_map = await merge_cost_product_mapping(
        pool,
        factory_id,
        normalized_names,
        cretas_map,
    )

    # Filter out excluded dishes from pos_rows before margin calc
    if excluded_set:
        pos_rows = [r for r in pos_rows if r["dish_name"] not in excluded_set]

    cost_map: Dict[str, float] = {}
    if cretas_map:
        async with pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
            cost_rows = await conn.fetch(
                """
                SELECT product_source_pk, food_cost::float AS food_cost
                  FROM agg_restaurant_product_cost
                 WHERE factory_id = $1 AND product_source_pk = ANY($2::text[])
                """,
                factory_id, list(cretas_map.values()),
            )
            cost_map = {r["product_source_pk"]: r["food_cost"] for r in cost_rows}

    # P2-7 加速 E: industry default cost ratio fallback for dishes without recipes.
    # Customer sees "估算毛利" (灰色 tag) instantly after upload; recipes overwrite later.
    INDUSTRY_DEFAULT_COST_RATIO = {
        "RESTAURANT_CHUAN": 0.35,    # 川菜
        "RESTAURANT_HOTPOT": 0.28,   # 火锅
        "RESTAURANT_FASTFOOD": 0.25,  # 快餐
        "RESTAURANT_WESTERN": 0.30,  # 西餐
        "RESTAURANT_NOODLES": 0.30,  # 面食
        "RESTAURANT_JAPANESE": 0.40,  # 日料 (食材贵)
        "RESTAURANT_CANTONESE": 0.32,
        "DEFAULT": 0.32,
    }
    industry_cost_ratio = INDUSTRY_DEFAULT_COST_RATIO["DEFAULT"]

    dishes = []
    total_rev_all = 0.0         # all dishes (for display "总营收")
    total_rev_with_cost = 0.0   # dishes with recipes (for avgRate denominator)
    total_profit = 0.0
    total_rev_estimated = 0.0   # 估算 (无配方) 部分营收
    total_profit_estimated = 0.0  # 估算 profit
    for r in pos_rows:
        source_pk = cretas_map.get(r["normalized_name"])
        food_cost = cost_map.get(source_pk, 0) if source_pk else 0
        total_cost = food_cost * r["qty"]
        gp = r["revenue"] - total_cost
        rate = gp / r["revenue"] if r["revenue"] > 0 else 0
        has_cost = food_cost > 0
        # 加速 E: when no recipe, use industry default to give estimated margin
        if not has_cost:
            est_cost_total = r["revenue"] * industry_cost_ratio
            est_gp = r["revenue"] - est_cost_total
            est_rate = 1.0 - industry_cost_ratio  # noqa: F841
            total_rev_estimated += r["revenue"]
            total_profit_estimated += est_gp
        dishes.append({
            "name": r["dish_name"],
            "qty": r["qty"],
            "revenue": r["revenue"],
            "foodCostUnit": food_cost,
            "totalCost": total_cost if has_cost else round(r["revenue"] * industry_cost_ratio, 2),
            "grossProfit": gp if has_cost else round(r["revenue"] * (1 - industry_cost_ratio), 2),
            "marginRate": rate if has_cost else (1 - industry_cost_ratio),
            "bills": r["bills"],
            "hasCost": has_cost,
            "isEstimated": not has_cost,
        })
        total_rev_all += r["revenue"]
        if has_cost:
            total_rev_with_cost += r["revenue"]
            total_profit += gp

    # ── 菜单工程四象限 (Kasavana-Smith) ────────────────────────────────
    # 轴二用**单份毛利贡献**(元/份)而不是毛利率: 90% 毛利但每份只赚 1 块的菜
    # (米饭)不是明星。经典菜单工程用的就是 contribution margin, 不是 rate。
    #
    # ⛔ 刻意在这里分类, 不新写一份成本 join —— 上面那个 for 已经用
    # cretas_map/cost_map 把每道菜的 foodCostUnit 算好了。另起一套就成了
    # 第二处成本口径, 两边迟早对不上。
    #
    # ⚠️ 与既有 /restaurant-ops/menu-quadrant 是**两个不同指标**, 不是重复:
    # 那个是「销量 × 营收」(docstring 自称收入模式), 会把高价低毛利的菜叫成金牛。
    # 这个是「销量 × 毛利贡献」。两者都保留, 各自标明轴是什么。
    #
    # 只有 hasCost 的菜进四象限: 估算毛利(行业默认成本率)对每道菜是同一个比率,
    # 拿它排序等于按营收排, 会造出一个看着像毛利分析、实则不是的榜。
    priced = [d for d in dishes if d["hasCost"] and d["qty"] > 0]
    for d in dishes:
        d["unitMargin"] = (
            round(d["grossProfit"] / d["qty"], 4) if d["hasCost"] and d["qty"] > 0 else None
        )
        d["quadrant"] = None
    if priced:
        qty_sorted = sorted(x["qty"] for x in priced)
        um_sorted = sorted(x["grossProfit"] / x["qty"] for x in priced)
        mid = len(priced) // 2
        qty_median = (
            qty_sorted[mid] if len(priced) % 2
            else (qty_sorted[mid - 1] + qty_sorted[mid]) / 2
        )
        um_median = (
            um_sorted[mid] if len(priced) % 2
            else (um_sorted[mid - 1] + um_sorted[mid]) / 2
        )
        for d in dishes:
            if d["unitMargin"] is None:
                continue
            hi_qty = d["qty"] >= qty_median
            hi_um = d["unitMargin"] >= um_median
            # 命名用菜单工程原词, 不用 BCG 的明星/金牛 —— 后者是既有收入模式在用的,
            # 两套同名不同轴会让人以为是同一个结论。
            d["quadrant"] = (
                "明星" if hi_qty and hi_um          # 又好卖又赚钱 → 保住, 别动价
                else "主力" if hi_qty               # 好卖不赚钱 → 降本或微调价
                else "谜题" if hi_um                # 赚钱不好卖 → 推荐位/服务员话术
                else "瘦狗"                          # 都不行 → 考虑下架
            )
    else:
        qty_median = 0.0
        um_median = 0.0

    # avgRate 用 "只算有配方菜" 分母, 避免 403 无配方菜稀释真实毛利率.
    avg_rate = total_profit / total_rev_with_cost if total_rev_with_cost > 0 else 0
    dishes_with_cost = sum(1 for d in dishes if d["hasCost"])
    coverage_revenue = total_rev_with_cost / total_rev_all if total_rev_all > 0 else 0

    # 加速 E: 估算 totals 包含无配方菜 (按行业默认成本率), 客户立即看全菜估算毛利
    total_profit_combined = total_profit + total_profit_estimated  # 精确 + 估算
    avg_rate_combined = total_profit_combined / total_rev_all if total_rev_all > 0 else 0

    # 🔴 出处与依据必须从**同一个条件**推出来, 不能各写各的。
    # 变异实测(2026-08-12): 第一版把 `estimationBasis` 单独挂在 `total_rev_estimated > 0`
    # 上, 把 provenance 强改成 MEASURED 之后出现了这个组合 ——
    #   provenance=MEASURED + estimationBasis="行业默认成本率 32%"
    #   + 限定语「未覆盖成本的菜品……不在结论内」
    # 而这条路的 472 元里**恰恰含着**那些未覆盖的菜。也就是说限定语在替这个数说谎。
    # 两个字段同源之后, 这个组合在结构上就不成立了。
    has_estimate = total_rev_estimated > 0
    combined_provenance = PROV_ESTIMATED if has_estimate else PROV_MEASURED
    estimation_basis = (
        _ESTIMATION_BASIS.format(pct=industry_cost_ratio * 100) if has_estimate else ""
    )

    data = {
        "windowDays": days,
        "totalRevenue": total_rev_all,
        "totalRevenueWithCost": total_rev_with_cost,
        "totalProfit": total_profit,
        "avgRate": avg_rate,
        # 加速 E 新字段: 精确+估算 合并版 (FE 可选切换显示)
        "totalProfitWithEstimated": total_profit_combined,
        "avgRateWithEstimated": avg_rate_combined,
        "industryDefaultCostRatio": industry_cost_ratio,
        # 菜单工程四象限的阈值。⚠️ 中位数是**按有成本的菜**算的, 与 coverage.dishCount
        # 同一个集合 —— 若只有 3 道菜有配方, 这个中位数只代表那 3 道, 前端要连
        # coverage 一起展示, 别让「10 个菜里 3 个」被读成「全店结论」。
        "menuEngineering": {
            "axis": "qty × unitMargin",
            "qtyMedian": qty_median,
            "unitMarginMedian": um_median,
            "dishCount": len(priced),
        },
        "coverage": {
            "dishCount": dishes_with_cost,
            "totalDishCount": len(dishes),
            "revenueRatio": coverage_revenue,
        },
        # 🔴 2026-08-12 架构收口 C: 这条路的「合并版」数字里**掺了估算**
        #    (无配方的菜按行业默认成本率折一个毛利), 而 AI 问答那条路是把它们
        #    排除在结论外的。两个数字不同是**对的** —— 但此前系统只给数字不给
        #    出处, 同一个店长拿到两个毛利数而不知道为什么。
        # ⛔ 数字一个都没动。这里只是让它带上自己的出处。
        # ⚠️ `totalProfit` / `avgRate` 是纯账上口径 → MEASURED;
        #    `totalProfitWithEstimated` / `avgRateWithEstimated` 掺了估算 → ESTIMATED。
        #    出处是**按字段**的, 不是按整个响应的。
        "provenance": {
            "totalProfit": PROV_MEASURED,
            "avgRate": PROV_MEASURED,
            "totalProfitWithEstimated": combined_provenance,
            "avgRateWithEstimated": combined_provenance,
        },
        "estimationBasis": estimation_basis,
        # 限定语**由上面两个字段生成**, 前端不许再手写一份灰色小字。
        # 覆盖率 100% 时它是空串 —— 说了反而是噪音。
        "qualifier": provenance_qualifier(
            combined_provenance, estimation_basis, coverage_ratio=coverage_revenue,
        ),
        "dishes": dishes,
    }
    return data
