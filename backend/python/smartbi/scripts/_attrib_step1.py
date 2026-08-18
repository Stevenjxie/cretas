# -*- coding: utf-8 -*-
"""第 1 步只读普查: 归因七步链现在走到第几步 / 计算式指代是不是已经做到了。

⛔ 不改任何东西。⛔ 不调 LLM（省钱且确定性）。
🔑 三个读数, 每个都带**来源标记**:
   A  `plan_dimensions` 对目标问句点亮了哪些维度  —— 纯函数, 无 DB
   B  每个租户的 laggard 缺口分布              —— 真数据, 决定阈值
   C  外部维度(商场活动/周边/竞品/物理客流)有没有数据 —— 决定「反问」该不该出现

⚠️ 阳性对照: B 组每个租户必须读到 ≥2 家店; 读到 0 家 ⇒ 那条读数作废(打 rc=2),
   ⛔ 不能把「我没读到」写成「这个租户没有门店差异」。
"""
from __future__ import annotations

import asyncio
import os
import sys
from datetime import date, timedelta

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from smartbi.scripts._probe_bootstrap import bootstrap_probe  # noqa: E402

ctx = bootstrap_probe("MOCK_REST")

from smartbi.agent.factbook import compute_store_attribution  # noqa: E402
from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine  # noqa: E402
from smartbi.gold import store_comparison  # noqa: E402
from smartbi.gold.restaurant.restaurant_ops_router import (  # noqa: E402
    demo_data_factory_for_code,
)
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

TENANTS = ["MOCK_REST", "DEMO_REST", "RES_3101_009", "R_GML_DEMO", "R_XMX_CHAIN"]

QUESTIONS = [
    "我要不要关掉最差的那家店",
    "最差的那家店是哪家",
    "哪家店拖后腿",
    "十六家店里头哪家最不行,是没人来还是客人花的钱少",
    "为什么这个月营业额下滑",
    "那最差的呢",
]

#: 能解释「为什么人少了」而系统**看不到线下**的那几个维度。
EXTERNAL_BLIND = ("physical_traffic", "mall_activity", "nearby_event", "competitor")


def part_a() -> None:
    print("=" * 78)
    print("A  plan_dimensions 读数 (纯函数, 无 DB, 无 LLM)")
    print("=" * 78)
    engine = ComprehensiveSynthesisEngine.__new__(ComprehensiveSynthesisEngine)
    for q in QUESTIONS:
        for has_history in (False, True):
            plan = ComprehensiveSynthesisEngine.plan_dimensions(
                engine, q, has_history=has_history)
            on = sorted(k for k, v in plan.items() if v is True)
            print(f"  q={q!r} history={has_history}")
            print(f"    attribution={plan['attribution']}  mode={plan['analysis_mode']}"
                  f"  auto_expand={plan['auto_expand']}")
            print(f"    点亮: {', '.join(on) or '<无>'}")
    print()


async def _pull(pool, fid, start, end):
    """返回 (门店行, 原始返回的形状标记) —— 形状要打出来, ⛔ 不靠猜。"""
    set_factory_id(fid)
    sales_factory = demo_data_factory_for_code("RESTAURANT_OPS_SALES_SUMMARY", fid)
    rows = await store_comparison(pool, sales_factory, (start, end))
    if isinstance(rows, dict):
        shape = f"dict keys={sorted(rows.keys())}"
        items = rows.get("stores") or rows.get("items") or rows.get("rows") or []
    elif isinstance(rows, list):
        shape = f"list len={len(rows)}"
        items = rows
    else:
        shape = f"{type(rows).__name__}"
        items = []
    return items, shape, sales_factory


async def part_b(pool) -> int:
    print("=" * 78)
    print("B  每个租户的 laggard 缺口分布 (真数据) —— 决定阈值")
    print("=" * 78)
    end = date.today() - timedelta(days=1)
    windows = [
        ("近30天", end - timedelta(days=29), end),
        ("近365天", end - timedelta(days=364), end),
    ]
    good = 0
    for fid in TENANTS:
        items = []
        used = None
        for label, start, wend in windows:
            try:
                items, shape, sales_factory = await _pull(pool, fid, start, wend)
            except Exception as exc:  # noqa: BLE001
                print(f"  {fid:<14} [{label}] 读取失败: {type(exc).__name__}: "
                      f"{str(exc)[:90]}")
                continue
            print(f"  {fid:<14} [{label} {start}~{wend}] gold_factory={sales_factory!r} "
                  f"{shape} 门店行={len(items)}")
            if items:
                used = label
                break
        if not items:
            print(f"  {fid:<14} 两个窗口都是 0 行 ⇒ 本条读数作废"
                  f" (⛔ 不写成「没有门店差异」)")
            continue
        att = compute_store_attribution(items)
        if not att or att.get("no_data"):
            print(f"  {fid:<14} 门店行={len(items)} 但 attribution no_data")
            continue
        good += 1
        lg = att["laggard"]
        bench = float(att["bench_revenue"] or 0)
        delta = float(lg["delta_revenue"] or 0)
        gap = abs(delta) / bench if bench else 0.0
        # 「咬得紧不紧」: 最差 vs 最好 的营收比
        revs = sorted(float(s["revenue"]) for s in att["stores"])
        spread = (revs[-1] - revs[0]) / revs[-1] if revs[-1] else 0.0
        print(f"  {fid:<14} 窗口={used}  n={att['n_stores']:<4} bench=¥{bench:,.0f}")
        print(f"      laggard={lg['store_name']!r} Δ=¥{delta:,.0f}  "
              f"缺口={gap * 100:.1f}%  主因={att['primary_cause']}")
        print(f"      客流效应=¥{lg['traffic_effect']:,.0f}  "
              f"客单价效应=¥{lg['ticket_effect']:,.0f}")
        print(f"      最差vs最好 差 {spread * 100:.1f}%   异常低客流店={att['anomalies']}")
    print()
    if good == 0:
        print("rc=2  一个租户都没读到门店数据 —— 仪器可能没连上, 本轮读数作废")
        return 2
    print(f"  有效租户: {good}/{len(TENANTS)}")
    return 0


async def main() -> int:
    part_a()
    pool = await ctx.pool()
    rc = await part_b(pool)
    return rc


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
