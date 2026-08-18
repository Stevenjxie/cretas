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
                 "一次说全", "直接输入", "直接说", "可以先看", "改看")

#: 阳性对照：这几条**已知**应该命中，用来证明仪器活着。
_CONTROL_TABLE = "哪家店卖得最好"        # 排行 ⇒ 必须给表
_CONTROL_EXTRA = "最近损耗怎么样"        # 答案里有门店表 + 损耗类型 ⇒ 必须多说一层

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
                "table": bool(_TABLE_RE.search(text)),
                "shown": _dims_in_text(text),
                "action": any(w in text for w in _ACTION_WORDS),
                "names_missing": bool(_dims_in_text(text)) or bool(
                    re.search(r"缺(的是|少)|没有(可用的)?[^\s。]{2,10}数据", text)),
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
    extra = [r for r in ans if r["shown"] - r["asked"]]
    print("\n=== ② 说一件他没想到的事（下界：答案里的维度 > 他问的维度）===")
    print("  答上的 %d 条里，至少多说了一层的 %d 条" % (len(ans), len(extra)))
    flat = [r for r in ans if not (r["shown"] - r["asked"])]
    for r in flat:
        print("    🔴 %-18s 问=%s 答=%s"
              % (r["q"], sorted(r["asked"]) or "∅", sorted(r["shown"]) or "∅"))

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
    ok_e = bool(ce) and all(r["shown"] - r["asked"] for r in ce)
    ok_c = all(r["cat_n"] > 0 for r in rows)
    print("  ④ 对照「%s」给了表 = %s" % (_CONTROL_TABLE, ok_t))
    print("  ② 对照「%s」多说了一层 = %s" % (_CONTROL_EXTRA, ok_e))
    print("  目录非空 = %s" % ok_c)
    if should:
        print("  ④ 命中率 %.0f%%  ⚠️ 近 0%% 或近 100%% 都**先查仪器**"
              % (len(gave) / len(should) * 100))
    if ans:
        print("  ② 命中率 %.0f%%  ⚠️ 同上" % (len(extra) / len(ans) * 100))

    if not (ok_t and ok_e and ok_c):
        print("\n⛔ rc=2 阳性对照没过 —— 本次读数作废")
        return 2
    bad = (len(should) - len(gave)) + len(flat) + (len(ref) - len(both))
    if bad:
        print("\n⚠️ rc=1 有 %d 条不满足 —— 读数有效，且指向缺陷" % bad)
        return 1
    print("\n✅ rc=0 三条判据全部满足")
    return 0


if __name__ == "__main__":
    t0 = time.time()
    rc = asyncio.run(main())
    print("\nELAPSED=%.1fs  rc=%d" % (time.time() - t0, rc))
    sys.exit(rc)
