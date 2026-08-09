"""人审台第一道(模型复判)的行为约束。

🔴 2026-08-08 我肉眼审 96 条候选, 拦下四类会固化成错误的。这道机制存在的意义
   就是让那四类**不再依赖某一次的仔细**。测试按那四类的真实形态来写。

⛔ 这里测的**不是模型判得准不准**(那取决于模型), 而是**这套机制在模型给出
   各种回答时的行为是否正确** —— 尤其是模型不可用、模型乱答、模型说不准时,
   它必须把候选推给人, 而不是放行。
"""
import pytest

from smartbi.gold.restaurant.promotion_llm_review import (
    UNSURE,
    VERDICT_AGREE,
    VERDICT_DISAGREE,
    VERDICT_UNSURE,
    ReviewVerdict,
    review_candidate,
    summarize,
)

CATALOGUE = {
    "RESTAURANT_OPS_SALES_SUMMARY": "整体营收、单量、客单价总览",
    "RESTAURANT_OPS_DISCOUNT_SUMMARY": "折扣力度、让利总额与占营收比",
    "RESTAURANT_OPS_SUPPLIER_PRICE": "同一食材不同供应商的报价差",
    "RESTAURANT_OPS_OUT_OF_DOMAIN": "天气、新闻、股票等不属于餐饮经营数据的问题",
    "RESTAURANT_OPS_RECIPE_COST": "菜品/食材成本",
}


def _stub(content):
    """把模型换成一个可控的回声。"""
    async def _call(slot, payload, **kw):
        return {"choices": [{"message": {"content": content}}]}
    return _call


class _Slot:
    REVIEW = "review"


async def _review(query, recorded, content):
    return await review_candidate(
        query, recorded, CATALOGUE,
        call_chain=_stub(content), slot=_Slot.REVIEW,
    )


@pytest.mark.asyncio
async def test_agreement_is_the_only_fast_path():
    got = await _review("最近30天总营收是多少", "RESTAURANT_OPS_SALES_SUMMARY",
                        '{"intent":"RESTAURANT_OPS_SALES_SUMMARY","reason":"问整体营收"}')
    assert got.verdict == VERDICT_AGREE
    assert got.needs_human_attention is False


@pytest.mark.asyncio
async def test_stale_candidate_is_caught():
    """🔴 真实案例一: 「最近30天折扣力度多大」记录的是营收总览。

    那是折扣意图**建立之前**留下的记录。按它晋升 = 把折扣永久固化成总览。
    """
    got = await _review("最近30天折扣力度多大", "RESTAURANT_OPS_SALES_SUMMARY",
                        '{"intent":"RESTAURANT_OPS_DISCOUNT_SUMMARY","reason":"问的是折扣"}')
    assert got.verdict == VERDICT_DISAGREE
    assert got.needs_human_attention is True


@pytest.mark.asyncio
async def test_no_data_mislabeled_as_out_of_domain_is_caught():
    """🔴 真实案例二: 「哪个供应商报价最贵」被记成域外(与天气同一档)。

    晋升它 = 对所有租户永久关门。提示词里写死了这条区分。
    """
    got = await _review("哪个供应商报价最贵", "RESTAURANT_OPS_OUT_OF_DOMAIN",
                        '{"intent":"RESTAURANT_OPS_SUPPLIER_PRICE","reason":"属于经营数据"}')
    assert got.verdict == VERDICT_DISAGREE


@pytest.mark.asyncio
async def test_cross_metric_ratio_goes_to_unsure_not_a_guess():
    """🔴 真实案例三: 「食材成本占营收多少」被记成「食材成本」。

    它是**比率**, 任何单意图都装不下 —— 模型该回 UNSURE 而不是硬挑一个。
    """
    got = await _review("食材成本占营收多少", "RESTAURANT_OPS_RECIPE_COST",
                        f'{{"intent":"{UNSURE}","reason":"跨指标比率"}}')
    assert got.verdict == VERDICT_UNSURE
    assert got.needs_human_attention is True


@pytest.mark.asyncio
async def test_model_unavailable_means_human_looks_not_pass():
    """⛔ 复判环节挂了 -> 全部推给人, **绝不默认放行**。

    方向反了这道机制就变成橡皮图章 —— 模型一挂, 所有候选自动通过。
    """
    async def _boom(slot, payload, **kw):
        raise RuntimeError("all providers exhausted")

    got = await review_candidate("随便什么问句", "RESTAURANT_OPS_SALES_SUMMARY",
                                 CATALOGUE, call_chain=_boom, slot=_Slot.REVIEW)
    assert got.verdict == VERDICT_UNSURE
    assert got.needs_human_attention is True


@pytest.mark.asyncio
async def test_unregistered_intent_from_model_is_not_trusted():
    """模型编了一个不存在的意图 -> 当 UNSURE, ⛔ 不能原样带进人审台。"""
    got = await _review("随便问句", "RESTAURANT_OPS_SALES_SUMMARY",
                        '{"intent":"RESTAURANT_OPS_编的一个","reason":"x"}')
    assert got.verdict == VERDICT_UNSURE
    assert "未注册" in got.reason


@pytest.mark.asyncio
async def test_garbage_output_is_not_trusted():
    got = await _review("随便问句", "RESTAURANT_OPS_SALES_SUMMARY", "我觉得应该是营收吧")
    assert got.verdict == VERDICT_UNSURE


@pytest.mark.asyncio
async def test_prompt_never_reveals_the_recorded_intent():
    """⛔ 不能告诉模型飞轮记录的是什么 —— 告诉它就变成「你同不同意」。

    要的是**独立的第二意见**; 泄露了答案, 模型会倾向附和, 复判失去意义。
    """
    import json

    seen = {}

    async def _spy(slot, payload, **kw):
        seen["text"] = json.dumps(payload["messages"], ensure_ascii=False)
        return {"choices": [{"message": {"content": '{"intent":"' + UNSURE + '","reason":"x"}'}}]}

    await review_candidate("最近30天折扣力度多大", "RESTAURANT_OPS_SALES_SUMMARY",
                           CATALOGUE, call_chain=_spy, slot=_Slot.REVIEW)
    assert "RESTAURANT_OPS_SALES_SUMMARY" in seen["text"], "意图目录本身要给"
    assert "记录" not in seen["text"] and "飞轮" not in seen["text"]
    # 目录里每个意图都出现, 说明没有把「记录的那个」单独标出来
    for code in CATALOGUE:
        assert code in seen["text"]


def test_summary_puts_attention_where_it_is_needed():
    vs = [
        ReviewVerdict("a", "X", "X", VERDICT_AGREE, ""),
        ReviewVerdict("b", "X", "Y", VERDICT_DISAGREE, ""),
        ReviewVerdict("c", "X", UNSURE, VERDICT_UNSURE, ""),
    ]
    s = summarize(vs)
    assert s["total"] == 3
    assert s["needs_attention"] == 2, "分歧 + 不确定都要人看"
    assert len(s["agree"]) == 1
