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


def _assign_subtree(func, name):
    """取 `func` 里对 `name` 的赋值子树。"""
    import ast
    import inspect

    tree = ast.parse(inspect.getsource(func))
    for n in ast.walk(tree):
        if isinstance(n, ast.Assign) and any(
                isinstance(t, ast.Name) and t.id == name for t in n.targets):
            return n
    raise AssertionError(f"找不到 {name} 的赋值")


def test_the_partial_month_caveat_is_produced_by_the_shared_helper():
    """🔴 「（截至N日）」由 `_month_label` 产出 —— 直接调它，**跑行为**。

    ⚠️ 补写的经过（留痕，别再走一遍）：第一版只测 `_markdown_table`
       （喂我自己造的行），变异「把限定语挪出格外」**全绿** ——
       那是本仓反复记的「测了 helper，没测接线」。
       第二版改成 AST 看「调用点有没有用到那两个名字」，**仍然全绿** ——
       变异写成 `("" if True else f"（截至…")`，名字留在死分支里。
       ⛔ 这时不该再去收窄 AST（形态 C⁸ 反过来：与其收窄判据，不如改结构），
       所以把行构造抽成 `_month_label`，判据从「结构」回到「行为」。
    """
    from datetime import date as _date

    from smartbi.gold.restaurant.restaurant_ops_router import _month_label

    d = _date(2026, 8, 18)
    assert _month_label("2026-08", is_last=True, partial=True, latest_day=d) == (
        "2026-08（截至18日）")
    # 阴性对照三条：不是最后一期 / 这一期是完整的 / 压根不知道到哪天
    assert _month_label("2026-07", is_last=False, partial=True, latest_day=d) == "2026-07"
    assert _month_label("2026-07", is_last=True, partial=False, latest_day=d) == "2026-07"
    assert _month_label("2026-07", is_last=True, partial=True, latest_day=None) == "2026-07"


def test_the_caveat_has_exactly_one_definition():
    """🔴 形态 D：这个限定语原先**有两份**（表格一份、图表 xAxis 一份）。

    📏 硬约束 8（改共享结构前后各数一次）：改之前 `git grep -c '（截至'`
       在 `resolve_trend_analysis` 里数到 **2 处手写**，改之后 **0 处手写
       + 1 处 helper**，两处都调 `_month_label`。

    ⛔ 这条闸钉的是「⛔ 不许再复制第三份」，不是「helper 算得对」
       （后者由上面那条行为断言守）。
    """
    import ast
    import inspect

    from smartbi.gold.restaurant import restaurant_ops_router as rr

    import re
    import textwrap

    src = textwrap.dedent(inspect.getsource(rr.resolve_trend_analysis))
    tree = ast.parse(src)

    # ⛔ 不 grep 源码文本 —— 第一版就是这么写的，把**注释里的举例**
    #    （`2026-08（截至18日）: ¥…`）也数了进去，闸当场误报。
    #    本仓记过三次同型（数注解不剥注释 / grep 数进 docstring / 数 `/ all_gross`）：
    #    ▎字符串计数量的是**文本**，AST 量的是**结构**。
    hand_written = [
        n.value for n in ast.walk(tree)
        if isinstance(n, ast.Constant) and isinstance(n.value, str)
        and re.search(r"（截至[^）]*日）", n.value)
    ]
    assert not hand_written, (
        f"又出现了手写的月份限定语 {hand_written} —— 用 `_month_label`，⛔ 别复制第三份"
    )

    calls = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
        and n.func.id == "_month_label"
    ]
    assert len(calls) == 2, f"_month_label 的调用点应为 2 处（表格 + 图表），拿到 {len(calls)}"


def test_the_bold_is_applied_at_the_call_site():
    """🔴 **调用点**必须给第一名加粗。

    ⚠️ 同上：变异「第一名不加粗」在只测 helper 的用例上全绿。
    """
    import ast

    from smartbi.gold.restaurant import restaurant_ops_router as rr

    node = _assign_subtree(rr.resolve_store_margin, "top_text")
    consts = {n.value for n in ast.walk(node)
              if isinstance(n, ast.Constant) and isinstance(n.value, str)}
    has_cmp = any(
        isinstance(n, ast.Compare) and isinstance(n.left, ast.Name)
        and n.left.id == "i"
        for n in ast.walk(node))
    assert "**" in consts, f"调用点没有加粗标记（常量 {sorted(consts)[:8]}）"
    assert has_cmp, "调用点没有「第几名」的判断 —— 加粗会落到所有行或都没有"


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
