# -*- coding: utf-8 -*-
"""缺口 #9 的**后果**：维度传不进执行器，老板看到的是不是同一份答案。

⛔ 不照架构文档的旧读数动手 —— 那句「返回逐字相同的答案」说的是
   WASTAGE_TOP 当时的形态，而它**已经修了**（现在收 dimensions）。

判据：同一个 intent、两个**维度不同**的问句 → 答案逐字相同 ⇒ 🔴 维度没起作用。

对照（缺一条读数作废）:
  阳性  WASTAGE_TOP（已收 dimensions）两句必须**不同** —— 否则仪器坏了
  阴性  同一句问两遍（每次清缓存）必须**相同** —— 否则有非确定性，比不了
  来源  每条读数带 tier（llm / plan_cache），⛔ 不然分不清「相同」是真相同
        还是两次命中同一个缓存条目
"""
from __future__ import annotations

import asyncio
import hashlib
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

#: (标签, A句, B句) —— A/B 的**维度**不同，其余尽量同义
PAIRS = [
    ("WASTAGE_TOP  [阳性对照]", "最近损耗怎么样", "哪家店损耗最多"),
    ("GROSS_MARGIN", "哪道菜毛利最高", "毛利这几个月的走势怎么样"),
    ("REQUISITION_TREND", "最近领料情况怎么样", "按门店看领料趋势"),
    ("STOCK_SHORTAGE", "哪些食材缺货了", "哪家店缺货最严重"),
    ("STAFFING_ADVICE", "员工排班合理吗", "晚市要排几个人"),
    ("CHANNEL_MIX", "外卖和堂食各占多少", "哪家店外卖占比最高"),
]

_CLEARED = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")


def _clear():
    for n in _CLEARED:
        getattr(ri, n)()          # ⛔ 有 helper 就用 helper，拼错的名字不报错


def _fp(text: str) -> str:
    return hashlib.sha1((text or "").encode("utf-8")).hexdigest()[:8]


async def _ask(pool, q, key):
    _clear()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=FID, session_key=key, semantic_first=True)
    if spec is None:
        return {"kind": "spec-None", "text": "", "intent": None, "dims": (),
                "tier": "-", "n": 0}
    tier = getattr(spec, "planner_authority", "") or "-"
    if not svc.should_delegate(spec, None, query=q):
        return {"kind": "no-delegate", "text": "", "intent": spec.intent,
                "dims": tuple(spec.dimensions or ()), "tier": tier, "n": 0}
    res = await svc.tiered_answer(q, pool, FID, ctx.role,
                                  precomputed_spec=spec, session_key=key)
    text = (res or {}).get("answer_text") or ""
    return {"kind": (res or {}).get("kind") or "-", "text": text,
            "intent": spec.intent, "dims": tuple(spec.dimensions or ()),
            "tier": tier, "n": len(text)}


async def main() -> int:
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    print("清了这几个缓存: %s\n" % "、".join(_CLEARED))

    # ── 阴性对照：同一句问两遍，必须相同 ──────────────────────────────────
    probe = "最近损耗怎么样"
    a = await _ask(pool, probe, "de-neg-1")
    b = await _ask(pool, probe, "de-neg-2")
    same = _fp(a["text"]) == _fp(b["text"])
    print("阴性对照（同一句两遍）: %s  fp=%s vs %s  tier=%s/%s"
          % ("✅ 相同" if same else "🔴 不同", _fp(a["text"]), _fp(b["text"]),
             a["tier"], b["tier"]))
    if not same:
        print("rc=2 同一句两遍就不一样 —— 有非确定性，本轮「相同/不同」读数比不了")
        return 2

    print("\n" + "=" * 100)
    rows = []
    for i, (label, qa, qb) in enumerate(PAIRS):
        ra = await _ask(pool, qa, "de-%d-a" % i)
        rb = await _ask(pool, qb, "de-%d-b" % i)
        identical = _fp(ra["text"]) == _fp(rb["text"]) and ra["n"] > 0
        rows.append((label, qa, qb, ra, rb, identical))
        print("\n%s" % label)
        print("  A %-22s kind=%-14s intent=%-34s dims=%-22s tier=%-38s n=%-5d fp=%s"
              % (qa, ra["kind"], (ra["intent"] or "∅").replace("RESTAURANT_OPS_", ""),
                 "、".join(ra["dims"]) or "∅", ra["tier"], ra["n"], _fp(ra["text"])))
        print("  B %-22s kind=%-14s intent=%-34s dims=%-22s tier=%-38s n=%-5d fp=%s"
              % (qb, rb["kind"], (rb["intent"] or "∅").replace("RESTAURANT_OPS_", ""),
                 "、".join(rb["dims"]) or "∅", rb["tier"], rb["n"], _fp(rb["text"])))
        same_intent = ra["intent"] == rb["intent"]
        print("  ⇒ %s%s"
              % ("🔴 **逐字相同**" if identical else "✅ 不同",
                 "" if same_intent else
                 "   ⚠️ 两句路由到了**不同 intent**，这一对比不了维度效果"))

    print("\n" + "=" * 100)
    ctrl = rows[0]
    if ctrl[5]:
        print("rc=2 阳性对照失败: WASTAGE_TOP 两句也逐字相同 —— 仪器坏了，读数作废")
        return 2
    print("阳性对照 ✅ WASTAGE_TOP（已收 dimensions）两句不同\n")

    bad = [r for r in rows[1:] if r[5]]
    cross = [r for r in rows[1:] if r[3]["intent"] != r[4]["intent"]]
    print("被测 %d 对中：**逐字相同**的 %d 对" % (len(rows) - 1, len(bad)))
    for label, qa, qb, _ra, _rb, _i in bad:
        print("   🔴 %-20s「%s」和「%s」给了同一份答案" % (label, qa, qb))
    print("\n⚠️ 路由到不同 intent、这一对比不了的 %d 对:" % len(cross))
    for label, qa, qb, ra, rb, _i in cross:
        print("   %-20s A→%s  B→%s"
              % (label, (ra["intent"] or "∅").replace("RESTAURANT_OPS_", ""),
                 (rb["intent"] or "∅").replace("RESTAURANT_OPS_", "")))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
