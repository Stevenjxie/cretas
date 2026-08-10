"""答案里出现表格, 它就必须是**结构合法**的表格 —— 每轮电池都查。

## 为什么要这道闸

2026-08-10 起连着四个 PR 把排行/构成改成 markdown 表格, 共 8 张。全部上线、
电池两轮 85/85、CI 全绿。**8 张表到用户手里全是坏的**:

    '**本月（2026-08-01 至 2026-08-11）菜品销量排行（卖得最好前 5）：**\n| # | 菜品 | …'

resolver 拼的是 `[标题, ""] + _markdown_table(...)`(两个空行), 到用户手里 `\n\n`
一个不剩 —— 下游一个跟表格无关的清洗函数把空行整行丢掉了。markdown-it 需要空行
才把表格当成一个块, 少了它整张表被并进上一段渲染成普通文字。

## 既有断言为什么全都看不见

| 形态 | 例子 | 为什么沉默 |
|---|---|---|
| 子串检查 | `"销量" in answer` | 字还在, 只是排版没了 |
| 源码检查 | `"_markdown_table(" in <该函数源码>` | 调用还在, 只是下游改了结果 |

两种在**结构上**就不可能覆盖「排版塌了」。所以这道闸不是补一条断言,
是补一个**此前不存在的检查维度**。

⛔ 它必须喂**未压平**的原文。电池里到处在用的 `flat = " ".join(message.split())`
   把所有空白压平 —— 拿 flat 查排版等于给自己发一张永远绿的通行证。
   `test_the_gate_is_fed_raw_text_not_the_flattened_one` 钉住这一点。
"""
from __future__ import annotations

import inspect

from smartbi.scripts.restaurant_ai_eval import markdown_table_problems as check

_GOOD = "\n".join([
    "**本月菜品销量排行：**",
    "",
    "| # | 菜品 | 销量 |",
    "| --- | --- | ---: |",
    "| 1 | 米饭 | 1,345 |",
    "| 2 | 娃娃菜 | 980 |",
])


def test_a_well_formed_table_passes():
    assert check(_GOOD) == []


def test_no_table_at_all_passes():
    """绝大多数答案没有表格 —— 这条闸不能对它们说话。"""
    assert check("本月全部门店营收 ¥1,234,567。") == []
    assert check("") == []


def test_missing_blank_line_before_the_table_is_caught():
    """🔴 承重: 这就是 8 张表全中的那个形状。"""
    broken = "\n".join([
        "**本月菜品销量排行：**",
        "| # | 菜品 | 销量 |",
        "| --- | --- | ---: |",
        "| 1 | 米饭 | 1,345 |",
    ])
    problems = check(broken)
    assert problems, "少了表头前的空行居然没被抓到 —— 这道闸白加了"
    assert any("缺空行" in p for p in problems), problems


def test_header_column_count_mismatch_is_caught():
    broken = "\n".join([
        "标题",
        "",
        "| # | 菜品 |",
        "| --- | --- | ---: |",
        "| 1 | 米饭 | 1,345 |",
    ])
    assert any("列数对不上" in p for p in check(broken)), check(broken)


def test_data_row_column_count_mismatch_is_caught():
    """菜名里带未转义的 `|` 就是这个形状 —— 错列不报错, 只是渲染成一坨。"""
    broken = "\n".join([
        "标题",
        "",
        "| # | 菜品 | 销量 |",
        "| --- | --- | ---: |",
        "| 1 | 米|饭 | 1,345 |",
    ])
    assert any("数据行列数对不上" in p for p in check(broken)), check(broken)


def test_escaped_pipe_in_a_cell_is_not_counted():
    """⛔ 阴性对照: 转义过的 `\\|` 是合法内容, 不能报错 ——
    否则拼装点做对了事反而被闸拦下, 下一个人就会把这道闸关掉。"""
    ok = "\n".join([
        "标题",
        "",
        "| # | 菜品 | 销量 |",
        "| --- | --- | ---: |",
        "| 1 | 米\\|饭 | 1,345 |",
    ])
    assert check(ok) == []


def test_table_at_the_very_start_is_fine():
    """整段以表格开头时没有「上一段」, 不该报缺空行。"""
    assert check("| # | 菜品 |\n| --- | --- |\n| 1 | 米饭 |") == []


def test_separator_with_no_header_is_caught():
    assert check("| --- | --- |\n| 1 | 米饭 |") != []


def test_it_would_have_caught_the_real_shipped_defect():
    """🔴🔴 最承重: 拿**真实上线过的坏输出**喂它, 必须红。

    下面这段是 2026-08-11 02:0x 从 prod 打真接口抓回来的原文(逐字复制, 未整理)。
    当时 `sanitize_customer_ai_text` 把空行全删了, 这张表在 App 里被并进上一段。
    四个 PR、两轮 85/85 电池、CI 全绿 —— 没有一条断言看得见它。

    ⛔ 这条不许改成我自己造的样例: 造的样例证明的是「我以为的坏形状」,
       只有真实字节能证明「这道闸当时真的会红」。
    """
    shipped_broken = (
        "最近30天（2026-07-13 至 2026-08-11）盘点总览:\n"
        "- 盘点 1250 次, 盘亏金额 **¥10730.80**, 盘盈金额 ¥5848.66\n"
        "盘亏食材前 10 名(按金额):\n"
        "| # | 食材 | 分类 | 盘亏金额 | 盘亏量 |\n"
        "| --- | --- | --- | ---: | ---: |\n"
        "| 1 | 鲈鱼 | 水产 | ¥2,657.07 | 73.81 kg |\n"
        "| 2 | 罗氏虾 | 水产 | ¥2,287.81 | 23.34 kg |\n"
        "建议动作:\n"
        "1. 对盘亏最高的食材先核查领料单、报损单和实际库存照片，找出未登记消耗。"
    )
    problems = check(shipped_broken)
    assert problems, "真实坏输出没被抓到 —— 这道闸对它要防的东西是瞎的"
    assert any("缺空行" in p for p in problems), problems


def test_the_fixed_output_passes():
    """阴性对照: 修好之后的真实输出必须**不**红, 否则这道闸会天天误报。

    同样逐字来自 prod(02:29 修复部署后)。
    """
    shipped_fixed = (
        "最近30天（2026-07-13 至 2026-08-11）盘点总览:\n"
        "- 盘点 1250 次, 盘亏金额 **¥10730.80**, 盘盈金额 ¥5848.66\n"
        "\n"
        "盘亏食材前 10 名(按金额):\n"
        "\n"
        "| # | 食材 | 分类 | 盘亏金额 | 盘亏量 |\n"
        "| --- | --- | --- | ---: | ---: |\n"
        "| 1 | 鲈鱼 | 水产 | ¥2,657.07 | 73.81 kg |\n"
        "| 2 | 罗氏虾 | 水产 | ¥2,287.81 | 23.34 kg |\n"
        "\n"
        "建议动作:\n"
        "1. 对盘亏最高的食材先核查领料单、报损单和实际库存照片，找出未登记消耗。"
    )
    assert check(shipped_fixed) == []


# ── 接线 ────────────────────────────────────────────────────────────────
def test_the_gate_runs_on_every_case_not_a_registered_list():
    """🔴 表格是哪一题给的不重要 —— 给了就必须合法, 所以它挂在通用路径上。

    逐题登记的话, 新增的表格答案默认不受检查, 而「默认不检查」正是这次
    8 张表全坏还全绿的原因。
    """
    import smartbi.scripts.restaurant_ai_eval as ev

    src = inspect.getsource(ev._run_case)
    assert "markdown_table_problems(" in src, "闸没有接在 _run_case 上"


def test_the_gate_is_fed_raw_text_not_the_flattened_one():
    """⛔ 承重: 必须喂 `message`, 不是 `flat`。

    `flat = " ".join(message.split())` 把所有空白压平 —— 拿它查排版, 这道闸
    在结构上就不可能变红。判据: **写完一条断言先问它靠什么变红。**
    """
    import smartbi.scripts.restaurant_ai_eval as ev

    src = inspect.getsource(ev._run_case)
    assert "markdown_table_problems(message)" in src, (
        "喂成 flat 了 —— 空白已被压平, 这道闸从此什么都测不到")
    # 阴性对照: flat 确实是压平过的, 否则上面这条讲的事不成立
    assert 'flat = " ".join(message.split())' in src
