"""MOCK_REST 菜单主数据 seed —— 补齐配方 / 食材单价 / 菜名映射, 让菜品成本算得出来。

## 为什么需要它

MOCK_REST 的工厂名就是「模拟平台餐饮租户 (假 POS 数据接入验证)」—— 它当初按
**POS 接入验证**建的, 只有交易流水, 从没给过菜单主数据。2026-08-01 四部门审计
实测的后果:

    财务  能答 4/4   实质 1/4
      · 「哪家店毛利最好」   → 毛利前 0 名门店
      · 「哪些菜食材成本最高」→ 菜品食材成本前 0 名
      · 「毛利最低的菜品」    → 毛利前 0 名菜品

三条同一根因: `agg_restaurant_product_cost` 0 行。而该表由 ETL 从
`fact_restaurant_recipe_line` 汇总, 上游实测:

    dim_product        10   ✅ (POS 侧已有菜)
    dim_ingredient     13   ✅ 名字有, **unit_price 全 NULL**
    fact_recipe_line    0   ❌
    cost_product 映射   0   ❌

已用阳性对照证明 **resolver 本身是好的**: 同一批问句在 RES_3101_009(有 383 条配方
/ 136 行成本)上全部答得出来。所以这是**数据缺口不是能力缺口**, 补数据即可, 不动代码。

## 数据来源与口径

主数据取自 139 上的餐饮外部平台模拟器世界模型
(`/www/wwwroot/mock-platform/data.db` 的 dish / ingredient / recipe 三表)。
下面的 `_DISHES / _INGREDIENTS / _RECIPE` 是 2026-08-01 从那里**逐字导出**的快照,
不是手抄 —— 手抄输入正是本仓踩过的坑(抄错一个 sub_sector 就让 diff 里凭空出现
「像真缺陷」的财务差异)。

🔴 **成本口径以 `dish.cost_cents` 为权威** (2026-08-01 Steve 拍板)。

模拟端有两个互相独立的成本事实, 实测差 2.2 倍:

    dish.cost_cents 合计   ¥193.80   食材成本率 42.0%   ← 权威
    22 条配方逐行算出       ¥ 87.41   食材成本率 19.0%

配方每道菜只列 2-4 种主料(藤椒鸡 = 鸡腿肉 + 藤椒 + 菜籽油), **不是完整 BOM** ——
没有配菜、调料、损耗。按它直接算, 毛利率会显示 81%, 技术上答得出来但不真实
(参考租户 RES_3101_009 实测加权毛利率 85.1%, 很可能同一成因)。

因此: **配方的「用了哪些主料、各多少」与食材单价都保持真实**, 而 `line_cost`
按各行原始占比摊到 `dish.cost_cents`, 把主料以外的部分显式分摊进去。

    line_cost_i = qty_i × unit_price_i / Σ(qty × unit_price) × dish_cost

⚠️ 代价是 `line_cost ≠ standard_qty × unit_price`。这是**有意的**, 不是 bug:
主料行承担了主料以外的成本。食材单价本身是**未缩放的真值**
(`dim_ingredient.cost_source='mock_platform'`), 被缩放的只有配方行的 line_cost。想改回逐行自洽, 就得先在 139 把配方补成
完整 BOM —— 见交接 `handoff-2026-08-01d`。

## 用法

    cd /www/wwwroot/cretas/code/backend/python
    PYTHONPATH=.:smartbi venv-current/bin/python -m smartbi.scripts.seed_mock_rest_menu \\
        --apply --confirm MOCK_REST

不带 `--apply` 是干跑(打印将要写入的内容, 事务回滚)。幂等: 三张表全部 UPSERT,
重复跑不产生重复行, 也不会累积孤儿。只碰 MOCK_REST。
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from decimal import Decimal
from pathlib import Path
from typing import Dict, Tuple

_PYTHON_ROOT = Path(__file__).resolve().parent.parent.parent
for _p in (str(_PYTHON_ROOT), str(_PYTHON_ROOT / "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

logging.basicConfig(level=logging.INFO, format="%(message)s")
logger = logging.getLogger("seed_mock_rest_menu")

FACTORY_ID = "MOCK_REST"

# ── 139 mock-platform 世界模型快照 (2026-08-01 逐字导出) ──────────────────
# dish: id, name, category, price_cents, cost_cents
_DISHES: Tuple[Tuple[int, str, str, int, int], ...] = (
    (1, "藤椒鸡", "热菜", 5800, 2100),
    (2, "水煮牛肉", "热菜", 6800, 2900),
    (3, "干锅花菜", "热菜", 3800, 1200),
    (4, "鲈鱼", "水产", 8800, 4200),
    (5, "罗氏虾", "水产", 12800, 6800),
    (6, "娃娃菜", "素菜", 2200, 600),
    (7, "米饭", "主食", 300, 80),
    (8, "酸梅汤", "饮品", 1200, 300),
    (9, "红糖糍粑", "甜品", 2600, 700),
    (10, "凉拌木耳", "凉菜", 1800, 500),
)

# ingredient: id, name, category, unit, unit_price_cents
_INGREDIENTS: Tuple[Tuple[int, str, str, str, int], ...] = (
    (1, "鸡腿肉", "肉类", "kg", 2400),
    (2, "牛肉", "肉类", "kg", 6800),
    (3, "鲈鱼", "水产", "kg", 3600),
    (4, "罗氏虾", "水产", "kg", 9800),
    (5, "花菜", "蔬菜", "kg", 800),
    (6, "娃娃菜", "蔬菜", "kg", 600),
    (7, "黑木耳", "干货", "kg", 4200),
    (8, "大米", "米面", "kg", 620),
    (9, "糯米粉", "米面", "kg", 900),
    (10, "红糖", "调料", "kg", 1100),
    (11, "乌梅", "干货", "kg", 5200),
    (12, "藤椒", "调料", "kg", 8600),
    (13, "菜籽油", "调料", "L", 1500),
)

# recipe: dish_id, ingredient_id, qty_milli  (千分之一 kg / L)
_RECIPE: Tuple[Tuple[int, int, int], ...] = (
    (1, 1, 220), (1, 12, 8), (1, 13, 25),
    (2, 2, 180), (2, 6, 80), (2, 12, 6), (2, 13, 35),
    (3, 5, 260), (3, 13, 20),
    (4, 3, 550), (4, 13, 15),
    (5, 4, 400), (5, 13, 10),
    (6, 6, 240),
    (7, 8, 110),
    (8, 10, 12), (8, 11, 18),
    (9, 9, 90), (9, 10, 30), (9, 13, 12),
    (10, 7, 22), (10, 13, 8),
)


def _dish_source_pk(dish_id: int) -> str:
    """菜品在成本域的稳定主键。与 139 世界模型的 dish.id 一一对应。"""
    return f"mp_dish_{dish_id:03d}"


def _ingredient_code(ingredient_id: int) -> str:
    """与 139 `/menu/ingredient/list` 的 ingredientCode 逐字一致。"""
    return f"mp_ingr_{ingredient_id:03d}"


def snapshot_as_normalized():
    """把快照转成与 connector 完全相同的归一化对象。

    🔴 **本脚本不再自己实现分摊、幂等键或写库** —— 全部委托 `menu_writer`,
    与 connector 共用同一条路径。各写一份的话, 首次 seed 与后续 connector 导入
    会算出不同的 line_cost、写出不同的 source_pk; 后者更凶险: 同一批菜会存在
    **两套配方行**, 食材成本直接翻倍, 而两套行看起来都合法。
    (2026-08-01 写 connector 时抓到 —— 当时 prod 已被本脚本早期版本写入 22 行
     旧键 `mp_rec_001_001`, 靠 `write_menu` 的剪除步骤清掉。)
    """
    from smartbi.ingestion.platforms.menu_models import (
        NormalizedDish, NormalizedIngredient, NormalizedRecipeLine,
    )
    P = "keruyun"
    dishes = [
        NormalizedDish(platform=P, dish_code=_dish_source_pk(d),
                       name=n, category=c, price_cents=p, cost_cents=cost)
        for d, n, c, p, cost in _DISHES
    ]
    ingredients = [
        NormalizedIngredient(platform=P, ingredient_code=_ingredient_code(i),
                             name=n, category=c, unit=u, unit_price_cents=up)
        for i, n, c, u, up in _INGREDIENTS
    ]
    recipe = [
        NormalizedRecipeLine(platform=P, dish_code=_dish_source_pk(d),
                             ingredient_code=_ingredient_code(i), qty_milli=q)
        for d, i, q in _RECIPE
    ]
    return dishes, ingredients, recipe


def compute_recipe_lines():
    """干跑预览用: 返回分摊后的配方行(与实际写入的完全一致)。"""
    from smartbi.ingestion.platforms.menu_writer import allocate_line_costs
    dishes, ingredients, recipe = snapshot_as_normalized()
    return allocate_line_costs(
        {d.dish_code: d.cost_cents for d in dishes},
        {i.ingredient_code: i.unit_price_cents for i in ingredients},
        [(r.dish_code, r.ingredient_code, r.qty_milli) for r in recipe],
    )


async def _seed(conn) -> Dict[str, int]:
    """写库全部委托 menu_writer.write_menu —— 与 connector 同一条路径。

    包括它的**剪除步骤**: 早期版本用过 `mp_rec_001_001` 这套幂等键, 不剪的话
    新旧两套会同时留在库里, 食材成本翻倍。
    """
    from smartbi.ingestion.platforms.menu_writer import write_menu
    dishes, ingredients, recipe = snapshot_as_normalized()
    return await write_menu(conn, FACTORY_ID, dishes, ingredients, recipe)


async def _run(apply: bool) -> int:
    from smartbi.config import get_pg_pool
    from smartbi.gold.restaurant.restaurant_ops_etl import materialize_gold_daily_ops

    lines = compute_recipe_lines()
    per_dish: Dict[str, Decimal] = {}
    for ln in lines:
        per_dish[ln.dish_code] = per_dish.get(ln.dish_code, Decimal(0)) + ln.line_cost
    logger.info("将写入 %d 条配方行, 覆盖 %d 道菜:", len(lines), len(per_dish))
    for dish_id, name, _c, price_cents, cost_cents in _DISHES:
        pk = _dish_source_pk(dish_id)
        got = per_dish.get(pk, Decimal(0))
        want = Decimal(cost_cents) / Decimal(100)
        flag = "" if got == want else f"  ⚠️ 与 dish.cost 不符(期望 {want})"
        logger.info("  %-10s %-14s 食材成本 ¥%-8s 售价 ¥%-7s 毛利率 %5.1f%%%s",
                    pk, name, got, Decimal(price_cents) / Decimal(100),
                    float((Decimal(price_cents) / Decimal(100) - got)
                          / (Decimal(price_cents) / Decimal(100)) * 100), flag)

    pool = await get_pg_pool()
    async with pool.acquire() as conn:
        tx = conn.transaction()
        await tx.start()
        try:
            stats = await _seed(conn)
            if not apply:
                await tx.rollback()
                logger.info("干跑完成(已回滚): %s", stats)
                logger.info("加 --apply --confirm MOCK_REST 才会真正写入。")
                return 0
            await tx.commit()
        except Exception:
            await tx.rollback()
            raise
    logger.info("写入完成: %s", stats)

    etl = await materialize_gold_daily_ops(pool, FACTORY_ID)
    logger.info("ETL 重算 Gold: %s", etl)
    async with pool.acquire() as conn:
        # ⚠️ set_config(..., is_local=true) 只在**事务内**有效。第一版把它和 count
        # 分成两条事务外语句, GUC 当场失效 → RLS 把行全藏起来 → 报「0 行」而实际
        # 已经写进去 10 行。查 Gold 表恒返回 0 且长得就像没数据, 正是这个坑。
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY_ID)
            n = await conn.fetchval(
                "SELECT count(*) FROM agg_restaurant_product_cost WHERE factory_id = $1",
                FACTORY_ID,
            )
    logger.info("agg_restaurant_product_cost 现有 %s 行 (seed 前为 0)", n)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="MOCK_REST 菜单主数据 seed (幂等)")
    ap.add_argument("--apply", action="store_true", help="真正写入; 不加则干跑并回滚")
    ap.add_argument("--confirm", default="", help="写入时必须显式传 MOCK_REST")
    args = ap.parse_args()
    if args.apply and args.confirm != FACTORY_ID:
        logger.error("拒绝执行: --apply 必须配 --confirm %s", FACTORY_ID)
        return 2
    return asyncio.run(_run(args.apply))


if __name__ == "__main__":
    raise SystemExit(main())
