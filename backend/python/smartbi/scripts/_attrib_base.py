# -*- coding: utf-8 -*-
"""基线: 决策型问句今天走到归因链的第几步 —— 真实入口, 绕过叙述缓存。

⚠️ 形态 A⁷: 基线**当场重跑**, ⛔ 不引用记忆里的数。
🔴 绕过 `narrative_cache`(TTL 24h, window_key 含当天日期), 否则量的是历史答案。
🔑 阳性对照(必然会变的量): `synthesize` 被调用时收到的 question + plan 点亮数
   + factbook.attribution 是否非 None。⚠️ 这三格全是 None ⇒ 答案根本没重新生成,
   读数无意义。
"""
from __future__ import annotations

import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

ctx = bootstrap_probe("MOCK_REST")

from smartbi.agent import synthesis_engine as se  # noqa: E402
from smartbi.agent.narrative_cache import NarrativeCacheService  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

FID = "MOCK_REST"
QUESTIONS = [
    "我要不要关掉最差的那家店",
    "哪家店拖后腿",
]

_CLEARED = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")

SEEN: list = []


def _instrument():
    """记 `synthesize` 真正收到的 question / 它算出的 plan / factbook.attribution。

    ⛔ 不改行为 —— 只在前后各记一笔。
    """
    orig_syn = se.ComprehensiveSynthesisEngine.synthesize
    orig_fb = se.ComprehensiveSynthesisEngine._build_factbook

    async def wrapped_fb(self, factory_id, date_range, plan, **kw):
        fb = await orig_fb(self, factory_id, date_range, plan, **kw)
        if SEEN:
            SEEN[-1]["attribution"] = fb.attribution
            SEEN[-1]["missing_dims"] = [d.get("code") for d in
                                        (fb.missing_dimensions or [])]
        return fb

    async def wrapped(self, factory_id, question, date_range, **kw):
        rec = {"question": question, "date_range": str(date_range),
               "plan": None, "attribution": None, "missing_dims": None,
               "source": None, "answer": None}
        SEEN.append(rec)
        rec["plan"] = self.plan_dimensions(
            question, has_history=bool(kw.get("conversation_history")),
            dimension_hints=kw.get("dimension_hints"))
        res = await orig_syn(self, factory_id, question, date_range, **kw)
        rec["source"] = getattr(res, "source", None)
        rec["answer"] = getattr(res, "answer", None)
        return res

    se.ComprehensiveSynthesisEngine.synthesize = wrapped
    se.ComprehensiveSynthesisEngine._build_factbook = wrapped_fb


def _clear():
    for n in _CLEARED:
        getattr(ri, n)()


async def _ask(pool, q, key):
    _clear()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=FID, session_key=key, semantic_first=True)
    return await svc.tiered_answer(q, pool, FID, ctx.role,
                                   precomputed_spec=spec, session_key=key) or {}


async def main() -> int:
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    pool = await ctx.pool()
    _instrument()

    # 绕过叙述缓存 (读侧), ⛔ 不动表
    async def _miss(self, *a, **kw):
        return None

    NarrativeCacheService.get = _miss
    if hasattr(NarrativeCacheService, "get_semantic"):
        NarrativeCacheService.get_semantic = _miss

    for i, q in enumerate(QUESTIONS):
        SEEN.clear()
        res = await _ask(pool, q, f"ab-{i}")
        text = res.get("answer_text") or ""
        print("=" * 78)
        print(f"问: {q}")
        print(f"  kind={res.get('kind')!r}  intent={res.get('intent')!r}")
        print(f"  synthesize 被调用 {len(SEEN)} 次")
        for rec in SEEN:
            plan = rec["plan"] or {}
            on = sorted(k for k, v in plan.items() if v is True)
            att = rec["attribution"]
            print(f"    收到的 question = {rec['question']!r}")
            print(f"    窗口 = {rec['date_range']}")
            print(f"    mode={plan.get('analysis_mode')} auto_expand={plan.get('auto_expand')}"
                  f" attribution={plan.get('attribution')}")
            print(f"    点亮({len(on)}): {', '.join(on)}")
            if att is None:
                print("    factbook.attribution = None  ⇒ 第②步(量价分解)没跑")
            elif att.get("no_data"):
                print("    factbook.attribution = no_data")
            else:
                lg = att.get("laggard") or {}
                print(f"    factbook.attribution: laggard={lg.get('store_name')!r} "
                      f"Δ=¥{lg.get('delta_revenue'):,.0f} 主因={att.get('primary_cause')} "
                      f"客流效应=¥{lg.get('traffic_effect'):,.0f} "
                      f"客单价效应=¥{lg.get('ticket_effect'):,.0f}")
            print(f"    source={rec['source']!r} 答案长度={len(rec['answer'] or '')}")
        # 老板真正看到的原文里有没有第②步的东西
        for mark in ("客流", "客单价", "客流效应", "客单价效应", "拖后腿"):
            print(f"  正文含 {mark!r}: {text.count(mark)} 次")
        print(f"  正文长度={len(text)}")
        print("---- 老板看到的原文 ----")
        print(text)
        print()
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
