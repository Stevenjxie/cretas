"""量最关键的那一句：**老板照着这个替代去问，答得上来吗？**

▎排在最前面的那个替代，他去问如果一无所获，这就是一条误发的提示。

⛔ 这不验收本轮改动（prod 跑的是部署前的活代码）。它验的是**被推荐的那个词
   本身能不能问得到** —— 而那正是「不误发」的全部内容。

阳性对照：一个**已知答得出来**的问句（「营收」）必须拿到 answer。
阴性对照：一个**已知算不出来**的问句（「翻台率」）必须拿到拒答 —— 两侧都动，
才说明这把尺子在量东西而不是恒返回同一个 kind。
"""
from __future__ import annotations

import asyncio
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FACTORY = "MOCK_REST"
QUESTIONS = [
    ("阳性对照", "营收"),
    ("替代-翻台率", "订单数"),
    ("替代-净利润", "毛利"),
    ("阴性对照", "翻台率怎么样"),
]


async def main(ctx):
    from smartbi.config import get_pg_pool
    from smartbi.gold.restaurant import restaurant_intent as ri
    from smartbi.gold.restaurant.restaurant_intent_service import tiered_answer

    pool = await get_pg_pool()

    # ⚠️ 跨样本读数前清缓存，并**贴出清了哪几个**（硬约束 3）
    cleared = []
    for fn in ("clear_semantic_plan_cache", "clear_route_cache",
               "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, fn)()
        cleared.append(fn)
    print("已清缓存: %s" % "、".join(cleared))

    for tag, q in QUESTIONS:
        try:
            out = await tiered_answer(q, pool, FACTORY, ctx.role,
                                      session_key="nearest-alt-probe-%s" % tag)
        except Exception as exc:  # noqa: BLE001 - 失败逐条贴, ⛔ 不 continue
            print("=" * 70)
            print("%-12s %-14s 🔴 %s: %s" % (tag, q, type(exc).__name__, exc))
            continue
        kind = (out or {}).get("kind")
        text = str((out or {}).get("answer_text") or "")
        print("=" * 70)
        print("%-12s 问句=%r  kind=%r  长度=%d" % (tag, q, kind, len(text)))
        print(text[:600])


if __name__ == "__main__":
    ctx = bootstrap_probe(FACTORY)
    if ctx.llm_dead_slots:
        print("🔴 LLM 槽没 key，本轮读数无效 —— 每条都会是 fail-closed 拒答",
              file=sys.stderr)
        sys.exit(2)
    asyncio.run(main(ctx))
