"""真值对账闸 —— 把聚合结果**还原成生成器的输入参数**，与源码常量逐条比。

🔴 为什么需要它（2026-08-09/10 五轮对抗审计的唯一幸存结论）：

   登记表能算 3168 个格子，而其中逐字核对过的只有 3 个。**跑得通不等于算对** ——
   今天实测的扇出缺陷就是「SQL 跑得通、数字看着像那么回事、结论错 57 倍」
   （米饭营收 ¥34,839 → ¥2,001,255，毛利率 99.5%）。

⛔ 三条纪律，全部是被推翻出来的：

   1. **左边从生成器源码取，绝不从数据库取。**
      从库里读出 128 再拿去比 128 —— 那是恒真式，一次都红不了。

   2. **右边必须走 `execute_cell`。**
      手写 `SUM(i.amount)/SUM(i.qty)` 也是恒真式：`amount` 本来就是采集时
      按 `qty × price` 算出来的，比值恒等于单价，只验了采集。
      判别力来自**分子由登记表自己选表达式** —— 扇出时它会选错，比值当场变形。

   3. **⛔ 不用 `_DISHES` 的第 4 列 `cost_cents`。**
      源码注释写着它**已弃用**（全成本由 `_full_cost_cents()` 从配方推导）。
      拿它当锚会红在一个不存在的问题上。这里用的是**食材成本**，
      由 `_RECIPES × _INGREDIENTS.unit_price_cents` 推出来。

⚠️ 比值锚对「分子分母同倍缩放」是瞎的（日期过滤错、采集丢行，两边一起变），
   所以下面还有绝对量锚补这个盲区。

用法：
    python -m smartbi.scripts.registry_truth_check              # 全跑
    python -m smartbi.scripts.registry_truth_check --window 7   # 指定月份
"""
from __future__ import annotations

import argparse
import asyncio
import os
import sys
from datetime import date
from typing import Dict, List, Optional, Sequence, Tuple

import asyncpg

from smartbi.gold.restaurant.generic_executor import (
    UnsupportedCell,
    execute_cell,
    existing_columns,
)

#: 🔑 **锚 = 生成器源码常量**，取自 `mock-platform/mock_platform/world/seed.py`：
#:     菜单价      `_DISHES` 的第 3 列 price_cents ÷ 100
#:     每份食材成本 `food_cost_cents(dish, unit_price)` ÷ 100
#:                 （= `_RECIPES` 逐行 用量 × `_INGREDIENTS` 单价 ÷ 1000）
#:
#: ⚠️ 这是**副本**。副本会漂移，所以有一道同步闸盯着它：
#:    `test_truth_anchors.py::test_anchors_match_the_generator_source`
#:    在能读到 seed.py 的地方重算一遍逐条比对，对不上就红。
#:    ⛔ 别手改这里的数 —— 改了同步闸会红；要改先改 seed.py。
_ANCHORS: Dict[str, Tuple[float, float]] = {
    # 菜名: (菜单价, 每份食材成本)
    "藤椒鸡": (58.00, 19.3300),
    "水煮牛肉": (68.00, 28.1500),
    "干锅花菜": (38.00, 6.2100),
    "鲈鱼": (88.00, 32.9400),
    "罗氏虾": (128.00, 49.4300),
    "娃娃菜": (22.00, 3.2000),
    "米饭": (3.00, 0.8100),
    "酸梅汤": (12.00, 2.7900),
    "红糖糍粑": (26.00, 2.7800),
    "凉拌木耳": (18.00, 3.3500),
}

#: 绝对量锚 —— 补比值锚的盲区。
#:
#: 🔴 为什么必需：比值锚对「分子分母**同倍**缩放」是瞎的 —— 日期过滤写错、
#:    租户过滤写错、采集丢了 30% 的行，这些让两边一起变，单价照样等于 128。
#:    绝对量是唯一能抓到它们的。
#: 来源：`seed.py` 的 `_STORES`(10) / `_INGREDIENTS`(25) / `_DISHES`(10)。
_ABSOLUTE_ANCHORS = {"菜品数": len(_ANCHORS), "门店数": 10, "食材数": 25}

#: 分布锚 —— 渠道的抽样权重。来源：`generator.py::_CHANNEL_WEIGHTS`。
#:
#: ⚠️ 口径必须是**按单量**不是按营收 —— 权重是**每张订单**按它抽渠道的，
#:    而各渠道客单价不同，营收占比会系统性偏离。2026-08-10 实测：
#:        按单量  0.6229 / 0.2782 / 0.0988   与源码差 0.3 个点 ✓
#:        按营收  0.6372 / 0.2688 / 0.0940   与源码差 1.7 个点 ✗
#:    先量了口径才没锚错 —— 锚在错的口径上，闸会天天报一个不存在的问题。
_CHANNEL_WEIGHTS = {"dine_in": 0.62, "takeaway": 0.28, "groupon": 0.10}
#: 2 个百分点。7 月约 6 万单，比例的标准误约 0.2 个点，这个容差远大于抽样噪音，
#: 又足够抓到真实的权重改动（如 0.62 → 0.55）。
_DISTRIBUTION_TOLERANCE = 0.02

#: 1 分钱。单价与食材成本都是「每份恒定」的量，比值应当精确到分。
_TOLERANCE = 0.011


async def _ratio(conn, cols, *, factory_id: str, numerator: str, dish: str,
                 rng: Tuple[date, date]) -> Optional[float]:
    """`numerator ÷ 销量`，两边**都走 execute_cell**，都过滤到这一道菜。"""
    out = []
    for metric in (numerator, "sales_qty"):
        r = await execute_cell(
            conn, factory_id=factory_id, metric_key=metric,
            dimension_key="product", aggregation_key="summary",
            date_range=rng, available_columns=cols, entity_filter=dish)
        if not r.ok or not r.rows:
            return None
        out.append(r.rows[0].get(metric))
    top, bottom = out
    if top is None or not bottom:
        return None
    return float(top) / float(bottom)


async def run(factory_id: str, rng: Tuple[date, date]) -> List[str]:
    failures: List[str] = []
    pool = await asyncpg.create_pool(
        host=os.getenv("SMARTBI_DB_HOST", "localhost"),
        user=os.getenv("SMARTBI_DB_USER", "smartbi_user"),
        database=os.getenv("SMARTBI_DB_NAME", "smartbi_prod_db"),
        password=os.environ["SMARTBI_DB_PASSWORD"], min_size=1, max_size=3)
    try:
        async with pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id)
            cols = await existing_columns(conn)

            print(f"{'菜品':10s} {'锚·菜单价':>10s} {'算出来':>10s}   "
                  f"{'锚·食材成本':>12s} {'算出来':>10s}")
            print("-" * 62)
            for dish, (price, food_cost) in _ANCHORS.items():
                async with pool.acquire() as c2:
                    await c2.execute(
                        "SELECT set_config('app.factory_id', $1, false)", factory_id)
                    got_price = await _ratio(c2, cols, factory_id=factory_id,
                                             numerator="revenue", dish=dish, rng=rng)
                    got_cost = await _ratio(c2, cols, factory_id=factory_id,
                                            numerator="food_cost", dish=dish, rng=rng)
                mark = "  "
                if got_price is None or abs(got_price - price) > _TOLERANCE:
                    failures.append(
                        f"{dish} 菜单价: 源码 {price:.2f}, 算出来 {got_price}")
                    mark = "❌"
                if got_cost is None or abs(got_cost - food_cost) > _TOLERANCE:
                    failures.append(
                        f"{dish} 每份食材成本: 源码 {food_cost:.4f}, 算出来 {got_cost}")
                    mark = "❌"
                print(f"{dish:10s} {price:10.2f} {(got_price if got_price is not None else float('nan')):10.2f}   "
                      f"{food_cost:12.4f} {(got_cost if got_cost is not None else float('nan')):10.4f} {mark}")

            # ── 绝对量锚：比值锚对「同倍缩放」是瞎的，这里补上 ──────────────
            print()
            for label, (metric, dim) in (("菜品数", ("sales_qty", "product")),
                                         ("门店数", ("revenue", "store")),
                                         ("食材数", ("wastage_cost", "ingredient"))):
                async with pool.acquire() as c3:
                    await c3.execute(
                        "SELECT set_config('app.factory_id', $1, false)", factory_id)
                    r = await execute_cell(
                        c3, factory_id=factory_id, metric_key=metric,
                        dimension_key=dim, aggregation_key="compare",
                        date_range=rng, available_columns=cols)
                got, want = len(r.rows), _ABSOLUTE_ANCHORS[label]
                ok = got == want
                if not ok:
                    failures.append(f"{label}: 源码 {want}, 算出来 {got}")
                print(f"绝对量·{label}: 源码 {want}, 算出来 {got} {'' if ok else '❌'}")

            # ── 跨粒度对账：全店汇总类格子的锚 ────────────────────────────
            #
            # 🔴 为什么需要：实测 **78% 的真实提问**落在「全店汇总」这类格子上
            #    (销量×全店 2298 次、营收×全店 1691 次、毛利率×全店 660 次)，
            #    而它们**一个锚都没有** —— 单价锚只覆盖菜品维度。
            #
            # ⛔ 这不是「各维度求和 == 全店合计」那种恒真式(我在第 3 轮审计里
            #    否掉过它)。差别在于**两边走不同的粒度、不同的表、不同的表达式**：
            #      左边  Σ(revenue×菜品)  → 明细表 SUM(i.amount)
            #      右边  折前营收×全店     → 交易表 SUM(t.gross_amount)
            #    扇出只会让**左边**爆炸(每张订单被每条明细重算)，右边纹丝不动 → 红。
            #    实测两边差 **0.00**。
            print()
            async with pool.acquire() as c5:
                await c5.execute(
                    "SELECT set_config('app.factory_id', $1, false)", factory_id)

                async def cell(metric: str, dim: str, agg: str):
                    return await execute_cell(
                        c5, factory_id=factory_id, metric_key=metric,
                        dimension_key=dim, aggregation_key=agg,
                        date_range=rng, available_columns=cols)

                by_dish = await cell("revenue", "product", "compare")
                gross = await cell("gross_revenue", "all", "summary")
                disc = await cell("discount_amount", "all", "summary")
                net = await cell("revenue", "all", "summary")
                margin = await cell("gross_margin", "all", "summary")
                cost_by_dish = await cell("food_cost", "product", "compare")

            def _one(r, key):
                return float(r.rows[0][key]) if (r.ok and r.rows) else None

            sum_items = sum(float(x["revenue"]) for x in by_dish.rows) if by_dish.rows else None
            g, d, n = _one(gross, "gross_revenue"), _one(disc, "discount_amount"), _one(net, "revenue")

            # ① 明细加总 == 折前营收（跨粒度，扇出必红）
            if sum_items is None or g is None:
                failures.append("跨粒度对账: 取不到数")
            else:
                ok = abs(sum_items - g) <= max(1.0, abs(g) * 1e-6)
                if not ok:
                    failures.append(f"Σ(各菜品营收) {sum_items:.2f} ≠ 折前营收 {g:.2f}")
                print(f"跨粒度·Σ(各菜品营收) {sum_items:15.2f}  折前营收 {g:15.2f} {'' if ok else '❌'}")

            # ② 折前 − 折扣 == 净额（三个不同的列，采集写错任一个就红）
            if None in (g, d, n):
                failures.append("净额恒等: 取不到数")
            else:
                ok = abs((g - d) - n) <= max(1.0, abs(n) * 1e-6)
                if not ok:
                    failures.append(f"折前 {g:.2f} − 折扣 {d:.2f} ≠ 净额 {n:.2f}")
                print(f"恒等·折前−折扣 {g - d:15.2f}  净额     {n:15.2f} {'' if ok else '❌'}")

            # ③ 全店毛利率 == 由**各菜品**的营收与成本算出来的加权值
            #    ⚠️ 两条路都走登记表, 但一条是 all 维度上的派生表达式、
            #       另一条是 product 维度逐行再加权 —— 派生表达式写错时只有一边变。
            m_all = _one(margin, "gross_margin")
            sum_cost = (sum(float(x["food_cost"]) for x in cost_by_dish.rows)
                        if cost_by_dish.rows else None)
            if None in (m_all, sum_items, sum_cost) or not sum_items:
                failures.append("毛利率一致性: 取不到数")
            else:
                m_from_dish = (sum_items - sum_cost) / sum_items * 100
                ok = abs(m_all - m_from_dish) <= 0.5
                if not ok:
                    failures.append(
                        f"全店毛利率 {m_all:.2f}% ≠ 由各菜品加权算出的 {m_from_dish:.2f}%")
                print(f"一致·全店毛利率 {m_all:14.2f}%  各菜品加权 {m_from_dish:12.2f}% {'' if ok else '❌'}")

            # ── 分布锚：渠道抽样权重。⚠️ 口径是**按单量**不是按营收 ──────────
            async with pool.acquire() as c4:
                await c4.execute(
                    "SELECT set_config('app.factory_id', $1, false)", factory_id)
                r = await execute_cell(
                    c4, factory_id=factory_id, metric_key="orders",
                    dimension_key="channel", aggregation_key="share",
                    date_range=rng, available_columns=cols)
            total = sum(float(x["orders"]) for x in r.rows) or 1.0
            print()
            for row in r.rows:
                ch = str(row.get("dim_label"))
                want = _CHANNEL_WEIGHTS.get(ch)
                got = float(row["orders"]) / total
                if want is None:
                    failures.append(f"渠道占比: 出现源码里没有的渠道 {ch!r}")
                    print(f"分布·{ch}: 源码没有这个渠道 ❌")
                    continue
                ok = abs(got - want) <= _DISTRIBUTION_TOLERANCE
                if not ok:
                    failures.append(
                        f"渠道占比 {ch}: 源码 {want:.2f}, 算出来 {got:.4f}")
                print(f"分布·{ch:9s} 源码 {want:.2f}  算出来 {got:.4f} {'' if ok else '❌'}")
            missing = set(_CHANNEL_WEIGHTS) - {str(x.get("dim_label")) for x in r.rows}
            if missing:
                failures.append(f"渠道占比: 源码有但一条都没查到 {sorted(missing)}")
    finally:
        await pool.close()
    return failures


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--factory", default="MOCK_REST")
    ap.add_argument("--year", type=int, default=2026)
    ap.add_argument("--month", type=int, default=7,
                    help="锚在**完整历史月份**上：当月还没走完时比值会因样本少而抖")
    args = ap.parse_args(argv)
    y, m = args.year, args.month
    end_day = 31 if m in (1, 3, 5, 7, 8, 10, 12) else (30 if m != 2 else 28)
    rng = (date(y, m, 1), date(y, m, end_day))
    print(f"真值对账 · 租户 {args.factory} · 区间 {rng[0]} ~ {rng[1]}\n")
    failures = asyncio.run(run(args.factory, rng))
    if failures:
        print(f"\nTRUTH_CHECK FAIL  {len(failures)} 条对不上:")
        for f in failures:
            print(f"  ❌ {f}")
        return 1
    print(f"\nTRUTH_CHECK OK  {len(_ANCHORS)*2 + len(_ABSOLUTE_ANCHORS)} 条锚全部对上")
    return 0


if __name__ == "__main__":
    sys.exit(main())
