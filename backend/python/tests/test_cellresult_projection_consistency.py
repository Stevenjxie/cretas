"""CellResult 投影一致性闸 —— 新增公开字段而投影没跟上就红。

## 为什么要这道闸（同一堵墙已经撞了三次）

| # | 字段 | 症状 |
|---|---|---|
| 1 | `kind` | Python 改对了，而这一路**根本不透传**，Java 永远走默认分支 |
| 2 | `rows` | resolver 逐行造好了结构化数据，投影只取 `answer_text`，正文印着 5 行表格而机器可读侧空着 |
| 3 | `provenance` | 字段落地了，而 prod 响应里**根本没有这个键** —— 这一轮日结盘点当场量到 |

根因是**投影用白名单逐字段列举，新字段默认丢失**。
⛔ 而它**不能**简单改成全量转发：`CellResult.sql` 就不该发给客户端。

⇒ 这是形态 D（同一个东西有两份，它一定会漂），而且是 D′ 那一类：
   **抽不成一份，所以闸要钉的是「两份一致」**。

## 闸的形状

`CellResult` 的每个字段必须落进下面两个集合之一，**二选一，不许两不沾**：

- `PROJECTED` —— 会（或应当）出现在给客户端的响应里
- `DELIBERATELY_NOT_PROJECTED` —— **显式排除**，登记本身就是
  「我知道它不该投影」的留痕

新增一个字段而两边都没登记 → 本条红。
⛔ 这条闸的价值全在**「默认失败」**：白名单的默认是「悄悄丢掉」，
   这道闸把默认改成「当场红」。
"""
import pytest

from dataclasses import fields

from smartbi.gold.restaurant.generic_executor import CellResult

#: 应当能到达客户端的字段。
#: ⚠️ 「应当」不等于「今天已经到了」—— `provenance` / `estimation_basis` 目前
#:    还没接进 `gold_reads` 的白名单（2026-08-13 日结盘点实测：prod 响应里没有
#:    这个键）。本闸先钉住**契约**，接线是另一件事，但至少它不再是隐形的。
PROJECTED = {
    "metric_key",
    "metric_label",
    "dimension_key",
    "aggregation_key",
    "unit",
    "rows",
    "missing_columns",
    "provenance",
    "estimation_basis",
    # 覆盖率。与 provenance / estimation_basis 同一类: 都是「这个数能不能用」
    # 的元信息。前端要打「覆盖 40%」这种标时拿得到, 不用自己再算一遍
    # (自己算就是同一个量的第二份定义, 必漂)。
    "coverage_ratio",
    # 被排除的异常成本卡, **指名带出去**。与 provenance/coverage_ratio 同一类:
    # 都是「这个数能不能用」的元信息。前端要在菜品旁边标红并给「去修」入口时
    # 拿得到 —— ⛔ 自己再判一遍就是同一个判据的第二份定义, 必漂。
    "cost_outliers",
    # T2 第一层「缺口」: 哪几道菜没有成本卡, 按营收降序。前端要自己排版
    # 「先补这几道」的清单时拿得到 —— ⛔ 自己再查一遍就是第二份缺口定义。
    "cost_gaps",
    # 覆盖率的分母。前端要算「补了这几道能到几成」时**必须**用它,
    # ⛔ 自己另取一次营收就是第二个分母, 两个覆盖率必漂。
    "coverage_denominator",
}

#: **显式排除**。每一条都要写清楚为什么不投影 —— 登记是留痕，不是豁免。
DELIBERATELY_NOT_PROJECTED = {
    # 拼出来的 SQL。⛔ 给客户端等于把库结构和查询逻辑一起发出去。
    # 它只服务于排查（日志/审计），不服务于渲染。
    "sql": "查询 SQL —— 不发给客户端(暴露库结构与查询逻辑)",
}


def test_every_cellresult_field_is_classified():
    """🔴 承重：新增字段而两边都没登记 → 红。

    ⛔ 白名单的默认是「悄悄丢掉」；这道闸把默认改成「当场红」。
       同一堵墙已经撞过三次（kind / rows / provenance），第四次不该再撞。
    """
    declared = {f.name for f in fields(CellResult)}
    classified = PROJECTED | set(DELIBERATELY_NOT_PROJECTED)

    unclassified = declared - classified
    assert not unclassified, (
        f"CellResult 新增了字段但没登记: {sorted(unclassified)}\n"
        f"  · 该投影 -> 加进 PROJECTED, **并把它接进 gold_reads 的响应白名单**\n"
        f"  · 不该投影 -> 加进 DELIBERATELY_NOT_PROJECTED 并写明理由\n"
        f"⛔ 不要只加登记不接线 —— 那只是把「悄悄丢掉」改成「登记过的悄悄丢掉」")


def test_no_stale_classification():
    """反向：登记了却已经不在 `CellResult` 上 —— 过期登记会让人以为它还在投影。"""
    declared = {f.name for f in fields(CellResult)}
    classified = PROJECTED | set(DELIBERATELY_NOT_PROJECTED)
    stale = classified - declared
    assert not stale, f"登记了但 CellResult 上已经没有这些字段: {sorted(stale)}"


def test_exclusions_carry_a_reason():
    """⛔ 显式排除必须写理由 —— 空理由等于没登记，下一个人无从判断该不该改。"""
    for name, reason in DELIBERATELY_NOT_PROJECTED.items():
        assert reason and reason.strip(), f"{name} 的排除理由是空的"


def test_the_two_sets_do_not_overlap():
    overlap = PROJECTED & set(DELIBERATELY_NOT_PROJECTED)
    assert not overlap, f"同一个字段既说要投影又说不投影: {sorted(overlap)}"


# ── 变异对照: 让这道闸红一次 ────────────────────────────────────────
def test_gate_fires_on_an_unclassified_new_field():
    """🔴 变异对照：模拟「有人给 CellResult 加了个字段」→ 本闸必须红。

    ⛔ 不做这条，`test_every_cellresult_field_is_classified` 可能只是
       「今天两个集合恰好覆盖了」，证明不了它下次会拦住人。
    """
    declared = {f.name for f in fields(CellResult)} | {"__new_field__"}
    classified = PROJECTED | set(DELIBERATELY_NOT_PROJECTED)
    assert declared - classified == {"__new_field__"}, (
        "模拟新增字段之后差集不对 —— 这条变异本身没构造成功")


def test_projection_reality_check_is_recorded_not_assumed():
    """⚠️ 本闸钉的是**契约**，不是「现在真的投影了」。

    2026-08-13 日结盘点实测：prod 响应里**没有** `provenance` 键。
    所以 `PROJECTED` 是「应当到达」的清单，`gold_reads` 的接线是另一件事。
    ⛔ 这条断言把这个区别写下来，免得下一个人看到 PROJECTED 就以为已经通了。
    """
    assert "provenance" in PROJECTED
    assert "estimation_basis" in PROJECTED
    # 真正接通与否由产品入口的断言去证（见 test_fill_offers 那几条的形态），
    # 不在这里假装已经通了。
