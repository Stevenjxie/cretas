# -*- coding: utf-8 -*-
"""「我要不要关掉最差的那家店」这一屏：给得出决定的东西，收掉淹没它的东西。

守两条，都是取舍顺序（**能不能决定 > 数字准不准 > 覆盖得全不全**）的落地：

  ④ 该给表格就给表格 —— 10 家店的排行/对比，散文说不清，给表
     (`_append_store_comparison_table`)
  取舍  「还可以补充的方面」原来一次列 12 条，把结论淹掉了
     (`_rank_missing_by_this_rounds_cause` 按**这一轮的主因**分档)

## 📏 fixture 的来历（⛔ 不是随手编的数）

门店行是 MOCK_REST prod 2026-08-18 **绕过叙述缓存**实拍的 10 家
（`_render_finance` 喂给模型的 Top 门店名单，与 `compute_store_attribution`
的入参同源），`primary_cause` 由**生产函数**现算，⛔ 不在桩里写死 ——
桩里能写的形状，真实上游未必产得出来（形态 B‴）。
"""
from __future__ import annotations

import ast
import inspect
import re
from pathlib import Path

import pytest

from smartbi.agent.dimension_catalog import (
    dimension_definition,
    dimension_status,
    missing_status,
)
from smartbi.agent.factbook import (
    LLM_STORE_ROSTER_CAP,
    FactBook,
    compute_store_attribution,
)
from smartbi.agent.synthesis_engine import (
    _CAUSE_INVESTIGATION_DIMENSIONS,
    ComprehensiveSynthesisEngine,
    _rank_missing_by_this_rounds_cause,
)

_TABLE = ComprehensiveSynthesisEngine._append_store_comparison_table
_GUIDANCE = ComprehensiveSynthesisEngine._append_dimension_guidance
_DIRECTIONS = ComprehensiveSynthesisEngine._append_investigation_directions

#: 📏 MOCK_REST prod 2026-08-18 绕过缓存实拍（最近 30 天）：(门店, 营收, 订单数)。
#: ⛔ 不要换成整齐的数字 —— 「10 家咬在 2.9% 以内、差在客单价不在客流」
#:    这个形状正是被测的那一种。
_PROD_STORES = (
    ("模拟·徐汇美罗城店", 2149026.10, 5897),
    ("模拟·普陀真如社区店", 2136539.48, 5904),
    ("模拟·宝山大场社区店", 2133998.10, 5904),
    ("模拟·陆家嘴正大店", 2133035.70, 5897),
    ("模拟·闵行莘庄社区店", 2131576.43, 5904),
    ("模拟·长宁龙之梦店", 2119565.28, 5897),
    ("模拟·静安嘉里中心店", 2114577.32, 5897),
    ("模拟·浦东金桥社区店", 2108962.47, 5904),
    ("模拟·杨浦五角场店", 2091883.81, 5897),
    ("模拟·打浦桥日月光店", 2089545.23, 5897),
)
_PROD_AVAILABLE = (
    ("revenue", "available"), ("period_comparison", "available"),
    ("store_comparison", "available"), ("guest_traffic", "available"),
    ("dish_sales", "available"), ("promotion", "partial"),
    ("supplier_cost", "available"), ("waste", "available"),
    ("stocktaking", "available"),
)
#: 📏 同一次实拍的 12 条 —— 就是把结论淹掉的那张清单。
_PROD_MISSING = (
    "physical_traffic", "dish_margin", "channel", "meal_period", "review",
    "inventory", "staffing", "weather", "holiday", "mall_activity",
    "nearby_event", "competitor",
)


def _rows(stores):
    return [{"name": n, "revenue": r, "orderCount": b} for n, r, b in stores]


def _factbook(*, stores=_PROD_STORES, available=_PROD_AVAILABLE,
              missing=_PROD_MISSING):
    """用**生产的**三个构造函数搭 FactBook，⛔ 不手写 dict 形状。"""
    fb = FactBook()
    fb.attribution = compute_store_attribution(_rows(stores))
    fb.available_dimensions = [
        dimension_status(code, status=status, evidence_level="REAL",
                         source="测试", reason=None, coverage=None)
        for code, status in available
    ]
    fb.missing_dimensions = [
        missing_status(code, reason="测试用：这一维没有数据") for code in missing
    ]
    return fb


def _label(code: str) -> str:
    return dimension_definition(code).label


def _table_separator_re() -> re.Pattern:
    """「给了表」的判据，**从交付定义探针里取**，⛔ 不在这里另写一份（形态 D）。

    ⛔ 不 import 那个脚本 —— 它在模块顶层就 `bootstrap_probe()` 连库。
    ⇒ 用 AST 只取 `_TABLE_RE = re.compile(<字面量>)` 里那个 **Constant 的 str**。
    """
    probe = (Path(__file__).resolve().parents[1]
             / "smartbi" / "scripts" / "restaurant_delivery_definitions_probe.py")
    tree = ast.parse(probe.read_text(encoding="utf-8"))
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assign):
            continue
        if not any(getattr(t, "id", None) == "_TABLE_RE" for t in node.targets):
            continue
        call = node.value
        if isinstance(call, ast.Call) and call.args:
            first = call.args[0]
            if isinstance(first, ast.Constant) and isinstance(first.value, str):
                return re.compile(first.value, re.M)
    raise AssertionError(
        "没能从交付定义探针里取到 `_TABLE_RE` 的字面量 —— 这条判据失去意义")


class TestTheFixtureReallyReproducesProd:
    """🔴 阳性对照：没有这条，下面每一条断言都可能在测一个我自己编的形状。"""

    def test_prod_rows_really_produce_the_ticket_cause(self):
        att = compute_store_attribution(_rows(_PROD_STORES))
        assert att["primary_cause"] == "客单价", att
        assert att["laggard"]["store_name"] == "模拟·打浦桥日月光店"
        assert att["laggard"]["delta_revenue"] == pytest.approx(-31326, abs=2)
        assert att["bench_revenue"] == pytest.approx(2120871, abs=2)

    def test_the_probe_criterion_is_really_a_gfm_separator(self):
        """阳性 + 阴性对照：取回来的那个正则真的在分辨表格。"""
        pattern = _table_separator_re()
        assert pattern.search("| --- | ---: |")
        assert not pattern.search("正文里的竖线 a | b | c 不算表")


class TestItGivesHimTheTable:
    """④ 该给表格就给表格 —— 排行/对比，散文说不清。"""

    def test_it_renders_a_table_the_delivery_probe_recognises(self):
        out = _TABLE("原文。", _factbook())
        assert _table_separator_re().search(out), out

    def test_worst_store_is_the_first_row_and_is_marked(self):
        out = _TABLE("原文。", _factbook())
        rows = [ln for ln in out.splitlines() if ln.startswith("| 模拟")]
        assert len(rows) == len(_PROD_STORES), rows
        assert rows[0].startswith("| 模拟·打浦桥日月光店（垫底）"), rows[0]
        assert sum(1 for r in rows if "（垫底）" in r) == 1, rows

    def test_rows_are_sorted_worst_first(self):
        out = _TABLE("原文。", _factbook())
        order = [ln.split("|")[1].strip().replace("（垫底）", "")
                 for ln in out.splitlines() if ln.startswith("| 模拟")]
        expected = [s["store_name"] for s in sorted(
            _factbook().attribution["stores"], key=lambda s: s["delta_revenue"])]
        assert order == expected, order

    def test_every_number_in_the_table_comes_from_attribution(self):
        """⛔ 表里不许有第二个数字来源 —— 每一格都要能在 attribution 里找到。"""
        fb = _factbook()
        out = _TABLE("原文。", fb)
        by_name = {s["store_name"]: s for s in fb.attribution["stores"]}
        for line in out.splitlines():
            if not line.startswith("| 模拟"):
                continue
            cells = [c.strip() for c in line.strip("|").split("|")]
            store = by_name[cells[0].replace("（垫底）", "")]
            assert cells[1] == "%.1f万" % (store["revenue"] / 10000)
            assert cells[2] == "{:,}".format(int(store["bills"]))
            assert cells[3] == "%.1f" % store["avg_ticket"]
            assert cells[4] == "%+.1f万" % (store["delta_revenue"] / 10000)

    def test_the_line_above_the_table_states_the_spread(self):
        """🔴 承重：「关不关最差那家」取决于断层多大 —— 跨度必须写出来。"""
        fb = _factbook()
        out = _TABLE("原文。", fb)
        revenues = [s["revenue"] for s in fb.attribution["stores"]]
        assert "%.1f万" % (min(revenues) / 10000) in out
        assert "%.1f万" % (max(revenues) / 10000) in out
        assert "%.1f万" % (fb.attribution["bench_revenue"] / 10000) in out

    def test_it_keeps_the_original_answer_above_the_table(self):
        out = _TABLE("核心结论：不建议关店。", _factbook())
        assert out.startswith("核心结论：不建议关店。")
        assert out.index("核心结论") < out.index("| 门店 |")


class TestTheTableShutsUpWhenItHasNothingToShow:
    """⛔ 每次都给表就是噪音 —— 三道门各配一条阴性对照。"""

    def test_no_attribution_appends_nothing(self):
        fb = _factbook()
        fb.attribution = None
        assert _TABLE("原文。", fb) == "原文。"

    def test_no_data_attribution_appends_nothing(self):
        fb = _factbook()
        fb.attribution = {"no_data": True}
        assert _TABLE("原文。", fb) == "原文。"

    def test_a_single_store_is_not_a_comparison(self):
        fb = _factbook()
        fb.attribution = dict(fb.attribution, stores=fb.attribution["stores"][:1])
        assert _TABLE("原文。", fb) == "原文。"


class TestTheTableTruncatesWithoutHidingTheSpread:
    """⚠️ 按差额升序截断会藏掉最好的那几家，而跨度正是决策要看的。"""

    @staticmethod
    def _many(n):
        return tuple(("模拟·%02d号店" % i, 2000000.0 + i * 20000, 5900)
                     for i in range(n))

    def test_it_shows_at_most_the_shared_roster_cap(self):
        """⛔ 不新造第二个上限 —— 行数由 `LLM_STORE_ROSTER_CAP` 决定。"""
        n = LLM_STORE_ROSTER_CAP + 7
        out = _TABLE("原文。", _factbook(stores=self._many(n)))
        rows = [ln for ln in out.splitlines() if ln.startswith("| 模拟")]
        assert len(rows) == LLM_STORE_ROSTER_CAP, len(rows)
        assert "另外 7 家" in out, out

    def test_the_spread_line_still_reports_the_unshown_maximum(self):
        """🔴 承重：截断之后跨度仍要报**全部**门店的最高值，⛔ 不是表里的最高值。"""
        n = LLM_STORE_ROSTER_CAP + 7
        fb = _factbook(stores=self._many(n))
        out = _TABLE("原文。", fb)
        revenues = [s["revenue"] for s in fb.attribution["stores"]]
        top = "%.1f万" % (max(revenues) / 10000)
        assert top in out.split("| 门店 |")[0], (
            "跨度那一行没有报出全部门店的最高营业额 —— "
            "老板会以为最好的一家就是表里最上面那几家")

    def test_no_truncation_notice_when_nothing_was_truncated(self):
        assert "另外" not in _TABLE("原文。", _factbook())


class TestTheAppendixStopsDrowningTheConclusion:
    """取舍顺序：能不能决定 > 覆盖得全不全。"""

    def test_only_the_dimensions_on_this_rounds_path_are_detailed(self):
        lead, folded = _rank_missing_by_this_rounds_cause(_factbook())
        assert [i["code"] for i in lead] == ["channel", "meal_period"], lead
        assert len(folded) == len(_PROD_MISSING) - 2

    def test_the_order_is_the_registered_investigation_order(self):
        """⛔ 顺序不是我排的 —— 沿用登记表里的排查顺序。"""
        lead, _folded = _rank_missing_by_this_rounds_cause(_factbook())
        registry = [c for c in _CAUSE_INVESTIGATION_DIMENSIONS["客单价"]
                    if c in {i["code"] for i in lead}]
        assert [i["code"] for i in lead] == registry

    def test_the_rendered_appendix_details_only_those_two(self):
        out = _GUIDANCE("原文。", _factbook())
        detailed = re.findall(r"^- \*\*([^*]+)\*\*：需要", out, re.M)
        assert detailed == [_label("channel"), _label("meal_period")], detailed

    def test_the_folded_ones_are_never_dropped_silently(self):
        """⛔ 绝不静默丢掉 —— 名字全列出来，并说清怎么要。"""
        fb = _factbook()
        out = _GUIDANCE("原文。", fb)
        _lead, folded = _rank_missing_by_this_rounds_cause(fb)
        exits = [ln for ln in out.splitlines() if "想看哪一项" in ln]
        assert len(exits) == 1, out
        for item in folded:
            assert item["label"] in exits[0], item["label"]
        assert "另外 %d 个方面" % len(folded) in exits[0]

    def test_the_exit_line_does_not_judge_for_him(self):
        """⛔ 不要替他做判断 —— 只说排查顺序，不说「补了也没用」。"""
        out = _GUIDANCE("原文。", _factbook())
        exit_line = [ln for ln in out.splitlines() if "想看哪一项" in ln][0]
        for phrase in ("没用", "不用看", "不必", "改变不了", "无关", "别看"):
            assert phrase not in exit_line, phrase

    def test_the_traffic_branch_ranks_by_its_own_path(self):
        """⚠️ prod 上主因恒为「客单价」⇒ 这一支没有 prod 验收（形态 B⁴）。"""
        stores = (("A店", 2000000.0, 6000), ("B店", 1900000.0, 5700),
                  ("C店", 600000.0, 1800))
        fb = _factbook(stores=stores)
        assert fb.attribution["primary_cause"] == "客流"
        lead, _folded = _rank_missing_by_this_rounds_cause(fb)
        codes = [i["code"] for i in lead]
        assert "physical_traffic" in codes and "review" in codes
        # 客单价那一支独有的维度⛔不许串进来
        assert "dish_margin" not in codes


class TestNoRankingBasisMeansNoTrimming:
    """⛔ 拿不到依据就不排序 —— 不许拍一个数字砍。"""

    def test_without_attribution_everything_is_still_detailed(self):
        fb = _factbook()
        fb.attribution = None
        lead, folded = _rank_missing_by_this_rounds_cause(fb)
        assert not folded and len(lead) == len(_PROD_MISSING)
        out = _GUIDANCE("原文。", fb)
        assert len(re.findall(r"^- \*\*[^*]+\*\*：需要", out, re.M)) == len(_PROD_MISSING)

    def test_unknown_primary_cause_keeps_everything(self):
        fb = _factbook()
        fb.attribution = dict(fb.attribution, primary_cause="毛利")
        lead, folded = _rank_missing_by_this_rounds_cause(fb)
        assert not folded and len(lead) == len(_PROD_MISSING)

    def test_nothing_missing_on_the_path_keeps_everything(self):
        fb = _factbook(missing=("weather", "holiday"))
        # 客单价那条路径上一条都不缺 ⇒ 没有排序依据
        assert not set(_CAUSE_INVESTIGATION_DIMENSIONS["客单价"]) & {"weather", "holiday"}
        lead, folded = _rank_missing_by_this_rounds_cause(fb)
        assert not folded and len(lead) == 2


class TestItIsWiredOnEveryPath:
    """机制在、没接上（形态 B）—— 接线数量与顺序都用 AST 钉住。"""

    @staticmethod
    def _call_lines(tree, name):
        return sorted(
            node.lineno
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and getattr(node.func, "attr", None) == name
        )

    @staticmethod
    def _tree():
        from smartbi.agent import synthesis_engine as se
        return ast.parse(inspect.getsource(se))

    def test_the_table_is_wired_as_many_times_as_the_appendix(self):
        tree = self._tree()
        mine = self._call_lines(tree, "_append_store_comparison_table")
        theirs = self._call_lines(tree, "_append_dimension_guidance")
        assert theirs, "没找到 `_append_dimension_guidance` 的调用点 —— 断言失去意义"
        assert len(mine) == len(theirs), (
            f"门店对比表接了 {len(mine)} 处、附录接了 {len(theirs)} 处 —— "
            f"少接一处就是有一条路上的老板永远看不到那张表")

    def test_the_table_comes_before_the_directions_on_every_path(self):
        tree = self._tree()
        mine = self._call_lines(tree, "_append_store_comparison_table")
        theirs = self._call_lines(tree, "_append_investigation_directions")
        assert len(mine) == len(theirs), (mine, theirs)
        for a, b in zip(mine, theirs):
            assert a < b, f"第 {a} 行的表排在第 {b} 行的排查方向之后"

    def test_rendered_order_is_table_then_directions_then_appendix(self):
        """端到端：按 `synthesize` **源码里的实际顺序**渲染一遍。

        ⛔ 顺序从 AST 取，⛔ 不在测试里自己写死 —— 写死的话「把两行对调」
        这条变异纹丝不动（B′：断言在守空气）。
        """
        names = ("_append_store_comparison_table",
                 "_append_investigation_directions",
                 "_append_dimension_guidance")
        calls = sorted(
            (node.lineno, node.func.attr)
            for node in ast.walk(self._tree())
            if isinstance(node, ast.Call)
            and getattr(node.func, "attr", None) in names
        )
        assert len(calls) >= 3, f"接线不足三处，这条断言失去意义: {calls}"
        fb = _factbook()
        out = "原文。"
        for _lineno, name in calls[-3:]:      # LLM 那条路上相邻的三个
            out = getattr(ComprehensiveSynthesisEngine, name)(out, fb)
        assert out.index("| 门店 |") < out.index("### 差距出在") < out.index(
            "### 还可补充的分析维度"), out
