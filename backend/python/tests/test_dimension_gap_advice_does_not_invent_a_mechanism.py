"""拒答可以说「我算不出」，⛔ 不许编一个**为什么**算不出。

## 缺陷（2026-08-18 prod 实测）

`_dimension_gap_advice` 的 `both` 分支原来对老板说：

    按门店我能算，但你这句还要求再按餐段拆一层，
    **这两层的数不在同一张表上**，拆不出来。

那半句是**硬编码**的，与 `asked` / `supported` / `plan` 全都无关 —— 对任何
维度组合都照打。而它是**假的**：

    fact_pos_transaction   99792 行
      store_id 为 NULL 的行 = 0      meal_period 为 NULL 的行 = 0
      门店 × 餐段 非空组合 = 20（10 店 × 午市/晚市）

两者就在**同一张表**上。（daypart resolver 甚至不读 `meal_period` 列，
它从**同一张表**的时间戳现算 —— 那样两层更是同源。）

▎系统知道的是「这次选中的算法不服务这一层」。
▎它**不知道**数据为什么拆不出来。编一个机制出来，老板会照着它去查数据接入，
▎白跑一趟，然后不再信这里说的话 —— 反目标里最重的那一条。

## ⚠️ 同型第二次，而且就在同一个函数里

该函数自己的注释记着上一次：建议词「门店或菜品」是硬编码的，
于是当 `extra` 恰好是门店时，它建议老板「把门店换成门店」。
判据当时就写下了：**建议必须由 `supported` 算出来，⛔ 不写死**。

上次写死的是**建议**，这次写死的是**理由**。⇒ 判据要抬到：
**说出来的每一句都得是算出来的。**

## ⚠️ 本文件的闸是**代理判据**（形态 E，标出来）

静态断言判不了「这句话是不是编的」。这里用两层近似：
  1. 行为层：拿**实测已知为假**的那个组合跑一次，正文不许出现数据布局断言
  2. 结构层：函数源码里不许出现一份写死的机制词表
两层都是近似 —— 它们拦得住这一类复发，⛔ 拦不住一句全新措辞的编造。
"""
from __future__ import annotations

import ast
import inspect

import pytest

from smartbi.gold.restaurant.restaurant_intent_service import (
    _dimension_gap_advice,
    _DIMENSION_LABEL,
)


class _Spec:
    """只带 `_dimension_gap_advice` 会读的那两个字段。"""

    def __init__(self, dimensions):
        self.dimensions = tuple(dimensions)


#: 断言「数据长什么样」的词。⚠️ 这是**代理判据** —— 它按词表匹配，
#: 拦得住这一类复发，拦不住一句全新措辞。⛔ 不要靠加词去逼近「编造」。
_DATA_LAYOUT_CLAIMS = (
    "同一张表", "不在同一", "两张表", "表里没有", "字段", "列",
    "没接入", "没有采集", "数据源",
)

#: 门店 × 餐段 —— **实测这两层同源**，所以任何「它们不在一起」的说法都是假的。
_MEASURED_FALSE_CASE = ("store", "meal_period")


def _advice(dims, plan):
    return _dimension_gap_advice(_Spec(dims), tuple(plan))


# ── 承重 ────────────────────────────────────────────────────────────────────

def test_it_does_not_claim_anything_about_where_the_data_lives():
    """🔴 用实测已知为假的那个组合跑：正文不许出现数据布局断言。"""
    text = _advice(_MEASURED_FALSE_CASE,
                   ("RESTAURANT_OPS_DAYPART_PERFORMANCE",))
    assert text, "这一组合本该给出具体的差 —— 空串说明用例没打中分支"
    hits = [w for w in _DATA_LAYOUT_CLAIMS if w in text]
    assert not hits, (
        f"又在编数据长什么样({hits}) —— 实测门店与餐段同源\n{text}"
    )


def test_it_still_says_which_layer_it_can_do():
    """阴性对照：⛔ 删掉编造的机制，**不许**把「差在哪一层」也一起删掉。

    那是这个函数存在的全部理由（形态 B 第 7 例：差是算出来的，只是没投影到用户侧）。
    """
    text = _advice(_MEASURED_FALSE_CASE,
                   ("RESTAURANT_OPS_DAYPART_PERFORMANCE",))
    assert _DIMENSION_LABEL["meal_period"] in text, "没说清差的是哪一层\n" + text
    assert "我能算" in text, "没说清哪一层能算\n" + text
    assert "分开问" in text or "先问" in text, "没给老板下一步动作\n" + text


def test_the_suggestion_is_computed_not_hardcoded():
    """阴性对照：建议里的维度名必须**跟着 supported 变**（上一次同型缺陷）。"""
    a = _advice(("store", "meal_period"), ("RESTAURANT_OPS_DAYPART_PERFORMANCE",))
    b = _advice(("dish", "meal_period"), ("RESTAURANT_OPS_GROSS_MARGIN",))
    assert a != b, "两个不同的 supported 给出同一句话 —— 建议多半又写死了"
    assert _DIMENSION_LABEL["meal_period"] in a
    assert _DIMENSION_LABEL["dish"] in b


def test_no_extra_dimension_says_nothing():
    """阴性对照：没有多出来的维度时 ⛔ 一个字都不许说。"""
    assert _advice(("store",), ("RESTAURANT_OPS_SALES_SUMMARY",)) == ""
    assert _advice((), ("RESTAURANT_OPS_SALES_SUMMARY",)) == ""


def test_the_no_supported_branch_talks_about_the_algorithm():
    """`supported` 为空那一支已经做对了 —— 它说**算法**，不说数据。

    ⛔ 这条是防回退：两支现在口径一致，别把其中一支改回去。
    """
    text = _advice(("store",), ("RESTAURANT_OPS_DISCOUNT_SUMMARY",))
    assert "算法" in text, text
    hits = [w for w in _DATA_LAYOUT_CLAIMS if w in text]
    assert not hits, f"这一支也开始编数据布局了({hits})\n{text}"


# ── 结构闸（代理判据，见模块 docstring）────────────────────────────────────

def test_the_function_has_no_hardcoded_mechanism_words():
    """⛔ 函数源码里的**字符串常量**不许含数据布局词。

    ⚠️ 代理判据：它量的是「有没有这几个词」，不是「这句话是不是编的」。
    ⛔ 不要靠往 `_DATA_LAYOUT_CLAIMS` 里加词来逼近「编造」——
       那会做出一个更复杂、误报更多、仍然漏新措辞的东西（形态 E）。

    ⛔ 用 AST 只看 `Constant` 字符串，**不看注释和 docstring** ——
       上面那段注释里就写着「不在同一张表上」（那是记的原话），
       grep 会把它数进来（形态 C⁸，本仓栽过三次）。
    """
    tree = ast.parse(inspect.getsource(_dimension_gap_advice))
    body = [n for n in tree.body[0].body]
    # 去掉 docstring 节点
    if (body and isinstance(body[0], ast.Expr)
            and isinstance(body[0].value, ast.Constant)
            and isinstance(body[0].value.value, str)):
        body = body[1:]

    literals = [
        node.value
        for stmt in body
        for node in ast.walk(stmt)
        if isinstance(node, ast.Constant) and isinstance(node.value, str)
    ]
    # 阳性对照：扫描得真的扫到东西，否则这条断言恒真
    assert any("我能算" in s for s in literals), (
        f"AST 没扫到函数里的文案（拿到 {literals[:5]}）—— 这条断言没有意义"
    )

    offenders = [(s, w) for s in literals for w in _DATA_LAYOUT_CLAIMS if w in s]
    assert not offenders, f"文案里又写死了数据布局的说法: {offenders}"


@pytest.mark.parametrize("plan,dims", [
    (("RESTAURANT_OPS_DAYPART_PERFORMANCE",), ("store", "meal_period")),
    (("RESTAURANT_OPS_SALES_SUMMARY",), ("store", "dish")),
    (("RESTAURANT_OPS_STORE_MARGIN",), ("store", "dish", "meal_period")),
    (("RESTAURANT_OPS_DISCOUNT_SUMMARY",), ("store",)),
    (("RESTAURANT_OPS_INVENTORY_WARNING",), ("store",)),
])
def test_no_combination_invents_a_mechanism(plan, dims):
    """跨组合：⛔ 任何一种维度组合都不许编数据布局。

    ⚠️ 被参数化的那一维要真的不同 —— 下面那条断言钉住它
       （否则「5 次全绿」可能只是同一个样本跑了 5 遍）。
    """
    text = _advice(dims, plan)
    hits = [w for w in _DATA_LAYOUT_CLAIMS if w in text]
    assert not hits, f"{plan} × {dims} 编了数据布局({hits})\n{text}"


def test_the_parametrized_cases_are_actually_different():
    """阳性对照：上面那 5 组必须产出**互不相同**的文案。"""
    texts = {
        _advice(dims, plan)
        for plan, dims in [
            (("RESTAURANT_OPS_DAYPART_PERFORMANCE",), ("store", "meal_period")),
            (("RESTAURANT_OPS_SALES_SUMMARY",), ("store", "dish")),
            (("RESTAURANT_OPS_STORE_MARGIN",), ("store", "dish", "meal_period")),
            (("RESTAURANT_OPS_DISCOUNT_SUMMARY",), ("store",)),
            (("RESTAURANT_OPS_INVENTORY_WARNING",), ("store",)),
        ]
    }
    assert len(texts) >= 4, (
        f"5 组只产出 {len(texts)} 种文案 —— 参数化多半没在参数化\n{texts}"
    )
