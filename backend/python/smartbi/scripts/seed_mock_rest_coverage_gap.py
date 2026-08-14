"""给 MOCK_REST 造成本卡缺口 —— **种在源头、可重放、可反向**。

## 为什么（owner 2026-08-14 二次裁定：**我上一版种错了层**）

MOCK_REST 100% 覆盖、无坏卡，于是我们建的东西**在它上面全都不显示**：

    「只算了 X% 的营收」   100% 时按设计消失
    「米饭成本卡记错单位」  没有坏卡
    「先补这 3 道」        没有缺口

演示出来只有三个数字，看不出和通用 AI 的区别 —— 而差异化全在**处理缺数据**上。

## 🔴 v1 种在 `agg_restaurant_product_cost` 上，被 ETL 冲掉了

Stage 3d 每轮从 `fact_restaurant_recipe_line` 重算 `food_cost`
（实测 `version=1640`，`computed_at` 一直在更新）。直接改 agg 表 = 改派生值，
下一轮就没了。**判据只验了可逆性，没验持久性。**

## ⚠️ MOCK_REST 的「源头」在**分析库**，不在运营库

    factory         recipes  product_types  recipe_line  agg_cost
    MOCK_REST             0             10           72        10
    DEMO_REST           383            136          383       136
    RES_3101_009        383            136          460       136

MOCK_REST 在**运营库 `cretas_prod_db.recipes` 里一行都没有**。那 72 行
`fact_restaurant_recipe_line` 是**孤儿** —— Stage 3b 从运营库拿不到源行，
而它也是 upsert-无删除，所以永远刷不掉。逐菜 `line_cost` 合计与 `food_cost`
精确相等（49.4300 / 3.1950 / 0.8100 / 2.7900 / 3.3460），证实 Stage 3d
就是从这 72 行算出来的。

## 🔴🔴 这个种子的持久性依赖一个前提，写死在这里

  ▎**前提：运营库里 MOCK_REST 的 `recipes` 行数 = 0。**
  ▎哪天有人往那儿种了 recipes，Stage 3b 就会把这 72 行孤儿刷掉，
  ▎种子**无声消失** —— 和 v1 被冲掉一模一样，只是触发条件换了。

⇒ 冒烟脚本必须**同时**检查这个前提（`smoke` 模式），⛔ 不能只检查覆盖率还是
   65% 档。否则下次它消失时，我们又会先去怀疑 ETL。

## 两件事各自为什么这么做

| | 做法 | 为什么扛得过 ETL |
|---|---|---|
| 坏卡 | `standard_qty` **和** `line_cost` 都 ×100 | Stage 3d 每轮重算 `food_cost = SUM(line_cost)`；而 `line_cost` 本身对 MOCK_REST **不会被重算**（见下） |
| 缺口 | `is_active = FALSE` **+ 一次性删 agg 行** | Stage 3d 有 `WHERE is_active = TRUE`，那几道不再进 GROUP BY；而它是 upsert-**无删除**，所以旧 agg 行必须手动删一次，之后永不重建 |

### 🔴 为什么坏卡要**直接写 `line_cost`**，只改 `standard_qty` 是惰性的

第一版只改了 `standard_qty`，跑完一轮 ETL 冒烟报
`③ 坏卡: 米饭 food_cost = 0.8100（应为 81.0000）🔴`。

原因: `sync_fact_recipe`(Stage 3b) 的**第一件事**就是

```python
    if not rows:          # restaurant_ops_etl.py:481
        return 0
```

MOCK_REST 在运营库里 0 行 recipes ⇒ 它**在算 `line_cost` 之前就返回了**。
那段 `line_cost = ROUND(standard_qty × unit_price)` 对这个租户从来不执行。

⇒ 对 MOCK_REST 而言，`line_cost` **就是**最深的活输入（Stage 3d 每轮读它）。
⚠️ 两个字段一起写是为了**内部自洽**: 只写 `line_cost` 会让
   `standard_qty × unit_price ≠ line_cost`，下一个来查的人会以为数据坏了。

⛔ 只关 `is_active` 不删 agg 行 = **空转，而且是静默的**：脚本会打印
   「已关闭 4 道菜」，跑完 ETL 那一屏还是 100%，长得像 ETL 没跑。

## 📌 缺口为什么是「删行」不是「置 NULL」

三个租户 `food_cost IS NULL` 都是 **0** 行，而覆盖率只有 42.2% / 61.3% ——
真实缺口全部来自「agg 表里**没有这一行**」（POS 卖的菜 join 不上）。
v1 用 NULL 是权宜之计，删行才是真实形状。

## 记账

正向改了什么、原值是多少，**全部写死在下面这两张表里** —— 反向脚本读同一份。
⛔ 不去库里「查当前值再改回去」：那在跑过两次正向之后会把坏值当成原值。
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
    for src_pk, (qty, cost) in BAD_CARD_LINES.items():
        # ⛔ 用 Decimal 不用 float: `float("0.8060") * 100` 会写进
        #    `80.60000000000001` —— 一个眼就看得出是脚本造的脏值。
        new_qty, new_cost = _x100(qty), _x100(cost)
        await conn.execute(
            "UPDATE fact_restaurant_recipe_line"
            "   SET standard_qty = $3::numeric, line_cost = $4::numeric"
            " WHERE factory_id = $1 AND source_pk = $2",
            FACTORY, src_pk, new_qty, new_cost)
        print(f"  坏卡 {BAD_CARD_NAME} {src_pk} "
              f"qty {qty}->{new_qty}  line_cost {cost}->{new_cost}")
    n = await conn.execute(
        "UPDATE fact_restaurant_recipe_line SET is_active = FALSE"
        " WHERE factory_id = $1 AND product_source_pk = ANY($2)",
        FACTORY, list(GAPS))
    print(f"  缺口 关闭配方行: {n}")
    # ⛔ 这一步不能省: Stage 3d 是 upsert-**无删除**, 不删的话旧 agg 行原样活着,
    #    覆盖率纹丝不动 —— 长得像 ETL 没跑。
    n = await conn.execute(
        "DELETE FROM agg_restaurant_product_cost"
        " WHERE factory_id = $1 AND product_source_pk = ANY($2)",
        FACTORY, list(GAPS))
    print(f"  缺口 删除 agg 行: {n}   ({', '.join(v[0] for v in GAPS.values())})")
    print("\n⚠️ 现在还看不到效果 —— `line_cost` 要等 Stage 3b 重算。"
          "跑一轮 ETL 之后再看 `smoke`。")


async def _revert(conn) -> None:
    for src_pk, (qty, cost) in BAD_CARD_LINES.items():
        await conn.execute(
            "UPDATE fact_restaurant_recipe_line"
            "   SET standard_qty = $3::numeric, line_cost = $4::numeric"
            " WHERE factory_id = $1 AND source_pk = $2",
            FACTORY, src_pk, qty, cost)
        print(f"  恢复 {BAD_CARD_NAME} {src_pk} qty -> {qty}  line_cost -> {cost}")
    n = await conn.execute(
        "UPDATE fact_restaurant_recipe_line SET is_active = TRUE"
        " WHERE factory_id = $1 AND product_source_pk = ANY($2)",
        FACTORY, list(GAPS))
    print(f"  恢复 配方行启用: {n}")
    # agg 行按**原值**插回去 —— ⛔ 不等 ETL: 「跑一轮就回来了」在 ETL 恰好
    #    坏掉的那天会让人以为是反向脚本没生效。
    for pk, (name, cost) in GAPS.items():
        await conn.execute(
            # ⚠️ `$1::varchar` 的 cast 不能省: 同一个 $1 既进 INSERT 的
            #    varchar 列, 又在 WHERE 里跟 varchar 比 —— 不 cast 时
            #    Postgres 会报 `inconsistent types deduced for parameter $1`。
            "INSERT INTO agg_restaurant_product_cost"
            " (factory_id, product_id, product_source_pk, food_cost,"
            "  ingredient_count, has_price_data, version, computed_at)"
            " SELECT $1::varchar, 0, $2::text, $3::numeric, count(*),"
            "        bool_and(line_cost IS NOT NULL), 1, NOW()"
            "   FROM fact_restaurant_recipe_line"
            "  WHERE factory_id = $1::varchar AND product_source_pk = $2::text"
            "    AND is_active"
            " ON CONFLICT (factory_id, product_source_pk) DO UPDATE SET"
            "    food_cost = EXCLUDED.food_cost,"
            "    ingredient_count = EXCLUDED.ingredient_count,"
            "    has_price_data = EXCLUDED.has_price_data,"
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
    """种子还在不在 —— **两件都查**。

    🔴 只查覆盖率不够: 种子消失有两种成因, 读数长得一样 ——
       ① agg 被 ETL 重建（缺口那一步没删干净）
       ② **运营库长出了 recipes**, Stage 3b 把 72 行孤儿刷掉了
    查不出 ② 的话, 下次它消失我们又会先去怀疑 ETL。
    """
    bad = 0
    n_recipes = await cretas_conn.fetchval(
        "SELECT count(*) FROM recipes WHERE factory_id = $1", FACTORY)
    ok = n_recipes == 0
    print(f"① 前提: 运营库 {FACTORY} 的 recipes 行数 = {n_recipes} "
          f"（必须为 0）{'✅' if ok else '🔴 前提破了 —— 种子随时会被 Stage 3b 刷掉'}")
    if not ok:
        bad += 1

    present = {r["pk"] for r in await conn.fetch(
        "SELECT product_source_pk AS pk FROM agg_restaurant_product_cost"
        " WHERE factory_id = $1", FACTORY)}
    still_there = [GAPS[p][0] for p in GAPS if p in present]
    ok = not still_there
    print(f"② 缺口: {len(GAPS) - len(still_there)}/{len(GAPS)} 道仍无 agg 行 "
          f"{'✅' if ok else '🔴 又回来了: ' + ', '.join(still_there)}")
    if not ok:
        bad += 1

    cost = await conn.fetchval(
        "SELECT food_cost FROM agg_restaurant_product_cost"
        " WHERE factory_id = $1 AND product_source_pk = $2",
        FACTORY, BAD_CARD_PK)
    ok = cost is not None and abs(float(cost) - float(BAD_CARD_SEEDED_COST)) < 0.01
    print(f"③ 坏卡: {BAD_CARD_NAME} food_cost = {cost} "
          f"（应为 {BAD_CARD_SEEDED_COST}）{'✅' if ok else '🔴'}")
    if not ok:
        bad += 1

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
