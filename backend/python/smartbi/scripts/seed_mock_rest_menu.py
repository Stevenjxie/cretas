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
主料行承担了主料以外的成本。识别方式是 `source_pk` 前缀 `mp_rec_` 与
`dim_ingredient.cost_source='mock_platform'`(食材单价本身是**未缩放的真值**,
被缩放的只有配方行的 line_cost)。想改回逐行自洽, 就得先在 139 把配方补成
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
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Dict, List, Tuple

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

_Q4 = Decimal("0.0001")


def _dish_source_pk(dish_id: int) -> str:
    """菜品在成本域的稳定主键。与 139 世界模型的 dish.id 一一对应。"""
    return f"mp_dish_{dish_id:03d}"


def _recipe_source_pk(dish_id: int, ingredient_id: int) -> str:
    return f"mp_rec_{dish_id:03d}_{ingredient_id:03d}"


def compute_recipe_lines() -> List[Dict[str, object]]:
    """把快照编译成待写入的配方行, line_cost 按占比摊到 dish.cost_cents。

    分摊后逐菜求和必须**恰好**等于 dish.cost_cents —— 舍入残差补到该菜金额
    最大的一行上, 否则 10 道菜累计能漂出几分钱, 而毛利是拿它逐行减出来的。
    """
    ingr_by_id = {i[0]: i for i in _INGREDIENTS}
    dish_by_id = {d[0]: d for d in _DISHES}
    by_dish: Dict[int, List[Tuple[int, int]]] = {}
    for dish_id, ingredient_id, qty_milli in _RECIPE:
        by_dish.setdefault(dish_id, []).append((ingredient_id, qty_milli))

    out: List[Dict[str, object]] = []
    for dish_id, lines in sorted(by_dish.items()):
        dish = dish_by_id[dish_id]
        target = Decimal(dish[4]) / Decimal(100)          # 元
        raw: List[Tuple[int, int, Decimal]] = []
        for ingredient_id, qty_milli in lines:
            unit_price = Decimal(ingr_by_id[ingredient_id][4]) / Decimal(100)
            qty = Decimal(qty_milli) / Decimal(1000)
            raw.append((ingredient_id, qty_milli, qty * unit_price))
        total_raw = sum(r[2] for r in raw)
        if total_raw <= 0:
            raise ValueError(f"dish {dish_id} 配方原始成本为 0, 无法分摊")

        scaled: List[Decimal] = [
            (r[2] / total_raw * target).quantize(_Q4, rounding=ROUND_HALF_UP)
            for r in raw
        ]
        drift = target.quantize(_Q4) - sum(scaled)
        if drift != 0:                       # 残差补到金额最大的一行
            biggest = max(range(len(scaled)), key=lambda i: scaled[i])
            scaled[biggest] += drift

        main_idx = max(range(len(raw)), key=lambda i: raw[i][2])
        for idx, (ingredient_id, qty_milli, _) in enumerate(raw):
            out.append({
                "source_pk": _recipe_source_pk(dish_id, ingredient_id),
                "product_source_pk": _dish_source_pk(dish_id),
                "mp_ingredient_id": ingredient_id,
                "standard_qty": Decimal(qty_milli) / Decimal(1000),
                "unit": ingr_by_id[ingredient_id][3],
                "is_main_ingredient": idx == main_idx,
                "line_cost": scaled[idx],
            })
    return out


async def _seed(conn) -> Dict[str, int]:
    stats: Dict[str, int] = {}
    await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY_ID)

    # ── 1. 食材单价 ────────────────────────────────────────────────────
    # 按**名字**匹配而不是按 id 偏移: 两边 id 恰好同序是巧合, 不是契约。
    names = [i[1] for i in _INGREDIENTS]
    rows = await conn.fetch(
        "SELECT ingredient_id, name FROM dim_ingredient "
        " WHERE factory_id = $1 AND name = ANY($2::varchar[])",
        FACTORY_ID, names,
    )
    ingr_id_by_name = {r["name"]: r["ingredient_id"] for r in rows}
    missing = [n for n in names if n not in ingr_id_by_name]
    if missing:
        # 禁降级: 少一个食材就意味着配方会挂空, 与其写一半不如整批不写。
        raise RuntimeError(f"dim_ingredient 缺少这些食材, 拒绝部分写入: {missing}")

    updated = 0
    for _mid, name, _cat, unit, price_cents in _INGREDIENTS:
        r = await conn.execute(
            "UPDATE dim_ingredient SET unit_price = $3, unit = COALESCE(unit, $4),"
            "       cost_source = 'mock_platform', updated_at = NOW()"
            " WHERE factory_id = $1 AND name = $2",
            FACTORY_ID, name, Decimal(price_cents) / Decimal(100), unit,
        )
        updated += int(r.split()[-1]) if r else 0
    stats["dim_ingredient_unit_price"] = updated

    # ── 2. 菜名 → product_source_pk 映射 ───────────────────────────────
    # 这张表是 SmartBI 侧的等价映射, 走它就**不必写 Java 库的 product_types**。
    cost_product = 0
    for dish_id, name, _cat, _price, _cost in _DISHES:
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
            FACTORY_ID, _dish_source_pk(dish_id), name,
        )
        cost_product += int(r.split()[-1]) if r else 0
    stats["dim_restaurant_cost_product"] = cost_product

    # ── 3. 配方行 ──────────────────────────────────────────────────────
    lines = compute_recipe_lines()
    written = 0
    for ln in lines:
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
            FACTORY_ID, ln["source_pk"], ln["product_source_pk"],
            ingr_id_by_name[
                next(i[1] for i in _INGREDIENTS if i[0] == ln["mp_ingredient_id"])
            ],
            ln["standard_qty"], ln["unit"], ln["is_main_ingredient"], ln["line_cost"],
        )
        written += int(r.split()[-1]) if r else 0
    stats["fact_restaurant_recipe_line"] = written
    return stats


async def _run(apply: bool) -> int:
    from smartbi.config import get_pg_pool
    from smartbi.gold.restaurant.restaurant_ops_etl import materialize_gold_daily_ops

    lines = compute_recipe_lines()
    per_dish: Dict[str, Decimal] = {}
    for ln in lines:
        per_dish[str(ln["product_source_pk"])] = (
            per_dish.get(str(ln["product_source_pk"]), Decimal(0)) + Decimal(str(ln["line_cost"]))
        )
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
