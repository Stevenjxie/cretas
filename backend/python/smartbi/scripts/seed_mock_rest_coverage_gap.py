"""给 MOCK_REST 造成本卡缺口 —— **可重放、可反向**。

## 为什么（owner 2026-08-14 一次性解冻 MOCK_REST 的红线，只解冻这一件）

MOCK_REST 100% 覆盖、无坏卡，于是我们建的东西**在它上面全都不显示**：

    「只算了 X% 的营收」   100% 时按设计消失
    「米饭成本卡记错单位」  没有坏卡
    「先补这 3 道」        没有缺口

演示出来只有三个数字，看不出和通用 AI 的区别 —— 而差异化全在**处理缺数据**上。
顺带它还修「MOCK_REST 太干净」造成过三次假绿的那一面（只修覆盖率这一维，见文末）。

## ⛔ 为什么是 `food_cost = NULL` 而不是 DELETE

两条路判「有没有卡」都看 `food_cost IS NOT NULL`（执行器 join / 问答 cost_map）。
置 NULL 与删行**对产品等价**，但：

  · 不丢行 → 反向只是把数字写回去，不需要重建行
  · `product_source_pk` / 桥接关系原样保留 → 不会顺带制造第二种缺陷

## 记账

正向改了什么、原值是多少，**全部写死在下面这张表里** —— 反向脚本读同一张表。
⛔ 不去库里「查当前值再改回去」：那在跑过两次正向之后会把坏值当成原值。
"""
from __future__ import annotations

import argparse
import asyncio
import sys

FACTORY = "MOCK_REST"

#: 造成缺口的菜 —— `food_cost` 置 NULL。
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

#: 单位记错的那张卡 —— ×100。用来演示「你的成本卡记错了单位」。
#: 🔑 owner: 这是这次解冻里**最值钱的一条** —— 通用 AI 永远说不出这句话。
#: ⚠️ 选米饭：它当日营收只占 0.54%，置为异常后对覆盖率几乎没有扰动，
#:    而且**与青花椒那张真实坏卡同形**（¥167.20 一份而卖 ¥16.80）。
BAD_CARD_PK = "mp_dish_007"
BAD_CARD_NAME = "米饭"
BAD_CARD_ORIGINAL = "0.8100"
BAD_CARD_SEEDED = "81.0000"          # ×100，比值远超 `COST_UNIT_ERROR_RATIO`


async def _run(mode: str) -> int:
    from smartbi.scripts._probe_bootstrap import bootstrap_probe
    ctx = bootstrap_probe(FACTORY)
    pool = await ctx.pool()

    async with pool.acquire() as conn:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", FACTORY)
        if mode == "apply":
            for pk, (name, _orig) in GAPS.items():
                await conn.execute(
                    "UPDATE agg_restaurant_product_cost SET food_cost = NULL "
                    " WHERE factory_id = $1 AND product_source_pk = $2",
                    FACTORY, pk)
                print(f"  缺口 {name}({pk}) food_cost -> NULL")
            await conn.execute(
                "UPDATE agg_restaurant_product_cost SET food_cost = $3::numeric "
                " WHERE factory_id = $1 AND product_source_pk = $2",
                FACTORY, BAD_CARD_PK, BAD_CARD_SEEDED)
            print(f"  坏卡 {BAD_CARD_NAME}({BAD_CARD_PK}) "
                  f"{BAD_CARD_ORIGINAL} -> {BAD_CARD_SEEDED}")
        elif mode == "revert":
            for pk, (name, orig) in GAPS.items():
                await conn.execute(
                    "UPDATE agg_restaurant_product_cost SET food_cost = $3::numeric "
                    " WHERE factory_id = $1 AND product_source_pk = $2",
                    FACTORY, pk, orig)
                print(f"  恢复 {name}({pk}) food_cost -> {orig}")
            await conn.execute(
                "UPDATE agg_restaurant_product_cost SET food_cost = $3::numeric "
                " WHERE factory_id = $1 AND product_source_pk = $2",
                FACTORY, BAD_CARD_PK, BAD_CARD_ORIGINAL)
            print(f"  恢复 {BAD_CARD_NAME}({BAD_CARD_PK}) -> {BAD_CARD_ORIGINAL}")
        else:  # status
            rows = await conn.fetch(
                "SELECT product_source_pk, food_cost FROM agg_restaurant_product_cost "
                " WHERE factory_id = $1 ORDER BY product_source_pk", FACTORY)
            for r in rows:
                mark = ""
                if r["product_source_pk"] in GAPS:
                    mark = "  <- 计划缺口"
                elif r["product_source_pk"] == BAD_CARD_PK:
                    mark = "  <- 计划坏卡"
                print(f"  {r['product_source_pk']:<14} {r['food_cost']}{mark}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("mode", choices=("apply", "revert", "status"))
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
