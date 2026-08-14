"""剪掉 MOCK_REST 那几行**陈旧的**成本卡 —— v3 的 47 侧半件。

⚠️⚠️ **种子本身已经不在这里了。** 真源头是 139 上的模拟平台
      (`seed_mock_platform_gap.py`, 跑在 139)。本脚本只剩**一次性剪除**这一件。

## 为什么还需要它（三层 upsert 都不删）

    139 seed.py          UPSERT, 无 DELETE  → 已由 139 那个脚本一次性删过
    menu_writer          UPSERT + 剪除本次菜集合内的陈旧行 → 它删的是 recipe_line
    ETL Stage 3d         UPSERT, **无 DELETE** → agg 行会原样留着 ← 本脚本管这个

实测: 菜单同步之后 `product_cost` 只 upsert 了 6 条(有配方的那 6 道),
而 `agg_restaurant_product_cost` 仍是 10 行 —— 那 4 行是**陈旧残留**,
覆盖率纹丝不动, 长得像同步没生效。

⇒ 剪一次之后**永不重建**: 源头没有配方 ⇒ GROUP BY 不产出 ⇒ upsert 无事可做。

## 记账

| 剪什么 | 原 food_cost（反向要用） |
|---|---|
| 罗氏虾 mp_dish_005 | 49.4300 |
| 娃娃菜 mp_dish_006 | 3.1950 |
| 凉拌木耳 mp_dish_010 | 3.3460 |
| 酸梅汤 mp_dish_008 | 2.7900 |

⚠️ 反向只把 agg 行按原值插回去。**要真正恢复得先在 139 上 revert** ——
   否则下一次菜单同步又把配方剪掉, 这里插回去的行会与源头不一致。
"""
from __future__ import annotations

import argparse
import asyncio
import sys

FACTORY = "MOCK_REST"

#: 造成缺口的菜。`(菜名, 原 food_cost)` —— 反向要把 agg 行按原值插回去。
#: 🔑 **挑高营收的**，照青花椒的真实形状（它营收前 18 里只有 4 道有卡）。
#: ⛔ 不随机删：随机删会让「先补这 3 道」的增量只有零点几个百分点，演示不出价值。
#:
#: 当日(2026-08-12)营收与占比，改动前实测：
#:   罗氏虾    ¥183,040.00  24.44%
#:   娃娃菜    ¥ 29,656.00   3.96%
#:   凉拌木耳  ¥ 26,244.00   3.50%
#:   酸梅汤    ¥ 17,268.00   2.31%
#:   合计                  34.21%  → 覆盖率 100% → 约 65.8%（落在 65~75% 目标带）
#: ⚠️ 留 4 个缺口而不是 3 个 —— 这样「先补这 3 道」才是**真的在排优先级**，
#:    而不是「把仅有的全列出来」。
GAPS = {
    "mp_dish_005": ("罗氏虾", "49.4300"),
    "mp_dish_006": ("娃娃菜", "3.1950"),
    "mp_dish_010": ("凉拌木耳", "3.3460"),
    "mp_dish_008": ("酸梅汤", "2.7900"),
}

#: 单位记错的那张卡 —— 份量 ×100。用来演示「你的成本卡记错了单位」。
#: 🔑 owner: 这是这次解冻里**最值钱的一条** —— 通用 AI 永远说不出这句话。
#: ⚠️ 选米饭：它当日营收只占 0.54%，置为异常后对覆盖率几乎没有扰动，
#:    而且**与青花椒那张真实坏卡同形**（¥167.20 一份而卖 ¥16.80）。
#: ⚠️ 改的是**份量**不是价格 —— 「一份米饭用 13 公斤米」正是单位录错的真实长相
#:    （kg 当成 g 填）。改单价会连累用同一种原料的其它菜。
BAD_CARD_PK = "mp_dish_007"
BAD_CARD_NAME = "米饭"
BAD_CARD_FACTOR = 100
#: `source_pk` → `(原 standard_qty, 原 line_cost)`。⛔ 正向写 `原值 × 100`
#: 而不是 `standard_qty * 100`，这样**跑两次正向也不会变成 ×10000**（幂等）。
#: ⚠️ 两个字段都要写 —— 见上面「为什么直接写 line_cost」。
BAD_CARD_LINES = {
    "mp_dish_007__mp_ingr_008": ("0.1300", "0.8060"),   # 米 6.2000/kg
    "mp_dish_007__mp_ingr_023": ("0.0010", "0.0040"),   # 盐 4.0000/kg
}
#: 上面两行加起来 = agg 上的 `food_cost`。×100 之后 = 81.0000，
#: 比值远超 `COST_UNIT_ERROR_RATIO`，会被 `dish_cost_is_implausible` 判出来。
BAD_CARD_ORIGINAL_COST = "0.8100"
BAD_CARD_SEEDED_COST = "81.0000"


def _x100(value: str) -> str:
    """`原值 × BAD_CARD_FACTOR`，保持原有小数位。⛔ 不走 float。"""
    from decimal import Decimal
    return str(Decimal(value) * BAD_CARD_FACTOR)


async def _apply(conn) -> None:
    """只做一件事: **剪掉那 4 行陈旧的 agg**。

    ⛔ 不再改 `fact_restaurant_recipe_line` —— 那一层由 139 的源头 + 菜单同步
       负责(v2 在那里种, 被平台同步抹掉了)。这里动手 = 与同步抢同一批行。
    """
    n = await conn.execute(
        "DELETE FROM agg_restaurant_product_cost"
        " WHERE factory_id = $1 AND product_source_pk = ANY($2)",
        FACTORY, list(GAPS))
    print(f"  剪除陈旧 agg 行: {n}   ({', '.join(v[0] for v in GAPS.values())})")
    print("  ⇒ 源头没有配方 ⇒ Stage 3d 的 GROUP BY 不产出它们 ⇒ 永不重建。")


async def _revert(conn) -> None:
    """把那 4 行 agg 按原值插回去。

    ⚠️ 这只恢复**派生层**。要真正回到改动前, **先在 139 上 revert**
       (`seed_mock_platform_gap.py revert`) 再扳一次菜单同步 ——
       否则下一次同步又把配方剪掉, 这里插回去的行与源头不一致。
    """
    for pk, (name, cost) in GAPS.items():
        await conn.execute(
            # ⚠️ `$1::varchar` 的 cast 不能省: 同一个 $1 既进 INSERT 的 varchar 列,
            #    又在 WHERE 里比较 —— 不 cast 会报
            #    `inconsistent types deduced for parameter $1`(实测踩过)。
            "INSERT INTO agg_restaurant_product_cost"
            " (factory_id, product_id, product_source_pk, food_cost,"
            "  ingredient_count, has_price_data, version, computed_at)"
            " VALUES ($1::varchar, 0, $2::text, $3::numeric, 0, TRUE, 1, NOW())"
            " ON CONFLICT (factory_id, product_source_pk) DO UPDATE SET"
            "    food_cost = EXCLUDED.food_cost,"
            "    version = agg_restaurant_product_cost.version + 1,"
            "    computed_at = NOW()",
            FACTORY, pk, cost)
        print(f"  恢复 agg 行 {name}({pk}) food_cost -> {cost}")


async def _status(conn) -> None:
    print("=== fact_restaurant_recipe_line（源头）===")
    for r in await conn.fetch(
            "SELECT product_source_pk AS pk, count(*) AS lines,"
            "       bool_and(is_active) AS active, sum(line_cost) AS cost"
            "  FROM fact_restaurant_recipe_line WHERE factory_id = $1"
            " GROUP BY 1 ORDER BY 1", FACTORY):
        mark = ("  <- 计划缺口" if r["pk"] in GAPS
                else "  <- 计划坏卡" if r["pk"] == BAD_CARD_PK else "")
        print(f"  {r['pk']:<14} 行数={r['lines']:<3} 全启用={r['active']}"
              f" line_cost合计={r['cost']}{mark}")
    print("\n=== agg_restaurant_product_cost（派生）===")
    rows = await conn.fetch(
        "SELECT product_source_pk AS pk, food_cost FROM agg_restaurant_product_cost"
        " WHERE factory_id = $1 ORDER BY 1", FACTORY)
    present = {r["pk"] for r in rows}
    for r in rows:
        mark = "  <- 计划坏卡" if r["pk"] == BAD_CARD_PK else ""
        print(f"  {r['pk']:<14} {r['food_cost']}{mark}")
    missing = [p for p in GAPS if p not in present]
    print(f"  缺口（表里没有这一行）: "
          f"{', '.join(f'{GAPS[p][0]}({p})' for p in missing) or '（无）'}")


async def _smoke(conn, cretas_conn) -> int:
    """种子还在不在 —— **连这一层的前提一起查**。

    🔴 上一版这里只查了「运营库 recipes = 0」, 而那个前提是我们**挑的一个**,
       不是全部。真正抹掉 v2 的写者(平台同步 `menu_writer`)从来没进过名单。
       ⇒ 判据改成: **先列全「谁写这张表」, 再把每个写者的前提都查一遍。**

    这一层的写者与前提:

      · 139 `seed.py`     → 前提: 那 4 道菜的跳过集合还在（139 侧脚本 smoke 查）
      · `menu_writer`     → 前提: 平台 recipe 端点不再报这 4 道菜  ← ② 查它
      · ETL Stage 3d      → upsert-无删除, 剪过一次就不再重建     ← ③ 查它
      · 运营库 `recipes`  → 对 MOCK_REST 恒 0(Stage 3b 早退)      ← ① 查它
    """
    bad = 0

    n_recipes = await cretas_conn.fetchval(
        "SELECT count(*) FROM recipes WHERE factory_id = $1", FACTORY)
    ok = n_recipes == 0
    print(f"① 前提: 运营库 {FACTORY} 的 recipes 行数 = {n_recipes} "
          f"（必须为 0，否则 Stage 3b 会开始写这张表）{'✅' if ok else '🔴'}")
    bad += 0 if ok else 1

    # 🔑 这一条是新的: 直接问**平台同步落下来的那一层**还有没有这几道菜的配方。
    #    它比「agg 有没有行」更靠近源头 —— agg 是派生的, recipe_line 是同步的产物。
    live = {r["pk"] for r in await conn.fetch(
        "SELECT DISTINCT product_source_pk AS pk FROM fact_restaurant_recipe_line"
        "  WHERE factory_id = $1 AND is_active", FACTORY)}
    back = [GAPS[p][0] for p in GAPS if p in live]
    ok = not back
    print(f"② 前提: 平台同步没有把这 4 道菜的配方搬回来 "
          f"{'✅' if ok else '🔴 又有了: ' + ', '.join(back)}"
          f"（这条红 = 139 上的缺口没了）")
    bad += 0 if ok else 1

    present = {r["pk"] for r in await conn.fetch(
        "SELECT product_source_pk AS pk FROM agg_restaurant_product_cost"
        " WHERE factory_id = $1", FACTORY)}
    still = [GAPS[p][0] for p in GAPS if p in present]
    ok = not still
    print(f"③ 缺口: {len(GAPS) - len(still)}/{len(GAPS)} 道仍无 agg 行 "
          f"{'✅' if ok else '🔴 又回来了: ' + ', '.join(still)}")
    bad += 0 if ok else 1

    cost = await conn.fetchval(
        "SELECT food_cost FROM agg_restaurant_product_cost"
        " WHERE factory_id = $1 AND product_source_pk = $2",
        FACTORY, BAD_CARD_PK)
    ok = cost is not None and abs(float(cost) - float(BAD_CARD_SEEDED_COST)) < 0.01
    print(f"④ 坏卡: {BAD_CARD_NAME} food_cost = {cost} "
          f"（应为 {BAD_CARD_SEEDED_COST}）{'✅' if ok else '🔴'}")
    bad += 0 if ok else 1

    print(f"\n=== 不满足的项: {bad} ===")
    return 1 if bad else 0


async def _run(mode: str) -> int:
    from smartbi.scripts._probe_bootstrap import bootstrap_probe
    ctx = bootstrap_probe(FACTORY)
    pool = await ctx.pool()
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)",
                           FACTORY)
        if mode == "apply":
            await _apply(conn)
        elif mode == "revert":
            await _revert(conn)
        elif mode == "smoke":
            import os
            import asyncpg
            cretas = await asyncpg.connect(
                user=os.environ["FOOD_KB_POSTGRES_USER"],
                password=os.environ["FOOD_KB_POSTGRES_PASSWORD"],
                host=os.environ.get("FOOD_KB_POSTGRES_HOST", "127.0.0.1"),
                port=int(os.environ.get("FOOD_KB_POSTGRES_PORT", "5432")),
                database=os.environ["FOOD_KB_POSTGRES_DB"])
            try:
                return await _smoke(conn, cretas)
            finally:
                await cretas.close()
        else:
            await _status(conn)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("mode", choices=("apply", "revert", "status", "smoke"))
    ap.add_argument("--confirm", default="",
                    help="apply/revert 必须给 YES-MOCK-REST-ONLY")
    args = ap.parse_args()
    if args.mode in ("apply", "revert") and args.confirm != "YES-MOCK-REST-ONLY":
        print("拒绝: 需要 --confirm YES-MOCK-REST-ONLY")
        print("⛔ 本脚本只动 MOCK_REST。青花椒 / DEMO_REST 一行不写。")
        return 2
    return asyncio.run(_run(args.mode))


if __name__ == "__main__":
    sys.exit(main())
