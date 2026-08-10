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


# ── 2026-08-10: 澄清延续轮 ────────────────────────────────────────────────
@dataclass
class _ContinuationSpec:
    requested_metrics: Tuple[str, ...] = field(default=())
    is_clarification_continuation: bool = False


def test_clarification_continuation_keeps_inherited_metrics():
    """🔴 prod 实测的那条链: 延续轮的 query 只是半句话, 不是用户问的问题。

        turn1 「米饭的销量是多少」 → 反问时间
        turn2 「本月」             → 反问门店
        turn3 「全部门店」          → planner 继承出 sales_volume

    在 turn3 上按 `query='全部门店'` 判「一个指标词都没沾」, 就会把继承来的
    sales_volume 当成 planner 编的剥掉 —— 后果不是少一个指标, 是整轮换了个
    问题回答(用户收到全店营收概览, 「米饭」不见了), 而回归电池里这一轮一断,
    同链后面 6+ 轮全部连坐。
    """
    spec = _ContinuationSpec(("sales_volume",), is_clarification_continuation=True)
    assert drop(spec, "全部门店").requested_metrics == ("sales_volume",)
    assert drop(spec, "全部门店") is spec, "延续轮应原样返回, 不该造新对象"


def test_non_continuation_turn_still_drops_invented_metrics():
    """阴性对照: 豁免只对延续轮生效。

    没有这条, 把豁免写成无条件 `return spec` 也能让上面那条通过 —— 那等于
    把整个函数删掉, 2026-08-07 的假拒当场回来。
    """
    spec = _ContinuationSpec(ALL_THREE, is_clarification_continuation=False)
    assert drop(spec, "最近30天各门店对比如何").requested_metrics == ()


def test_continuation_flag_absent_behaves_like_false():
    """没有这个字段的 spec(旧调用方/测试替身)必须仍走原逻辑, 不能因 getattr 失败
    而静默全部保留。"""
    assert drop(_Spec(ALL_THREE), "最近30天各门店对比如何").requested_metrics == ()


# ── 2026-08-11: 同一把尺子也要量 wants_margin ────────────────────────────
@dataclass
class _MarginSpec:
    requested_metrics: Tuple[str, ...] = field(default=())
    wants_margin: bool = False
    asks_profitability: bool = False
    is_clarification_continuation: bool = False


def test_sales_question_does_not_get_a_margin_contract():
    """🔴 prod 落库实证([27] 回归电池): 算出了正确答案, 然后把它丢了。

        query='本月模拟·打浦桥日月光店的米饭卖得怎么样'
        contract_pass=false   contract_missing=["margin_integrity"]
        rejected_answer='「模拟·打浦桥日月光店」的「米饭」在本月销量 **1,345 份**、
                         营收 **¥4,035.00**。'

    用户问销量, 系统查出了销量和营收, 答案里还写着「如需毛利计算方法, 可问…」——
    然后因为交不出一份**毛利口径校验**被整份扔掉, 用户收到「请补充具体范围后重试」。

    `wants_margin` 是 planner 编的: 原句「卖得怎么样」一个毛利词都没沾。而本文件
    开头那条判据对它同样成立 —— **用户从没提过的指标不可能让答案变成答非所问,
    只能造成假拒**。既有实现只把这把尺子用在 `requested_metrics` 上, 漏了这半边。

    ⚠️ 这题不是「回归」: 同一份代码、同一个模型下, 21:16 通过而 21:25 失败
       (落库有据)。是 planner 对「卖得怎么样」在毛利/销量之间摇摆, 计划缓存把
       某一次结果冻住 6 小时, 于是看起来稳定 —— 每次部署重启都在重掷。
    """
    spec = _MarginSpec(("sales_volume",), wants_margin=True)
    assert drop(spec, "本月模拟·打浦桥日月光店的米饭卖得怎么样").wants_margin is False


def test_named_margin_question_keeps_the_margin_contract():
    """🔴 阴性对照: 用户真说了毛利, 契约必须照旧要 —— 否则这个修法就是把闸拆了。

    [28]「本月模拟·打浦桥日月光店的毛利率」当前是通过的, 必须保持。
    """
    spec = _MarginSpec((), wants_margin=True)
    assert drop(spec, "本月模拟·打浦桥日月光店的毛利率").wants_margin is True


@pytest.mark.parametrize("query", ["本月毛利怎么样", "这个月利润高吗", "毛利率是多少"])
def test_any_margin_word_keeps_it(query):
    assert drop(_MarginSpec((), wants_margin=True), query).wants_margin is True


def test_profitability_question_keeps_margin_without_the_word():
    """🔴「赚钱吗」没有「毛利」二字, 但它**就是**在问毛利 —— 不能剥。

    电池 [13]「本月全部门店米饭赚钱吗」/[22]「有没有店在亏损」走的都是这条:
    `wants_margin` 由 `asks_profitability` 推出来, 不是 planner 凭空编的。
    只按「有没有毛利词」判会把它们一起剥掉, 那是拿一条假拒换另一条。
    """
    spec = _MarginSpec((), wants_margin=True, asks_profitability=True)
    assert drop(spec, "本月全部门店米饭赚钱吗").wants_margin is True


def test_margin_intent_is_kept_on_a_clarification_continuation():
    """延续轮的 query 只是半句话 —— 与指标那条同样的理由, 同样不剥。"""
    spec = _MarginSpec(("gross_margin",), wants_margin=True,
                       is_clarification_continuation=True)
    assert drop(spec, "全部门店").wants_margin is True


def test_spec_without_wants_margin_field_is_untouched():
    """旧调用方/测试替身没有这个字段时不能炸 —— dataclasses.replace 会拒绝未知字段。"""
    spec = _Spec(ALL_THREE)
    assert drop(spec, "最近30天各门店对比如何").requested_metrics == ()


def test_dropping_it_actually_removes_the_contract_element():
    """🔴 接缝: 剥掉 `wants_margin` 必须真的让契约不再要 margin_integrity。

    只测「这个字段被改成 False」等于没测 —— 缺陷在接缝上。prod 落库记录里
    [27] 的 `contract_missing` **只有** ["margin_integrity"] 这一项, 所以让它
    不再被要求就是让那个正确答案被送出去。这条把两端钉在一起。
    """
    from smartbi.gold.restaurant.answer_contract import (
        MARGIN_CAPABLE_INTENTS, required_elements,
    )
    from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec

    # ⛔ 用真类造替身, 不手抄字段清单 —— 手抄表会漂, 且失败长成 AttributeError,
    #    看不出是替身过期(同 test_ambiguous_store_gets_candidates 的做法)。
    def _real_spec():
        return RestaurantQuerySpec(
            intent="RESTAURANT_OPS_STORE_MARGIN", domain="restaurant",
            date_range=(None, None), window_label="本月", relative_window=None,
            metrics=(), wants_margin=True, asks_profitability=False,
            dimensions=("dish", "store"), comparison=None, confidence=1.0,
            source_tier="test", requested_metrics=("sales_volume",),
        )

    assert "RESTAURANT_OPS_STORE_MARGIN" in MARGIN_CAPABLE_INTENTS, (
        "阴性对照: 这个 intent 不在毛利契约范围内的话, 下面两条都等于空转")

    q = "本月模拟·打浦桥日月光店的米饭卖得怎么样"
    before = required_elements(_real_spec())
    assert "margin_integrity" in before, "改之前就不要它, 那这题从来不是这么挂的"

    after = required_elements(drop(_real_spec(), q))
    assert "margin_integrity" not in after
    assert "margin_value" not in after


def test_margin_drop_reuses_the_same_token_table():
    """⛔ 与指标那条同一份词表, 不许另建 —— 两份迟早打架。"""
    import inspect

    import smartbi.gold.restaurant.restaurant_intent_service as svc

    src = inspect.getsource(svc._drop_planner_invented_metrics)
    assert "gross_margin" in src, "毛利词表必须从 _REQUEST_TEXT_TOKENS['gross_margin'] 取"
    # 阴性对照: 该键真的非空, 否则上面那条断言等于空转
    assert _REQUEST_TEXT_TOKENS.get("gross_margin")
