# -*- coding: utf-8 -*-
"""查「这周比上周怎么样」为什么变成 9 个字的拒答。

📏 20 分钟前它还是 `answer n=1256`（完整答案 + 表格 + 米饭成本卡异常）。
⛔ 不推断是抖动还是退步 —— 连问三次，每次带**来源标记**（tier）和
   LLM 故障计数，逐条贴出来。

🔑 阳性对照：一条**确定稳定**的问句同跑三次，它必须每次都答上 ——
   否则说明是整体环境问题，不是这一条的问题。
"""
from __future__ import annotations

import asyncio
import logging
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
Q = "这周比上周怎么样"
CONTROL = "哪家店卖得最好"          # 🔑 阳性对照：确定稳定的那一条
ROUNDS = 3
_CLEARED = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")
_FLAP = ("timeout", "exception", "output invalid", "exhausted",
         "empty_api_key", "circuit")


class _Flaps(logging.Handler):
    def __init__(self):
        super().__init__(level=logging.WARNING)
        self.n = 0

    def emit(self, record):
        try:
            msg = record.getMessage()
        except Exception:  # noqa: BLE001
            return
        if "llm_router" in (record.name or "") or "[llm_router]" in msg:
            if any(m in msg.lower() for m in _FLAP):
                self.n += 1


def _clear():
    for n in _CLEARED:
        getattr(ri, n)()


async def _ask(pool, q, key):
    _clear()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=FID, session_key=key, semantic_first=True)
    if spec is None:
        return {"kind": "spec-None", "n": 0, "tier": "-", "text": "",
                "intent": None, "clar": None}
    res = await svc.tiered_answer(q, pool, FID, ctx.role,
                                  precomputed_spec=spec, session_key=key)
    text = (res or {}).get("answer_text") or ""
    return {"kind": (res or {}).get("kind") or "-", "n": len(text),
            "tier": getattr(spec, "planner_authority", "") or "-",
            "text": text, "intent": spec.intent,
            "clar": spec.clarification_needed}


async def main() -> int:
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    print("清了: %s\n" % "、".join(_CLEARED))

    handler = _Flaps()
    logging.getLogger().addHandler(handler)
    rows = []
    ctrl_rows = []
    try:
        for i in range(ROUNDS):
            handler.n = 0
            r = await _ask(pool, Q, "sr-%d" % i)
            r["flap"] = handler.n
            rows.append(r)

            handler.n = 0
            c = await _ask(pool, CONTROL, "sr-c-%d" % i)
            c["flap"] = handler.n
            ctrl_rows.append(c)
    finally:
        logging.getLogger().removeHandler(handler)

    print("=" * 92)
    print("被测「%s」" % Q)
    for i, r in enumerate(rows):
        print("  第%d次 kind=%-14s n=%-5d intent=%-34s clar=%-5s tier=%-34s flap=%d"
              % (i + 1, r["kind"], r["n"],
                 (r["intent"] or "∅").replace("RESTAURANT_OPS_", ""),
                 r["clar"], r["tier"], r["flap"]))
    print("\n🔑 阳性对照「%s」" % CONTROL)
    for i, c in enumerate(ctrl_rows):
        print("  第%d次 kind=%-14s n=%-5d flap=%d" % (i + 1, c["kind"], c["n"], c["flap"]))

    ok_ctrl = all(c["kind"] == "answer" and c["n"] > 200 for c in ctrl_rows)
    print("\n阳性对照三次都答上 = %s" % ok_ctrl)
    if not ok_ctrl:
        print("rc=2 对照本身就不稳 ⇒ 这是整体环境问题，⛔ 不是这一条的问题")
        return 2

    kinds = {r["kind"] for r in rows}
    ns = [r["n"] for r in rows]
    print("\n判据:")
    print("  三次 kind = %s" % sorted(kinds))
    print("  三次 n    = %s" % ns)
    if len(kinds) > 1 or (max(ns) - min(ns)) > 200:
        print("  ⇒ ⚠️ **不稳定** —— 是抖动，⛔ 不是确定性退步")
    elif kinds == {"answer"}:
        print("  ⇒ ✅ 三次都答上 —— 那次 n=9 是一次性抖动")
    else:
        print("  ⇒ 🔴 三次都拒答且长度一致 —— **确定性退步**，要查")
    print("\n" + "=" * 92)
    for i, r in enumerate(rows):
        print("\n--- 第%d次正文 ---\n%s" % (i + 1, r["text"][:1200]))
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
