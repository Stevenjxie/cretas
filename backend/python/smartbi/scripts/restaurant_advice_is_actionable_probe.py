# -*- coding: utf-8 -*-
"""拒答给老板的那句「换个问法」——**照做一遍，真的能答上吗**。

## 为什么要有这个探针（2026-08-18）

那一轮 9 个 PR 里有 5 个改的是**答得可不可信**（不编机制、不误报、
能算的别说算不出、口径别漂）。而当时唯一的全局仪器
`restaurant_panorama_probe` 的判据是「答上 / 没答上」——
▎**那 5 个改动一条都不会让 40/48 变成 41/48。**

实测正是如此：本轮结束仍是 40/48、缺口 0，与上一轮持平。
⛔ 不造仪器，下一轮同样会「做了很多、数字不动」。

## 本探针量的那一件事

交付定义⑤：**答不了时说清缺什么 · 怎么拿到 · 他自己要干什么。**

拒答里那句「想看的话分开问，例如先问「按门店怎么样」」是一句**承诺**。
▎一句兑现不了的承诺，比不给建议更糟 —— 老板照做、失败一次，
▎之后就不再照做了，而那时我们真正给对的建议也一起失效。

⇒ 判据：**把那句建议原样问一遍，看它是不是真的答得上。**

## ⚠️ 解析建议用的是正则 —— 这是**代理判据**，标出来

建议的格式是我们自己产的（`_dimension_gap_advice` 里的
`「按{X}怎么样」`）。格式一改，正则就**静默失效**，而症状是
「一条建议都没有」——和「产品不再给建议」长得一模一样。

⇒ 阳性对照（⛔ 不许省）：
  · 至少要有一条拒答**解析出**建议；解析率近 0% 或近 100% 都**先查仪器**
    （本仓的数字出处闸就是这么抓出自己两个缺陷的）
  · 至少要有一条问句 `kind == "answer"`，否则整条链没到执行

## 三态退出码（硬约束 4）

    rc=0  所有建议都兑现了
    rc=1  有建议兑现不了（读数有效，且指向缺陷）
    rc=2  **这次没量到** —— 阳性对照没过 / 一条建议都没解析出来 / LLM 槽是死的

⛔ `rc=2` 必须与 `rc=1` 用不同措辞告警：两态跑批会把「没量到」折叠进「没问题」。
"""
from __future__ import annotations

import os
import re
import sys
import time
import asyncio

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FID = os.environ.get("PROBE_FID", "MOCK_REST")
ROUNDS = int(os.environ.get("PROBE_ROUNDS", "1"))
ctx = bootstrap_probe(FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 🔴 **阳性对照问句，不参与统计。**
#:
#: 第一版没有它，阳性对照写的是「被测问句里至少一条答上」——
#: 而被测问句是**特意挑的会撞维度闸的**，于是必然 0 条答上、
#: `rc=2` **恒成立**，这个探针永远给不出有效读数。
#: ▎一道恒红的闸和一条恒真的断言一样没用（形态 B′ 的镜像）。
#: ⇒ 阳性对照必须是**独立**的一条已知能答上的问句。
POSITIVE_CONTROL = "最近生意怎么样"

#: 会撞上维度闸的问句。⛔ 不放「一定答得上」的 —— 那样一条建议都收不到。
#: ⚠️ 每条都**明确带两层**或带一个已知不被服务的维度。
#:
#: ⛔ 名字**不叫 `QUESTIONS`**：`restaurant_panorama_probe` 里已经有一个同名常量,
#:    值完全不同。`test_no_same_named_constant_drifts` 当场把我拦下来了 ——
#:    同名不同值正是形态 D 的长相, 而读代码的人不会去比两个文件。
REFUSAL_QUESTIONS = [
    "哪家店折扣最多",
    "哪家店最缺货",
    "哪个食材涨得最多",
    "按门店看趋势",
    "米饭卖得怎么样",
    "哪家店缺货最严重",
    "哪家店的哪道菜毛利最高",
    "各门店的翻台率怎么样",
]

#: 建议问句的两种长相，都出自 `_dimension_gap_advice`：
#:   「想看的话分开问，例如先问「按门店怎么样」。」
#:   「换成问「按食材怎么样」我就能答。」
#: ⚠️ 代理判据：正则跟着那两句文案走，文案一改这里**静默失效**。
_ADVICE_RE = re.compile(r"[「『]([^」』]{2,20})[」』]")
#: 只认真正像「换个问法」的那种 —— ⛔ 否则会把答案里任意引号内容当成建议。
_ADVICE_HINT = ("怎么样", "看看", "问")

_CLEARERS = ("clear_semantic_plan_cache", "clear_route_cache",
             "clear_tenant_gate_cache", "clear_promoted_routes_cache")


def _clear() -> None:
    for name in _CLEARERS:
        # ⛔ 不用 getattr(..., None)：拼错的属性名不会报错，只会静默 no-op
        getattr(ri, name)()


def extract_advice(text: str):
    """从拒答正文里抽出「他被建议去问的那句话」。

    返回候选列表（可能为空）。⛔ 只收像问句的，⛔ 不收任意引号内容。
    """
    out = []
    for cand in _ADVICE_RE.findall(text or ""):
        cand = cand.strip()
        if not cand or cand in out:
            continue
        if any(h in cand for h in _ADVICE_HINT):
            out.append(cand)
    return out


async def ask(pool, query: str, session_key: str):
    """走生产那条序列问一次，返回 (kind, text, tier)。"""
    _clear()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        catalogue = rr.current_dish_catalogue()
        spec = await ri.parse_restaurant_query(
            query, pool, factory_id=FID, session_key=session_key,
            semantic_first=True)
    if spec is None:
        return "spec-None", "", "?", len(catalogue or ())
    tier = getattr(spec, "source_tier", "?")
    if not svc.should_delegate(spec, None, query=query):
        return "no-delegate", "", tier, len(catalogue or ())
    res = await svc.tiered_answer(query, pool, FID, ctx.role,
                                  precomputed_spec=spec, session_key=session_key)
    return ((res or {}).get("kind") or "-",
            (res or {}).get("answer_text") or "", tier, len(catalogue or ()))


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 轮数=%d" % (ctx.db_name, FID, ROUNDS))
    dead = getattr(ctx, "llm_dead_slots", None)
    if dead:
        print("\n⛔ rc=2 LLM 槽没有活账号: %s —— 每条答案都会是 fail-closed 拒答,"
              " 读数作废" % "、".join(dead))
        return 2
    print("每次调用前清: %s\n" % ", ".join(_CLEARERS))

    refusals = 0
    with_advice = 0
    honoured = 0
    broken = []
    answered_any = 0
    catalogue_ok = True

    for rnd in range(1, ROUNDS + 1):
        print("---------- 第 %d 轮 ----------" % rnd)
        # 🔴 阳性对照先跑, 不计入统计 —— 它证明「整条链到得了执行」。
        #    ⛔ 不能拿被测问句里「有没有答上的」当对照: 它们是**特意挑的拒答**。
        pc_kind, pc_text, pc_tier, pc_cat = await ask(
            pool, POSITIVE_CONTROL, "advice-r%d-pc" % rnd)
        catalogue_ok = catalogue_ok and pc_cat > 0
        if pc_kind == "answer":
            answered_any += 1
        print("  [阳性对照] %-14s kind=%-14s tier=%-11s n=%d"
              % (POSITIVE_CONTROL, pc_kind, pc_tier, len(pc_text)))

        for qi, q in enumerate(REFUSAL_QUESTIONS):
            key = "advice-r%d-q%d" % (rnd, qi)
            kind, text, tier, cat_n = await ask(pool, q, key)
            catalogue_ok = catalogue_ok and cat_n > 0
            if kind == "answer":
                print("  [答上]   %-14s tier=%-11s n=%d" % (q, tier, len(text)))
                continue
            refusals += 1
            advice = extract_advice(text)
            if not advice:
                print("  [无建议] %-14s tier=%-11s kind=%s | %s"
                      % (q, tier, kind, text[:60].replace("\n", " ")))
                continue
            with_advice += 1
            for cand in advice:
                a_kind, a_text, a_tier, _ = await ask(
                    pool, cand, key + "-adv")
                ok = a_kind == "answer"
                honoured += int(ok)
                mark = "✅ 兑现" if ok else "🔴 兑现不了"
                print("  [建议]   %-14s → 「%s」 %s (kind=%s tier=%s n=%d)"
                      % (q, cand, mark, a_kind, a_tier, len(a_text)))
                if not ok:
                    broken.append((q, cand, a_kind,
                                   a_text[:120].replace("\n", " ")))

    print("\n" + "=" * 72)
    print("拒答 %d 条; 其中给出建议 %d 条; 建议兑现 %d 条"
          % (refusals, with_advice, honoured))
    if broken:
        print("\n🔴 兑现不了的建议（老板照做会扑空）:")
        for q, cand, k, snippet in broken:
            print("   %-14s → 「%s」 kind=%s\n      %s" % (q, cand, k, snippet))

    # ── 阳性对照（⛔ 不许省）────────────────────────────────────────────
    print("\n=== 阳性对照 ===")
    print("  目录非空 = %s" % catalogue_ok)
    print("  对照问句「%s」答上 = %s (%d/%d 轮)"
          % (POSITIVE_CONTROL, answered_any > 0, answered_any, ROUNDS))
    print("  至少一条拒答解析出建议 = %s (%d/%d)"
          % (with_advice > 0, with_advice, refusals))
    if refusals:
        rate = with_advice / refusals * 100
        print("  建议解析率 = %.1f%%  ⚠️ 近 0%% 或近 100%% 都**先查仪器**" % rate)

    if not (catalogue_ok and answered_any and with_advice):
        print("\n⛔ rc=2 仪器没活着 —— 本次读数作废, ⛔ 不要拿它做前后对比")
        return 2
    if broken:
        print("\n⚠️ rc=1 有建议兑现不了 —— 读数有效, 且指向缺陷")
        return 1
    print("\n✅ rc=0 所有给出的建议都兑现了")
    return 0


if __name__ == "__main__":
    t0 = time.time()
    rc = asyncio.run(main())
    print("\nELAPSED=%.1fs  rc=%d" % (time.time() - t0, rc))
    sys.exit(rc)
