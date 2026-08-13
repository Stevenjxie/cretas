"""两条路的合计层毛利对账 —— 形态 D 的闸。

## 为什么需要它

同一个「合计毛利」有**两份实现**:
  · 日结/通用格子 → `generic_executor` 拆分执行
  · 毛利问答      → `resolve_gross_margin` 自带 SQL

owner 2026-08-13 裁定: 抽不动就立闸钉住两份一致。
⛔ 两个数字都对外, 不一致时店长会问「为什么不一样」—— 那是最贵的形态 D。

## 三态退出码(硬约束 4)

    0 = 两条路一致
    1 = 不一致(读数有效, 且指向缺陷)
    2 = **仪器问题**: 一条路没算出数 / 租户没数据 —— 本次读数作废

⛔ rc=2 单独告警。「两条都是 None」在数值上「相等」, 但那不是一致, 是没量到。

## 🔴 这道闸看不见什么

**两侧同源**。若 executor 和 resolver 最终走到同一段代码, 口径一变两侧一起变,
diff 恒 0, 它永远绿 —— 本仓有前科:「我自己写的自洽闸是个恒真式, 一次都红不了
—— 左右来源相同」。

⇒ 补它的是 `tests/test_margin_parity.py` 那道**源码闸**(比两边用的实收列名)。
   反过来源码闸看不见「变量拼装 / 等价写法」, 由本闸的数字比对兜。两道互补。

⚠️ 「两侧独立」**已经用真口径变异验过**(2026-08-13): 把
   `_paid_revenue_in_window` 打桩成 None → resolver 走**自己的回退路径**退回
   item 口径 → diff = **-31,125.59**(正是折扣额), 而 executor 侧一动不动。
⛔ 不能用容差变异代替: 那是硬改阈值, 不管两侧从哪来, 测不出同源。
"""
import asyncio
import datetime
import json
import os
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FACTORY = os.environ.get("PARITY_FACTORY", "MOCK_REST")
OUT = os.environ.get("PARITY_OUT", "/tmp/margin_parity.json")
#: 对账哪一天。⚠️ 默认**昨天** —— 当天数据可能还没落库, 拿空数据比会得出
#: 「两边都是空所以一致」这个恒真结论。
_DAY = (datetime.date.fromisoformat(os.environ["PARITY_DAY"])
        if os.environ.get("PARITY_DAY")
        else datetime.date.today() - datetime.timedelta(days=1))

ctx = bootstrap_probe(FACTORY)

from smartbi.gold.restaurant import generic_executor as ge  # noqa: E402
from smartbi.gold.restaurant.restaurant_ops_router import (  # noqa: E402
    resolve_gross_margin,
)

_TOLERANCE = float(os.environ.get("PARITY_TOLERANCE", "0.01"))


def _write(payload) -> None:
    """产出永远落盘, 包括早退 —— 否则台账会读到上一次的计数(实测踩过)。"""
    with open(OUT, "w", encoding="utf-8", newline="") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2, default=str)


async def main() -> int:
    pool = await ctx.pool()

    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", FACTORY)
        cell = await ge.execute_cell(
            conn, factory_id=FACTORY, metric_key="gross_profit",
            dimension_key="all", aggregation_key="summary",
            date_range=(_DAY, _DAY))
    executor_profit = (float(cell.rows[0]["gross_profit"])
                       if cell.rows and cell.rows[0].get("gross_profit") is not None
                       else None)

    # 🔴 必须带角色。不带 → RBAC 判成无价格权限 → 返回 `rbac_masked`, 一个数都没有。
    #    2026-08-13 实测: 对账闸连着两次报 rc=2, 我据此推断「meta 构造点没补全」——
    #    **推断错了**, 真因是这里没传 role。
    #    ⛔ 判据: 闸报「没量到」时先查**仪器自己的调用参数**, 再去查被测对象。
    answer = await resolve_gross_margin(
        pool, FACTORY, role=ctx.role,
        date_range=(_DAY, _DAY), window_label=str(_DAY))
    resolver_profit = None
    meta = getattr(answer, "meta", None) or {}
    # ⚠️ 具名字段优先。kpis 没有 label, 按标签猜必然读不到 —— 实测第一次
    #    跑闸就报 rc=2(读不到), 那不是「不一致」也不是「一致」。
    for key in ("aggregate_gross_profit", "gross_profit", "total_profit"):
        if isinstance(meta.get(key), (int, float)):
            resolver_profit = float(meta[key])
            break
    if resolver_profit is None:
        for kpi in (getattr(answer, "kpis", None) or []):
            label = str(kpi.get("label", "")) if isinstance(kpi, dict) else ""
            if "毛利" in label and "率" not in label:
                raw = str(kpi.get("value", "")).replace("¥", "").replace(",", "")
                try:
                    resolver_profit = float(raw)
                except ValueError:
                    pass
                break

    payload = {
        "date": _DAY.isoformat(),
        "factory_id": FACTORY,
        "executor_gross_profit": executor_profit,
        "resolver_gross_profit": resolver_profit,
        "diff": (None if None in (executor_profit, resolver_profit)
                 else round(executor_profit - resolver_profit, 2)),
    }
    _write(payload)
    print(json.dumps(payload, ensure_ascii=False, default=str))

    # 🔴 「两边都是 None」在数值上相等, 但那不是一致, 是**没量到**。
    if executor_profit is None or resolver_profit is None:
        print("INSTRUMENT: 有一条路没算出合计毛利 —— 本次读数作废")
        return 2
    if abs(payload["diff"]) > _TOLERANCE:
        print(f"PARITY BROKEN: 两条路差 {payload['diff']}")
        return 1
    return 0


if __name__ == "__main__":
    print(f"=== {datetime.datetime.now():%F %T} margin parity "
          f"factory={FACTORY} day={_DAY} ===")
    sys.exit(asyncio.run(main()))
