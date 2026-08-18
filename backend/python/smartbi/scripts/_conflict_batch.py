# -*- coding: utf-8 -*-
"""缺口 #6 第 1 步：量「今天因为**撞到第一个冲突就放弃**而拒答的有几条」。

⛔ 这一步不许跳 —— 本轮已有多条缺口的依据读数被证明过期
   （`docs/decisions/2026-08-18-22项缺口清单-逐条重测.md`：#1 影响面 0、#9 后果 0）。

## 它量什么

owner 定稿 ④之二 说的流程是「把**全部**维度校对完 → 收集**所有**冲突 →
一次性丢回 ③ → 重试**只有一次**」。今天的实现是 `_execution_mismatch`：
一串 `if ... return`，**撞到第一个就 return**，而且**一次都不重试**。

所以要分两问，⛔ 不能合成一问：

    Q1  有几条拒答是撞在这道闸上的？（分母）
    Q2  这些条里，冲突是**叠着的**（≥2，收集齐了才救得回）
        还是**单个**（收集不收集都一样，只有重试才可能救）？

## 冲突层数怎么数（⛔ 不复制那串 if）

不照抄条件、也不硬编码任何一句文案 —— **把真函数当黑盒逐槽消解**：

    对每个入参槽（store_dish / dish_mention / store_mention）单独置空一次，
    看返回值变不变。变了 ⇒ 这一层由那个槽驱动，把它置空后再问一次，
    看**后面还有没有第二层**。都不变 ⇒ 这一层由 spec/plan 驱动，是终点。

⚠️ 已知的读数陷阱，逐条标出来，⛔ 不藏：
   `store_mention` 置空**可能凭空造出**一条新冲突（有一条判据要求它为空）。
   所以带 `store_mention` 消解的那些层一律打 `⚠️artifact?` 标记，人读。

## 对照（缺一条读数作废）

    仪器活着   `_execution_mismatch` 的录音器被调用 ≥1 次
               —— ⛔ 否则「0 条撞闸」和「我没跑到那道闸」是同一个读数
    阳性       整批至少 1 条 `kind == answer`
    阴性       同一句问两遍（每次清 4 个缓存）必须**逐字相同**
               —— 它同时是「裸重试能不能救回来」那个读数的口径
    来源标记   每条读数带 `planner_authority`

## 三态退出

    rc=0  量到了，且「多冲突」条数 ≥1
    rc=1  量到了，但「多冲突」条数 = 0（⇒ 批量收集这一半不复现）
    rc=2  **这次没量到东西**（LLM 槽死 / 录音器没被调用 / 一条答案都没有）
"""
from __future__ import annotations

import asyncio
import hashlib
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

FID = os.environ.get("PROBE_FID", "MOCK_REST")
RETRIES = int(os.environ.get("PROBE_RETRIES", "2"))

ctx = bootstrap_probe(FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

# ⛔ 问句集**单一来源** —— 从全景探针 import，不在这里抄一份。
#    抄一份的那一刻，两边的「基线」就不可比了（那正是全景探针 docstring 里
#    记的那次代价）。
from smartbi.scripts.restaurant_panorama_probe import QUESTIONS  # noqa: E402

#: 额外的**多冲突候选**：句子里同时限定门店和菜品 / 同时要两层拆分。
#: ⛔ 它们与 `QUESTIONS` 分开统计 —— 混进去就换了仪器。
STRESS = (
    "浦东店的米饭卖得怎么样",
    "社区店本月毛利率是多少",
    "哪家店的鲈鱼损耗最多",
    "各门店按食材看领料花了多少",
    "美罗城店最近30天哪道菜毛利最高",
    "按门店和菜品一起看这个月的销量",
    "浦东店这个月食材成本占营收多少",
    "哪家店晚市的客单价最高",
)

#: 【C 组】**原句取自 prod 日志**里真的撞过这道闸的问句（`执行前拦截` 行）。
#:
#: 🔴 为什么必须有这一组：A/B 两组 4 次跑一条**门店范围**类拦截都没产生，
#:    而真实流量里那一类占 ~40%（08-15~08-18 共 24 次拦截中 9+1 次）。
#:    ▎我自己编的问句集**漏掉了第二常见的那一类冲突** —— 而那一类恰好是
#:    ▎唯一「槽位驱动」、因而唯一可能与维度类**叠起来**的一类。
#: ⛔ 与 A/B 分开统计：加进 A 组就换了仪器，前后基线不可比。
REAL = (
    "本月社区店的营收",              # store='社区店'  门店范围类 ×2 次
    "有几家店是亏钱的",              # store='有几家店' —— 门店名抽错也算这一类
    "外卖和堂食哪个更赚钱",          # dims=('channel','dish')
    "这月比上月好还是差，差在哪",    # intent ∉ plan
    "周末和平时哪个更赚钱，差多少",  # dims=('weekday',)
)

CACHE_CLEARERS = (
    "clear_semantic_plan_cache",
    "clear_route_cache",
    "clear_tenant_gate_cache",
    "clear_promoted_routes_cache",
)

#: `_execution_mismatch` 的三个可消解入参槽。⛔ 顺序无关，逐个试。
#: ⚠️ 名字**不能**叫 `SLOTS` —— `t7_deepseek_acceptance.py` 里已经有一个
#: `SLOTS`(LLM 槽名)，同名不同值会被 `test_no_drifted_duplicate_constants`
#: 判成常量漂移。📏 那道闸是 CI 抓到的，我本地两次 `-k` 过滤(`probe or script`
#: / `restaurant`)**都没选中它** —— 又一次「过滤器让『没有失败』和『我没跑到它』
#: 变成同一个读数」。
MISMATCH_SLOTS = ("store_dish", "dish_mention", "store_mention")

REAL_MISMATCH = svc._execution_mismatch
CALLS = []

#: 🔴「今天一次重试都没有」这句话，⛔ 不能靠读代码下结论（`_t3_llm_parse` 有
#: 4 个调用点，看代码只能说「它们**看起来**是四条不同分支」）。这里把它变成
#: **行为读数**：数**同一次** `parse_restaurant_query` 里 T3 被调了几次。
#:   ≤1  ⇒ 没有重试（本缺口后半确实缺）
#:   ≥2  ⇒ 已经有重试了，那本缺口的前提本身就错了
REAL_T3 = ri._t3_llm_parse
T3_CALLS = []


async def _recording_t3(*args, **kwargs):
    T3_CALLS.append(1)
    return await REAL_T3(*args, **kwargs)


def _recording_mismatch(spec, plan, *, dish_mention, store_mention, store_dish):
    """录音器：只记录，⛔ 不改变行为（返回真函数的返回值）。"""
    result = REAL_MISMATCH(
        spec, plan, dish_mention=dish_mention,
        store_mention=store_mention, store_dish=store_dish,
    )
    CALLS.append({
        "spec": spec, "plan": plan, "dish_mention": dish_mention,
        "store_mention": store_mention, "store_dish": store_dish,
        "result": result,
    })
    return result


def clear_caches():
    for name in CACHE_CLEARERS:
        getattr(ri, name)()          # ⛔ 有 helper 就用 helper
    return list(CACHE_CLEARERS)


def _fp(text):
    return hashlib.md5((text or "").encode("utf-8")).hexdigest()[:8]


def stacked_conflicts(call):
    """这一次拦截背后**叠了几层**冲突。返回 (层列表, 备注列表)。

    ⛔ 不复制 `_execution_mismatch` 的任何一个条件、不硬编码它的任何一句文案 ——
       把它当黑盒，逐槽消解后再问一次。
    """
    args = {
        "dish_mention": call["dish_mention"],
        "store_mention": call["store_mention"],
        "store_dish": call["store_dish"],
    }
    layers, notes = [], []
    for _depth in range(4):
        reason = REAL_MISMATCH(call["spec"], call["plan"], **args)
        if reason is None:
            break
        driver = None
        for slot in MISMATCH_SLOTS:
            if args[slot] is None:
                continue
            trial = dict(args)
            trial[slot] = None
            if REAL_MISMATCH(call["spec"], call["plan"], **trial) != reason:
                driver = slot
                break
        layers.append({"reason": reason, "driver": driver or "spec/plan"})
        if driver is None:
            # spec/plan 驱动 —— 不动 spec 就消不掉，它是这条链的终点。
            break
        args[driver] = None
        if driver == "store_mention":
            notes.append(
                "第 %d 层之后置空了 store_mention —— 后续层可能是 artifact"
                % len(layers))
    return layers, notes


async def run_once(pool, query, session_key):
    """完全复刻 `gold_reads.py` 那条生产序列。"""
    clear_caches()
    before = len(CALLS)
    T3_CALLS.clear()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        spec = await ri.parse_restaurant_query(
            query, pool, factory_id=FID, session_key=session_key,
            semantic_first=True,
        )
    # ⛔ 在 parse 之后立刻取 —— 问的是「**同一次规划**里 T3 被调了几次」。
    row = {"q": query, "t3_n": len(T3_CALLS)}
    if spec is None:
        return row | {"kind": "spec-None", "authority": "-", "calls": []}
    row |= {
        "spec_obj": spec,
        "intent": (spec.intent or "∅").replace("RESTAURANT_OPS_", ""),
        "authority": spec.planner_authority,
        "tier": spec.source_tier,
        "plan": [c.replace("RESTAURANT_OPS_", "") for c in spec.planned_intents],
        "dims": list(spec.dimensions),
        "unsupported": list(spec.unsupported_requirements or ()),
        "clarify_flag": bool(spec.clarification_needed),
    }
    if not svc.should_delegate(spec, None, query=query):
        return row | {"kind": "no-delegate", "text": "", "n": 0, "fp": _fp(""),
                      "calls": CALLS[before:]}
    result = await svc.tiered_answer(
        query, pool, FID, ctx.role, precomputed_spec=spec,
        session_key=session_key,
    )
    text = (result or {}).get("answer_text") or ""
    return row | {
        "kind": (result or {}).get("kind") or "None",
        "code": ((result or {}).get("code") or "").replace("RESTAURANT_OPS_", ""),
        "contract": (result or {}).get("contract_pass"),
        "text": text, "n": len(text), "fp": _fp(text),
        "calls": CALLS[before:],
    }


def emit(row):
    print("  kind=%-14s authority=%-26s intent=%-24s plan=%-24s dims=%-14s "
          "unsupported=%-14s n=%-5s fp=%s"
          % (row.get("kind", "-"), row.get("authority", "-"),
             row.get("intent", "-"), ",".join(row.get("plan", [])) or "∅",
             ",".join(row.get("dims", [])) or "∅",
             ",".join(row.get("unsupported", [])) or "∅",
             row.get("n"), row.get("fp")))


async def main():
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s ROLE=%s TODAY=%s RETRIES=%d"
          % (ctx.db_name, FID, ctx.role, time.strftime("%Y-%m-%d"), RETRIES))
    if ctx.llm_dead_slots:
        print("rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2
    svc._execution_mismatch = _recording_mismatch
    ri._t3_llm_parse = _recording_t3
    print("录音器已挂在 svc._execution_mismatch 与 ri._t3_llm_parse 上"
          "（只记录，不改行为）")

    # ── 阴性对照：同一句两遍必须逐字相同 ──────────────────────────────────
    probe_q = "最近损耗怎么样"
    a = await run_once(pool, probe_q, "cb-neg-1")
    b = await run_once(pool, probe_q, "cb-neg-2")
    deterministic = a["fp"] == b["fp"]
    print("\n阴性对照（同一句两遍，每次清 %s）: %s  fp=%s vs %s  authority=%s / %s"
          % (list(CACHE_CLEARERS), "✅ 逐字相同" if deterministic else "⚠️ 不同",
             a["fp"], b["fp"], a.get("authority"), b.get("authority")))

    print("\n" + "=" * 110)
    print("【A 组】全景基线问句集（%d 条，与 restaurant_panorama_probe 同一份）"
          % len(QUESTIONS))
    print("=" * 110)
    rows = []
    for i, q in enumerate(QUESTIONS):
        row = await run_once(pool, q, "cb-a-%d" % i)
        row["group"] = "A"
        rows.append(row)
        print("\n[A%02d] %s" % (i, q))
        emit(row)

    print("\n" + "=" * 110)
    print("【B 组】多冲突候选问句（%d 条，⛔ 与 A 组分开统计）" % len(STRESS))
    print("=" * 110)
    for i, q in enumerate(STRESS):
        row = await run_once(pool, q, "cb-b-%d" % i)
        row["group"] = "B"
        rows.append(row)
        print("\n[B%02d] %s" % (i, q))
        emit(row)

    print("\n" + "=" * 110)
    print("【C 组】prod 日志里真撞过这道闸的原句（%d 条，⛔ 与 A/B 分开统计）"
          % len(REAL))
    print("=" * 110)
    for i, q in enumerate(REAL):
        row = await run_once(pool, q, "cb-c-%d" % i)
        row["group"] = "C"
        rows.append(row)
        print("\n[C%02d] %s" % (i, q))
        emit(row)

    # ── 仪器活着吗 ────────────────────────────────────────────────────────
    print("\n" + "=" * 110)
    print("仪器自检：`_execution_mismatch` 录音器被调用 %d 次" % len(CALLS))
    answered = [r for r in rows if r.get("kind") == "answer"]
    print("阳性对照：kind==answer 的 %d 条 / %d" % (len(answered), len(rows)))
    if not CALLS:
        print("rc=2 录音器一次都没被调用 —— 「0 条撞闸」和「我没跑到那道闸」"
              "分不开，本轮读数作废")
        return 2
    if not answered:
        print("rc=2 一条答案都没有 —— 仪器坏了（或者链路整体不可用），读数作废")
        return 2

    # ── 逐条数冲突层数 ────────────────────────────────────────────────────
    print("\n" + "=" * 110)
    print("【撞在 `_execution_mismatch` 上的拒答】逐条数冲突层数")
    print("=" * 110)
    blocked = []
    for row in rows:
        hits = [c for c in row.get("calls", ()) if c["result"]]
        if not hits:
            continue
        call = hits[0]
        layers, notes = stacked_conflicts(call)
        row["layers"] = layers
        row["layer_notes"] = notes
        blocked.append(row)
        print("\n[%s] %s" % (row["group"], row["q"]))
        print("   intent=%s plan=%s dims=%s authority=%s"
              % (row.get("intent"), ",".join(row.get("plan", [])) or "∅",
                 ",".join(row.get("dims", [])) or "∅", row.get("authority")))
        print("   冲突层数 = %d" % len(layers))
        for li, layer in enumerate(layers, 1):
            print("     第%d层 [%s驱动] %s" % (li, layer["driver"], layer["reason"]))
        for note in notes:
            print("     ⚠️ %s" % note)
        print("   老板看到的原文: %s" % (row.get("text") or "")[:220])

    multi = [r for r in blocked if len(r.get("layers", ())) >= 2]
    single = [r for r in blocked if len(r.get("layers", ())) == 1]

    # ── 🔴 剥离仪器的阳性对照 ────────────────────────────────────────────
    #
    # ⛔ 没有这一段，「多冲突 0 条」和「我的剥离方法根本数不出第二层」
    #    是**同一个读数**。构造一个**已知叠了两层**的输入喂给同一个函数:
    #    拿一条真实的、spec/plan 驱动的拦截，再给它一个 `store_dish`
    #    （那会先触发另一条判据）⇒ 正确的仪器必须数出 2 层。
    print("\n" + "=" * 110)
    if not blocked:
        print("rc=2 本轮一条都没撞闸 —— 剥离仪器没有可用的阳性对照，"
              "「多冲突 0 条」这个读数作废")
        return 2
    seed = [c for c in blocked[0]["calls"] if c["result"]][0]
    control = dict(seed)
    control["store_dish"] = "__PROBE_STACKED__"
    ctrl_layers, _ctrl_notes = stacked_conflicts(control)
    print("剥离仪器阳性对照（同一条拦截 + 人为叠一个 store_dish 冲突）: %d 层"
          % len(ctrl_layers))
    for li, layer in enumerate(ctrl_layers, 1):
        print("   第%d层 [%s驱动] %s" % (li, layer["driver"], layer["reason"]))
    if len(ctrl_layers) < 2:
        print("rc=2 阳性对照只数出 %d 层 —— 剥离仪器数不出第二层，"
              "「多冲突 0 条」这个读数作废" % len(ctrl_layers))
        return 2
    # 阴性对照：一次没有冲突的调用，必须读出 **0 层** ——
    # ⛔ 否则「层数」这个量恒 ≥1，多/单的区分就没意义了。
    clean = [c for c in CALLS if not c["result"]]
    if clean:
        clean_layers, _ = stacked_conflicts(clean[0])
        print("剥离仪器阴性对照（一次没有冲突的调用）: %d 层 %s"
              % (len(clean_layers), "✅" if not clean_layers else "🔴 恒 ≥1，读数作废"))
        if clean_layers:
            return 2

    # ── 🔴「今天一次重试都没有」—— 这是**行为读数**，⛔ 不是读代码 ─────────
    t3_counts = [r.get("t3_n", 0) for r in rows]
    dist = {}
    for n in t3_counts:
        dist[n] = dist.get(n, 0) + 1
    print("\n同一次规划里 T3(`_t3_llm_parse`) 被调用的次数分布: %s"
          % ", ".join("%d 次→%d 条" % (k, dist[k]) for k in sorted(dist)))
    print("  最大值 = %d  ⇒ %s"
          % (max(t3_counts) if t3_counts else 0,
             "**一次重试都没有**（本缺口后半确实缺）"
             if (max(t3_counts) if t3_counts else 0) <= 1
             else "🔴 已经有重试了 —— 本缺口的前提本身就错了"))
    if sum(t3_counts) == 0:
        print("rc=2 T3 一次都没被调用 —— 录音器没接上，这条读数作废")
        return 2

    # ── 跨闸冲突计数：⛔ 不只看 `_execution_mismatch` 那一道 ─────────────
    #
    # owner 定稿说的是「把**全部**维度校对完，收集**所有**冲突」，而今天的
    # 校对散在三处，谁先短路谁说了算。所以对**每一条**问句都把三处各问一遍，
    # ⛔ 不管产品实际停在哪一处:
    #     ① 指标不支持   `spec.unsupported_requirements`
    #     ② 门店简称对上多家  `_resolve_store_mentions(...).first_ambiguous()`
    #     ③ 能力/维度对不上  `_execution_mismatch`（三个槽全置空 = 只留
    #        spec/plan 驱动的那一部分，⛔ 这是下界，不会高估）
    print("\n" + "=" * 110)
    print("【跨闸】每条问句把三处校对各问一遍，看有没有**同时**成立的")
    print("=" * 110)
    stores = await ri._load_store_options(pool, FID)
    print("门店目录 %d 家（阳性对照：这个数必须 > 0）" % len(stores))
    cross_multi = []
    for row in rows:
        spec = row.get("spec_obj")
        if spec is None:
            continue
        conflicts = []
        if row.get("unsupported"):
            conflicts.append("指标不支持:%s" % ",".join(row["unsupported"]))
        resolution = await ri._resolve_store_mentions(row["q"], stores)
        if resolution is not None and resolution.first_ambiguous() is not None:
            conflicts.append("门店简称对上多家:%s"
                             % resolution.first_ambiguous()[0])
        pure = REAL_MISMATCH(spec, tuple(spec.planned_intents),
                             dish_mention=None, store_mention=None,
                             store_dish=None)
        if pure:
            conflicts.append("能力/维度:%s" % pure)
        row["cross"] = conflicts
        if len(conflicts) >= 2:
            cross_multi.append(row)
            print("  🔴 %-30s 同时 %d 个: %s"
                  % (row["q"][:30], len(conflicts), " ｜ ".join(conflicts)))
    print("  跨闸【同时 ≥2 个冲突】的问句: %d 条 / %d" % (len(cross_multi), len(rows)))

    # ── 裸重试能不能救回来（⛔ 不带冲突原因的下界） ────────────────────────
    print("\n" + "=" * 110)
    print("【裸重试】对每条被拦的问句再编译 %d 次（清缓存 + 新 session_key，"
          "⛔ 不带冲突原因）" % RETRIES)
    print("=" * 110)
    rescued = []
    for row in blocked:
        outcomes = []
        for k in range(RETRIES):
            again = await run_once(pool, row["q"], "cb-retry-%d-%s"
                                   % (k, _fp(row["q"])))
            outcomes.append((again.get("kind"), again.get("authority"),
                             again.get("fp")))
        ok = any(o[0] == "answer" for o in outcomes)
        if ok:
            rescued.append(row["q"])
        print("  %-32s %s   %s"
              % (row["q"][:32], "✅ 裸重试救回" if ok else "❌ 裸重试救不回",
                 "  ".join("%s/%s/%s" % o for o in outcomes)))

    # ── 小结 ──────────────────────────────────────────────────────────────
    print("\n" + "=" * 110)
    print("### 小结（口径：MOCK_REST，%s，A 组 %d + B 组 %d + C 组 %d 条）"
          % (time.strftime("%Y-%m-%d"), len(QUESTIONS), len(STRESS), len(REAL)))
    print("  剥离仪器阳性对照                          %d 层 ✅（≥2 才算活着）"
          % len(ctrl_layers))
    print("  撞在 `_execution_mismatch` 上的拒答      %d 条" % len(blocked))
    print("    其中【多冲突】(层数 ≥2)                %d 条" % len(multi))
    for r in multi:
        print("       · %s  (层数 %d)" % (r["q"], len(r["layers"])))
    print("    其中【单冲突】(层数 =1)                %d 条" % len(single))
    for r in single:
        print("       · %s  ← %s" % (r["q"], r["layers"][0]["reason"]))
    print("  裸重试（不带原因）能救回                  %d 条" % len(rescued))
    for q in rescued:
        print("       · %s" % q)
    print("  阴性对照（同一句两遍）                    %s"
          % ("逐字相同 ⇒ 裸重试的差异是真差异" if deterministic
             else "⚠️ 不同 ⇒ 裸重试读数受非确定性影响，按口径读"))
    blocked_ids = {id(r) for r in blocked}
    other_refusals = [r for r in rows
                      if r.get("kind") != "answer" and id(r) not in blocked_ids]
    print("  其它非 answer（⛔ 不撞这道闸，不在本缺口范围内） %d 条"
          % len(other_refusals))
    for r in other_refusals:
        print("       · %-30s kind=%-14s intent=%-22s unsupported=%s"
              % (r["q"][:30], r.get("kind"), r.get("intent", "-"),
                 ",".join(r.get("unsupported", [])) or "∅"))
        # ⛔ 原文要逐条读 —— 「不撞这道闸」不等于「不是冲突导致的」，
        #    这一句是判断它属不属于本缺口的**唯一**依据。
        print("         原文: %s" % (r.get("text") or "")[:200])
    print("  跨闸【同时 ≥2 个冲突】的问句              %d 条 / %d"
          % (len(cross_multi), len(rows)))
    for r in cross_multi:
        print("       · %-30s %s" % (r["q"][:30], " ｜ ".join(r["cross"])))
    return 0 if (multi or cross_multi) else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
