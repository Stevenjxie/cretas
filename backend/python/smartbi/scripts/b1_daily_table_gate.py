"""B-1 第二步闸 · 日结表格的呈现规则。

## 夹具的形状必须是真实 SQL 出得来的(形态 B‴)

`compute_dish_margins` 对**缺卡**的菜给的是:
    hasCost=False, totalCost=营收×0.32, grossProfit=营收×0.68, marginRate=0.68
⛔ 夹具**不能**写成 `grossProfit: None` —— 那个形状真实那侧永远不产出,
   照它写出来的断言在生产上守不到任何东西。

## 四条断言各自守什么

1. **恒等式**: 抬头毛利 − 表内毛利合计 == 被截断且有卡的菜的毛利。
   对不上就是这张表在说谎(漏了一类菜)。
2. **「毛利是 0」vs「算不出来」看得出区别**: 前者 `¥0.00`, 后者 `缺成本卡`。
   ⚠️ 这条是本轮最容易写错的 —— 缺卡填 0 在代码里最省事, 在店长眼里是**假读数**。
3. **⛔ 没有毛利率**: `0.68` / `68%` 一次都不许出现(常数回声)。
4. **套餐披露在**: 挂账文档 §5 明写「表格必须带一条披露」。

## 阳性对照(硬约束 9)

断言 2 和 3 都是**阴性**的(某个东西**不该**出现)。
⇒ 各配一条证明它出得来: 全部有卡的夹具里 `缺成本卡` 一次都不出现(说明那个字面
   是被数据驱动的, 不是恒不出现); 而 `¥0.00` 在毛利为零的行上确实出得来。
"""
import sys

from smartbi.gold.restaurant.daily_table import (
    COMBO_DISCLOSURE,
    NO_COST_CARD,
    explain_gap,
    pick_rows,
    render,
)

RATIO = 0.32


def _dish(name, revenue, qty, *, unit_cost=None):
    """按 `compute_dish_margins` 的**真实**产出形状造行。"""
    if unit_cost is None:                       # 缺卡: 走行业默认成本率估算
        return {
            "name": name, "qty": qty, "revenue": revenue, "foodCostUnit": 0,
            "totalCost": round(revenue * RATIO, 2),
            "grossProfit": round(revenue * (1 - RATIO), 2),
            "marginRate": 1 - RATIO, "hasCost": False, "isEstimated": True,
        }
    total_cost = unit_cost * qty
    gp = revenue - total_cost
    return {
        "name": name, "qty": qty, "revenue": revenue, "foodCostUnit": unit_cost,
        "totalCost": total_cost, "grossProfit": gp,
        "marginRate": gp / revenue if revenue else 0,
        "hasCost": True, "isEstimated": False,
    }


def _data(dishes):
    with_cost = [d for d in dishes if d["hasCost"]]
    return {
        "windowDays": 1,
        "dishes": dishes,
        "totalRevenue": sum(d["revenue"] for d in dishes),
        "totalRevenueWithCost": sum(d["revenue"] for d in with_cost),
        "totalProfit": sum(d["grossProfit"] for d in with_cost),   # 抬头只算有卡的
        "industryDefaultCostRatio": RATIO,
        # 🔴 真实那侧确实产出这两个 —— 夹具不带就等于替产品把限定语删了,
        #    断言会在一个「没有限定语的世界」里全绿(形态 B‴: 桩的形状要真实那侧给得出)
        "coverage": {
            "dishCount": len(with_cost),
            "totalDishCount": len(dishes),
            "revenueRatio": (sum(d["revenue"] for d in with_cost)
                             / sum(d["revenue"] for d in dishes)) if dishes else 0,
        },
        "estimationBasis": ("无配方菜按行业默认成本率 32% 估算"
                            if len(with_cost) < len(dishes) else ""),
    }


MIXED = _data([
    _dish("罗氏虾",   5000.0, 100, unit_cost=20.0),
    _dish("凉拌木耳", 3000.0, 200, unit_cost=6.0),
    _dish("白灼菜心", 1200.0,  80),                       # 缺卡
    _dish("例汤",      800.0,  40, unit_cost=20.0),       # 毛利恰好为 0
    _dish("米饭",      600.0, 600, unit_cost=0.5),
    _dish("小菜A",     400.0,  20, unit_cost=5.0),
    _dish("小菜B",     300.0,  10, unit_cost=5.0),
    # 🔴 坏成本卡: 成本 > 营收。prod 真跑时 MOCK_REST 上真的有(那张 ¥81.00 米饭卡),
    #    被截断的 4 道菜合计毛利 −¥115,674 而营收只有 ¥77,147。
    _dish("坏卡菜",    200.0,  10, unit_cost=81.0),
])
ALL_CARDED = _data([
    _dish("甲", 900.0, 10, unit_cost=10.0),
    _dish("乙", 500.0,  5, unit_cost=10.0),
])


def main() -> int:
    bad = []

    def check(name, ok, detail=""):
        print(f"{'✅' if ok else '🔴'} {name}" + (f"  {detail}" if detail else ""))
        if not ok:
            bad.append(name)

    _c = MIXED["coverage"]
    _expected_cov = f"{_c['dishCount']}/{_c['totalDishCount']}"
    out = render(MIXED, top_n=4)
    print("── 渲染原文(top_n=4) ──")
    print(out)
    print("── 原文结束 ──\n")

    rows, leader = pick_rows(MIXED, top_n=4)
    gap = explain_gap(MIXED, rows + ([leader] if leader else []))

    check(
        "1 恒等式 抬头毛利−表内合计 == 被截断且有卡的毛利",
        gap["identityHolds"],
        f"gap={gap['profitGap']:.2f} truncatedProfit={gap['truncatedProfit']:.2f}",
    )
    check(
        "2a 缺卡的行写「缺成本卡」",
        f"| {NO_COST_CARD} | {NO_COST_CARD} |" in out,
    )
    check(
        "2b 毛利真的为 0 的行写 ¥0.00（与「算不出来」看得出区别）",
        "| ¥0.00 |" in out,
    )
    check("3a 不出现毛利率 0.68", "0.68" not in out)
    check("3b 不出现 68%", "68%" not in out and "68.0%" not in out)
    check("4 套餐披露在", COMBO_DISCLOSURE in out)
    # 🔴 这条原本写的是 `"份数最多" in out` —— 守**字面**。
    #    而那个字面正是要从菜名格里拿掉的东西(它在 420px 手机上把菜品列撑到 55%,
    #    把毛利列挤出屏外)。⇒ 断言从「守字面」抬到「守性质」+ 补一条阴性对照。
    _table_part = out.split("\n\n", 1)[0]
    check(
        "5 份数最多的那道标了「推断」，且份数**不在表格单元格里**",
        "推断" in out and "份数" in out          # 性质: 推断被标出来了
        and "份" not in _table_part,             # 阴性: 份数注记不许留在表格里撑宽
        "份数注记已移出表格",
    )

    check(
        "7 覆盖率限定语在（客户读的正是这一层，删了就是普通 BI 报表）",
        # ⛔ 不把覆盖率写死成 "6/7" —— 第一版就是那么写的, 往夹具里加一道菜
        #    (坏卡菜)之后当场红, 而**产品完全正确**。断言被夹具的偶然形状绑死了。
        #    ⇒ 从 data 自己算出该出现的字面, 断言「表里说的 == 数据说的」。
        "没覆盖的那部分不在毛利结论里" in out and _expected_cov in out,
        f"coverage {_expected_cov}",
    )

    check(
        "8 成本>营收的菜被点名 + 给了下一步动作（能做决定 > 数准）",
        "成本比营收还高" in out and "坏卡菜" in out and "先核这几张成本卡" in out,
    )

    # 🔴 断言的是**渲染后的分段**, 不是文案内容。
    #    markdown 把连续非空行并成一个段落 —— 四条披露若只用 \n 分隔, 在店长屏幕上
    #    是一坨。本仓踩过同形(8 张表格并成一坨, CI 全绿没发现)。
    #    ⛔ `·` / `ℹ️` / `⚠️` 都不是 markdown 列表语法, 指望它们分行是错的。
    body = out.split("|\n\n", 1)[1] if "|\n\n" in out else out
    blocks = [b for b in body.split("\n\n") if b.strip()]
    runs = [b for b in blocks if len([l for l in b.split("\n") if l.strip()]) > 1]
    check(
        "6 每条披露自成一段(markdown 不会把它们并成一坨)",
        not runs,
        f"{len(blocks)} 段, 粘连的段落 {len(runs)} 个",
    )

    print("\n" + "=" * 78)
    # ── 阳性对照 ──────────────────────────────────────────────────────────
    carded = render(ALL_CARDED, top_n=10)
    ctrl_a = NO_COST_CARD not in carded
    ctrl_b = "¥0.00" not in carded
    print(f"[阳性对照 a] 全部有卡时「{NO_COST_CARD}」不出现: {ctrl_a}")
    ctrl_c = "成本比营收还高" not in carded
    print(f"[阳性对照 b] 没有零毛利行时 ¥0.00 不出现: {ctrl_b}")
    print(f"[阳性对照 c] 全是好卡时不喊「成本比营收还高」: {ctrl_c}")
    if not (ctrl_a and ctrl_b and ctrl_c):
        print("⛔ 那两个字面与数据无关(恒出现) ⇒ 断言 2a/2b 是恒真式, 本轮作废。")
        return 2

    print(f"[主断言]   {10 - len(bad)}/10 条通过")
    if bad:
        print(f"\n🔴 不通过: {bad}")
        return 1
    print("=" * 78)
    print("✅ 呈现规则全部成立")
    return 0


if __name__ == "__main__":
    sys.exit(main())
