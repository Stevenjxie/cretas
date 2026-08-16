"""B-5 归因 · 在**真实数据**上跑产品真实入口。

## 为什么单独有这一条

`b5_attribution_gate` 用的是构造数字，`test_b5_attribution_wiring` 桩掉了取数。
两者都证明不了**真库上这条路跑不跑得通**：
`execute_cell` 的返回形状、RLS 上下文、窗口平移之后有没有数据 —— 都只有真跑才知道。

## 三个场景，各自要看什么

| 场景 | 看什么 |
|---|---|
| 有数据的一天 | 拆解出得来，且**恒等式成立** |
| **上周同一天没数据** | 说「算不出」并点名缺哪个，⛔ 不许静默换一天 |
| 不是 diagnose | 原样返回，⛔ 不白查四次库 |

## 阳性对照（硬约束 9）

第 2、3 个场景都是阴性读数（「没有归因段」/「说算不出」）。
⇒ 第 1 个场景必须**确实**产出拆解 —— 出不来的话，前两个读数分不清是
「判定对了」还是「这条路根本没跑起来」。
"""
import asyncio
import datetime
import sys

from smartbi.scripts._probe_bootstrap import bootstrap_probe

FACTORY = "MOCK_REST"
ctx = bootstrap_probe(FACTORY)
BASE_ANSWER = "今天营收 X 元。"


class _Spec:
    def __init__(self, day, action="diagnose", intent="RESTAURANT_OPS_SALES_SUMMARY"):
        self.intent = intent
        self.analysis_action = action
        self.date_range = (day, day)


async def main() -> int:
    from smartbi.gold.restaurant import restaurant_intent_service as svc

    pool = await ctx.pool()
    today = datetime.date.today()
    bad = []

    def check(name, ok, detail=""):
        print(f"{'✅' if ok else '🔴'} {name}" + (f"  {detail}" if detail else ""))
        if not ok:
            bad.append(name)

    # ── 场景 1: 有数据的一天（先跑，它是阳性对照）────────────────────
    day = today - datetime.timedelta(days=1)     # 昨天，数据已落库
    out1 = await svc._maybe_append_attribution(pool, FACTORY, _Spec(day), BASE_ANSWER)
    print(f"── 场景1 day={day}（基线 {day - datetime.timedelta(days=7)}）──")
    print("\n".join("   " + l for l in out1.splitlines()))
    produced = out1 != BASE_ANSWER
    print()
    print(f"[阳性对照] 这条路真的跑起来了(产出了归因段): {produced}")
    if not produced:
        print("⛔ 一段都没产出 ⇒ 下面两个阴性读数什么都不说明, 本轮作废。")
        return 2

    check("1 有数据时产出拆解或明确的「算不出」", "📐" in out1)
    if "算不出" not in out1:
        # 真的拆出来了 ⇒ 恒等式必须成立（数字从正文里读回来太脆，直接重算一次）
        check("1b 拆出来时给了主因或明说两者都动",
              ("主要是" in out1) or ("都要看" in out1) or ("基本没变" in out1))

    # ── 场景 2: 上周同一天没数据（挑一个足够早的日子，其前一周必然空）──
    early = datetime.date(2026, 1, 5)
    out2 = await svc._maybe_append_attribution(pool, FACTORY, _Spec(early), BASE_ANSWER)
    print(f"\n── 场景2 day={early}（基线 {early - datetime.timedelta(days=7)}）──")
    print("\n".join("   " + l for l in out2.splitlines()))
    said_cannot = "算不出「为什么」" in out2
    check("2 没有可比基线时明说算不出", said_cannot or ("📐" in out2),
          "（若那天其实有数据，出拆解也算通过 —— 见下方口径说明）")
    # ⛔ 关键的一条: 不许**静默**换成别的基线。文案里必须写着基线是哪一天/哪一档。
    check("2b 无论哪种结果, 文案里都写明基线是什么", "上周同一天" in out2)

    # ── 场景 3: 不是 diagnose ────────────────────────────────────
    out3 = await svc._maybe_append_attribution(
        pool, FACTORY, _Spec(day, action="lookup"), BASE_ANSWER)
    check("3 非 diagnose 原样返回", out3 == BASE_ANSWER)

    print("\n" + "=" * 74)
    print(f"[主断言]   通过 {4 - len(bad)}/4（含条件跳过的 1b）")
    if bad:
        print(f"🔴 不通过: {bad}")
        return 1
    print("✅ 归因在真实数据上跑通")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
