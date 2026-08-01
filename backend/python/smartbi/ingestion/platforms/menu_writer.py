"""把归一化后的菜单主数据落到 SmartBI 侧三张表, 并重算菜品成本。

落点(全部在 smartbi 库, **不碰 Java 库的 product_types**):

    dim_ingredient.unit_price        食材单价(按 name 匹配既有行)
    dim_restaurant_cost_product      菜名 → product_source_pk 映射
    fact_restaurant_recipe_line      配方行(含分摊后的 line_cost)
    ↓ ETL materialize_gold_daily_ops
    agg_restaurant_product_cost      逐菜食材成本 ← 毛利分析的输入

为什么走 `dim_restaurant_cost_product` 而不是 `cretas_prod_db.product_types`:
后者是 Java 侧主数据, 由 Java 拥有; 而毛利 resolver 的名字解析链里
`merge_cost_product_mapping` 提供了 SmartBI 侧的等价映射(已有 7 个租户在用)。
走它就不必跨库写别人的表。

🔴 **食材成本 = 配方逐行 qty × 单价, 不取菜品自报成本。**
`dish.cost_cents` 是平台侧的**全成本**(含人工水电), 而本表要的是**食材**成本 ——
两者不是一回事。2026-08-01 之前平台给的配方每道菜只列 2-4 种主料且用量偏小,
算出的食材成本率只有 19%(毛利率 81%), 于是一度改成「按占比摊到 dish.cost_cents」
来补偿。那个补偿是**语义错的**: 它把人工水电摊进了 `food_cost`, 成本率变成 42%
——那是全成本率不是食材成本率。

根治在上游: 平台把配方补完整(主料用量重估 + 补齐配菜/调料/油, 22→72 行)之后,
逐行相加本身就落在 32.3%(毛利率 67.7%), 正是休闲餐饮的正常区间。所以这里直接
`line_cost = qty × unit_price`, 三个事实(用量/单价/line_cost)完全自洽, 不再有
「line_cost ≠ 用量×单价」那个疙瘩。

"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from typing import Dict, Iterable, List, Mapping, Optional, Sequence, Tuple

logger = logging.getLogger(__name__)

_Q4 = Decimal("0.0001")


@dataclass(frozen=True)
class AllocatedLine:
    dish_code: str
    ingredient_code: str
    qty_milli: int
    line_cost: Decimal          # 元
    is_main_ingredient: bool


def compute_line_costs(
    ingredient_unit_price_cents: Mapping[str, int],
    recipe: Iterable[Tuple[str, str, int]],
    known_dishes: Optional[Iterable[str]] = None,
) -> List[AllocatedLine]:
    """逐行算食材成本: `line_cost = qty(千分之一) / 1000 × 单价(分) / 100`。

    这是**食材成本口径的唯一实现** —— `smartbi/scripts/seed_mock_rest_menu.py`
    也 import 它。两处各写一份的话, 首次 seed 与后续 connector 导入会算出不同的
    line_cost, 而症状是「成本表数字每次同步都变一点」, 没人看得出是口径分叉。

    ⛔ 悬空引用**硬失败**而不是跳过: 配方指向不存在的菜/食材, 写进去就是永远算不出
    成本的行, 而表现只是「毛利榜少了一道菜」—— 没人会察觉。
    """
    known = set(known_dishes) if known_dishes is not None else None
    by_dish: Dict[str, List[Tuple[str, int]]] = {}
    for dish_code, ingredient_code, qty_milli in recipe:
        if known is not None and dish_code not in known:
            raise ValueError(f"配方指向未知菜品 {dish_code!r}")
        by_dish.setdefault(dish_code, []).append((ingredient_code, qty_milli))

    out: List[AllocatedLine] = []
    for dish_code in sorted(by_dish):
        rows: List[Tuple[str, int, Decimal]] = []
        for ingredient_code, qty_milli in by_dish[dish_code]:
            if ingredient_code not in ingredient_unit_price_cents:
                raise ValueError(f"配方指向未知食材 {ingredient_code!r}")
            unit_price = Decimal(ingredient_unit_price_cents[ingredient_code]) / Decimal(100)
            cost = (Decimal(qty_milli) / Decimal(1000) * unit_price).quantize(
                _Q4, rounding=ROUND_HALF_UP)
            rows.append((ingredient_code, qty_milli, cost))
        if sum(r[2] for r in rows) <= 0:
            # 全 0 的配方会让这道菜显示 100% 毛利, 比没有配方更糟。
            raise ValueError(f"菜品 {dish_code} 的配方食材成本为 0")
        main_idx = max(range(len(rows)), key=lambda i: rows[i][2])
        for idx, (ingredient_code, qty_milli, cost) in enumerate(rows):
            out.append(AllocatedLine(
                dish_code=dish_code,
                ingredient_code=ingredient_code,
                qty_milli=qty_milli,
                line_cost=cost,
                is_main_ingredient=idx == main_idx,
            ))
    return out


def recipe_source_pk(dish_code: str, ingredient_code: str) -> str:
    """配方行的稳定幂等键。与 seed 脚本一致, 重复同步不产生第二套行。"""
    return f"{dish_code}__{ingredient_code}"


async def write_menu(
    conn,
    factory_id: str,
    dishes: Sequence,
    ingredients: Sequence,
    recipe_lines: Sequence,
) -> Dict[str, int]:
    """写三张表。调用方负责事务与 RLS GUC。

    幂等: 三张表全部 UPSERT。食材按**名字**匹配既有 dim_ingredient 行 ——
    平台的 ingredientCode 与库里的 ingredient_id 是两套编号, 用名字对齐。
    """
    await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
    stats: Dict[str, int] = {}

    # ── 1. 食材单价 ───────────────────────────────────────────────────
    name_by_code = {i.ingredient_code: i.name for i in ingredients}
    rows = await conn.fetch(
        "SELECT ingredient_id, name FROM dim_ingredient"
        " WHERE factory_id = $1 AND name = ANY($2::varchar[])",
        factory_id, list(name_by_code.values()),
    )
    ingr_id_by_name = {r["name"]: r["ingredient_id"] for r in rows}
    missing = sorted(n for n in name_by_code.values() if n not in ingr_id_by_name)
    if missing:
        # 禁降级: 少一个食材, 依赖它的那几道菜就会缺行, 成本偏低而不报错。
        raise RuntimeError(f"dim_ingredient 缺少这些食材, 拒绝部分写入: {missing}")

    updated = 0
    for ing in ingredients:
        r = await conn.execute(
            "UPDATE dim_ingredient"
            "   SET unit_price = $3, unit = COALESCE(unit, $4),"
            "       cost_source = 'mock_platform', updated_at = NOW()"
            " WHERE factory_id = $1 AND name = $2",
            factory_id, ing.name,
            Decimal(ing.unit_price_cents) / Decimal(100), ing.unit,
        )
        updated += int(r.split()[-1]) if r else 0
    stats["dim_ingredient_unit_price"] = updated

    # ── 2. 菜名 → product_source_pk 映射 ──────────────────────────────
    mapped = 0
    for dish in dishes:
        r = await conn.execute(
            """
            INSERT INTO dim_restaurant_cost_product
                   (factory_id, product_source_pk, product_name, normalized_name,
                    source, is_active)
            VALUES ($1, $2, $3, $3, 'mock_platform', TRUE)
            ON CONFLICT (factory_id, product_source_pk) DO UPDATE SET
                product_name    = EXCLUDED.product_name,
                normalized_name = EXCLUDED.normalized_name,
                source          = EXCLUDED.source,
                is_active       = TRUE,
                updated_at      = NOW()
            """,
            factory_id, dish.dish_code, dish.name,
        )
        mapped += int(r.split()[-1]) if r else 0
    stats["dim_restaurant_cost_product"] = mapped

    # ── 3. 配方行(分摊后的 line_cost) ─────────────────────────────────
    allocated = compute_line_costs(
        {i.ingredient_code: i.unit_price_cents for i in ingredients},
        [(r.dish_code, r.ingredient_code, r.qty_milli) for r in recipe_lines],
        known_dishes=[d.dish_code for d in dishes],
    )
    unit_by_code = {i.ingredient_code: i.unit for i in ingredients}
    written = 0
    for line in allocated:
        r = await conn.execute(
            """
            INSERT INTO fact_restaurant_recipe_line
                   (factory_id, source_pk, product_id, product_source_pk,
                    ingredient_id, standard_qty, unit, is_main_ingredient,
                    line_cost, is_active, created_at, updated_at)
            VALUES ($1, $2, 0, $3, $4, $5, $6, $7, $8, TRUE, NOW(), NOW())
            ON CONFLICT (factory_id, source_pk) DO UPDATE SET
                product_source_pk  = EXCLUDED.product_source_pk,
                ingredient_id      = EXCLUDED.ingredient_id,
                standard_qty       = EXCLUDED.standard_qty,
                unit               = EXCLUDED.unit,
                is_main_ingredient = EXCLUDED.is_main_ingredient,
                line_cost          = EXCLUDED.line_cost,
                is_active          = TRUE,
                updated_at         = NOW()
            """,
            factory_id,
            recipe_source_pk(line.dish_code, line.ingredient_code),
            line.dish_code,
            ingr_id_by_name[name_by_code[line.ingredient_code]],
            Decimal(line.qty_milli) / Decimal(1000),
            unit_by_code.get(line.ingredient_code),
            line.is_main_ingredient,
            line.line_cost,
        )
        written += int(r.split()[-1]) if r else 0
    stats["fact_restaurant_recipe_line"] = written

    # ── 4. 剪除本命名空间里平台已不再报的配方行 ────────────────────────
    # 主数据同步的语义是**全量替换**而不是只增不删: 平台删掉一条配方, 库里那行
    # 若留着, 它仍会被 ETL 计进 food_cost —— 成本只增不减且没人察觉。
    #
    # 🔴 这一步同时修掉一个真实隐患: 首次 seed 用的幂等键是 `mp_rec_001_001`,
    #    而本 writer 用 `mp_dish_001__mp_ingr_001`。不剪的话 connector 首跑会
    #    在同一批菜上写出**第二套 22 行**, 食材成本直接翻倍, 而两套行看起来
    #    都合法。(2026-08-01 写第二个实现时抓到, 当时 prod 已有 22 行旧键。)
    #
    # 作用域刻意收紧到「我们刚写的这批菜」: 只删 product_source_pk 命中本次
    # dish 集合、且 source_pk 不在本次写入集合里的行。别的租户、别的来源、
    # 别的菜品命名空间一律不碰。
    dish_codes = [d.dish_code for d in dishes]
    keep_pks = [recipe_source_pk(l.dish_code, l.ingredient_code) for l in allocated]
    pruned = await conn.execute(
        "DELETE FROM fact_restaurant_recipe_line"
        " WHERE factory_id = $1"
        "   AND product_source_pk = ANY($2::varchar[])"
        "   AND source_pk <> ALL($3::varchar[])",
        factory_id, dish_codes, keep_pks,
    )
    stats["pruned_stale_lines"] = int(pruned.split()[-1]) if pruned else 0

    logger.info("[menu-writer] factory=%s %s", factory_id, stats)
    return stats


async def sync_menu(pool, adapter, factory_id: str) -> Dict[str, int]:
    """从平台拉全量菜单主数据并落库。一个事务, 要么整套写进去要么一行都不写。

    为什么是**全量而不是增量**: 分摊的分母是「这道菜的全部配方行」, 只拿到一半
    时分母偏小, line_cost 会被系统性放大 —— 而结果看起来完全正常。主数据总量很小
    (实测 10 菜 / 13 食材 / 22 配方), 全量拉一次的代价远小于一个算错的成本表。

    调用方负责节律: 这是主数据, 不该跟着订单每分钟拉。
    """
    dishes = await adapter.fetch_all("dish")
    ingredients = await adapter.fetch_all("ingredient")
    recipe = await adapter.fetch_all("recipe")
    async with pool.acquire() as conn:
        async with conn.transaction():
            return await write_menu(conn, factory_id, dishes, ingredients, recipe)


__all__ = [
    "compute_line_costs", "recipe_source_pk", "write_menu", "sync_menu",
    "AllocatedLine",
]
