"""澄清按钮的合成问句 —— 产出侧(`_clarification_followups`)与消费侧
(`_approved_exact_continuation_route` / `_trusted_named_dish_button_continuation` /
`_parse_continuation`)必须能对上。

## 缺陷背景 (2026-08-16)

`_clarification_followups` 把按钮的 `question` 从光秃秃的词(`本月`)改成了
合成的完整问句(`本月哪个菜卖得好`) —— 原因是光秃秃的词成了进程内计划缓存
全系统最高频的键, 而键里不含会话上下文, 生产实测一条对话的计划被另一条
对话命中(见 `tests/test_clarification_buttons_compose_full_question.py`)。

但**接收侧**三处判据(`_approved_exact_continuation_route` /
`_trusted_named_dish_button_continuation` / `_parse_continuation` 的
`concatenated`)全是按「答案是个片段」写的: 白名单是精确相等, 拼接是
`f"{original_query} {answer}"`。改了发什么没改认什么 ⇒ 零 LLM 确定性延续
整条失效(实测 4/4 HIT → 0/4), 每次点按钮退回 T3 编译(~2.1s), LLM 挂掉时
链断; 且拼接结果把原问句重复一遍。

## 本文件守的判据

本次代码评审的核心批评是: **没有一条测试跨过产出侧→消费侧的边界。**
所以承重测试必须把按钮的 `question` **原样取自产出侧**
(`_clarification_followups` 的返回值), 喂给消费侧 —— ⛔ 不手写裸词或合成句,
那正是产品已经不再产出/尚未产出过的形状(本仓形态 B‴: 桩的自由度会让人
构造出真实那侧永远不产出的形状)。
"""
from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest

from smartbi.gold.restaurant.restaurant_intent import (
    RestaurantQuerySpec,
    TIME_CLARIFICATION_QUESTION,
    _approved_exact_continuation_route,
    _approved_exact_shape,
    _button_answer_fragment,
    _parse_continuation,
    _trusted_named_dish_button_continuation,
)
from smartbi.gold.restaurant.restaurant_intent_service import _clarification_followups

# `_approved_exact_shape` 接受的白名单问句之一 —— ⚠️ 这不是任选的: 用一个
# 不在 `_APPROVED_EXACT_ROUTES` 里的种子, `_approved_exact_continuation_route`
# 会在判据之前就因 `_approved_exact_shape(...) is None` 提前返回 None,
# 阳性对照会静默读成 0(交接里点名烧过一次探针在这上面)。
SEED = "哪个菜卖得好"
SEED_CODE = "RESTAURANT_OPS_GROSS_MARGIN"

# `_trusted_named_dish_button_continuation` 走的是另一套准入(命名菜品 +
# 受信指标), 用仓里现成的、被多处既有测试验证过能编译成功的种子
# (test_restaurant_intent.py / test_clarification_buttons_compose_full_question.py
# 都用它), ⛔ 不要自己另编一个没被验证过的种子。
DISH_SEED = "米饭的销量是多少"


def _spec(**overrides) -> RestaurantQuerySpec:
    """⚠️ 用**真的** RestaurantQuerySpec, ⛔ 不用 SimpleNamespace ——
    抄自 `tests/test_clarification_buttons_compose_full_question.py` 的同名
    helper(同一个被测模块, 12 个必填字段不能只传 clarification_question)。
    """
    defaults = dict(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        domain="restaurant",
        date_range=(None, None),
        window_label="全部历史",
        relative_window=False,
        metrics=(),
        wants_margin=False,
        asks_profitability=False,
        dimensions=(),
        comparison=None,
        confidence=0.9,
        source_tier="keyword",
        clarification_needed=True,
        clarification_question=None,
    )
    defaults.update(overrides)
    return RestaurantQuerySpec(**defaults)


# ── 前置事实: 种子确实是已批准的零 LLM 路由 ─────────────────────────────
def test_fixture_seed_is_an_approved_exact_route():
    """⚠️ 少了它, 下面所有 `_approved_exact_continuation_route` 断言在
    `_approved_exact_shape` 判不出这个种子时会**静默读成 0** —— 交接里
    点名烧过一次探针在这上面。"""
    assert _approved_exact_shape(SEED) == (SEED_CODE, False, False)


# ══════════════════════════════════════════════════════════════════
# 1. 端到端(承重): 产出侧的 question 原样喂给消费侧, 四个时间按钮全部 HIT
# ══════════════════════════════════════════════════════════════════
def test_producer_buttons_hit_the_consumer_for_all_four_time_windows():
    spec = _spec(clarification_question=TIME_CLARIFICATION_QUESTION)
    buttons = _clarification_followups(spec, SEED)

    # 阳性对照: 拿到了按钮, 且它们确实是**合成句**而不是光秃秃的词 ——
    # 否则下面"HIT"可能只是因为按钮意外退化成了旧形状。
    assert len(buttons) == 4, buttons
    for b in buttons:
        assert b["question"] != b["label"], (
            f"按钮 {b} 的 question 退化成了裸词, 这条测试就测不到合成句的形状了")
        assert b["question"].endswith(SEED), b

    for b in buttons:
        routed = _approved_exact_continuation_route(
            SEED, b["question"], TIME_CLARIFICATION_QUESTION)
        assert routed == SEED_CODE, (
            f"按钮 {b} 的合成句没有被接收侧认出 —— 零 LLM 延续断了: {b}")


# ══════════════════════════════════════════════════════════════════
# 2. 阳性对照: 旧的光秃秃的词仍然 HIT(证明白名单本身没被动过)
# ══════════════════════════════════════════════════════════════════
@pytest.mark.parametrize("window", ["本月", "上个月", "最近7天", "最近30天"])
def test_legacy_bare_word_answer_still_hits(window):
    assert _approved_exact_continuation_route(
        SEED, window, TIME_CLARIFICATION_QUESTION) == SEED_CODE


# ══════════════════════════════════════════════════════════════════
# 3. 阴性对照: helper 不许放宽授权范围
# ══════════════════════════════════════════════════════════════════
def test_arbitrary_free_text_still_declines():
    """用户手打的自由文本 —— 不是 `<片段>+original_query` 的形状,
    helper 原样返回, 判据照旧拒绝。"""
    assert _approved_exact_continuation_route(
        SEED, "随便问点别的", TIME_CLARIFICATION_QUESTION) is None


def test_a_sentence_ending_with_original_query_but_not_an_approved_window_declines():
    """⛔ 这是本条修复最容易踩空的角: 一个**恰好**以 original_query 结尾的串,
    还原出来的片段(`随便看看`)不在批准窗口表里 —— 必须仍然 None。

    少了这条, `_button_answer_fragment` 会被误用成"任何以 original_query
    结尾的串都能通过", 那就不是"认识合成句", 是放宽了白名单。
    """
    answer = f"随便看看{SEED}"
    assert _button_answer_fragment(SEED, answer) == "随便看看"
    assert _approved_exact_continuation_route(
        SEED, answer, TIME_CLARIFICATION_QUESTION) is None


# ══════════════════════════════════════════════════════════════════
# 4. 不重复拼接: 消费侧算出的合成串里 original_query 只出现一次
# ══════════════════════════════════════════════════════════════════
def test_trusted_dish_continuation_combined_string_is_not_doubled():
    """`_trusted_named_dish_button_continuation` 的 `combined` 现在必须用
    还原后的片段, ⛔ 不能是 `f"{original_query} {合成句}"`(那会把
    original_query 重复一遍, 并流入 resolver_query_seed → question_seed →
    答案自己的换时间按钮)。

    直接断言 `resolver_query_seed` 的**实际值**, 不只断言"不为 None"。
    """
    composed_answer = f"本月{DISH_SEED}"
    spec = _trusted_named_dish_button_continuation(
        DISH_SEED, composed_answer, TIME_CLARIFICATION_QUESTION)

    assert spec is not None, "夹具失效: 命名菜品+销量的可信上下文按钮路径没有命中"
    assert spec.resolver_query_seed == f"{DISH_SEED} 本月", spec.resolver_query_seed
    assert spec.resolver_query_seed.count(DISH_SEED) == 1, (
        f"original_query 在合成串里出现了不止一次: {spec.resolver_query_seed!r}")
    assert spec.window_label == "本月"
    assert spec.dish_slot == "米饭"


@pytest.mark.asyncio
async def test_parse_continuation_concatenated_is_not_doubled():
    """`_parse_continuation` 里的 `concatenated` 同样不许把 original_query
    拼两遍 —— 用 promoted_code 分支直接把它送进 `_build_spec` 的路径断言
    `resolver_query_seed` 的实际值(见该分支: `_build_spec(promoted_code,
    concatenated, ...)`, 未传 `time_phrase` 时 `effective_query == query`,
    `resolver_query_seed == effective_query`)。

    只桩 `_is_restaurant_tenant`(唯一的 I/O), 其余全走真实代码路径。
    """
    pending = {
        "original_query": SEED,
        "clarification_question": TIME_CLARIFICATION_QUESTION,
    }
    composed_answer = f"本月{SEED}"

    with patch(
        "smartbi.gold.restaurant.restaurant_intent._is_restaurant_tenant",
        new=AsyncMock(return_value=True),
    ):
        spec = await _parse_continuation(
            composed_answer, object(), factory_id="DEMO_REST", pending=pending)

    assert spec is not None
    assert spec.intent == SEED_CODE
    assert spec.planner_authority == "promoted_exact"
    assert spec.resolver_query_seed == f"{SEED} 本月", spec.resolver_query_seed
    assert spec.resolver_query_seed.count(SEED) == 1, (
        f"original_query 在 concatenated 里出现了不止一次: {spec.resolver_query_seed!r}")


# ══════════════════════════════════════════════════════════════════
# 5. answer == original_query 精确相等 → 不还原(没有可还原的片段)
# ══════════════════════════════════════════════════════════════════
def test_answer_equal_to_original_query_is_returned_unchanged():
    assert _button_answer_fragment(SEED, SEED) == SEED


def test_empty_original_query_is_a_noop():
    """`original_query` 为空(理论上不该发生, 但 helper 不应该因此炸或误判) ——
    guard 里 `q and ...` 短路, 原样返回。"""
    assert _button_answer_fragment("", "随便什么") == "随便什么"


def test_shorter_or_equal_length_answer_is_never_reduced():
    """`len(a) > len(q)` 这一半准入是承重的: 答案不比 original_query 长,
    不可能是 `<片段>+original_query` 的形状, 不许尝试还原。"""
    assert _button_answer_fragment(SEED, "短") == "短"
