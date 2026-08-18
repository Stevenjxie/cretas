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
#: ⚠️ 上游 LLM 编译 spec 有**非确定性**: 同一棵树同一句, 一次回 answer、
#:    一次回 clarification(「你想基于哪个时间范围」)。⇒ 单次 A/B 不作数,
#:    跑 ROUNDS 轮并把每轮的 kind 逐条贴出来, ⛔ 不只贴汇总。
ROUNDS = int(os.environ.get("ATTRIB_ROUNDS", "3"))
QUESTIONS = [
    "我要不要关掉最差的那家店",
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
               "source": None, "answer": None, "elapsed_ms": None, "tokens": None}
        SEEN.append(rec)
        rec["plan"] = self.plan_dimensions(
            question, has_history=bool(kw.get("conversation_history")),
            dimension_hints=kw.get("dimension_hints"))
        res = await orig_syn(self, factory_id, question, date_range, **kw)
        rec["source"] = getattr(res, "source", None)
        rec["answer"] = getattr(res, "answer", None)
        # 代价那一半 —— auto_expand 拉满维度要付的钱和时间, ⛔ 不能只报收益
        rec["elapsed_ms"] = getattr(res, "elapsed_ms", None)
        rec["tokens"] = getattr(res, "tokens", None)
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

    label = os.environ.get("ATTRIB_LABEL", "<未标注>")
    tally = []
    for i, q in enumerate(QUESTIONS * ROUNDS):
        SEEN.clear()
        res = await _ask(pool, q, f"ab-{label}-{i}")
        text = res.get("answer_text") or ""
        print("=" * 78)
        print(f"[{label}] 第 {i + 1}/{len(QUESTIONS) * ROUNDS} 轮   问: {q}")
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
            print(f"    source={rec['source']!r} 答案长度={len(rec['answer'] or '')}"
                  f" elapsed_ms={rec.get('elapsed_ms')} tokens={rec.get('tokens')}")
        # 老板真正看到的原文里有没有第②步的东西。
        # ⚠️ 判据数的是**数字**, ⛔ 不是「客单价」这三个字 ——
        #    `sanitize_customer_ai_text` 会把它改写成「每单平均消费」,
        #    按词计数会读出 0 次并得出「量价分解没进正文」的**反向**结论。
        att = next((r["attribution"] for r in SEEN if r["attribution"]), None)
        if att and not att.get("no_data"):
            lg = att.get("laggard") or {}
            probes = {
                "落后店客单价": lg.get("avg_ticket"),
                "全链客单价": att.get("chain_avg_ticket"),
                "落后店订单数": lg.get("bills"),
                "全链平均订单数": att.get("bench_bills"),
            }
            n_hit = 0
            for pl, val in probes.items():
                if val is None:
                    print(f"  {pl}: <无>")
                    continue
                s = f"{val:,.1f}".rstrip("0").rstrip(".")
                plain = f"{val:.1f}".rstrip("0").rstrip(".")
                hit = (s in text) or (plain in text) or (f"{int(val):,}" in text)
                n_hit += bool(hit)
                print(f"  正文含 {pl}={plain}: {'是' if hit else '否'}")
            tally.append((res.get("kind"), len(SEEN), True, n_hit, len(text),
                          SEEN[0].get("elapsed_ms"), SEEN[0].get("tokens")))
        else:
            print("  factbook.attribution 为空 ⇒ 量价分解的数字不可能出现在正文里")
            tally.append((res.get("kind"), len(SEEN), False, 0, len(text),
                          SEEN[0].get("elapsed_ms") if SEEN else None,
                          SEEN[0].get("tokens") if SEEN else None))
        print(f"  正文长度={len(text)}")
        print("---- 老板看到的原文 ----")
        print(text)
        print()

    print("=" * 78)
    print(f"[{label}] 汇总 —— ⛔ 逐轮也在上面, 这里只是索引")
    print("  轮 | kind        | synth | 量价分解 | 数字/4 | 正文长 | elapsed_ms | tokens")
    for n, (kind, calls, has_att, n_hit, ln, ms, tk) in enumerate(tally, 1):
        print(f"   {n} | {str(kind):<11} | {calls:<5} | "
              f"{'是' if has_att else '否':<7} | {n_hit}/4    | {ln:<6} | "
              f"{str(ms):<10} | {tk}")
    ok = sum(1 for t in tally if t[2])
    print(f"  {ROUNDS} 轮里有量价分解的: {ok}/{len(tally)}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
