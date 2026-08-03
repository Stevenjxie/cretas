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
import subprocess
import sys
from contextlib import asynccontextmanager
from pathlib import Path

import pytest

# 脚本住在 backend/python/scripts/ 而不是仓库根的 scripts/audit/ ——
# **只有前者会被 deploy-smartbi-python.sh 同步到服务器**, 而它要挂 systemd timer
# 定时跑。放在 scripts/audit/ 时它在服务器上根本不存在(2026-07-31 挂 timer 时发现)。
_SCRIPT = (
    Path(__file__).resolve().parents[1]
    / "scripts" / "restaurant_capability_audit.py"
)
_spec = importlib.util.spec_from_file_location("_cap_audit", _SCRIPT)
assert _spec and _spec.loader, f"audit script not found at {_SCRIPT}"
audit = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(audit)

_ADV_SCRIPT = (
    Path(__file__).resolve().parents[1]
    / "scripts" / "restaurant_adversarial_audit.py"
)
_adv_spec = importlib.util.spec_from_file_location("_adversarial_audit", _ADV_SCRIPT)
assert _adv_spec and _adv_spec.loader, f"adversarial audit not found at {_ADV_SCRIPT}"
adversarial = importlib.util.module_from_spec(_adv_spec)
_adv_spec.loader.exec_module(adversarial)


def test_audit_entry_bootstraps_capture_logger_imports_in_isolated_python():
    """The production audit must not depend on a hand-written PYTHONPATH."""
    code = (
        "import importlib.util, pathlib; "
        f"p=pathlib.Path({str(_SCRIPT)!r}); "
        "s=importlib.util.spec_from_file_location('_audit_isolated', p); "
        "m=importlib.util.module_from_spec(s); s.loader.exec_module(m); "
        "from smartbi.services.llm_fallback_logger import log_template_hit; "
        "print(log_template_hit.__name__)"
    )
    completed = subprocess.run(
        [sys.executable, "-I", "-c", code],
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr
    assert completed.stdout.strip() == "log_template_hit"


@pytest.mark.asyncio
async def test_run_case_owns_the_tenant_context(monkeypatch):
    """The supported entrypoint must not rely on probe-side RLS setup."""
    from smartbi import tenant_ctx
    from smartbi.gold.restaurant import restaurant_intent, restaurant_ops_router

    seen: list[str] = []

    @asynccontextmanager
    async def fake_catalogue_scope(_pool, _factory_id):
        yield

    async def fake_parse(*_args, **_kwargs):
        return None

    monkeypatch.setattr(tenant_ctx, "set_factory_id", seen.append)
    monkeypatch.setattr(
        restaurant_ops_router, "dish_catalogue_scope", fake_catalogue_scope,
    )
    monkeypatch.setattr(restaurant_intent, "parse_restaurant_query", fake_parse)

    result = await audit._run_case(object(), "MOCK_REST", "restaurant_manager", "测试")

    assert seen == ["MOCK_REST"]
    assert result["error"].startswith("spec=None")


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


def test_capability_audit_covers_all_three_future_staffing_horizons():
    questions = " ".join(q for _f, q, _e in audit.CASES)
    for horizon in ("明天", "下周", "下个月"):
        assert horizon in questions, f"能力审计缺少预测排班范围: {horizon}"


def test_adversarial_audit_accepts_honest_historical_staffing_boundary():
    case = ("边界-历史人效", "各岗位这个月的人效怎么样", (), None)
    output = {
        "kind": "clarification",
        "intent": "STAFFING_ADVICE",
        "answer": (
            "这条问题不能偷换成预测。请改问明天、下周或下个月；"
            "历史人效只作为预测依据。"
        ),
    }
    assert adversarial._classify(case, output, "restaurant_owner") == (True, "")


def test_adversarial_audit_rejects_historical_staffing_execution():
    case = ("边界-历史人效", "各岗位这个月的人效怎么样", (), None)
    output = {
        "kind": "answer",
        "intent": "STAFFING_ADVICE",
        "answer": "明天预测排班 FactBook：建议补人。下周、下个月另算。历史人效已参考。",
    }
    ok, reason = adversarial._classify(case, output, "restaurant_owner")
    assert ok is False
    assert "范围澄清" in reason


def test_sample_queries_are_not_reused_as_the_test_set():
    """⛔ 仓库自带的 SAMPLE_QUERIES 是当初用来调关键词的, 拿它评分等于拿训练集
    当测试集(实测它得 0 误读, 而真实话术同期 17/20 误读)。"""
    from smartbi.gold.restaurant import restaurant_ops_router as router

    sample = getattr(router, "SAMPLE_QUERIES", None)
    if not sample:
        pytest.skip("SAMPLE_QUERIES 不在这个模块里, 无法交叉检查")
    overlap = {q for _f, q, _e in audit.CASES} & set(sample)
    assert not overlap, f"审计用例与 SAMPLE_QUERIES 重合: {overlap}"
