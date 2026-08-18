# -*- coding: utf-8 -*-
"""部署判据③：真跑三条最关键的路径，读老板会看到的原文。"""
from __future__ import annotations

import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

ctx = bootstrap_probe("MOCK_REST")

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

FID = "MOCK_REST"
QS = ["翻台率怎么样", "这周比上周怎么样", "哪个供应商报价最贵"]
_CLEARED = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")


def _clear():
    for n in _CLEARED:
        getattr(ri, n)()


async def main() -> int:
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    print("清了: %s\n" % "、".join(_CLEARED))
    seen_any = False
    for i, q in enumerate(QS):
        _clear()
        set_factory_id(FID)
        async with rr.dish_catalogue_scope(pool, FID):
            cat = rr.current_dish_catalogue()
            spec = await ri.parse_restaurant_query(
                q, pool, factory_id=FID, session_key="fin-%d" % i,
                semantic_first=True)
        if not cat:
            print("rc=2 目录为空 —— 仪器没起来")
            return 2
        if spec is None:
            print("\n【%s】 spec=None" % q)
            continue
        res = await svc.tiered_answer(q, pool, FID, ctx.role,
                                      precomputed_spec=spec,
                                      session_key="fin-%d" % i)
        text = (res or {}).get("answer_text") or ""
        seen_any = seen_any or bool(text)
        print("\n" + "=" * 88)
        print("【%s】 kind=%s n=%d" % (q, (res or {}).get("kind"), len(text)))
        print("-" * 88)
        print(text)
    if not seen_any:
        print("\nrc=2 一条正文都没拿到 —— 读数作废")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
