# -*- coding: utf-8 -*-
"""两个口径差的那一条：「我要不要关掉最差的那家店」为什么被判 B。

`verdict()` 第一条是 `"还没有数据" in text ⇒ B-诚实缺数据`。
⛔ 不从分类器代码推断产品行为 —— 把**老板会看到的原文**整段打出来，
   逐条读：那句「还没有数据」到底是「这个问题答不了」，
   还是「答了，只是顺带说某一小块没有」。

⚠️ 绕过叙述缓存 —— 8-17 那条 948 字条目曾经让我差点为一条历史答案改代码。
   这里把命中的 `窗口 / 长度 / 来源` 一起贴出来。
"""
from __future__ import annotations

import asyncio
import os
import re
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
Q = "我要不要关掉最差的那家店"
#: 阳性对照 —— 一条**确定答得上**的问句，它的正文里不该有「还没有数据」
CONTROL = "哪家店卖得最好"

_CLEARED = ("clear_semantic_plan_cache", "clear_route_cache",
            "clear_tenant_gate_cache", "clear_promoted_routes_cache")


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
        return None, {}
    res = await svc.tiered_answer(q, pool, FID, ctx.role,
                                  precomputed_spec=spec, session_key=key)
    return spec, (res or {})


def _hits(text: str):
    """「还没有数据」每一处出现的上下文 —— ⛔ 不只报次数，报**它在说什么**。"""
    out = []
    for m in re.finditer("还没有数据", text or ""):
        s = max(0, m.start() - 60)
        e = min(len(text), m.end() + 60)
        out.append(text[s:e].replace("\n", " ⏎ "))
    return out


async def main() -> int:
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    print("清了: %s\n" % "、".join(_CLEARED))

    # 🔴 阳性对照：一条确定答得上的问句
    _cs, cres = await _ask(pool, CONTROL, "vb-ctrl")
    ctext = cres.get("answer_text") or ""
    if not ctext:
        print("rc=2 阳性对照拿不到答案 —— 仪器坏了")
        return 2
    ctrl_hits = _hits(ctext)
    print("阳性对照「%s」 kind=%s n=%d  含「还没有数据」%d 处"
          % (CONTROL, cres.get("kind"), len(ctext), len(ctrl_hits)))
    for h in ctrl_hits:
        print("    …%s…" % h)
    print()

    spec, res = await _ask(pool, Q, "vb-main")
    text = res.get("answer_text") or ""
    print("=" * 92)
    print("Q: %s" % Q)
    print("  kind=%s  code=%s  n=%d  intent=%s  dims=%s"
          % (res.get("kind"), res.get("code"), len(text),
             getattr(spec, "intent", None), tuple(getattr(spec, "dimensions", ()) or ())))
    hs = _hits(text)
    print("  「还没有数据」出现 %d 处:" % len(hs))
    for h in hs:
        print("    …%s…" % h)
    print("=" * 92)
    print(text)
    print("=" * 92)
    print("\n判据（逐条读，⛔ 不打分）:")
    print("  · kind == answer            : %s" % (res.get("kind") == "answer"))
    print("  · 正文里有没有给出一个**决定依据**（排行/对比/门槛）: 看上面原文")
    print("  · 那句「还没有数据」修饰的是**整个问题**还是**某一小块**: 看上面上下文")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
