"""涉钱答案的角色闸 —— 结构性判据, 不是逐个 resolver 的补丁。

## 2026-08-01 prod 实拍(MOCK_REST, 同一问句换角色)

    问句                        resolver            老板          后厨
    哪家店毛利最好              STORE_MARGIN        ¥34,959,425   看不到   <- 有门
    损耗金额最高的食材          WASTAGE_TOP         ¥278,254.85   ¥278,254.85
    采购花了多少钱              REQUISITION_TREND   ¥7,094,935    ¥7,094,935
    盘点亏了多少                STOCK_SHORTAGE      ¥5,836.21     ¥5,836.21

对照组(STORE_MARGIN)证明脱敏机制本身是好的 —— 它在声明了的地方精确生效、
在没声明的地方精确失效。

## 根因是**机制**, 不是那几个 resolver 忘了写

`resolve_by_code` 按签名过滤 kwargs, 没声明 `role` 的 resolver 拿不到它。
这一点写在它自己的 docstring 里: "legacy resolvers silently ignore ``role``"。
与 #2076 丢 `date_range` 是同一个机制 —— 那次的后果是答错时间窗, 这次是钱不脱敏。

于是 RBAC 变成**逐个 resolver 自愿加入, 而「没加入」没有任何东西会发现**:
9 个涉钱 resolver 里 5 个加了、4 个没加, 谁都没报错。

## 所以这里钉的是「不可能静默漏掉」而不是「这 4 个已经修好」

`test_every_resolver_is_classified` 让新增 resolver **必须**做一次分类决定,
否则红。这才是让第 15 个 resolver 不重蹈覆辙的东西。
"""
from __future__ import annotations

import re

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import (
    _MONEY_BEARING_INTENTS,
    _MONEY_GATE_ALTERNATIVES,
    _MONEY_SELF_MASKING_INTENTS,
    _NO_MONEY_INTENTS,
    _RESOLVERS,
    resolve_by_code,
)

MONEY = re.compile(r"¥\s*[\d,]+\.?\d*|[\d,]+\.\d{2}\s*元")
UNPRIVILEGED = "restaurant_chef"


class _ExplodingPool:
    """任何 DB 访问都炸 —— 用来证明脱敏是在**查库之前**短路的。

    如果闸放在查完之后再擦文本, 数据已经被取出来了, 而且擦文本随时可能漏掉
    一种新的金额写法。短路才是「结构上不可能泄露」。
    """

    def acquire(self):  # noqa: D401
        raise AssertionError("脱敏路径不应该访问数据库 —— 闸放晚了")


def test_every_resolver_is_classified():
    """新增 resolver 必须显式分类, 不能静默继承「不脱敏」。"""
    classified = set(_MONEY_BEARING_INTENTS) | set(_NO_MONEY_INTENTS)
    unclassified = sorted(set(_RESOLVERS) - classified)
    assert not unclassified, (
        f"这些 resolver 没有声明是否涉钱: {unclassified}\n"
        "把它加进 _MONEY_BEARING_INTENTS 或 _NO_MONEY_INTENTS —— "
        "不分类就等于默认不脱敏, 那正是 2026-08-01 泄露的成因。"
    )
    overlap = sorted(set(_MONEY_BEARING_INTENTS) & set(_NO_MONEY_INTENTS))
    assert not overlap, f"同时出现在两张表里: {overlap}"
    stale = sorted(classified - set(_RESOLVERS))
    assert not stale, f"分类表里有已经不存在的 resolver: {stale}"


def test_self_masking_resolvers_can_actually_see_the_role():
    """⛔ 本文件最重要的一条。

    声明「我自己就地脱敏」的 resolver, 签名里**必须**有 `role` —— 没有 role 就
    根本收不到角色(resolve_by_code 按签名过滤 kwargs), 也就不可能脱敏。

    2026-08-01 的泄露正是这个形态: 4 个涉钱 resolver 既没有 role 参数, 中央也没有
    闸, 于是后厨拿到了 ¥7,094,935 的采购总额。这条让「声明了却做不到」直接变红。
    """
    import inspect

    broken = []
    for code in sorted(_MONEY_SELF_MASKING_INTENTS):
        fn = _RESOLVERS[code]
        params = inspect.signature(fn).parameters
        has_catch_all = any(
            p.kind is inspect.Parameter.VAR_KEYWORD for p in params.values()
        )
        if "role" not in params and not has_catch_all:
            broken.append(code)
    assert not broken, (
        f"这些 intent 声明自己脱敏, 但 resolver 签名里没有 role, 收不到角色: {broken}\n"
        "要么给 resolver 加 role 参数并真的脱敏, 要么把它从 "
        "_MONEY_SELF_MASKING_INTENTS 移出去交给中央闸。"
    )


def test_gate_alternatives_cover_every_centrally_gated_intent():
    """被中央拦的 intent 必须有替代问法, 否则脱敏就退化成一句冷冰冰的拒绝。"""
    centrally_gated = set(_MONEY_BEARING_INTENTS) - set(_MONEY_SELF_MASKING_INTENTS)
    missing = sorted(centrally_gated - set(_MONEY_GATE_ALTERNATIVES))
    assert not missing, f"这些 intent 被拦了却没给替代问法: {missing}"


@pytest.mark.parametrize(
    "code", sorted(set(_MONEY_BEARING_INTENTS) - set(_MONEY_SELF_MASKING_INTENTS)),
)
@pytest.mark.asyncio
async def test_money_intent_is_masked_for_unprivileged_role(code):
    answer = await resolve_by_code(
        code, _ExplodingPool(), "F_TEST", role=UNPRIVILEGED,
    )
    assert answer is not None, f"{code} 脱敏路径返回了 None"
    assert answer.meta.get("rbac_masked") is True, f"{code} 没有标记 rbac_masked"
    assert not MONEY.findall(answer.answer_text or ""), (
        f"{code} 对无价格权限角色仍吐出金额: {answer.answer_text[:160]}"
    )


@pytest.mark.parametrize(
    "code", sorted(set(_MONEY_BEARING_INTENTS) - set(_MONEY_SELF_MASKING_INTENTS)),
)
@pytest.mark.asyncio
async def test_masked_answer_still_tells_the_user_what_they_can_do(code):
    """脱敏不是甩一句「无权限」—— 既有 STORE_MARGIN 的写法会给出量视角的替代问法,
    这条守住那个质量, 免得后来的实现退化成一句冷冰冰的拒绝。"""
    answer = await resolve_by_code(
        code, _ExplodingPool(), "F_TEST", role=UNPRIVILEGED,
    )
    text = answer.answer_text or ""
    assert "权限" in text, text
    assert len(text) > 40, f"{code} 的脱敏文案太短, 没有告诉用户能做什么: {text}"


@pytest.mark.asyncio
async def test_no_role_is_treated_as_unprivileged():
    """缺省 role(内部调用/老客户端)必须 fail-closed, 不能当成有权限。"""
    answer = await resolve_by_code(
        "RESTAURANT_OPS_REQUISITION_TREND", _ExplodingPool(), "F_TEST",
    )
    assert answer.meta.get("rbac_masked") is True
