"""第 4 步: prod 上真跑一遍, 读**老板会看到的原文**。

⚠️ prod 跑的是**部署后的活代码**。本探针把改动过的两个文件用
   `importlib` 从一个旁路目录先行加载（⛔ 不覆盖线上文件、⛔ 不部署）,
   所以它量的是「这份改动在 prod 的真实数据 + 真实 LLM 上是什么行为」。

判据（每问一句都打全, ⛔ 不只打结论）:
  · kind          answer / clarification —— **拒答也是一种「不一样的答案」**,
                  不带这一格会把拒答读成「答上来了」
  · store_slots   计划里落的门店 —— 期望是**库里的全名**
  · 正文前 200 字  老板真正看到的那句话

阳性对照: 用**全名**问一遍, 必须与简称问出同一个门店槽。
阴性对照: 用一个与本租户完全无关的词问, 必须不落任何门店槽。

⚠️ 跨样本读数前清缓存并**贴出清了哪几个**（硬约束 3）。
"""
from __future__ import annotations

import asyncio
import importlib.util
import json
import os
import sys
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

ctx = bootstrap_probe(os.environ.get("PROBE_FID", "MOCK_REST"))

#: 改动过的两个文件放在**旁路目录**。⛔ 不覆盖部署树里的文件, ⛔ 不重启服务 ——
#: 线上那个进程的模块早已在内存里, 而这里只影响本探针自己的 import。
OVERLAY = os.environ.get("ENTITY_OVERLAY", "/tmp/entity_overlay")


def _preload(dotted: str, filename: str) -> bool:
    """把旁路目录里的文件注册成 `dotted` 这个模块名 —— 必须在**任何**
    `import restaurant_intent` 之前跑, 否则拿到的是部署树里那份旧的。"""
    path = os.path.join(OVERLAY, filename)
    if not os.path.exists(path):
        print("🔴 旁路文件不存在: %s" % path)
        return False
    spec = importlib.util.spec_from_file_location(dotted, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[dotted] = module
    spec.loader.exec_module(module)
    return True


_OVERLAY_OK = (
    _preload("smartbi.canonical.entity_resolution.shortlist", "shortlist.py")
    and _preload("smartbi.gold.restaurant.restaurant_intent", "restaurant_intent.py")
)

#: ⚠️ 「浦东店」连跑 3 次是**有理由的**, ⛔ 不是凑数:
#:    真 LLM 对同一条问句实测跑出过**三种**输出形状(自己归一 / 回声简称 /
#:    一家都没提名), 而第三种曾经让这条问句从 ✅ 变 🔴。
#:    ▎一次绿证明不了「接住了」, 只证明这次抽到的是好那一种。
CASES = [
    ("简称-唯一", "宝山店最近30天营收多少", "模拟·宝山大场社区店"),
    ("简称-唯一", "徐汇店最近30天营收多少", "模拟·徐汇美罗城店"),
    ("简称-唯一#1", "浦东店最近30天营收多少", "模拟·浦东金桥社区店"),
    ("简称-唯一#2", "浦东店最近30天营收多少", "模拟·浦东金桥社区店"),
    ("简称-唯一#3", "浦东店最近30天营收多少", "模拟·浦东金桥社区店"),
    ("简称-唯一", "长宁店最近30天营收多少", "模拟·长宁龙之梦店"),
    ("简称-多条", "社区店最近30天营收多少", None),
    ("阳性-全名", "模拟·宝山大场社区店最近30天营收多少", "模拟·宝山大场社区店"),
    ("阴性-无关", "量子纠缠火箭发射器最近30天营收多少", None),
]


def _clear_caches(ri, shortlist):
    """⛔ 用各模块自己的 helper, ⛔ 不拼属性名。清了哪几个**贴出来**。"""
    cleared = []
    for name in ("clear_semantic_plan_cache", "clear_route_cache",
                 "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, name)()
        cleared.append(name)
    shortlist.clear_shortlist_cache()
    cleared.append("clear_shortlist_cache")
    print("  [清缓存] " + " / ".join(cleared)
          + "  shortlist_cache_size=%d" % shortlist.shortlist_cache_size())


async def main() -> int:
    pool = await ctx.pool()
    fid = ctx.factory_id
    if ctx.llm_dead_slots:
        print("🔴 LLM 槽死了: %s —— 每条答案都会是 fail-closed 拒答, 读数作废 (rc=2)"
              % (ctx.llm_dead_slots,))
        return 2

    from smartbi.gold.restaurant import restaurant_intent as ri
    from smartbi.canonical.entity_resolution import shortlist
    from smartbi.gold.restaurant.restaurant_intent_service import tiered_answer

    # ── 先证明「这份代码里有我的改动」——⛔ 不假设 scp 送对了 ────────────
    #    ▎找到标记 ≠ 它能跑, 所以下面还要**真跑一次那条路径**。
    marker = hasattr(ri, "_resolve_store_mentions")
    print("代码自检: 旁路加载=%s  restaurant_intent 有 _resolve_store_mentions=%s"
          % (_OVERLAY_OK, marker))
    print("代码自检: restaurant_intent 来自 %s" % getattr(ri, "__file__", "?"))
    print("代码自检: shortlist 来自 %s  SIM_FLOOR=%s SIM_MARGIN=%s"
          % (getattr(shortlist, "__file__", "?"),
             shortlist.SIM_FLOOR, shortlist.SIM_MARGIN))
    if not (_OVERLAY_OK and marker):
        print("🔴 跑的是**没有改动**的旧代码 —— 读数作废 (rc=2)")
        return 2
    # 阳性对照: 消解函数本身在这台机器上真的跑得动(⛔ 不只是 import 得到)
    smoke = await ri._resolve_store_mentions(
        "宝山店营收", ("模拟·宝山大场社区店", "模拟·徐汇美罗城店"))
    print("代码自检(真跑一次): %s" % (smoke,))
    if not (smoke and smoke.aliases):
        print("🔴 消解函数在 prod 上跑不出结果 —— 读数作废 (rc=2)")
        return 2

    stores = await ri._load_store_options(pool, fid)
    print("available_stores(%d): %s\n" % (
        len(stores), json.dumps(list(stores), ensure_ascii=False)))

    for label, query, expect_store in CASES:
        print("─" * 76)
        print("[%s] %s" % (label, query))
        _clear_caches(ri, shortlist)
        t0 = time.perf_counter()
        try:
            spec = await ri.parse_restaurant_query(
                query, pool, factory_id=fid, semantic_first=True)
        except Exception as exc:  # noqa: BLE001
            print("  🔴 规划抛异常: %r" % (exc,))
            continue
        plan_ms = (time.perf_counter() - t0) * 1000
        print("  intent=%s  source_tier=%s  authority=%s  %.0fms" % (
            spec.intent, spec.source_tier, spec.planner_authority, plan_ms))
        print("  store_slots=%s  store_scope=%s  defaulted=%s" % (
            json.dumps(list(spec.store_slots), ensure_ascii=False),
            spec.store_scope, spec.store_scope_defaulted))
        print("  clarification_needed=%s" % spec.clarification_needed)
        if spec.clarification_question:
            print("  ▎老板会看到的问句: %s" % spec.clarification_question)
        if spec.clarification_options:
            print("  ▎按钮: %s" % json.dumps(
                list(spec.clarification_options), ensure_ascii=False))
        if expect_store is not None:
            ok = tuple(spec.store_slots) == (expect_store,)
            print("  判据 store_slots == (%r,) -> %s" % (expect_store, "✅" if ok else "🔴"))
        elif label.startswith("阴性"):
            print("  判据 不落任何门店槽 -> %s" % ("✅" if not spec.store_slots else "🔴"))

        # ── 真跑一次答案, 读老板看到的正文 ────────────────────────────
        t0 = time.perf_counter()
        try:
            result = await tiered_answer(
                query, pool, factory_id=fid, role=ctx.role,
                precomputed_spec=spec)
        except Exception as exc:  # noqa: BLE001
            print("  🔴 执行抛异常: %r" % (exc,))
            continue
        ans_ms = (time.perf_counter() - t0) * 1000
        kind = (result or {}).get("kind")
        text = ((result or {}).get("answer_text") or "").replace("\n", " ")
        print("  kind=%s  %.0fms  正文 %d 字" % (kind, ans_ms, len(text)))
        print("  ▎正文前 200 字: %s" % text[:200])
    print("─" * 76)
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
