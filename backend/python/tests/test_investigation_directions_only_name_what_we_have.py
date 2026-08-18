# -*- coding: utf-8 -*-
"""归因七步链第 ⑥⑦ 步：给排查方向时，⛔ 只许说这份 FactBook 里真的有数的那几维。

## 它守的是反目标里最重的那一条

> 一条误发的提示烧掉的是「这东西说的话能信」。
> **排在最前面的那个命中，他去查会不会一无所获？**

所以「系统里有数、可以接着查」这一行里的每一维，它的数**必须就在同一份答案的
数据段里**。判据取 `available_dimensions` / `missing_dimensions` ——
与 `_grounding_findings` 里那条镜像规则（有数据的维度不许说成「你没提供」）
**同一个数据源**，⛔ 不新造第二套判定（形态 D）。

## 📏 fixture 的来历（⛔ 不是随手编的数）

门店行是 MOCK_REST prod 2026-08-18 实拍的 10 家（营收 + 订单数），
`primary_cause` 由**生产函数** `compute_store_attribution` 现算，
⛔ 不是在桩里写死 `{"primary_cause": "客单价"}` ——
桩里能写的形状，真实上游未必产得出来（形态 B‴）。

阳性对照钉在这条 fixture 上：它必须真的算出 `客单价` 且拖后腿门店是打浦桥，
数字与 prod 逐条吻合（Δ=-35623 / 客流效应=-2444 / 客单价效应=-33218）。
"""
from __future__ import annotations

import ast
import inspect

import pytest

from smartbi.agent.dimension_catalog import (
    DIMENSIONS,
    dimension_definition,
    dimension_status,
    missing_status,
)
from smartbi.agent.factbook import FactBook, compute_store_attribution
from smartbi.agent.synthesis_engine import (
    _CAUSE_INVESTIGATION_DIMENSIONS,
    _INVESTIGATION_LAST_RESORT,
    ComprehensiveSynthesisEngine,
)

_APPEND = ComprehensiveSynthesisEngine._append_investigation_directions

#: 📏 MOCK_REST prod 2026-08-18 最近30天实拍：(门店, 营收, 订单数)。
#: ⛔ 不要换成整齐的数字 —— 「10 家咬在 2.7% 以内、差在客单价不在客流」
#:    这个形状正是被测的那一种。
_PROD_STORES = (
    ("模拟·徐汇美罗城店", 2126568.95, 5845),
    ("模拟·普陀真如社区店", 2121507.71, 5862),
    ("模拟·陆家嘴正大店", 2117859.34, 5845),
    ("模拟·宝山大场社区店", 2116985.63, 5862),
    ("模拟·闵行莘庄社区店", 2116442.79, 5862),
    ("模拟·长宁龙之梦店", 2101216.26, 5845),
    ("模拟·静安嘉里中心店", 2096821.27, 5845),
    ("模拟·浦东金桥社区店", 2092555.37, 5862),
    ("模拟·杨浦五角场店", 2072320.65, 5845),
    ("模拟·打浦桥日月光店", 2067338.32, 5845),
)

#: 📏 同一次 prod 读数里 `available_dimensions` / `missing_dimensions` 的实际归属。
_PROD_AVAILABLE = (
    ("revenue", "available"), ("period_comparison", "available"),
    ("store_comparison", "available"), ("guest_traffic", "available"),
    ("dish_sales", "available"), ("promotion", "partial"),
    ("supplier_cost", "available"), ("waste", "available"),
    ("stocktaking", "available"),
)
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


def _line_starting(text: str, prefix: str) -> str:
    hits = [ln for ln in text.splitlines() if ln.startswith(prefix)]
    assert len(hits) == 1, f"期望恰好一行以 {prefix!r} 开头，实际 {hits}"
    return hits[0]


class TestTheFixtureReallyReproducesProd:
    """🔴 阳性对照：没有这条，下面每一条断言都可能在测一个我自己编的形状。"""

    def test_prod_rows_really_produce_the_ticket_cause(self):
        att = compute_store_attribution(_rows(_PROD_STORES))
        assert att["primary_cause"] == "客单价", att
        lg = att["laggard"]
        assert lg["store_name"] == "模拟·打浦桥日月光店"
        assert lg["delta_revenue"] == pytest.approx(-35623, abs=2)
        assert lg["traffic_effect"] == pytest.approx(-2444, abs=2)
        assert lg["ticket_effect"] == pytest.approx(-33218, abs=2)


class TestItOnlyNamesDimensionsWeActuallyHave:

    def test_can_check_line_lists_only_available_dimensions(self):
        """🔴 承重：「可以接着查」里出现一个 missing 维度 = 让老板白跑一趟。"""
        out = _APPEND("原文。", _factbook())
        can = _line_starting(out, "- 系统里有数、可以接着查：")
        for code in _PROD_MISSING:
            assert _label(code) not in can, (
                f"{_label(code)} 在 missing 里，却被说成「可以接着查」—— "
                f"老板照这条去查会一无所获")
        # 客单价那一支登记的 available 维度必须都在
        assert _label("promotion") in can and _label("dish_sales") in can, can

    def test_cannot_check_line_lists_only_missing_dimensions(self):
        out = _APPEND("原文。", _factbook())
        cannot = _line_starting(out, "- 现在查不了（这些数据还没接进来）：")
        assert _label("channel") in cannot and _label("meal_period") in cannot
        for code, _status in _PROD_AVAILABLE:
            assert _label(code) not in cannot, (
                f"{_label(code)} 明明有数，却被说成查不了")

    def test_partial_coverage_is_labelled_as_partial(self):
        """⚠️ `partial` 不标出来，就是把「看得见结构」说成了「算得出结论」。"""
        out = _APPEND("原文。", _factbook())
        can = _line_starting(out, "- 系统里有数、可以接着查：")
        marked = can.split(_label("promotion"))[1][:14]
        assert "数据不全" in marked, can
        # 阴性对照：status=available 的那一维**不许**被标成不全
        assert "数据不全" not in can.split(_label("dish_sales"))[1][:14], can

    def test_every_named_label_comes_from_the_catalog(self):
        """⛔ 不新造第二份措辞（形态 D）—— 每个粗体名都必须逐字来自 catalog。"""
        out = _APPEND("原文。", _factbook())
        named = set(__import__("re").findall(r"\*\*([^*]+)\*\*", out))
        known = {item.label for item in DIMENSIONS}
        assert named and named <= known, f"这些名字不在 catalog 里: {named - known}"


class TestItShutsUpWhenItHasNothingToSay:
    """⛔ 每次都说就是废话 —— 四道门各配一条阴性对照。"""

    def test_no_attribution_appends_nothing(self):
        fb = _factbook()
        fb.attribution = None
        assert _APPEND("原文。", fb) == "原文。"

    def test_no_data_attribution_appends_nothing(self):
        fb = _factbook()
        fb.attribution = {"no_data": True}
        assert _APPEND("原文。", fb) == "原文。"

    def test_attribution_without_a_laggard_appends_nothing(self):
        fb = _factbook()
        fb.attribution = dict(fb.attribution or {}, laggard={})
        assert _APPEND("原文。", fb) == "原文。"

    def test_unknown_primary_cause_appends_nothing(self):
        """主因是个我们没登记的词 ⇒ 闭嘴，⛔ 不许兜底成一段通用建议。"""
        fb = _factbook()
        fb.attribution = dict(fb.attribution or {}, primary_cause="毛利")
        assert _APPEND("原文。", fb) == "原文。"

    def test_when_no_listed_dimension_is_known_either_way_it_appends_nothing(self):
        fb = _factbook(available=(("revenue", "available"),), missing=("weather",))
        assert _APPEND("原文。", fb) == "原文。"


class TestTheLastResortSentenceDoesNotClaimExclusion:
    """第 ⑦ 步：⛔ 它不许宣称「都排除过了」—— 产品并没有逐条跑过。"""

    def test_it_is_present(self):
        out = _APPEND("原文。", _factbook())
        assert _INVESTIGATION_LAST_RESORT in out

    def test_it_never_claims_the_checks_were_already_done(self):
        for phrase in ("已排除", "都排除了", "已经排除", "排除完毕", "可以确定"):
            assert phrase not in _INVESTIGATION_LAST_RESORT, phrase
        assert "还没到那一步" in _INVESTIGATION_LAST_RESORT


class TestTheTrafficBranch:
    """⚠️ prod 上 `primary_cause` 恒为「客单价」⇒ 这一支**没有 prod 验收**。

    形态 B⁴：一道在验收环境里永远不被走到的分支，等于没验过。
    所以这里用构造数据把它走一遍，并在设计卡里登记「prod 未验证」。
    """

    def test_traffic_cause_uses_its_own_dimension_list(self):
        stores = (("A店", 2000000.0, 6000), ("B店", 1900000.0, 5700),
                  ("C店", 600000.0, 1800))
        att = compute_store_attribution(_rows(stores))
        assert att["primary_cause"] == "客流", att
        fb = _factbook(stores=stores)
        out = _APPEND("原文。", fb)
        assert "差距出在「客流」" in out
        cannot = _line_starting(out, "- 现在查不了（这些数据还没接进来）：")
        assert _label("physical_traffic") in cannot
        # 客单价那一支独有的维度⛔不许串进来
        assert _label("dish_sales") not in out


class TestTheMapIsExhaustiveOverWhatAttributionCanEmit:
    """主因是**闭集**（生产函数只产两个值）—— 那就必须逐个登记，⛔ 不许漏一个。"""

    def test_every_primary_cause_the_producer_can_emit_is_registered(self):
        tree = ast.parse(inspect.getsource(compute_store_attribution))
        emitted = {
            node.value
            for node in ast.walk(tree)
            if isinstance(node, ast.Assign)
            and any(getattr(t, "id", None) == "primary" for t in node.targets)
            and isinstance(node.value, ast.Constant)
            and isinstance(node.value.value, str)
        }
        emitted = {n.value for n in emitted}
        assert emitted, "没从生产函数里取到任何 primary_cause 字面量 —— 这条断言失去意义"
        assert emitted <= set(_CAUSE_INVESTIGATION_DIMENSIONS), (
            f"这些主因没有登记排查方向: {emitted - set(_CAUSE_INVESTIGATION_DIMENSIONS)}")

    def test_every_registered_code_is_a_real_catalog_dimension(self):
        for cause, codes in _CAUSE_INVESTIGATION_DIMENSIONS.items():
            assert codes, cause
            for code in codes:
                dimension_definition(code)   # 未登记的 code 会抛 ValueError


class TestItIsWiredEverywhereTheGuidanceIs:
    """机制在、没接上（形态 B）—— 接线数量必须与既有那一条逐一对应。"""

    @staticmethod
    def _call_lines(tree, name):
        return sorted(
            node.lineno
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and getattr(node.func, "attr", None) == name
        )

    def test_same_number_of_call_sites_as_dimension_guidance(self):
        from smartbi.agent import synthesis_engine as se

        tree = ast.parse(inspect.getsource(se))
        mine = self._call_lines(tree, "_append_investigation_directions")
        theirs = self._call_lines(tree, "_append_dimension_guidance")
        assert theirs, "没找到 `_append_dimension_guidance` 的调用点 —— 断言失去意义"
        assert len(mine) == len(theirs), (
            f"排查方向接了 {len(mine)} 处、缺失清单接了 {len(theirs)} 处 —— "
            f"少接一处就是有一条路上的老板永远看不到排查方向")

    def test_it_runs_before_the_missing_dimension_list_on_every_path(self):
        """⛔ 排查方向是结论的一部分，那张 12 条清单是附录 —— 顺序不能反。"""
        from smartbi.agent import synthesis_engine as se

        tree = ast.parse(inspect.getsource(se))
        mine = self._call_lines(tree, "_append_investigation_directions")
        theirs = self._call_lines(tree, "_append_dimension_guidance")
        for a, b in zip(mine, theirs):
            assert a < b, f"第 {a} 行的排查方向排在第 {b} 行的缺失清单之后"

    def test_rendered_order_puts_directions_before_the_missing_list(self):
        """端到端：两段真的拼起来之后，顺序仍然是「结论 → 附录」。"""
        fb = _factbook()
        out = ComprehensiveSynthesisEngine._append_dimension_guidance(
            _APPEND("原文。", fb), fb)
        assert out.index("### 差距出在") < out.index("### 还可补充的分析维度"), out
