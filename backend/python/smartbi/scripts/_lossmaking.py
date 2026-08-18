# -*- coding: utf-8 -*-
"""「有几家店是亏钱的」到底断在哪一格 —— 按 owner 给的排查顺序，⛔ 不先下结论。

## 为什么要这个探针

上一轮我把**两个互不相同的根因**合并成了一句话，然后用「以 llm 为主」
给一个可能是确定性的缺口找了不做的理由。owner 拆开了：

    A「有几家店」被当成店名        ⇒ 实体问题 ⇒ 查租户门店目录，查不到就丢槽位
    B code=STORE_MARGIN ∉ plan=(GROSS_MARGIN,) ⇒ spec 自相矛盾
                                   ⇒ 出口不变量 code ∈ planned_intents

**两条都是确定性代码**，都不需要读句意。

## 判据顺序（owner 写死的）

    2. store_slot == "有几家店"      ⇒ A
    3. code ∉ plan 且 dims 非空      ⇒ B，2636 那条 realign 为什么没开火
    4. dims == ()                    ⇒ 交接件「第一档第 1 项」的覆盖洞
                                       （2601 的 `if repair_candidate and dimensions:`）
    5. 以上全排除，才轮到模型侧

## ⛔ 每一格都打出来，⛔ 不只打结论

⚠️ 形态 A¹³：读数要带**来源标记** —— 加 `source_tier`，
   否则分不清「两轮一致」是真一致还是命中了同一个缓存条目。
⚠️ 硬约束 3：每轮开跑前清缓存并**贴出清了哪几个**。
⚠️ ≥3 轮：LLM 对同一句问句吐过三种不同形状（门店简称那次实测）。

## 对照组

「有没有店是亏钱的」—— 📏 上一轮实测它**答对了**
（`dims=('store',)` / `kind=answer` /「10 家门店中没有毛利为负的门店」）。
两句并排打，差别落在哪一格一眼可见。

## 阳性对照（⛔ 不许省）

- 菜单目录 10 条 ⇒ 库连上了
- 对照组至少一轮 `kind == "answer"` ⇒ 执行链活着
  （否则「两句都拒答」和「我根本没跑起来」是同一个读数）
"""
from __future__ import annotations

import asyncio
import logging
import re

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FID = "MOCK_REST"
ROUNDS = 3
ctx = bootstrap_probe(FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

LOSSMAKING_QS = [
    ("🔴目标", "有几家店是亏钱的"),
    ("✅对照", "有没有店是亏钱的"),
]

#: 这几条日志直接回答 owner 的第 3/4 步 —— 那两个分支有没有开火。
_WATCH = (
    "contract-repair resolver",          # repair 执行了
    "contract-repair SKIPPED",           # 2601 那道闸拦下了
    "plan realigned to planner label",   # 2636 那条 realign 开火了
    "执行前拦截",                        # 下游拒答
    "门店简称",                          # A 那一支
)


class _Collect(logging.Handler):
    def __init__(self):
        super().__init__()
        self.lines: list[str] = []

    def emit(self, record):
        try:
            msg = record.getMessage()
        except Exception:  # noqa: BLE001
            return
        if any(w in msg for w in _WATCH):
            self.lines.append(msg)


def _clear() -> list[str]:
    """⛔ 硬约束 3：清了哪几个要**贴出来**，拼错的属性名不会报错。"""
    done = []
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()
        done.append(n)
    return done


def _fmt(v):
    return "()" if v in (None, (), []) else repr(v)


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
        print("⛔ rc=2 菜单目录为 0 —— 库没连上，读数作废")
        return 2

    handler = _Collect()
    logging.getLogger("smartbi.gold.restaurant.restaurant_intent").addHandler(handler)
    logging.getLogger(
        "smartbi.gold.restaurant.restaurant_intent_service").addHandler(handler)

    n_answer_control = 0
    for rnd in range(1, ROUNDS + 1):
        for tag, q in LOSSMAKING_QS:
            cleared = _clear()
            handler.lines.clear()
            set_factory_id(FID)
            async with rr.dish_catalogue_scope(pool, FID):
                spec = await ri.parse_restaurant_query(
                    q, pool, factory_id=FID,
                    session_key="lm-r%d-%s" % (rnd, tag), semantic_first=True)
            res = None
            if spec is not None and svc.should_delegate(spec, None, query=q):
                res = await svc.tiered_answer(
                    q, pool, FID, ctx.role, precomputed_spec=spec,
                    session_key="lm-r%d-%s" % (rnd, tag))
            kind = (res or {}).get("kind") or "-"
            text = (res or {}).get("answer_text") or ""
            src = ((res or {}).get("source_tier")
                   or (res or {}).get("source") or "?")
            if tag == "✅对照" and kind == "answer":
                n_answer_control += 1

            code = getattr(spec, "intent", None) if spec else None
            plan = tuple(getattr(spec, "planned_intents", ()) or ()) if spec else ()
            dims = tuple(getattr(spec, "dimensions", ()) or ()) if spec else ()
            print("\n" + "=" * 76)
            print("r%d 【%s】%s" % (rnd, tag, q))
            print("  清了: %s" % "、".join(cleared))
            print("  ── owner 第 1 步：spec 每一格 ──")
            print("    intent(code)        = %s" % code)
            print("    planned_intents     = %s" % _fmt(plan))
            print("    dimensions          = %s" % _fmt(dims))
            print("    store_slot          = %s" % _fmt(
                getattr(spec, "store_slot", None) if spec else None))
            print("    store_slots         = %s" % _fmt(
                tuple(getattr(spec, "store_slots", ()) or ()) if spec else ()))
            print("    store_options(目录) = %d 家" % len(
                tuple(getattr(spec, "store_options", ()) or ()) if spec else ()))
            print("    store_scope         = %s" % _fmt(
                getattr(spec, "store_scope", None) if spec else None))
            print("    dish_slot           = %s" % _fmt(
                getattr(spec, "dish_slot", None) if spec else None))
            print("    requested_metrics   = %s" % _fmt(
                tuple(getattr(spec, "requested_metrics", ()) or ()) if spec else ()))
            print("    clarification_needed= %s" % (
                getattr(spec, "clarification_needed", None) if spec else None))
            print("    ── 结果 ──")
            print("    kind=%s  n=%d  source_tier=%s" % (kind, len(text), src))
            # 🔴 owner 的三步判据，机械地打出来，⛔ 不靠人事后回想
            print("    ── 判据 ──")
            print("    ② store_slot 是问句片段?  %s" % (
                "🔴 是 (A: 实体问题)" if (
                    getattr(spec, "store_slot", None) in ("有几家店", "有没有店")
                ) else "否"))
            print("    ③ code ∉ plan 且 dims 非空? %s" % (
                "🔴 是 (B: spec 自相矛盾)"
                if (code and plan and code not in plan and dims) else "否"))
            print("    ④ dims == ()?             %s" % (
                "🔴 是 (覆盖洞: 2601 的外层 gate)" if not dims else "否"))
            print("    ── 相关日志（⛔ 不截断）──")
            for line in handler.lines:
                print("      " + re.sub(r"\s+", " ", line)[:190])
            if not handler.lines:
                print("      (无)")
            print("    ── 老板看到的原文（前 320 字）──")
            print("      " + (text[:320].replace("\n", "\n      ") or "(空)"))

    print("\n阳性对照·对照组 kind==answer = %d/%d 轮" % (n_answer_control, ROUNDS))
    if n_answer_control == 0:
        print("⛔ rc=2 对照组一轮都没答上 —— "
              "分不清「产品拒答」和「我没跑起来」，本次读数作废")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
