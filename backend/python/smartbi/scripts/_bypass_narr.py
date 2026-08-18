# -*- coding: utf-8 -*-
"""绕过叙述缓存重跑「我要不要关掉最差的那家店」—— 判「这是不是当前代码的缺陷」。

🔴 为什么必须绕过（memory 记过一次，这是第二次撞上同一形态）:
   prod 答案说「你给的摘要里只有营业额排名前 5 的门店」，
   而盘上 `LLM_STORE_ROSTER_CAP = 20` 且两处都读它。
   `narrative_cache` 里有一条 n=846、**今天 00:04 写入**、window_key 是今天的条目，
   正文含「前5」——846 + 两段尾注 ≈ 948，与实测长度吻合。

▎判「这是不是当前代码的缺陷」，先绕过缓存重跑一次。

⛔ 只读，不删 prod 数据 —— 用 monkeypatch 让缓存**读**失效，不动表。
🔑 关键读数是「喂给模型的门店行有几条」：
   0 不是 5 也不是 20 ⇒ 那说明答案根本没重新生成（memory 里救过我一次的那格）。
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

#: 🔑 喂给模型的门店行数 —— 由这里记录，⛔ 不靠正文推断
FED = {"rows": None, "calls": 0, "disclaimer": None}


def _instrument():
    """记一笔「渲染给 LLM 的门店名单有几行」。⛔ 不改行为。

    ⚠️ `_render_finance` 是 `FactBook` 的**方法**，不是模块级函数 ——
       第一版 patch 成 `fb._render_finance` 当场 AttributeError。
    🔑 数的是**它往 lines 里加了几条门店行**，⛔ 不是 `top_stores` 的长度：
       前者是真正喂进 prompt 的，后者是截断之前的。
    """
    orig = fb.FactBook._render_finance

    def wrapped(self, lines):
        before = len(lines)
        out = orig(self, lines)
        added = lines[before:]
        FED["calls"] += 1
        FED["rows"] = sum(1 for ln in added if re.match(r"^\s+\d+\. ", ln))
        FED["disclaimer"] = any("在系统里" in ln for ln in added)
        return out

    fb.FactBook._render_finance = wrapped
    return orig


def _clear():
    for n in _CLEARED:
        getattr(ri, n)()


async def _ask(pool, key):
    _clear()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            Q, pool, factory_id=FID, session_key=key, semantic_first=True)
    res = await svc.tiered_answer(Q, pool, FID, ctx.role,
                                  precomputed_spec=spec, session_key=key)
    return (res or {})


def _top5_phrases(text: str):
    return [m.group(0) for m in re.finditer(r"[^。\n]{0,40}前\s*5[^。\n]{0,40}", text or "")]


async def main() -> int:
    pool = await ctx.pool()
    if getattr(ctx, "llm_dead_slots", None):
        print("rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    print("清了: %s" % "、".join(_CLEARED))
    print("常量 LLM_STORE_ROSTER_CAP = %s\n" % fb.LLM_STORE_ROSTER_CAP)

    orig_render = _instrument()

    # ── A 组：现状（叙述缓存照常） ────────────────────────────────────────
    FED.update(rows=None, calls=0)
    a = await _ask(pool, "bn-a")
    a_text = a.get("answer_text") or ""
    a_rows, a_calls = FED["rows"], FED["calls"]

    # ── B 组：绕过叙述缓存 ────────────────────────────────────────────────
    orig_get = NarrativeCacheService.get
    orig_sem = getattr(NarrativeCacheService, "get_semantic", None)

    async def _miss(self, *a, **kw):
        return None

    NarrativeCacheService.get = _miss
    if orig_sem is not None:
        NarrativeCacheService.get_semantic = _miss
    try:
        FED.update(rows=None, calls=0)
        b = await _ask(pool, "bn-b")
        b_text = b.get("answer_text") or ""
        b_rows, b_calls = FED["rows"], FED["calls"]
    finally:
        NarrativeCacheService.get = orig_get
        if orig_sem is not None:
            NarrativeCacheService.get_semantic = orig_sem
        fb.FactBook._render_finance = orig_render

    print("=" * 92)
    for tag, text, rows, calls in (("A 现状", a_text, a_rows, a_calls),
                                   ("B 绕过叙述缓存", b_text, b_rows, b_calls)):
        ph = _top5_phrases(text)
        print("\n【%s】 n=%d   🔑 喂给模型的门店行=%s（_render_finance 调用 %d 次）"
              % (tag, len(text), rows, calls))
        print("   摘要里有没有那句「其余…在系统里」= %s" % FED.get("disclaimer"))
        print("   含「前5」的句子 %d 处:" % len(ph))
        for p in ph:
            print("     …%s…" % p.strip())

    print("\n" + "=" * 92)
    # 🔴 阳性对照: B 组必须真的重新生成 —— 否则两侧读数没有可比性
    if b_calls == 0:
        print("rc=2 B 组 _render_finance 一次都没被调用 ⇒ **答案没有重新生成**，")
        print("     这一轮的「有没有前5」读数无意义（memory 里救过我一次的那格）")
        return 2
    if not b_rows:
        print("rc=2 B 组喂给模型的门店行 = %r ⇒ 0 不是 5 也不是 20，" % b_rows)
        print("     说明取数那一步就没跑起来，读数作废")
        return 2
    print("阳性对照 ✅ B 组真的重新生成了（门店行 %d，_render_finance 调用 %d 次）"
          % (b_rows, b_calls))
    print("\n判据: B 组正文里还说不说「前 5」——")
    print("   说  ⇒ 🔴 当前代码的缺陷，要改代码")
    print("   不说 ⇒ 那 948 字是**缓存里的历史答案**，⛔ 不改代码")
    print("\nB 组含「前5」的句子数 = %d" % len(_top5_phrases(b_text)))
    print("\n" + "=" * 92)
    print(b_text)
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
