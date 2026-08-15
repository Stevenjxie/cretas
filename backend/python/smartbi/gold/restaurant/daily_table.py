"""日结菜品表 —— **纯呈现层**。

⛔ 本模块一个数都不算。输入是 `dish_margin.compute_dish_margins` 的返回
   (那是本仓自称的「唯一的结构化出数处」), 这里只做**排版**。
   ⛔ 尤其禁止在这里写 `revenue - food_cost` —— 毛利是 `metric_registry` 的
   `Derived`, presentation 层自己减一遍就是第二份口径(形态 D 必漂)。

## 三条已裁定的形状

### ① 三列: 营收 / 成本 / 毛利。⛔ 没有毛利率列

`marginRate` 对缺卡的菜恒等于 `1 - industryDefaultCostRatio` = 0.68 ——
**一个常数在回声**。把它列出来等于给每道缺卡的菜发一个看起来精确的假读数。

### ② 「毛利是 0」与「毛利算不出来」必须**看得出区别**

⛔ 缺卡的行不许填 `0` / 空白 / `-`：那三种在视觉上都读作「毛利为零」，
而真相是**这道菜没有成本卡, 算不出来**。⇒ 明写 `缺成本卡`。

⚠️ `compute_dish_margins` 对缺卡的菜**确实给了** `grossProfit`
(= 营收 × 0.68 的估算)。本模块**主动丢弃**那个数字 —— 这不是改口径,
是拒绝把一个常数回声当成这道菜的毛利显示给店长。

### ③ 差额必须能被解释, 且**恰好**等于两部分之和

    抬头毛利 − 表内毛利合计 == 缺卡部分 + 截断部分

对不上就是这张表在说谎。`explain_gap` 把它算出来, 闸断言它恒等。

## 套餐披露(来自 2026-08-15 的审计挂账)

表里的「菜品」行, **套餐和单品是混在一起的** —— 而客户明确说这两类要分开算。
系统里套餐实体不存在, 所以这件事**做不到**, ⇒ 那就说出来。
▎那一格挂着就要说它挂着 —— 这正是我们和通用 AI 的分界。
"""
from typing import Any, Dict, List, Optional, Tuple

#: 缺成本卡时成本/毛利两格的字面。⛔ 不用 0 / 空 / `-`(那三种都读作「毛利为零」)。
NO_COST_CARD = "缺成本卡"

#: 套餐披露。⛔ 不许省 —— B-1 可以做, 但不许声称它的菜品口径对套餐成立。
COMBO_DISCLOSURE = (
    "⚠️ 表里的「菜品」是 POS 下发的每一行, **套餐和单品混在一起**。"
    "系统里没有套餐这个实体，所以这两类分不开算。"
)


def _money(value: float) -> str:
    return f"¥{value:,.2f}"


def _row(cells: List[str]) -> str:
    return "| " + " | ".join(cells) + " |"


def explain_gap(data: Dict[str, Any], shown: List[Dict[str, Any]]) -> Dict[str, Any]:
    """把「抬头 vs 表内」的差额拆成**缺卡部分**和**截断部分**。

    ⛔ 这里不重算任何指标, 只是把 `data` 里已有的数分组相加。

    返回的 `identity_holds` 是这张表的自检: 差额若不等于两部分之和,
    说明我漏了一类菜, 表格在说谎。
    """
    dishes = data.get("dishes", []) or []
    shown_names = {d.get("name") for d in shown}

    head_profit = float(data.get("totalProfit") or 0.0)      # 抬头: 只含有卡的菜
    head_revenue = float(data.get("totalRevenue") or 0.0)    # 抬头: 全部菜

    shown_profit = sum(float(d.get("grossProfit") or 0.0) for d in shown if d.get("hasCost"))
    shown_revenue = sum(float(d.get("revenue") or 0.0) for d in shown)

    # 缺卡: 在表里显示了, 但没有成本卡 ⇒ 抬头毛利里本来就不含它们(抬头只算有卡的)
    no_card = [d for d in shown if not d.get("hasCost")]
    # 截断: 压根没显示的菜
    truncated = [d for d in dishes if d.get("name") not in shown_names]
    truncated_profit = sum(
        float(d.get("grossProfit") or 0.0) for d in truncated if d.get("hasCost")
    )
    truncated_revenue = sum(float(d.get("revenue") or 0.0) for d in truncated)

    profit_gap = head_profit - shown_profit
    # 🔴 抬头毛利只统计**有卡**的菜 ⇒ 缺卡的菜对这个差额贡献 **0**。
    #    差额全部来自被截断的、且**有卡**的那些菜。
    #    ⚠️ 这一条不直觉, 正因如此才要断言它 —— 写成「缺卡也算进差额」会差出一大截。
    identity_holds = abs(profit_gap - truncated_profit) < 0.01

    return {
        "headRevenue": head_revenue,
        "headProfit": head_profit,
        "shownRevenue": shown_revenue,
        "shownProfit": shown_profit,
        "noCardCount": len(no_card),
        "noCardRevenue": sum(float(d.get("revenue") or 0.0) for d in no_card),
        "truncatedCount": len(truncated),
        "truncatedRevenue": truncated_revenue,
        "truncatedProfit": truncated_profit,
        "profitGap": profit_gap,
        "identityHolds": identity_holds,
    }


def pick_rows(
    data: Dict[str, Any], *, top_n: int = 10
) -> Tuple[List[Dict[str, Any]], Optional[Dict[str, Any]]]:
    """按营收取前 N 道；另把**份数最多**的那道单独拎出来(若不在前 N 里)。

    ⚠️ 「卖得最多」按**份数**理解 —— 这是一个**推断**, ⛔ 不是用户说的原话。
       调用方必须把这条推断在文案里标出来, 不许当成用户的要求。
    """
    dishes = list(data.get("dishes", []) or [])
    by_revenue = sorted(dishes, key=lambda d: float(d.get("revenue") or 0.0), reverse=True)
    rows = by_revenue[:top_n]
    if not dishes:
        return rows, None
    qty_leader = max(dishes, key=lambda d: float(d.get("qty") or 0.0))
    if qty_leader in rows:
        return rows, None
    return rows, qty_leader


def render(data: Dict[str, Any], *, top_n: int = 10) -> str:
    """渲染成 markdown 表格 + 披露。载体是问答屏的 `MarkdownRenderer`。"""
    rows, qty_leader = pick_rows(data, top_n=top_n)
    if not rows:
        return ""

    lines = [
        _row(["菜品", "营收", "成本", "毛利"]),
        _row(["---", "---:", "---:", "---:"]),
    ]
    for d in rows:
        if d.get("hasCost"):
            cost, profit = _money(float(d["totalCost"])), _money(float(d["grossProfit"]))
        else:
            # ⛔ 丢弃 `compute_dish_margins` 给缺卡菜算的估算值(营收 × 0.68 的常数回声)
            cost = profit = NO_COST_CARD
        lines.append(_row([str(d.get("name") or "—"), _money(float(d.get("revenue") or 0.0)), cost, profit]))

    shown = list(rows)
    if qty_leader is not None:
        if qty_leader.get("hasCost"):
            cost = _money(float(qty_leader["totalCost"]))
            profit = _money(float(qty_leader["grossProfit"]))
        else:
            cost = profit = NO_COST_CARD
        qty = float(qty_leader.get("qty") or 0.0)
        lines.append(_row([
            f"{qty_leader.get('name')}（份数最多，{qty:g} 份）",
            _money(float(qty_leader.get("revenue") or 0.0)), cost, profit,
        ]))
        shown.append(qty_leader)

    gap = explain_gap(data, shown)
    notes = []
    if qty_leader is not None:
        notes.append(
            "ℹ️ 最后一行是按**份数**理解的「卖得最多」——"
            "这是我的推断，你要是指营收最高，那就是表里第一行。"
        )
    if gap["noCardCount"]:
        notes.append(
            f"· {gap['noCardCount']} 道菜没有成本卡（合计营收 {_money(gap['noCardRevenue'])}），"
            f"成本和毛利**算不出来** —— 表里写的是「{NO_COST_CARD}」，⛔ 不是 0。"
        )
    if gap["truncatedCount"]:
        notes.append(
            f"· 还有 {gap['truncatedCount']} 道菜没列进来（合计营收 {_money(gap['truncatedRevenue'])}、"
            f"毛利 {_money(gap['truncatedProfit'])}）—— 表里毛利合计与全店毛利差的 "
            f"{_money(gap['profitGap'])} 就是它们。"
        )
    # 🔴 限定语不许丢。客户读的**正是**这一层 —— 删了, 产品就是普通 BI 报表。
    #    ⛔ 不自己算覆盖率, 直接取 `compute_dish_margins` 已经算好的 `coverage`
    #    (形态 D: 自己再算一遍就是第二份口径)。
    cov = data.get("coverage") or {}
    if cov.get("totalDishCount"):
        ratio = float(cov.get("revenueRatio") or 0.0)
        notes.append(
            f"📐 这张表的成本/毛利只覆盖 {cov.get('dishCount')}/{cov.get('totalDishCount')} 道菜"
            f"（按营收算 {ratio * 100:.1f}%）。"
            f"**没覆盖的那部分不在毛利结论里** —— ⛔ 别把它读成全店毛利。"
        )
    basis = data.get("estimationBasis")
    if basis:
        notes.append(f"📐 {basis}")

    notes.append(COMBO_DISCLOSURE)

    # 🔴 披露之间必须是**空行**, ⛔ 不能只用 \n。
    #    markdown 把连续的非空行当**同一个段落**软换行 —— 四条披露会在店长屏幕上
    #    并成一坨。而 `·` / `ℹ️` / `⚠️` 都**不是** markdown 列表语法, 救不了它。
    #    ⚠️ 本仓踩过一模一样的: 8 张表格上线后并成一坨, 四个 PR + 两轮 85/85 电池
    #      + CI 全绿都没发现 —— 因为没有任何断言读的是**渲染后**的分段。
    return "\n".join(lines) + "\n\n" + "\n\n".join(notes)
