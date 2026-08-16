"""B-5 归因闸 · 拆解的恒等式与三种结论。

## 五档断言

1. **恒等式** —— 客流 + 客单价 + 交叉项 **恰好** == ΔR（⛔ 不许「差不多」）
2. **主因判定** —— 只有客流动 ⇒ 说客流；只有客单价动 ⇒ 说客单价
3. **⛔ 不许编主因** —— 两个都在动（交叉项大）时必须说「都要看」，
   ⚠️ 这一档是本闸的重点：**编一个主因是最容易也最有害的错**，
   而它在第 1 档（恒等式）上**完全绿**
4. **拆不出来时明说** —— 缺输入 / 单量为 0，⛔ 不许退回「今天营收 X 元」
5. **None ⛔ 不兜底成 0** —— 「没取到」和「没营业」对归因是相反的意思

## 阳性对照（硬约束 9）

第 3、4 档都是阴性的（某句话**不该**出现）。⇒ 配一条证明它出得来：
同一套渲染在纯客流场景下**确实**打出「主要是客流」。
出不来的话，「没说主因」就是恒真式。
"""
import sys

from smartbi.gold.restaurant.attribution import decompose, render

BASE = "上周同一天"


def main() -> int:
    bad = []

    def check(name, ok, detail=""):
        print(f"{'✅' if ok else '🔴'} {name}" + (f"  {detail}" if detail else ""))
        if not ok:
            bad.append(name)

    # ── 1 恒等式（四个形状，含涨/跌/两者都动）────────────────────────
    cases = [
        ("纯客流下降", dict(revenue_now=8000.0, orders_now=80.0,
                            revenue_base=10000.0, orders_base=100.0)),
        ("纯客单价下降", dict(revenue_now=8000.0, orders_now=100.0,
                              revenue_base=10000.0, orders_base=100.0)),
        # 🔴 第一版这里写的是 (6300, 90) —— 我以为那是「两者都动」, 实测
        #    交叉项只占 ΔR 的 **8.1%**, 是清清楚楚的客单价主导。闸判对了, **夹具错了**。
        #    真正「两者都动到不能归因」是大促形态: 人多了不少、客单价大跌,
        #    两个贡献互相抵消, 于是交叉项相对 ΔR 变得很大。
        ("两者都动(大促形态)", dict(revenue_now=9000.0, orders_now=150.0,
                                    revenue_base=10000.0, orders_base=100.0)),
        ("营收上涨", dict(revenue_now=13200.0, orders_now=110.0,
                          revenue_base=10000.0, orders_base=100.0)),
    ]
    for label, kw in cases:
        d = decompose(**kw)
        s = d["traffic_contribution"] + d["ticket_contribution"] + d["cross_term"]
        check(f"1 恒等式 [{label}]", d["identityHolds"],
              f"三项和={s:.2f} ΔR={d['delta_revenue']:.2f}")

    # ── 2 主因判定 ────────────────────────────────────────────────
    d_traffic = decompose(**cases[0][1])
    d_ticket = decompose(**cases[1][1])
    check("2a 纯客流 ⇒ driver=traffic", d_traffic["driver"] == "traffic",
          f"实得 {d_traffic['driver']}")
    check("2b 纯客单价 ⇒ driver=ticket", d_ticket["driver"] == "ticket",
          f"实得 {d_ticket['driver']}")

    # ── 3 ⛔ 不许编主因 ───────────────────────────────────────────
    d_both = decompose(**cases[2][1])
    txt_both = render(d_both, base_label=BASE)
    check("3a 两者都动 ⇒ driver 为空（⛔ 不编）", d_both["driver"] is None,
          f"交叉项 {d_both['cross_term']:.2f} / ΔR {d_both['delta_revenue']:.2f}")
    check("3b 两者都动的文案说「都要看」，且**不出现**「主要是」",
          "都要看" in txt_both and "主要是" not in txt_both)

    # ── 4 拆不出来时明说 ─────────────────────────────────────────
    d_missing = decompose(revenue_now=100.0, orders_now=None,
                          revenue_base=200.0, orders_base=10.0)
    t_missing = render(d_missing, base_label=BASE)
    # ⚠️ 用 .get 取 —— 第一版写 d_missing["missing"], 变异 S3 把那个键去掉之后
    #    闸**崩在 KeyError**, rc=1 但一条 🔴 都没打。
    #    ⇒ 那次「变异红了」红的是异常不是断言, 什么都不说明(形态 C‴)。
    check("4a 缺输入 ⇒ ok=False 且点名缺哪个", (not d_missing["ok"])
          and "今天单量" in (d_missing.get("missing") or []))
    check("4b 缺输入的文案明说算不出，⛔ 不退回报数",
          "算不出「为什么」" in t_missing and "说不了为什么" in t_missing)
    d_zero = decompose(revenue_now=0.0, orders_now=0.0,
                       revenue_base=1000.0, orders_base=10.0)
    check("4c 单量为 0 ⇒ 说客单价没定义", (not d_zero["ok"])
          and "客单价没有定义" in render(d_zero, base_label=BASE))

    # ── 5 None ⛔ 不兜底成 0 ──────────────────────────────────────
    d_none = decompose(revenue_now=None, orders_now=10.0,
                       revenue_base=1000.0, orders_base=10.0)
    check("5 None 不被当成 0（「没取到」≠「没营业」）",
          (not d_none["ok"]) and d_none["reason"] == "missing_inputs")

    print("\n" + "=" * 74)
    # ── 阳性对照 ────────────────────────────────────────────────
    txt_traffic = render(d_traffic, base_label=BASE)
    ctrl = "主要是客流" in txt_traffic
    print(f"[阳性对照] 纯客流场景下「主要是客流」**确实**出得来: {ctrl}")
    if not ctrl:
        print("⛔ 这句话在任何输入下都不出现 ⇒ 第 3b 档是恒真式, 本轮读数作废。")
        return 2
    ctrl2 = "算不出「为什么」" not in txt_traffic
    print(f"[阳性对照] 正常场景下**不**说「算不出」: {ctrl2}")
    if not ctrl2:
        print("⛔ 拒答文案恒出现 ⇒ 第 4b 档不区分好坏。")
        return 2

    print("\n── 纯客流场景的完整文案 ──")
    print("\n".join("   " + l for l in txt_traffic.splitlines()))

    total = 4 + 2 + 2 + 3 + 1
    print(f"\n[主断言]   {total - len(bad)}/{total} 条通过")
    if bad:
        print(f"\n🔴 不通过: {bad}")
        return 1
    print("=" * 74)
    print("✅ 归因拆解全部成立")
    return 0


if __name__ == "__main__":
    sys.exit(main())
