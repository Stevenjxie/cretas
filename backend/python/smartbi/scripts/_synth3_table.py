# -*- coding: utf-8 -*-
"""任务三：要不要给「10 家店摊开看」的表 —— 先量**表里的数和正文的数对不对得上**。

📏 上一轮 B 组（绕过叙述缓存）的正文说
    「模拟·打浦桥日月光店 30天营业额约208.7万，比全链平均（约211.9万）少约3.2万」
而 `compute_store_attribution` 算出来的是
    revenue=2067338 (206.7万) / delta=-35623 (-3.6万)

▎两个数不一样。给表之前必须先知道**哪一个是喂给模型的那一份** ——
▎否则加一张确定性的表，就是在同一份答案里放两个相反的说法。

⛔ 只读。
"""
from __future__ import annotations

import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

ctx = bootstrap_probe("MOCK_REST")

from smartbi.agent import factbook as fb  # noqa: E402
from smartbi.agent.narrative_cache import NarrativeCacheService  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

FID = "MOCK_REST"
Q = "我要不要关掉最差的那家店"

_CLEARED = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")

CAP = {"fin_lines": [], "attr": None, "calls": 0,
       "synth_calls": 0, "synth_sources": []}


def _instrument():
    from smartbi.agent import synthesis_engine as se

    orig_render = fb.FactBook._render_finance
    orig_synth = se.ComprehensiveSynthesisEngine.synthesize

    def render(self, lines):
        before = len(lines)
        out = orig_render(self, lines)
        CAP["calls"] += 1
        CAP["fin_lines"] = list(lines[before:])
        CAP["attr"] = self.attribution
        return out

    async def synth(self, *a, **kw):
        CAP["synth_calls"] += 1
        res = await orig_synth(self, *a, **kw)
        CAP["synth_sources"].append(getattr(res, "source", "?"))
        return res

    fb.FactBook._render_finance = render
    se.ComprehensiveSynthesisEngine.synthesize = synth

    def restore():
        fb.FactBook._render_finance = orig_render
        se.ComprehensiveSynthesisEngine.synthesize = orig_synth

    return restore


def _clear():
    for n in _CLEARED:
        getattr(ri, n)()


async def main() -> int:
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 槽没有活账号")
        return 2
    print("清了: %s" % "、".join(_CLEARED))

    restore = _instrument()
    orig_get = NarrativeCacheService.get
    orig_sem = getattr(NarrativeCacheService, "get_semantic", None)

    async def _miss(self, *a, **kw):
        return None

    NarrativeCacheService.get = _miss
    if orig_sem is not None:
        NarrativeCacheService.get_semantic = _miss
    try:
        _clear()
        set_factory_id(FID)
        async with rr.dish_catalogue_scope(pool, FID):
            spec = await ri.parse_restaurant_query(
                Q, pool, factory_id=FID, session_key="s3-t", semantic_first=True)
        res = await svc.tiered_answer(Q, pool, FID, ctx.role,
                                      precomputed_spec=spec, session_key="s3-t")
    finally:
        NarrativeCacheService.get = orig_get
        if orig_sem is not None:
            NarrativeCacheService.get_semantic = orig_sem
        restore()

    text = (res or {}).get("answer_text") or ""

    print("\n🔑 synthesize 调用 %d 次，source=%s；_render_finance 调用 %d 次；n=%d"
          % (CAP["synth_calls"], CAP["synth_sources"], CAP["calls"], len(text)))
    if not CAP["calls"]:
        print("rc=2 `_render_finance` 一次都没被调用 ⇒ 没有重新生成，读数作废")
        print("\n---- 本轮正文（仅供定位，⛔ 不作为读数）----")
        print(text)
        return 2

    print("\n## ① 喂给模型的财务段（`_render_finance` 往 prompt 里加的原文）")
    for ln in CAP["fin_lines"]:
        print("   " + ln)

    att = CAP["attr"] or {}
    print("\n## ② `compute_store_attribution` 算出来的（确定性，表要用的就是它）")
    print("   bench_revenue=%s  bench_bills=%s  chain_avg_ticket=%s  primary=%s"
          % (att.get("bench_revenue"), att.get("bench_bills"),
             att.get("chain_avg_ticket"), att.get("primary_cause")))
    print("   %-26s %12s %8s %10s %12s" % ("门店", "营业额", "单量", "客单价", "Δ vs 平均"))
    for s in sorted(att.get("stores") or [], key=lambda x: x.get("delta_revenue") or 0):
        print("   %-26s %12s %8s %10s %12s"
              % (s.get("store_name"), s.get("revenue"), s.get("bills"),
                 s.get("avg_ticket"), s.get("delta_revenue")))

    print("\n## ③ 正文（判「208.7万」这类数从哪来）")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
