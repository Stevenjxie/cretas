# -*- coding: utf-8 -*-
"""缺口 #9 的影响面：登记表说「我能按 X 拆」，而执行器**收不到** X。

形态 D + B 的组合：两份不一致，且不一致的表现不是报错，是
**「哪家店损耗最多」和「最近损耗怎么样」返回逐字相同的答案**（交付定义③）。

判据（硬的，⛔ 不靠注释）:
  `resolve_by_code` 按签名过滤 kwargs —— 没声明的**静默丢弃**。
  所以「签名里有没有 dimensions」就是「它能不能拿到维度」。
  ⚠️ 这条本身也要跑一次证实，⛔ 不从注释推断（见 _prove_silent_drop）。
"""
from __future__ import annotations

import asyncio
import inspect
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.gold.restaurant.restaurant_intent_service import (  # noqa: E402
    _RESOLVER_DIMENSIONS,
)


async def _prove_silent_drop() -> str:
    """🔴 先证明「没声明的 kwarg 被静默丢弃」，⛔ 不从注释推断。

    ⚠️ 第一版拿真 resolver（STORE_DIRECTORY）当对照，它在 `None` pool 上
       先炸了 `AttributeError` 而不是 `TypeError` —— **阳性对照没过**，
       读数当场作废。⇒ 换成自带的探针 resolver，零外部依赖。
    """
    seen = {}

    async def _probe_no_dims(pool, factory_id, *, role=None):
        seen["got"] = {"role": role}
        return "OK-NO-DIMS"

    async def _probe_with_dims(pool, factory_id, *, role=None, dimensions=()):
        seen["got2"] = {"role": role, "dimensions": tuple(dimensions or ())}
        return "OK-WITH-DIMS"

    code_a, code_b = "__PROBE_NO_DIMS__", "__PROBE_WITH_DIMS__"
    rr._RESOLVERS[code_a] = _probe_no_dims
    rr._RESOLVERS[code_b] = _probe_with_dims
    try:
        # ① 阳性对照：直接调不声明 dimensions 的那个 → **必须** TypeError
        direct_raised = False
        try:
            await _probe_no_dims(None, "MOCK_REST", dimensions=("store",))
        except TypeError as exc:
            direct_raised = "dimensions" in str(exc)

        # ② 被测：走 resolve_by_code 传 dimensions → 应该**不**炸（被丢弃）
        dropped_ok = False
        try:
            r = await rr.resolve_by_code(code_a, None, "MOCK_REST",
                                         dimensions=("store",), role="x")
            dropped_ok = (r == "OK-NO-DIMS"
                          and seen.get("got") == {"role": "x"})
        except TypeError:
            dropped_ok = False

        # ③ 阴性对照：声明了 dimensions 的那个 → **必须**真收到
        r2 = await rr.resolve_by_code(code_b, None, "MOCK_REST",
                                      dimensions=("store",), role="x")
        passed_through = (r2 == "OK-WITH-DIMS"
                          and seen.get("got2", {}).get("dimensions") == ("store",))
    finally:
        rr._RESOLVERS.pop(code_a, None)
        rr._RESOLVERS.pop(code_b, None)

    if not direct_raised:
        return "⚠️ 阳性对照没过（直接调没在 dimensions 上炸）—— 本条读数作废"
    if not passed_through:
        return "⚠️ 阴性对照没过（声明了却没收到）—— 仪器坏了，读数作废"
    if not dropped_ok:
        return "🔴 resolve_by_code **没有**静默丢弃 —— 判据不成立，需重新设计"
    return ("✅ 已证实: 未声明→静默丢弃(不炸, kwargs 里没有它) / "
            "已声明→原样收到 / 直接调未声明的→TypeError")


def main() -> int:
    proof = asyncio.run(_prove_silent_drop())
    print("判据自证: %s\n" % proof)
    if proof.startswith("⚠️") or proof.startswith("🔴"):
        return 2

    print("=" * 92)
    print("%-42s %-26s %s" % ("intent", "登记表说能按这些拆", "执行器收得到吗"))
    print("=" * 92)
    rows = []
    for code, dims in sorted(_RESOLVER_DIMENSIONS.items()):
        fn = rr._RESOLVERS.get(code)
        if fn is None:
            rows.append((code, dims, None, "不在 _RESOLVERS 里(服务层派发)"))
            continue
        params = inspect.signature(fn).parameters
        # ⚠️ 有 **kwargs 的**照样收得到** —— resolve_by_code 的过滤原话:
        #    "filter kwargs down to each resolver's accepted parameter names
        #     (unless it has a **kwargs catch-all)"。
        #    第一版漏了这一支 ⇒ STORE_MARGIN 被误报成缺口（假阳性）。
        catch_all = any(v.kind == inspect.Parameter.VAR_KEYWORD
                        for v in params.values())
        got = ("dimensions" in params) or catch_all
        rows.append((code, dims, got,
                     "(经 **kwargs 收到)" if catch_all and "dimensions" not in params
                     else ""))

    gap = []
    for code, dims, got, note in rows:
        short = code.replace("RESTAURANT_OPS_", "")
        mark = "✅" if got else ("—" if got is None else "🔴")
        print("%-42s %-26s %s %s"
              % (short, "、".join(sorted(dims)) or "∅", mark, note))
        if got is False and len(dims) >= 2:
            gap.append((short, dims))

    print("\n登记表 %d 个 intent；其中执行器收得到 dimensions 的 %d 个"
          % (len(rows), sum(1 for r in rows if r[2] is True)))
    print("\n🔴 **登记≥2 个维度、而执行器收不到**（说能拆、实际拆不了）= %d 个:"
          % len(gap))
    for short, dims in gap:
        print("   %-34s 登记: %s" % (short, "、".join(sorted(dims))))
    print("\n⚠️ 登记只有 0~1 个维度的不算缺口 —— 一个维度无所谓「按哪个拆」。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
