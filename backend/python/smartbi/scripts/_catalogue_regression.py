# -*- coding: utf-8 -*-
"""#2872 的回归：门店核对**丢掉了本该保留的东西**没有？

## 我引入的风险

`_mentions_backed_by_catalogue` 把「目录里查不到的提及」丢掉。
那么这两类会怎样 ——

    (a) 老板打错字 / 问一家**真实存在但还没进 dim_store 的新店**
        → 原来会说「没有找到名为 X 的门店」（诚实）
        → 现在被丢掉 ⇒ 会不会**静默按全店回答**？（那是编答案）

    (b) 「量子纠缠火箭发射器最近30天营收多少」
        → 📏 之前实测: store=None + 「我不会编造答案」
        → 现在还在吗？

▎**修一个「不该拦的被拦了」，最容易顺手造出「该说的不说了」。**
▎这正是 C⁶：修复的进度会掩盖修复的缺口。

## 阳性对照（⛔ 不许省）

- 菜单目录 10 条 ⇒ 库连上了
- 真实门店全名那条必须 `kind == "answer"` ⇒ 执行链活着
  （否则「全都答不上」和「我没跑起来」是同一个读数）
"""
from __future__ import annotations

import asyncio

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FID = "MOCK_REST"
ROUNDS = 2
ctx = bootstrap_probe(FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: ⚠️ 带前缀命名 —— 同名常量漂移闸会拦 `QS` / `CASES` 这种通名。
CATALOGUE_REGRESSION_CASES = [
    # (标签, 问句, 我期望看到什么)
    ("阳性·真店全名", "模拟·徐汇美罗城店最近30天营收多少", "该答上，且点名这家店"),
    ("阳性·真店简称", "宝山店最近30天营收多少", "该答上（简称归一）"),
    ("🔴风险a·没进目录的店", "模拟·青浦新开店最近30天营收多少",
     "该说查不到这家店，⛔ 不该静默按全店回答"),
    ("🔴风险b·完全不存在", "量子纠缠火箭发射器最近30天营收多少",
     "该说我不会编造答案"),
    ("对照·本轮修的那句", "有几家店是亏钱的", "不该再出现假拒答"),
]


def _clear():
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
        done.append(n)
    return done


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d" % (ctx.db_name, FID, ROUNDS))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        cat = rr.current_dish_catalogue()
    print("阳性对照·菜单目录 = %d 条" % len(cat or ()))
    if not cat:
        print("⛔ rc=2 库没连上，读数作废")
        return 2

    n_positive = 0
    for rnd in range(1, ROUNDS + 1):
        for tag, q, expect in CATALOGUE_REGRESSION_CASES:
            cleared = _clear()
            set_factory_id(FID)
            async with rr.dish_catalogue_scope(pool, FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=FID, session_key="cr-r%d-%s" % (rnd, tag),
                    semantic_first=True)
            res = None
            if spec is not None and svc.should_delegate(spec, None, query=q):
                res = await svc.tiered_answer(
                    q, pool, FID, ctx.role, precomputed_spec=spec,
                    session_key="cr-r%d-%s" % (rnd, tag))
            kind = (res or {}).get("kind") or "-"
            text = (res or {}).get("answer_text") or ""
            if tag.startswith("阳性") and kind == "answer":
                n_positive += 1
            print("\n" + "=" * 76)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("  期望: %s" % expect)
            print("  清了 %d 个缓存" % len(cleared))
            print("  store_slot=%r  store_scope=%r  dims=%s  kind=%s  n=%d" % (
                getattr(spec, "store_slot", None) if spec else None,
                getattr(spec, "store_scope", None) if spec else None,
                tuple(getattr(spec, "dimensions", ()) or ()) if spec else (),
                kind, len(text)))
            print("  ── 老板看到的原文（前 300 字）──")
            print("  " + (text[:300].replace("\n", "\n  ") or "(空)"))

    print("\n阳性对照·真店问句 kind==answer = %d/%d"
          % (n_positive, 2 * ROUNDS))
    if n_positive == 0:
        print("⛔ rc=2 真店问句一条都没答上 —— 分不清「产品坏了」和「我没跑起来」")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
