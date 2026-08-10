"""排行类答案用 markdown 表格输出，且全站只有一处表格拼装。

## 为什么改

排行本来就是表格数据（名次 / 名称 / 销量 / 营收），过去输出成编号列表：

    1. **招牌青花椒味(单人份)** — 销量 5,605.87 份、营收 ¥322,920.53
    2. 招牌青花椒鱼可乐单人套餐 — 销量 3,050.3 份、营收 ¥194,995.79

列表形态让人**没法竖着比数**。前端 `MarkdownRenderer` 用的是
react-native-markdown-display（markdown-it），GFM 表格开箱即用，
`table/thead/th/tr/td` 样式早就写好了——缺口一直在后端。

## 为什么是一个函数而不是各写一遍

排行/对比类答案有十几处。各写一遍就是十几份格式：改一次要改十几处，
而漏掉的那处**不报错**，只是长得跟别处不一样。

⛔ 本文件的承重断言就是「只有一处拼装」——它防的不是渲染错，是**格式分叉**。

## 两个静默失效点

1. 单元格里的 `|` 不转义，一个菜名就能把整张表的列数冲乱——而 markdown 表格
   错列**不报错**，只是渲染成一坨。
2. 表格前没有空行，markdown-it 会把它并进上一段当普通文字——**同样不报错**。
"""
from __future__ import annotations

import inspect
import re

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import _markdown_table


def test_renders_a_well_formed_gfm_table():
    out = _markdown_table(["#", "菜品", "销量（份）"], [[1, "娃娃菜", "1,541.88"]])
    assert out[0] == "", "表格前必须有空行, 否则会被并进上一段当普通文字"
    assert out[1] == "| # | 菜品 | 销量（份） |"
    assert re.fullmatch(r"\|( -{3}:? \|)+", out[2]), f"分隔行不合法: {out[2]!r}"
    assert out[3] == "| 1 | 娃娃菜 | 1,541.88 |"


def test_right_align_marks_the_numeric_columns():
    """金额/数量列右对齐 —— 数字左对齐读起来对不齐, 表格就白做了。"""
    out = _markdown_table(["名", "额"], [["a", "1"]], right_align={1})
    assert out[2] == "| --- | ---: |"


def test_pipe_in_a_cell_cannot_break_the_columns():
    """菜名里带 `|` 时必须转义 —— 错列不报错, 只是渲染成一坨。"""
    out = _markdown_table(["菜品"], [["带|竖线的菜名"]])
    row = out[-1]
    assert row == "| 带\\|竖线的菜名 |"
    # 阴性对照: 转义后, 这一行的未转义竖线数必须与表头一致(各 2 个)
    unescaped = len(re.findall(r"(?<!\\)\|", row))
    assert unescaped == len(re.findall(r"(?<!\\)\|", out[1])), "列数被内容冲乱了"


def test_newline_in_a_cell_is_flattened():
    """单元格里的换行会把表格截断成两半, 必须压成空格。"""
    out = _markdown_table(["备注"], [["第一行\n第二行"]])
    assert "\n" not in out[-1]
    assert out[-1] == "| 第一行 第二行 |"


def test_empty_rows_still_produce_a_valid_header():
    """没有数据行时也得是一张合法的空表, 不能吐半张。"""
    out = _markdown_table(["#", "菜品"], [])
    assert len(out) == 3 and out[2].count("---") == 2


def test_only_one_place_builds_tables_in_the_router():
    """🔴 承重: 全站只有 `_markdown_table` 一处拼表格。

    十几处排行/对比答案各写一遍格式, 改一次要改十几处, 漏掉的那处不报错 ——
    这正是本仓反复出现的「同一件事多个载体」。

    判据: 源码里出现 `"| "` 开头的表格行字面量, 除了这个函数自身, 都算分叉。
    """
    import smartbi.gold.restaurant.restaurant_ops_router as router

    src = inspect.getsource(router)
    helper_src = inspect.getsource(_markdown_table)
    outside = src.replace(helper_src, "")
    # 表格分隔行(`| --- |`)是最不会误伤的特征: 正常文案不会写这个
    strays = re.findall(r'"\|\s*-{3,}', outside)
    assert not strays, (
        f"除 _markdown_table 外还有 {len(strays)} 处在手写表格分隔行 —— "
        f"格式会分叉。改成调用 _markdown_table。")


def _resolver_source(fn_name: str) -> str:
    """按函数粒度取源码 —— 整文件 grep 分不出「哪个 resolver」。"""
    import ast

    import smartbi.gold.restaurant.restaurant_ops_router as router

    src = inspect.getsource(router)
    for node in ast.walk(ast.parse(src)):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == fn_name:
            return ast.get_source_segment(src, node) or ""
    raise AssertionError(f"{fn_name} 不在 restaurant_ops_router 里了 —— 改名了就更新这张表")


# 🔴 断言的**主语**: 每个 resolver 一行, 表格在答什么。
# ⛔ 不许改成「从源码里现算有哪些函数调了 _markdown_table」—— 那是左右同源的
#    恒真式, 永远等于当前实现, 一条都红不了(本仓 08-09 为此栽过)。
_MUST_RENDER_A_TABLE = {
    "resolve_gross_margin": "菜品销量排行",
    "resolve_store_margin": "某菜的门店排行 / 各店菜品排行",
    "resolve_channel_mix": "渠道构成",
    "resolve_wastage_top": "损耗排行",
    "resolve_inventory_warning": "库存预警清单",
    "resolve_discount_summary": "折扣构成(不含上方的单条汇总事实)",
    "resolve_daypart_performance": "各时段表现",
    "resolve_stock_shortage": "盘亏食材排行",
    "resolve_recipe_cost": "菜品食材成本排行",
    "resolve_requisition_trend": "领用食材排行",
}

# 同形状但**还没转**的 —— 登记在案, 不是漏掉。转一个就从这里挪进上面那张表。
# 2026-08-11: 已清空(盘亏/配方成本/领料三个都转完了)。
# ⛔ 别因为空了就删掉这张表: 它是「下一个写排行的人该在哪表态」的落点,
#    删了之后新增的漏网之鱼就没有登记处, `test_no_resolver_silently_ships_
#    a_numbered_ranking` 只能报错而给不出去处。
_PENDING_TABLE: dict = {}

# ⛔ 刻意不转: 表格化反而更差。
_EXEMPT_FROM_TABLE = {
    "resolve_store_directory": "门店名单是单列, 表格只会多两条竖线",
}


@pytest.mark.parametrize("fn_name", sorted(_MUST_RENDER_A_TABLE))
def test_each_listed_resolver_builds_its_own_table(fn_name):
    """🔴 承重: **这个** resolver 自己调了表格渲染。

    旧断言是 `"_markdown_table(" in 整个文件`: 只要文件里还剩一个调用点就永远为真。
    实测(2026-08-11): 把菜品排行那一处退回编号列表、另外 3 处保留, 7 条断言**全绿** ——
    它名义上测「菜品排行用了表格」, 实际测的是「全站至少有过一次调用」。

    判据: 一条断言先问「它靠什么变红」—— 这条靠**被点名的那个函数**自己的源码变红。
    """
    seg = _resolver_source(fn_name)
    assert "_markdown_table(" in seg, (
        f"{fn_name}（{_MUST_RENDER_A_TABLE[fn_name]}）没有调用 _markdown_table —— "
        f"这段答案退回了纯文本/编号列表形态。")


def test_no_resolver_silently_ships_a_numbered_ranking():
    """新写的排行必须显式表态: 转表格 / 待转 / 豁免, 三者取一。

    ⚠️ 这条挂在排版特征(`enumerate` + `{i+1}.` 编号行)上, 所以它**不是承重断言** ——
       换个拼法就绕过去了。它只负责「新增的漏网之鱼」这一个方向, 承重的是上面那条。
    """
    import ast

    import smartbi.gold.restaurant.restaurant_ops_router as router

    src = inspect.getsource(router)
    known = set(_MUST_RENDER_A_TABLE) | set(_PENDING_TABLE) | set(_EXEMPT_FROM_TABLE)
    numbered = re.compile(r'f"\{i\s*\+\s*1\}\.|f"\{idx\}\.')
    strays = []
    for node in ast.walk(ast.parse(src)):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        seg = ast.get_source_segment(src, node) or ""
        if "enumerate(" in seg and numbered.search(seg) and node.name not in known:
            strays.append(node.name)
    assert not strays, (
        f"这些 resolver 在拼编号排行但三张表里都没登记: {strays} —— "
        f"要么转成 _markdown_table, 要么写进 _PENDING_TABLE/_EXEMPT_FROM_TABLE 说明理由。")


def test_the_three_registries_do_not_overlap():
    """同一个 resolver 不能既「已转」又「待转」—— 重叠说明有人只改了一半。"""
    assert not (set(_MUST_RENDER_A_TABLE) & set(_PENDING_TABLE))
    assert not (set(_MUST_RENDER_A_TABLE) & set(_EXEMPT_FROM_TABLE))
    assert not (set(_PENDING_TABLE) & set(_EXEMPT_FROM_TABLE))
