"""答案契约要认「因权限而缺」一态，⛔ 不当成「算不出来」。

## 缺口的实测长相（2026-08-18，改之前跑出来的读数）

    A 无价格权限被遮蔽    missing = ['profitability_verdict']
    C 有权限但真算不出来   missing = ['profitability_verdict']

▎**逐字相同。** 下游据此拼出「这次没算出是否赚钱的判断…说清楚具体范围我再试
一次」—— 两处都是假的: 算得出、是不给他看; 而他把范围说一百遍也拿不到,
因为拦他的是角色不是范围。

## 这一批断言各自在守什么

每条用例的 docstring 写明它守的**行为**（⛔ 不是它调了哪个函数）——
变异要打在那个行为上, 不是随手改一行实现。

设计卡: docs/decisions/2026-08-18-契约认因权限而缺-设计卡.md
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant import answer_contract as contract
from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec


def _spec(**overrides) -> RestaurantQuerySpec:
    defaults = dict(
        intent="RESTAURANT_OPS_SALES_SUMMARY", domain="restaurant",
        date_range=(None, None), window_label="全部历史", relative_window=False,
        metrics=("revenue",), wants_margin=False, asks_profitability=False,
        dimensions=(), comparison=None, confidence=0.95, source_tier="keyword",
    )
    defaults.update(overrides)
    return RestaurantQuerySpec(**defaults)


def _money_spec(**overrides) -> RestaurantQuerySpec:
    """一个「问赚不赚钱」的 spec —— 金额元素全都被要求到。"""
    base = dict(
        asks_profitability=True, wants_margin=True,
        window_label="最近30天", relative_window=True,
    )
    base.update(overrides)
    return _spec(**base)


#: 无价格权限时 resolver 逐字产出的文本（restaurant_ops_router.py:4588-4599）。
#: 三件事齐全: 缺什么 / 他自己要干什么 / 怎么拿到。
MASKED_TEXT_THREE_THINGS = (
    "菜品毛利、成本和营收金额属于成本/价格权限，当前角色不能查看金额。"
    "可以先看销量视角：问「哪个菜卖得好」看菜品销量排行；"
    "如需毛利数据请联系管理员开通价格查看权限。"
)

#: `resolve_sales_summary` 就地脱敏时的正文形状: 金额位是 `***`,
#: 而 `if spec.wants_margin:` 整块挂在 `if can_see_money:` 下且**没有 else**
#: ⇒ 毛利/权限这件事正文里一个字都不说（restaurant_ops_router.py:8256 起）。
STRIPPED_TEXT_SAYS_NOTHING = (
    "最近30天经营能看：覆盖 30 天、5 家门店，共 12,345 单。"
    "总营收 ***，平均每单 ***。\n\n"
    "建议：先把低于中位的门店拉出来，看是客流少、平均每单低，还是折扣过重。"
)

#: 有价格权限、金额都在，只是**真的**算不出毛利（全店没有成本卡）。
GENUINELY_UNCOMPUTABLE_TEXT = (
    "最近30天 全部门店营收 ¥1,234,567.00，共 12,345 单。\n"
    "毛利这次算不出来：门店里 0/58 道菜有完整成本卡。"
)


# ── 1. 核心: 两态必须结构性可分 ──────────────────────────────────────────

def test_permission_gap_and_uncomputable_are_two_states_not_one():
    """守的行为: **「因权限而缺」与「算不出来」不许再产出同一个读数。**

    这是整条改动的存在理由 —— 改之前两者的 `missing` 逐字相同,
    老板对着一个他永远拿不到的东西被告知「说清楚具体范围我再试一次」。
    """
    by_permission = contract.validate(
        _money_spec(intent="RESTAURANT_OPS_GROSS_MARGIN"),
        MASKED_TEXT_THREE_THINGS, kpis=[], meta={"rbac_masked": True},
    )
    uncomputable = contract.validate(
        _money_spec(), GENUINELY_UNCOMPUTABLE_TEXT, kpis=[],
        meta={"price_view": True, "margin": {"marginInvariantPass": True}},
    )

    # ⛔ 判据不是「措辞不同」, 是**两个读数在结构上不相等**。
    assert by_permission.blocked_by_permission is True
    assert uncomputable.blocked_by_permission is False
    assert by_permission.permission_blocked != uncomputable.permission_blocked


def test_permission_masked_answer_is_not_reported_as_uncomputable():
    """守的行为: 正文已经说清三件事时, 契约**不再判它失败** ——
    老板拿到的是 resolver 那段三件事齐全的话, 而不是「这次没算出…」。"""
    result = contract.validate(
        _money_spec(intent="RESTAURANT_OPS_GROSS_MARGIN"),
        MASKED_TEXT_THREE_THINGS, kpis=[], meta={"rbac_masked": True},
    )
    assert result.passed is True, f"仍在判失败: {result!r}"
    assert "profitability_verdict" in result.permission_blocked
    assert result.missing == []


def test_the_money_gate_answer_actually_shipped_satisfies_the_criterion():
    """守的行为: 判据钉在**真实上游产出**上, 不是钉在测试自己写的桩上。

    直接调 `restaurant_ops_router._money_masked_answer`（纯函数, 不碰库）——
    它就是无价格权限时中央金额闸真正发给用户的那个 `OpsAnswer`。

    ⚠️ 这条同时是**防漂闸**: 谁把那段文案里的「联系管理员开通」或替代问法
       删掉, 这里立刻红 —— 判据与真实文案是**同一份**, ⛔ 不是两份。
    """
    from smartbi.gold.restaurant.restaurant_ops_router import _money_masked_answer

    answer = _money_masked_answer("RESTAURANT_OPS_RECIPE_COST")
    assert answer.meta.get("rbac_masked") is True, "上游形状变了, 这条判据的前提没了"

    result = contract.validate(
        _money_spec(intent="RESTAURANT_OPS_GROSS_MARGIN"),
        answer.answer_text, kpis=answer.kpis, meta=answer.meta,
    )
    assert result.passed is True, f"真实上游的遮蔽答案被判失败: {result!r}"
    assert result.permission_explained is True
    assert result.blocked_by_permission is True


# ── 2. 最硬的对照: 真算不出来的那一侧不许被吞进权限态 ────────────────────

def test_genuinely_uncomputable_is_never_blamed_on_permission():
    """守的行为: **有权限、真算不出来** 的答案, `permission_blocked` 必须是空。

    🔴 这条红了比不修更糟: 老板会照着提示去找管理员开一个他**已经有**的权限,
       去查一无所获 —— 那正是「一条误发的提示烧掉的是可信」。
    """
    result = contract.validate(
        _money_spec(), GENUINELY_UNCOMPUTABLE_TEXT, kpis=[],
        meta={"price_view": True, "margin": {"marginInvariantPass": True}},
    )
    assert result.permission_blocked == []
    assert result.blocked_by_permission is False
    assert result.passed is False, "真算不出来的那一侧行为不该变"
    assert "profitability_verdict" in result.missing


@pytest.mark.parametrize("meta", [
    {},
    {"margin": {"marginInvariantPass": True}},
    {"price_view": None},
    {"rbac_masked": False},
])
def test_a_resolver_that_does_not_report_price_view_is_not_assumed_unauthorised(meta):
    """守的行为: **缺省不许被推断成「没权限」。**

    `price_view` 缺省表示「这个 resolver 不报告价格权限」, 不是「没权限」。
    把它按真值判断, 所有不报告的 resolver 会一起被误分类 —— 那是一次
    大面积的误发提示。fail-safe 方向是**保持今天的行为**。
    """
    result = contract.validate(
        _money_spec(), GENUINELY_UNCOMPUTABLE_TEXT, kpis=[], meta=dict(meta),
    )
    assert result.permission_blocked == []
    assert result.blocked_by_permission is False


# ── 3. 说清与没说清: 「你没这个权限」只说了三件事里的一件 ────────────────

def test_stripped_answer_that_explains_nothing_keeps_todays_behaviour():
    """守的行为: 上游把金额剥了却**一个字不说明**时, 行为逐字不变(仍判失败),
    只是把成因标注出来。

    ⛔ 不在契约里放行 —— 放行只是把「假建议」换成「沉默」, 两条都不满足
       「说清缺什么 / 怎么拿到 / 他自己要干什么」。那句话该由 resolver 出。
    """
    result = contract.validate(
        _money_spec(), STRIPPED_TEXT_SAYS_NOTHING, kpis=[],
        meta={"price_view": False, "margin": {"marginInvariantPass": True}},
    )
    assert result.passed is False
    assert result.permission_explained is False
    # 行为不变: 缺的还是那两项, 与改动之前的读数逐字相同
    assert result.missing == ["profitability_verdict", "margin_value"]
    # 但成因已经标注出来, 这一类的规模从此可量
    assert result.permission_blocked == ["profitability_verdict", "margin_value"]


@pytest.mark.parametrize("text,missing_piece", [
    (
        "菜品毛利属于价格查看权限，当前角色不能查看金额。",
        "只说了缺什么",
    ),
    (
        "菜品毛利属于价格查看权限，当前角色不能查看金额。"
        "如需毛利数据请联系管理员开通价格查看权限。",
        "没说他自己能干什么",
    ),
    (
        "菜品毛利属于价格查看权限，当前角色不能查看金额。"
        "可以先看销量视角：问「哪个菜卖得好」看菜品销量排行。",
        "没说怎么拿到",
    ),
])
def test_saying_only_that_he_lacks_permission_does_not_count(text, missing_piece):
    """守的行为: 三件事**缺一件就不算说清**。

    交付定义第 5 条: 答不了的时候要说清缺什么、怎么拿到、他自己要干什么;
    「只说拿不到」不算数。
    """
    result = contract.validate(
        _money_spec(intent="RESTAURANT_OPS_GROSS_MARGIN"),
        text, kpis=[], meta={"rbac_masked": True},
    )
    assert result.permission_explained is False, missing_piece
    assert result.passed is False, missing_piece


# ── 4. 范围: 权限态只吞金额元素, 不许吞掉真实缺口 ────────────────────────

def test_permission_state_does_not_swallow_non_money_gaps():
    """守的行为: 非金额缺口(这里是「问了顾客评价却没答」)**不许**被权限态吞掉。

    把 `request_coverage` 收进金额元素表, 会让一个与钱无关的真实缺口
    静默消失 —— 那是「量错了对象」。
    """
    spec = _money_spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        requested_metrics=("customer_review",),
    )
    result = contract.validate(
        spec, MASKED_TEXT_THREE_THINGS, kpis=[], meta={"rbac_masked": True},
    )
    assert "request_coverage" in result.missing, "非金额缺口被权限态吞掉了"
    assert "request_coverage" not in result.permission_blocked
    assert result.passed is False


def test_permission_state_does_not_swallow_a_missing_dish_name():
    """守的行为: 同上, 维度类缺口(该点名的菜没点)也不许被权限态吞掉。"""
    spec = _money_spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        dimensions=("dish",), dish_slot="小炒黄牛肉",
    )
    result = contract.validate(
        spec, MASKED_TEXT_THREE_THINGS, kpis=[], meta={"rbac_masked": True},
    )
    assert "dish_name" in result.missing
    assert "dish_name" not in result.permission_blocked


# ── 5. 接线: 多意图合并时信号藏在 sub_results 里 ─────────────────────────

def test_signal_is_found_when_it_only_survives_inside_sub_results():
    """守的行为: 多意图合并只把 `rbac_masked` 提升到顶层,
    `price_view` 留在 `sub_results` 里 —— 判据要够得着它。

    够不着的话这个特性在多意图问句上**静默失效**(机制在、没接上)。
    """
    result = contract.validate(
        _money_spec(intent="RESTAURANT_OPS_GROSS_MARGIN"),
        MASKED_TEXT_THREE_THINGS, kpis=[],
        meta={"sub_results": {
            "RESTAURANT_OPS_SALES_SUMMARY": {"price_view": False},
        }},
    )
    assert result.blocked_by_permission is True
    assert result.passed is True


def test_result_repr_carries_the_new_dimension():
    """守的行为: 排查时 `repr` 要能一眼看出这是哪一态 ——
    只印 `missing` 的话, A 与 C 在日志里又变成同一个样子。"""
    result = contract.validate(
        _money_spec(intent="RESTAURANT_OPS_GROSS_MARGIN"),
        MASKED_TEXT_THREE_THINGS, kpis=[], meta={"rbac_masked": True},
    )
    assert "permission_blocked" in repr(result)
