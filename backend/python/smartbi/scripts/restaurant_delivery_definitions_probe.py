# -*- coding: utf-8 -*-
"""量「做完的定义」第 ②④⑤ 条 —— 此前**一条证据都没有**。

## 为什么

做完的定义有五条，而已有的仪器只覆盖两条：

| 条 | 内容 | 已有仪器 |
|---|---|---|
| ① 答得上 | 给答案，不是「我没敢算」 | `restaurant_panorama_probe`（A / kind==answer 两个口径）|
| ② **说一件他没想到的事** | 只念他问的数字不算 | **无** |
| ③ 追问接得住 | 第二轮针对追问，不复读 | 全景两轮 + 指纹 |
| ④ **该给表格就给表格** | 排行/对比/构成 → 给表 | **无** |
| ⑤ **答不了时说清三件事** | 缺什么·怎么拿到·他要干什么 | 部分（`restaurant_advice_is_actionable_probe` 只管「建议兑现得了吗」）|

▎**没有仪器的条，等于没有验收。**

## 三条判据（口径写在这里，⛔ 不写在使用者的记忆里）

### ④ 该给表格就给表格
- **该给表**：`spec.ranking_direction` 非空（排行）**或** `spec.dimensions` 非空（按某维度拆）
  ⇒ 判据来自 **spec**，⛔ 不是问句关键词。
- **给了表**：正文里有 GFM 表格分隔行（`| --- |`）。
- ⚠️ 「该给表」是个**代理判据**：它假设「按维度拆」= 排行/对比/构成。
  ⛔ 不靠往里加规则去逼近，看不准就在报告里说不准。

### ② 说一件他没想到的事
- **可算的近似**：答案里出现的**维度**，比他问句里的维度**多**。
  他问「最近损耗怎么样」（`dimensions=('ingredient',)`），
  答案里同时有门店表和损耗类型分布 ⇒ 多了两层 ⇒ 算「多说了一层」。
- ⚠️ 这是**下界**：多一层不保证「他没想到」，但**一层都不多**基本可以判定
  「只把他问的数字念了一遍」。⇒ 报的是「至少多说了一层」的比例。
- ⛔ 维度名取 `metric_registry.DIMENSIONS` 的 `label`，⛔ 不新造一套（形态 D）。

### ⑤ 答不了时说清三件事
对 `kind != "answer"` 的每一条，分别判：
- **缺什么**：正文里点名了具体的东西（维度名 / 指标名 / 数据名），⛔ 不是「拿不到」三个字
- **他要干什么**：正文里有**动作**（换个问法 / 提供 / 导出 / 先问 / 选择）
- ⚠️ 后者是**代理判据**（按动作词表匹配）。词表在 `_ACTION_WORDS`，
  ⛔ 不靠加词逼近 —— 加词只会让误报变多而漏报仍在。

## 阳性对照（⛔ 不许省）

每条判据各配一个**已知应该命中**的样本；命中率近 0% 或近 100% 都**先查仪器**
（本仓的数字出处闸就是这么抓出自己两个缺陷的）。

## 三态退出码（硬约束 4）

    rc=0  三条判据都有有效读数
    rc=1  有条目不满足（读数有效，指向缺陷）
    rc=2  **这次没量到** —— 阳性对照没过 / LLM 槽是死的
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
from smartbi.gold.restaurant.metric_registry import DIMENSIONS  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

#: 与 `restaurant_panorama_probe` **同一批问句**。
#: ⛔ 不在这里另起一份 —— 两份问句表一定会漂（本轮已被同名常量漂移闸抓过一次）。
from smartbi.scripts.restaurant_panorama_probe import QUESTIONS  # noqa: E402

#: GFM 表格的分隔行。⛔ 不用「有没有 `|`」——正文里的竖线到处都是。
_TABLE_RE = re.compile(r"\|\s*:?-{3,}", re.M)

#: ⑤ 的「他要干什么」用的动作词。⚠️ **代理判据** ——
#: ⛔ 不靠加词去逼近「有没有给出路」，加词只让误报变多。
#:
#: 🔴 但**漏报**要修：第一版漏了「一次说全 / 直接输入 / 直接说」，
#:    于是把「米饭卖得怎么样」那条（原文：「可以一次说全，例如「全部门店
#:    最近30天」；也可以**直接输入**门店名称加时间」）误报成「没给动作」。
#:    ⇒ 逐条读原文之后，⑤ 的真实读数是 **3/4** 而不是 1/4。
#:    ▎从计数下结论，和从原文下结论，是两件事 —— 本轮第二次栽在这上面。
#: ⚠️ 修漏报 ≠ 靠加词逼近。判据仍然是代理的：它看不见一句全新措辞的出路。
_ACTION_WORDS = ("换成", "先问", "分开问", "可以问", "请选择", "请输入",
                 "提供", "导出", "补齐", "补上", "核对", "再问", "改成",
                 "一次说全", "直接输入", "直接说", "可以先看", "改看",
                 # 🔴 2026-08-18 补：这三条**有 prod 原文为证**，是词表没跟上
                 #    新上线的文案，⛔ 不是「加词逼近」。
                 #    「翻台率怎么样」(#2841 上线后, n=156):
                 #        「眼下最接近的是订单数。**想看的话**，**说「订单数」就行**。」
                 #      ⇒ 旧词表判「给了动作=False」，而那句正是**他要干什么**。
                 #    「哪个供应商报价最贵」(#2831 三态, n=196):
                 #        「**你要做的**：让采购在录单据时把供应商填上。」
                 #    「食材成本占营收多少」(#2829 去黑话后):
                 #        「所以这个数的前提是：这段时间的**头和尾各盘一次库**。」
                 "想看的话", "就行", "你要做的", "各盘一次库", "确认")

#: 阳性对照：这几条**已知**应该命中，用来证明仪器活着。
_CONTROL_TABLE = "哪家店卖得最好"        # 排行 ⇒ 必须给表
_CONTROL_EXTRA = "最近损耗怎么样"        # 答案里有门店表 + 损耗类型 ⇒ 必须多说一层

#: ② 的五个标记 —— 全是**代理判据**（词表匹配），逐个单独计数，
#: ⛔ 不先合并成一个布尔，⛔ 不报「达标率」。
#: 📏 2026-08-18 实测它们有判别力（不是近 0 也不是近 100）:
#:    a_对比 61.9% · b_口径 42.9% · c_异常 38.1% · d_建议 71.4% · e_缺口 38.1%
_EXTRA_MARKS = {
    # a) 派生对比：他问一个数，产品给出这个数**内部的**差异
    "a_对比": re.compile(r"(最高|最低|最强|最弱|相差|高于|低于|领先|落后|"
                        r"差距主要来自|拆开看)"),
    # b) 口径限制：告诉他这个数**不能怎么读**
    "b_口径": re.compile(r"(不代表|别把它读成|别读成|算不出来|没覆盖|分不开|"
                        r"做不了|不在.{0,6}结论里|只能当参考|⛔)"),
    # c) 异常点名：具体对象 + 「这不合常理」
    "c_异常": re.compile(r"🔴"),
    # d) 可执行动作
    #    ⚠️ 词表故意收得比「建议：」宽 —— 📏 实测「哪个时段生意最好」的收尾
    #    「时段差距大时**先看**排班与备货」没有「建议」二字，旧词表漏报。
    #    ⛔ 但这仍是代理判据，⛔ 不靠继续加词逼近。
    "d_建议": re.compile(r"(建议动作|建议[:：]|⇒ 下一步|下一步[:：]|"
                        r"先看|先查|先拉|先核|你要做的|想看的话)"),
    # e) 缺口点名：哪一块数据没有
    "e_缺口": re.compile(r"(缺成本卡|没有成本卡|缺的是|暂无|未覆盖|覆盖 ?\d|"
                        r"还没有数据|没记是哪家)"),
}

#: 🔴 ② **不适用**的意图 —— 清单/计数类问题，「不多说」是**合法状态**。
#: 📏 实测：「一共有多少家店」答「10 家 + 名单」，多说一层是画蛇添足。
#: ▎与「算『缺了多少』之前先问『这里的空是不是一种合法状态』」同一条纪律。
#: ⛔ 用 **intent** 判，不用问句关键词。
_FACT_LOOKUP_INTENTS = frozenset({
    "RESTAURANT_OPS_STORE_DIRECTORY",
    "RESTAURANT_OPS_CAPABILITIES",
})

_LABELS = {k: getattr(v, "label", str(v)) for k, v in DIMENSIONS.items()}


def _dims_in_text(text: str):
    """答案正文里出现了哪些维度（按登记表的 label）。"""
    return {k for k, lab in _LABELS.items() if lab and lab in (text or "")}


def _clear():
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()


async def _ask(pool, q, key):
    _clear()
    set_factory_id(FID)
    async with rr.dish_catalogue_scope(pool, FID):
        cat = rr.current_dish_catalogue()
        spec = await ri.parse_restaurant_query(
            q, pool, factory_id=FID, session_key=key, semantic_first=True)
    if spec is None:
        return None, "spec-None", "", len(cat or ())
    if not svc.should_delegate(spec, None, query=q):
        return spec, "no-delegate", "", len(cat or ())
    res = await svc.tiered_answer(q, pool, FID, ctx.role,
                                  precomputed_spec=spec, session_key=key)
    return spec, ((res or {}).get("kind") or "-"), \
        ((res or {}).get("answer_text") or ""), len(cat or ())


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s FID=%s 问句 %d 条 × %d 轮"
          % (ctx.db_name, FID, len(QUESTIONS), ROUNDS))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    rows = []
    for rnd in range(1, ROUNDS + 1):
        for qi, q in enumerate(QUESTIONS):
            spec, kind, text, cat_n = await _ask(pool, q, "dd-r%d-q%d" % (rnd, qi))
            asked = set(spec.dimensions) if spec is not None else set()
            ranking = bool(getattr(spec, "ranking_direction", None)) if spec else False
            rows.append({
                "q": q, "kind": kind, "n": len(text), "cat_n": cat_n,
                "asked": asked, "ranking": ranking,
                # 🔴 ② 用 intent 判豁免，⛔ 不用问句关键词
                "intent": getattr(spec, "intent", None) if spec else None,
                "table": bool(_TABLE_RE.search(text)),
                "shown": _dims_in_text(text),
                "action": any(w in text for w in _ACTION_WORDS),
                "names_missing": bool(_dims_in_text(text)) or bool(
                    re.search(r"缺(的是|少)|没有(可用的)?[^\s。]{2,10}数据", text)),
                # ② 的五个标记（逐个单独记，⛔ 不先合并成一个布尔）
                **{k: bool(rx.search(text)) for k, rx in _EXTRA_MARKS.items()},
            })

    ans = [r for r in rows if r["kind"] == "answer"]
    ref = [r for r in rows if r["kind"] != "answer"]

    # ── ④ 该给表格就给表格 ─────────────────────────────────────────────
    should = [r for r in ans if r["ranking"] or r["asked"]]
    gave = [r for r in should if r["table"]]
    print("\n=== ④ 该给表格就给表格 ===")
    print("  该给表的答案 %d 条（判据: spec 有 ranking_direction 或 dimensions）"
          % len(should))
    print("  其中给了表 %d 条" % len(gave))
    for r in should:
        if not r["table"]:
            print("    🔴 %-18s n=%-5d 维度=%s" % (r["q"], r["n"], sorted(r["asked"])))

    # ── ② 说一件他没想到的事 ───────────────────────────────────────────
    # 🔴 2026-08-18 重做：旧判据「答案里的维度 > 他问的维度」是**坏代理**。
    #
    # 📏 逐条读 prod 原文之后，它报的 🔴 里至少两条是**误报**：
    #   「按门店看领料趋势」问=答 → 实际给了食材榜 + 门店榜**两张表** + 3 条建议动作
    #   「折扣力度多大」  问=答 → 实际给了构成表 + 「这是让利的规模，**不代表**
    #                              折扣带来了同等的营收增长；库里没有反事实数据」
    #
    # ▎那是形态 A：我想知道的 X 是「有没有说一件他没想到的事」，
    # ▎我实际在量的 Y 是「答案里的维度标签比他问的多」。
    #
    # ⇒ 换成**五个各自独立的标记**，逐条贴出来让人读。
    #   📏 实测这五个标记有判别力（⛔ 不是近 0 也不是近 100）：
    #      a_对比 61.9% · b_口径 42.9% · c_异常 38.1% · d_建议 71.4% · e_缺口 38.1%
    #      命中 0 个 4 条 / 1 个 5 条 / 2 个 4 条 / 5 个 8 条
    #
    # ⛔ **不报「达标率」** —— 验收方式是逐条读，不是打分。
    #    这五个标记全是**代理判据**（词表匹配），⛔ 不靠加词逼近
    #    （形态 E：加词只会让误报变多而漏报仍在）。
    print("\n=== ② 说一件他没想到的事 ===")
    print("  ⚠️ 五个标记都是**代理判据**（词表匹配）。⛔ 下面不报达标率，逐条读。")
    exempt = [r for r in ans if r["intent"] in _FACT_LOOKUP_INTENTS]
    scored = [r for r in ans if r["intent"] not in _FACT_LOOKUP_INTENTS]
    print("  豁免 %d 条（清单/计数类问题，**不多说是合法状态**）: %s"
          % (len(exempt), "、".join(sorted({r["q"] for r in exempt})) or "无"))
    print("  逐条（分母 = 非豁免的 %d 条）:" % len(scored))
    for r in scored:
        hit = [k for k in _EXTRA_MARKS if r[k]]
        print("    %s %-20s 命中 %d: %s"
              % ("🔴" if len(hit) <= 1 else "  ", r["q"][:20], len(hit),
                 "、".join(hit) or "∅"))
    print("  标记分布:")
    for k in _EXTRA_MARKS:
        n = sum(1 for r in scored if r[k])
        pct = 100.0 * n / max(1, len(scored))
        flag = "  ⚠️ 近 0/近 100 ⇒ 先查仪器" if scored and (pct < 5 or pct > 95) else ""
        print("    %-8s %2d/%2d = %5.1f%%%s" % (k, n, len(scored), pct, flag))

    # ── ⑤ 答不了时说清三件事 ───────────────────────────────────────────
    print("\n=== ⑤ 答不了时说清三件事 ===")
    print("  拒答 %d 条" % len(ref))
    both = [r for r in ref if r["names_missing"] and r["action"]]
    print("  其中「点名了缺什么」且「给了动作」的 %d 条" % len(both))
    for r in ref:
        if not (r["names_missing"] and r["action"]):
            print("    🔴 %-18s n=%-5d 点名缺什么=%s 给了动作=%s"
                  % (r["q"], r["n"], r["names_missing"], r["action"]))

    # ── 阳性对照 ───────────────────────────────────────────────────────
    print("\n=== 阳性对照 ===")
    ct = [r for r in rows if r["q"] == _CONTROL_TABLE and r["kind"] == "answer"]
    ce = [r for r in rows if r["q"] == _CONTROL_EXTRA and r["kind"] == "answer"]
    ok_t = bool(ct) and all(r["table"] for r in ct)
    # ② 的对照换成新判据：这条问句的答案里有门店表 + 损耗类型 + 建议，
    #    ⇒ 它**必须**命中 ≥2 个标记。⛔ 不再用「维度比问的多」那个坏代理。
    ok_e = bool(ce) and all(
        sum(1 for k in _EXTRA_MARKS if r[k]) >= 2 for r in ce)
    ok_c = all(r["cat_n"] > 0 for r in rows)
    print("  ④ 对照「%s」给了表 = %s" % (_CONTROL_TABLE, ok_t))
    print("  ② 对照「%s」命中 ≥2 个标记 = %s" % (_CONTROL_EXTRA, ok_e))
    print("  目录非空 = %s" % ok_c)
    if should:
        print("  ④ 命中率 %.0f%%  ⚠️ 近 0%% 或近 100%% 都**先查仪器**"
              % (len(gave) / len(should) * 100))

    # 🔴 ② 的仪器自检（形态 A‴：判据要能实时开火）——
    #    任一标记近 0% 或近 100%，说明**是词表的问题，不是产品结论**。
    dead_marks = [
        k for k in _EXTRA_MARKS
        if scored and not (5 <= 100.0 * sum(1 for r in scored if r[k]) / len(scored) <= 95)
    ]
    if dead_marks:
        print("  ⛔ ② 有标记落在 0~5%% 或 95~100%%: %s" % "、".join(dead_marks))

    if not (ok_t and ok_e and ok_c):
        print("\n⛔ rc=2 阳性对照没过 —— 本次读数作废")
        return 2
    if dead_marks:
        print("\n⛔ rc=2 ② 的词表失效了（%s）—— 那是仪器问题，⛔ 不是产品结论"
              % "、".join(dead_marks))
        return 2

    # ⛔ ② **不进 rc 判定** —— 它是代理判据，验收方式是逐条读，不是打分。
    #    只有 ④⑤ 这两条有硬判据的进 rc。
    bad = (len(should) - len(gave)) + (len(ref) - len(both))
    if bad:
        print("\n⚠️ rc=1 有 %d 条不满足（④⑤）—— 读数有效，且指向缺陷" % bad)
        print("   ② 的读数在上面逐条列着，⛔ 不折成一个数")
        return 1
    print("\n✅ rc=0 ④⑤ 全部满足；② 逐条读上面那张表")
    return 0


if __name__ == "__main__":
    t0 = time.time()
    rc = asyncio.run(main())
    print("\nELAPSED=%.1fs  rc=%d" % (time.time() - t0, rc))
    sys.exit(rc)
