# -*- coding: utf-8 -*-
"""缺口 #1 的影响面：24 问句里有几条 LLM 会给出一个**目录说不是菜**的 dish。

⛔ 不为一个不确定影响面的东西大改 —— 先量。
配阳性对照：拿一个**目录里真有**的菜名进去，判据必须说「是菜」。
"""
from __future__ import annotations

import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

ctx = bootstrap_probe("MOCK_REST")

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.scripts.restaurant_panorama_probe import QUESTIONS  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

FID = "MOCK_REST"

#: 计算式指代 —— 架构点 23 说它们「不是名字，是算出来才知道是谁」
PROBES = list(QUESTIONS) + [
    "最差的那道菜卖得怎么样",
    "最好的那道菜毛利多少",
    "那最差的呢",
]


def _clear():
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()


async def main() -> int:
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 死槽: %s" % ctx.llm_dead_slots)
        return 2

    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        cat = list(rr.current_dish_catalogue() or ())
        print("菜单目录 %d 条: %s" % (len(cat), cat[:12]))
        if not cat:
            print("rc=2 目录为空 —— 判据在目录不可用时恒为 False，读数无意义")
            return 2
        # 🔴 阳性对照：目录里真有的菜名，判据必须说「是菜」
        pos = cat[0]
        if rr._catalogue_says_not_a_dish(pos):
            print("rc=2 阳性对照失败: 目录里的 %r 被判成「不是菜」" % pos)
            return 2
        # 阴性对照：一个必然不是菜的词
        if not rr._catalogue_says_not_a_dish("最差"):
            print("rc=2 阴性对照失败: 「最差」被判成「是菜」")
            return 2
        print("对照 ✅  目录内 %r → 是菜 / 「最差」→ 不是菜\n" % pos)

    rows = []
    for i, q in enumerate(PROBES):
        _clear()
        set_factory_id(FID)
        async with rr.dish_catalogue_scope(pool, FID):
            spec = await ri.parse_restaurant_query(
                q, pool, factory_id=FID, session_key="dg-%d" % i,
                semantic_first=True)
            dish = getattr(spec, "dish", None) if spec else None
            not_a_dish = bool(dish) and rr._catalogue_says_not_a_dish(dish)
        rows.append((q, dish, not_a_dish,
                     getattr(spec, "intent", None) if spec else None,
                     bool(spec and spec.clarification_needed)))

    print("=" * 88)
    print("%-24s %-10s %-6s %-28s %s" % ("问句", "dish", "非菜", "intent", "clar"))
    print("=" * 88)
    for q, dish, bad, intent, clar in rows:
        mark = "🔴" if bad else "  "
        print("%s %-22s %-10s %-6s %-28s %s"
              % (mark, q[:22], dish or "∅", "是" if bad else "", intent or "∅", clar))

    with_dish = [r for r in rows if r[1]]
    bad = [r for r in rows if r[2]]
    print("\n%d 条问句中：设了 dish 的 %d 条，其中**目录说不是菜**的 %d 条"
          % (len(rows), len(with_dish), len(bad)))
    for q, dish, _b, intent, clar in bad:
        print("   🔴 %-24s dish=%r intent=%s clar=%s" % (q, dish, intent, clar))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
