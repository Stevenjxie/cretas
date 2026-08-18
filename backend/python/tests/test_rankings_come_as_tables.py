"""排行和逐期对比要给**表格** —— 四个数挤在一行文字里，老板没法比。

交付定义④：排行、对比、构成这类，文字说不清楚，就给表。

## 📏 缺陷（prod 实测，MOCK_REST，24 问句探针 `restaurant_delivery_definitions_probe`）

该给表的 11 条里只有 8 条给了。逐条读那两条：

```
「哪家店成本最高」 STORE_MARGIN 1227 字
  毛利前 10 名门店:
   1. **模拟·徐汇美罗城店**: 已覆盖营收 ¥1,446,000.00 / 毛利 ¥983,290.46(68.0%), 5845 单
   2. 模拟·陆家嘴正大店: 已覆盖营收 ¥1,449,468.00 / 毛利 ¥982,266.71(67.8%), 5845 单
   …                                    ← 10 行 × 4 列的排行榜，写成了编号列表

「营收趋势怎么样」 TREND_ANALYSIS 511 字
  各月营收:
    2026-07: ¥8,584,383.32
    2026-08（截至18日）: ¥12,443,254.32   ← 逐期对比，写成了缩进列表
```

⛔ 两处都走 `_markdown_table`（**全站唯一一处表格拼装**），不手拼竖线。
"""
from __future__ import annotations

import re

from smartbi.gold.restaurant.restaurant_ops_router import _markdown_table

#: GFM 表格的分隔行。⛔ 不用「有没有 `|`」—— 正文里的竖线到处都是。
_SEP = re.compile(r"\|\s*:?-{3,}")


def _rows(store_n=3):
    return [
        {"name": "模拟·徐汇美罗城店", "revenue_with_cost": 1446000.0,
         "gross_profit": 983290.46, "margin_rate": 0.680, "bills": 5845},
        {"name": "模拟·陆家嘴正大店", "revenue_with_cost": 1449468.0,
         "gross_profit": 982266.71, "margin_rate": 0.678, "bills": 5845},
        {"name": "模拟·普陀真如社区店", "revenue_with_cost": 1437730.0,
         "gross_profit": 974965.48, "margin_rate": 0.678, "bills": 5861},
    ][:store_n]


# ── 承重：那两处**在源码里**真的走了表格拼装 ────────────────────────────────

def test_store_margin_ranking_is_rendered_as_a_table():
    """🔴 `top_text` 必须由 `_markdown_table` 产出，⛔ 不是编号列表。

    ⛔ 用 AST 看**调用**，不 grep（注释里就写着 `_markdown_table`）。
    """
    import ast
    import inspect

    from smartbi.gold.restaurant import restaurant_ops_router as rr

    src = inspect.getsource(rr.resolve_store_margin)
    tree = ast.parse(src)
    assigns = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.Assign)
        and any(isinstance(t, ast.Name) and t.id == "top_text" for t in n.targets)
    ]
    assert assigns, "找不到 top_text 的赋值 —— 用例没打中"
    called = {
        c.func.id
        for a in assigns for c in ast.walk(a)
        if isinstance(c, ast.Call) and isinstance(c.func, ast.Name)
    }
    assert "_markdown_table" in called, (
        f"门店排行没走表格拼装（拿到 {sorted(called)}）—— 那是 10 行 × 4 列的排行榜"
    )


def test_monthly_revenue_is_rendered_as_a_table():
    """🔴 `month_list_text` 同上 —— 逐期对比是竖着比的数。"""
    import ast
    import inspect

    from smartbi.gold.restaurant import restaurant_ops_router as rr

    src = inspect.getsource(rr.resolve_trend_analysis)
    tree = ast.parse(src)
    assigns = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.Assign)
        and any(isinstance(t, ast.Name) and t.id == "month_list_text"
                for t in n.targets)
    ]
    assert assigns, "找不到 month_list_text 的赋值"
    called = {
        c.func.id
        for a in assigns for c in ast.walk(a)
        if isinstance(c, ast.Call) and isinstance(c.func, ast.Name)
    }
    assert "_markdown_table" in called, (
        f"各月营收没走表格拼装（拿到 {sorted(called)}）"
    )


# ── 承重：表格本身长得对 ────────────────────────────────────────────────────

def test_the_table_has_one_column_per_number():
    """四个数各占一列 —— 那是「文字说不清楚」的那部分。"""
    out = "\n".join(_markdown_table(
        ["#", "门店", "已覆盖营收", "毛利", "毛利率", "订单"],
        [["1", "**A店**", "¥1,446,000.00", "¥983,290.46", "68.0%", "5,845"]],
        right_align={2, 3, 4, 5}))
    assert _SEP.search(out), "不是 GFM 表格\n" + out
    assert out.count("|") >= 12, out
    for cell in ("已覆盖营收", "毛利率", "订单", "¥983,290.46", "68.0%"):
        assert cell in out, cell


def test_the_first_place_stays_bold():
    """⚠️ 第一名的加粗要留着 —— 那是**点名**，老板最先看的东西。"""
    out = "\n".join(_markdown_table(
        ["#", "门店"], [["1", "**A店**"], ["2", "B店"]]))
    assert "**A店**" in out and "**B店**" not in out, out


# ── 🔴 阴性对照：那个限定语必须留在行内 ────────────────────────────────────

def test_the_partial_month_caveat_stays_in_the_cell():
    """🔴「（截至N日）」必须留在**月份那一格**里。

    它说明最后一期是**未完结**的。挪走或省掉，老板会把一个半月的数
    当成整月去比 —— 本仓记过同型：▎**每个数都对，合起来是谎**。
    """
    out = "\n".join(_markdown_table(
        ["月份", "营收"],
        [["2026-07", "¥8,584,383.32"],
         ["2026-08（截至18日）", "¥12,443,254.32"]],
        right_align={1}))
    line = [ln for ln in out.splitlines() if "2026-08" in ln]
    assert line, out
    assert "（截至18日）" in line[0], (
        "限定语不在月份那一行里 —— 老板会拿半个月的数当整月比\n" + out
    )


def test_an_empty_ranking_says_so_instead_of_an_empty_table():
    """阴性对照：没有可排名的门店时 ⛔ 不许给一张空表。

    ▎一张只有表头的表，读起来像「有数据但都是 0」。
    """
    import inspect

    from smartbi.gold.restaurant import restaurant_ops_router as rr

    src = inspect.getsource(rr.resolve_store_margin)
    assert "暂无成本完整、可参与毛利排名的门店" in src, (
        "空清单的兜底文案没了 —— 会退化成一张空表"
    )
