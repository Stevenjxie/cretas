"""审计脚本自己的评分逻辑也要有测试。

一个坏掉的审计工具会**伪装成「系统没问题」** —— 它把所有东西判成 OK, 而没人会
去质疑一份全绿的报告。所以纯判定函数单独拎出来钉住。

尤其是 WRONG_INTENT 这一档: 它是「答出来了, 但答的不是你问的那件事」。这类最
危险 —— 输出**看起来像一份正常答案**, 只有对着预期 intent 才看得出走错了
resolver。2026-07-30 实测过两次(损耗按量答成按额、采购问题被改写成全店汇总),
所以它必须与 OK 分开计数, 不能被并进「答出来了」。
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

_SCRIPT = (
    Path(__file__).resolve().parents[3]
    / "scripts" / "audit" / "restaurant_capability_audit.py"
)
_spec = importlib.util.spec_from_file_location("_cap_audit", _SCRIPT)
assert _spec and _spec.loader, f"audit script not found at {_SCRIPT}"
audit = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(audit)


def test_answer_with_expected_intent_is_ok():
    assert audit.classify(("WASTAGE_TOP",), "WASTAGE_TOP", "answer") == ("OK", "")


def test_answer_with_any_of_several_acceptable_intents_is_ok():
    verdict, _ = audit.classify(
        ("TREND_ANALYSIS", "SALES_SUMMARY"), "SALES_SUMMARY", "answer")
    assert verdict == "OK"


def test_answer_with_the_wrong_resolver_is_not_ok():
    """最危险的一档: 输出看起来像正常答案, 答的却不是问的那件事。"""
    verdict, note = audit.classify(("REQUISITION_TREND",), "SALES_SUMMARY", "answer")
    assert verdict == "WRONG_INTENT"
    assert "REQUISITION_TREND" in note and "SALES_SUMMARY" in note


def test_empty_expectation_only_requires_an_answer():
    """有些问句只要求「能答出来」, 不限 resolver —— 但它仍然必须是 answer。"""
    assert audit.classify((), "ANYTHING", "answer") == ("OK", "")
    assert audit.classify((), "ANYTHING", "clarification")[0] == "CLARIFY"


def test_clarification_is_its_own_bucket_not_a_pass():
    """反问不能算通过 —— 它可能合理, 也可能是「该答没答」, 要人来看。"""
    assert audit.classify(("WASTAGE_TOP",), "WASTAGE_TOP", "clarification")[0] == "CLARIFY"


@pytest.mark.parametrize("kind", ["", "-", "None", "no-delegate"])
def test_no_answer_and_no_clarification_is_a_failure(kind):
    verdict, note = audit.classify(("WASTAGE_TOP",), "WASTAGE_TOP", kind)
    assert verdict == "NO_ANSWER"
    assert kind in note or "-" in note


def test_case_table_covers_the_regressions_it_was_built_from():
    """用例表必须一直盖住已修的那几个真缺陷, 否则它们悄悄回归也没人知道。"""
    questions = " ".join(q for _f, q, _e in audit.CASES)
    for regression in ("采购花了多少钱", "外卖占比", "卖得最好的几个菜"):
        assert regression in questions, f"用例表丢了回归项: {regression}"


def test_sample_queries_are_not_reused_as_the_test_set():
    """⛔ 仓库自带的 SAMPLE_QUERIES 是当初用来调关键词的, 拿它评分等于拿训练集
    当测试集(实测它得 0 误读, 而真实话术同期 17/20 误读)。"""
    from smartbi.gold.restaurant import restaurant_ops_router as router

    sample = getattr(router, "SAMPLE_QUERIES", None)
    if not sample:
        pytest.skip("SAMPLE_QUERIES 不在这个模块里, 无法交叉检查")
    overlap = {q for _f, q, _e in audit.CASES} & set(sample)
    assert not overlap, f"审计用例与 SAMPLE_QUERIES 重合: {overlap}"
