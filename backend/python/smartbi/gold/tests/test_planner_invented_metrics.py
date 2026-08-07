"""契约要求覆盖的是「用户问了什么」，不是「planner 想到了什么」。

🔴 2026-08-07 prod 落库记录（`smart_bi_llm_fallback_log.agg_meta`）：

    query='最近30天各门店对比如何'
    analysis_action=compare  dimensions=['store']
    requested_metrics=['revenue','orders','sales_volume']
    planned_intents=['RESTAURANT_OPS_STORE_MARGIN','RESTAURANT_OPS_SALES_SUMMARY']
    contract_pass=false

用户只说了「各门店对比如何」—— **三个指标全是 T3 自己编的**。而
`_request_coverage_present` 要求答案里每个 requested_metric 都出现对应词，答案给了
营收/订单却没有「销量」，契约不过 → 用户拿到反问。

🔑 判据：**契约的目的是防「答非所问」，而用户从没提过的指标不可能让答案变成
答非所问** —— 它只能造成假拒。
"""
from dataclasses import dataclass, field
from typing import Tuple

import pytest

from smartbi.gold.restaurant.answer_contract import _REQUEST_TEXT_TOKENS
from smartbi.gold.restaurant.restaurant_intent_service import (
    _drop_planner_invented_metrics as drop,
)


@dataclass
class _Spec:
    requested_metrics: Tuple[str, ...] = field(default=())


ALL_THREE = ("revenue", "orders", "sales_volume")


def test_vague_comparison_drops_every_invented_metric():
    """prod 那条的形状: 一个指标词都没沾, 三个全是编的。"""
    got = drop(_Spec(ALL_THREE), "最近30天各门店对比如何")
    assert got.requested_metrics == ()


def test_named_metric_is_kept():
    """🔴 用户说了「销量」, 那就是他要的 —— 答案没给就该老实失败。

    只满足「planner 加的」不够: 必须真的是**原句里一个词都没沾**才去掉,
    否则会把真实的覆盖不足掩盖成通过。
    """
    got = drop(_Spec(ALL_THREE), "最近30天各门店销量对比")
    assert got.requested_metrics == ("sales_volume",)


@pytest.mark.parametrize("query,expected", [
    ("最近30天各门店营收对比", ("revenue",)),
    ("各门店订单量和销量对比", ("orders", "sales_volume")),
    ("各门店营业额、单量、销售量都要", ALL_THREE),
])
def test_only_the_unmentioned_ones_go(query, expected):
    assert drop(_Spec(ALL_THREE), query).requested_metrics == expected


def test_unregistered_metric_is_never_touched():
    """词表里没有的指标一律保留 —— 判不了就别动(同维度那条的处理)。"""
    spec = _Spec(("some_future_metric",))
    got = drop(spec, "随便问点什么")
    assert got.requested_metrics == ("some_future_metric",)
    assert got is spec, "没有改动时应原样返回"


def test_no_metrics_or_no_query_is_a_noop():
    spec = _Spec(())
    assert drop(spec, "各门店对比如何") is spec
    spec2 = _Spec(ALL_THREE)
    assert drop(spec2, "") is spec2


def test_it_reuses_the_contract_token_table():
    """⛔ 判「用户提没提」与判「答案答没答」必须用同一份词表。

    两份词表迟早会打架 —— 本轮已经栽过一次同型的（喂 LLM 的文本与校验用的事实集
    不是同一份，导致接口确定性 409）。这条钉住复用关系。
    """
    import smartbi.gold.restaurant.restaurant_intent_service as svc
    import inspect

    src = inspect.getsource(svc._drop_planner_invented_metrics)
    assert "_REQUEST_TEXT_TOKENS" in src, "改成自己的词表了 —— 那就是第二处定义"
    assert "answer_contract" in src, "词表必须从 answer_contract 取, 不是本地复制一份"
    # 阴性对照: 词表真的非空且含本例用到的键, 否则上面两条断言等于空转。
    assert _REQUEST_TEXT_TOKENS.get("sales_volume")
    assert _REQUEST_TEXT_TOKENS.get("revenue")


def test_filter_is_applied_only_at_the_contract_check():
    """🔴 不许就地改 spec —— `requested_metrics` 还有别的消费者。

    第一版把过滤结果赋回 spec，后续提问建议(按 requested_metrics 生成)当场变了，
    被 `test_tiered_answer_returns_typed_focus_entity_and_followups` 抓到：
    建议从「看菜品成本」变成了「看菜品销量」。

    判据：**改一个字段之前先问它还有谁在读。** 只想影响契约，就只把过滤后的副本
    交给契约。
    """
    import inspect
    import smartbi.gold.restaurant.restaurant_intent_service as svc

    src = inspect.getsource(svc.tiered_answer)
    assert "spec = _drop_planner_invented_metrics" not in src, (
        "又把过滤结果赋回 spec 了 —— 那会顺带改掉后续提问建议等其它消费者的行为"
    )
    assert "_contract.validate(\n            _drop_planner_invented_metrics(spec, query)" in src, (
        "过滤必须发生在契约校验的入参处; 换了写法就把这道闸架空了"
    )
