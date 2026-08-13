"""真·口径变异：让 resolver 那侧退回 item 口径，闸必须红，**且红出折扣额**。

## 为什么不用容差变异

容差变异硬改阈值，不管两侧从哪来 —— 它绕开了我们**有前科**的那个问题：
「我自己写的自洽闸是个恒真式，一次都红不了 —— 左右来源相同」。
若两侧最终走到同一段代码，口径一变两侧一起变，diff 永远 0，闸永远绿。

真口径变异同时验两件事：
  ① 闸会不会抓口径分歧
  ② **闸的两侧是不是真的独立**   ← 这件才是要紧的

## 为什么不加开关、不改磁盘

`_paid_revenue_in_window()` 返回 None 时，resolver **自己的回退路径**就会退回
`total_rev = total_rev_items`（item 口径）—— 那正是修复前的行为。
所以把它打桩成 None 就是一次真实的口径变异，不动 prod 上任何文件。

## 判据（比「红了」更严）

红了还不够，**要红出正确的数**：`|diff|` ≈ 31,125.59（折扣额）。
红了但 diff 是别的数 = 变异没打到该打的地方 —— 形态 C″ 的镜像：
变异不红要证明变异生效，变异红了也要证明**红的是那个原因**。
"""
import asyncio
import datetime
import json
import os
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FACTORY = os.environ.get("PARITY_FACTORY", "MOCK_REST")
DAY = datetime.date.fromisoformat(os.environ["PARITY_DAY"])
EXPECTED_DISCOUNT = float(os.environ.get("EXPECTED_DISCOUNT", "31125.59"))
ctx = bootstrap_probe(FACTORY)

from smartbi.gold.restaurant import generic_executor as ge  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as router  # noqa: E402


async def _executor_profit(pool):
    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY)
        cell = await ge.execute_cell(
            conn, factory_id=FACTORY, metric_key="gross_profit",
            dimension_key="all", aggregation_key="summary", date_range=(DAY, DAY))
    return (float(cell.rows[0]["gross_profit"])
            if cell.rows and cell.rows[0].get("gross_profit") is not None else None)


async def _resolver_profit(pool):
    a = await router.resolve_gross_margin(
        pool, FACTORY, role=ctx.role, date_range=(DAY, DAY), window_label=str(DAY))
    return (getattr(a, "meta", None) or {}).get("aggregate_gross_profit")


async def main():
    pool = await ctx.pool()
    out = {}

    # ── 基线：未变异 ────────────────────────────────────────────────
    base_exec = await _executor_profit(pool)
    base_res = await _resolver_profit(pool)
    out["baseline"] = {"executor": base_exec, "resolver": base_res,
                       "diff": None if None in (base_exec, base_res)
                       else round(base_exec - base_res, 2)}

    # ── 变异：resolver 侧退回 item 口径（走它自己的回退路径）────────────
    real = router._paid_revenue_in_window

    async def _no_paid(*a, **kw):
        return None

    router._paid_revenue_in_window = _no_paid
    try:
        mut_exec = await _executor_profit(pool)
        mut_res = await _resolver_profit(pool)
    finally:
        router._paid_revenue_in_window = real

    out["mutated"] = {"executor": mut_exec, "resolver": mut_res,
                      "diff": None if None in (mut_exec, mut_res)
                      else round(mut_exec - mut_res, 2)}

    # ── 判据 ──────────────────────────────────────────────────────
    checks = {}
    checks["baseline_green"] = (out["baseline"]["diff"] is not None
                                and abs(out["baseline"]["diff"]) <= 0.01)
    checks["mutation_red"] = (out["mutated"]["diff"] is not None
                              and abs(out["mutated"]["diff"]) > 0.01)
    # 🔴 红得对不对: |diff| 必须是折扣额
    checks["red_for_the_right_reason"] = (
        out["mutated"]["diff"] is not None
        and abs(abs(out["mutated"]["diff"]) - EXPECTED_DISCOUNT) < 1.0)
    # 🔴 两侧独立: 变异只该动 resolver 那一侧, executor 一动不动
    checks["sides_are_independent"] = (
        base_exec is not None and mut_exec is not None
        and abs(base_exec - mut_exec) < 0.01
        and base_res is not None and mut_res is not None
        and abs(base_res - mut_res) > 0.01)

    out["checks"] = checks
    out["expected_discount"] = EXPECTED_DISCOUNT
    print(json.dumps(out, ensure_ascii=False, indent=2, default=str))
    return 0 if all(checks.values()) else 1


sys.exit(asyncio.run(main()))
