"""日结表格的呈现规则 —— **CI 版**。

## 为什么要有这一份

这些断言原本只活在 `smartbi/scripts/b1_daily_table_gate.py`（手工探针）里。
用「破坏产品行为、只跑 CI 会跑的那部分」审计了一次，结果：

    CI 自动跑的部分: 抓到 1 / 8，**活下来 7**

也就是说下面这几条行为退化了，CI **不会红**：
无毛利率列 · 成本>营收点名 · 覆盖率限定语 · 缺卡状态。

▎本仓自己的教训：**闸不跑 = 没有闸**。手工探针在我手上全绿，
▎但一次退化不会有人知道。

⇒ 把承重的那几条搬进 `tests/`。⛔ 手工探针不删（它带完整输出，排查时有用），
  但**可信度以这一份为准**。

## 夹具形状（形态 B‴）

按 `compute_dish_margins` 的**真实**产出造：缺卡的菜给的是
`hasCost=False, totalCost=营收×0.32, grossProfit=营收×0.68, marginRate=0.68`。
⛔ 不能写成 `grossProfit: None` —— 那个形状真实那侧永远不产出。
"""
import pytest

from smartbi.gold.restaurant.daily_table import (
    COMBO_DISCLOSURE,
    NO_COST_CARD,
    explain_gap,
    pick_rows,
    render,
)

RATIO = 0.32


def _dish(name, revenue, qty, *, unit_cost=None):
    if unit_cost is None:                       # 缺卡：走行业默认成本率估算
        return {"name": name, "qty": qty, "revenue": revenue, "foodCostUnit": 0,
                "totalCost": round(revenue * RATIO, 2),
                "grossProfit": round(revenue * (1 - RATIO), 2),
                "marginRate": 1 - RATIO, "hasCost": False, "isEstimated": True}
    total_cost = unit_cost * qty
    gp = revenue - total_cost
    return {"name": name, "qty": qty, "revenue": revenue, "foodCostUnit": unit_cost,
            "totalCost": total_cost, "grossProfit": gp,
            "marginRate": gp / revenue if revenue else 0,
            "hasCost": True, "isEstimated": False}


def _data(dishes):
    with_cost = [d for d in dishes if d["hasCost"]]
    return {
        "windowDays": 1, "dishes": dishes,
        "totalRevenue": sum(d["revenue"] for d in dishes),
        "totalRevenueWithCost": sum(d["revenue"] for d in with_cost),
        "totalProfit": sum(d["grossProfit"] for d in with_cost),   # 抬头只算有卡的
        "industryDefaultCostRatio": RATIO,
        "coverage": {
            "dishCount": len(with_cost), "totalDishCount": len(dishes),
            "revenueRatio": (sum(d["revenue"] for d in with_cost)
                             / sum(d["revenue"] for d in dishes)) if dishes else 0,
        },
        "estimationBasis": ("无配方菜按行业默认成本率 32% 估算"
                            if len(with_cost) < len(dishes) else ""),
    }


MIXED = _data([
    _dish("罗氏虾", 5000.0, 100, unit_cost=20.0),
    _dish("凉拌木耳", 3000.0, 200, unit_cost=6.0),
    _dish("白灼菜心", 1200.0, 80),                    # 缺卡
    _dish("例汤", 800.0, 40, unit_cost=20.0),         # 毛利恰好为 0
    _dish("米饭", 600.0, 600, unit_cost=0.5),
    _dish("小菜A", 400.0, 20, unit_cost=5.0),
    _dish("小菜B", 300.0, 10, unit_cost=5.0),
    _dish("坏卡菜", 200.0, 10, unit_cost=81.0),       # 成本 > 营收
])
ALL_CARDED = _data([
    _dish("甲", 900.0, 10, unit_cost=10.0),
    _dish("乙", 500.0, 5, unit_cost=10.0),
])


@pytest.fixture(scope="module")
def out():
    return render(MIXED, top_n=4)


def test_identity_gap_equals_truncated_carded_profit():
    """🔴 承重墙：抬头毛利 − 表内合计 == 被截断且有卡的菜的毛利。

    ⚠️ 抬头只统计**有卡**的菜 ⇒ 缺卡的菜对差额贡献 0。
    这一条不直觉，正因如此才要断言它。
    """
    rows, leader = pick_rows(MIXED, top_n=4)
    gap = explain_gap(MIXED, rows + ([leader] if leader else []))
    assert gap["identityHolds"], gap


def test_missing_cost_card_is_named_not_zeroed(out):
    """缺卡的行写「缺成本卡」—— ⛔ 不是 0 / 空 / `-`（那三种都读作「毛利为零」）。

    🔴 断言查**字面**，⛔ 不查 `NO_COST_CARD` 常量。
    第一版写的是 `f"| {NO_COST_CARD} | {NO_COST_CARD} |" in out` —— 而把常量
    改成 `"¥0.00"` 的变异**一条都不红**：断言跟着常量一起变，两边同源，**恒成立**。
    实测那次变异下表格长这样，而闸全绿：

        | 白灼菜心 | ¥1,200.00 | ¥0.00 | ¥0.00 |

    ⚠️ 「左右两边来源相同 ⇒ 断言永远红不了」，本仓记过的同一族。
    """
    assert "| 缺成本卡 | 缺成本卡 |" in out, out
    # 阴性对照：那一行**不许**同时出现金额形态的成本/毛利
    row = next(l for l in out.splitlines() if "白灼菜心" in l)
    assert "¥0.00" not in row, f"缺卡行出现了金额, 会被读成「毛利为零」: {row}"


def test_a_real_zero_margin_still_shows_as_money(out):
    """「毛利是 0」与「算不出来」必须**看得出区别**。"""
    assert "| ¥0.00 |" in out, out


def test_no_margin_rate_column(out):
    """⛔ 没有毛利率列 —— 对缺卡菜恒等于 `1−0.32`，是**一个常数在回声**。"""
    assert "0.68" not in out and "68%" not in out and "毛利率" not in out, out


def test_coverage_qualifier_is_present(out):
    """限定语不许丢 —— 客户读的**正是**这一层，删了就是普通 BI 报表。"""
    cov = MIXED["coverage"]
    assert f"{cov['dishCount']}/{cov['totalDishCount']}" in out, out
    assert "没覆盖的那部分不在毛利结论里" in out, out


def test_cost_above_revenue_is_called_out_with_a_next_action(out):
    """成本>营收的菜要**点名**并给下一步动作（能做决定 > 数准）。"""
    assert "成本比营收还高" in out and "坏卡菜" in out, out
    assert "先核这几张成本卡" in out, out


def test_combo_disclosure_is_present(out):
    assert COMBO_DISCLOSURE in out, out


def test_each_disclosure_is_its_own_paragraph(out):
    """markdown 把连续非空行并成一个段落 —— 披露之间必须是**空行**。

    ⚠️ 本仓踩过：8 张表格上线后并成一坨，四个 PR + 两轮 85/85 + CI 全绿都没发现，
       因为没有任何断言读的是**渲染后的分段**。
    """
    body = out.split("|\n\n", 1)[1]
    runs = [b for b in body.split("\n\n")
            if len([l for l in b.split("\n") if l.strip()]) > 1]
    assert not runs, f"有 {len(runs)} 段粘在一起:\n{runs}"


def test_qty_note_is_not_inside_the_table_cell(out):
    """份数注记必须在披露里，⛔ 不在菜名格里。

    ⚠️ 实测：塞进菜名格会把菜品列撑到 420px 屏的 55%，
       把最右边的**毛利列挤出屏外** —— 而毛利是这张表的全部意义。
    """
    table_part = out.split("\n\n", 1)[0]
    assert "份" not in table_part, table_part
    assert "推断" in out and "份数" in out


# ── 阳性对照：证明上面那些阴性断言不是恒真式 ──────────────────────────
def test_positive_controls_all_carded_and_healthy():
    """全部有卡、没有坏卡、毛利非零时，⛔ 上面几句话都不该出现。

    少了这一条，「缺成本卡不出现」「不喊成本比营收高」在
    **任何**输入下都成立 —— 那就不区分好坏了。
    """
    carded = render(ALL_CARDED, top_n=10)
    assert NO_COST_CARD not in carded, carded
    assert "成本比营收还高" not in carded, carded
    assert "¥0.00" not in carded, carded
    # 而它**确实**渲染出了表格（否则上面三条只是「什么都没渲染」）
    assert "| 菜品 | 营收 | 成本 | 毛利 |" in carded, carded
