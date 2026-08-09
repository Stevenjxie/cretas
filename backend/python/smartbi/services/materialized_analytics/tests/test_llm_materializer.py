"""Unit tests for llm_materializer — LLM insight materialization (Fix2).

Covers the degradation contract (禁止降级处理): on any LLM failure / empty /
unparseable / partial output, the rule insight_text MUST survive untouched and
no empty/fake content is stored.

All tests mock the LLM call — no network, no DB.
"""
from __future__ import annotations

import json

import pytest

from smartbi.services.materialized_analytics import llm_materializer as lm
from smartbi.services.materialized_analytics.persistence import _result_to_row_fields
from smartbi.services.materialized_analytics.templates.base import TemplateResult


def _make_results():
    return [
        TemplateResult(
            code="top_n_by_dim", title="Top 10 维度排名",
            data={"primary_dim": "门店", "top_rows": [{"label": "A", "total": 100}]},
            kpis={"top_label": "A", "top_value": 100},
            insight_text="门店 Top 10:A 独占 50%。 建议: ...(rule)",
        ),
        TemplateResult(
            code="monthly_trend", title="时间趋势",
            data={"trend": [{"month": "2026-01", "total": 1000}]},
            kpis={"latest": 1000},
            insight_text="1 月营收 1000 元(rule)",
        ),
        # A skipped one — must be ignored by the LLM materializer.
        TemplateResult(
            code="anomaly_detection", title="异常检测",
            data={}, applies=False, skip_reason="no signal",
        ),
        # An errored one — must be ignored.
        TemplateResult(
            code="pareto_analysis", title="帕累托",
            data={}, applies=False, error=True, skip_reason="compute error",
        ),
    ]


@pytest.fixture(autouse=True)
def _force_api_key(monkeypatch):
    """Make get_settings().llm_api_key truthy so the gate passes, and neutralize
    the distillation-capture DB call by default (pool=None → clean no-op) so the
    existing tests don't attempt a real connection. Capture-specific tests
    override get_pg_pool with a mock pool.
    """
    class _S:
        llm_api_key = "test-key"
        llm_insight_model = "qwen3.5-flash"

    monkeypatch.setattr("config.get_settings", lambda: _S())

    async def _no_pool():
        return None
    monkeypatch.setattr("smartbi.config.get_pg_pool", _no_pool)


def _patch_llm(monkeypatch, response):
    async def fake_call_llm(prompt, system_role=None, **kwargs):
        if isinstance(response, Exception):
            raise response
        return response
    monkeypatch.setattr(
        "smartbi.services.insights.llm_client.call_llm", fake_call_llm
    )


@pytest.mark.asyncio
async def test_success_fans_insights_and_exec_summary(monkeypatch):
    results = _make_results()
    llm_json = json.dumps({
        "executive_summary": "整体营收稳健, 门店分化明显。" * 3,
        "insights": {
            "top_n_by_dim": "门店 A 贡献过半, 因其位于核心商圈; 建议复制其选址逻辑到新店。",
            "monthly_trend": "1 月营收 1000 元处于淡季低位; 建议 2 月加推套餐拉升客单。",
        },
    }, ensure_ascii=False)
    _patch_llm(monkeypatch, llm_json)

    exec_summary = await lm.generate_llm_insights(results, "restaurant")

    assert exec_summary and "营收" in exec_summary
    top = next(r for r in results if r.code == "top_n_by_dim")
    trend = next(r for r in results if r.code == "monthly_trend")
    assert top.llm_insight and "核心商圈" in top.llm_insight
    assert trend.llm_insight and "淡季" in trend.llm_insight
    # rule insight_text untouched
    assert top.insight_text.endswith("(rule)")
    # skipped/errored never get llm_insight
    skipped = next(r for r in results if r.code == "anomaly_detection")
    assert skipped.llm_insight is None


@pytest.mark.asyncio
async def test_persistence_prefers_llm_insight_when_present(monkeypatch):
    results = _make_results()
    _patch_llm(monkeypatch, json.dumps({
        "executive_summary": "s",
        "insights": {"top_n_by_dim": "LLM 富洞察内容"},
    }, ensure_ascii=False))
    await lm.generate_llm_insights(results, "restaurant")

    top = next(r for r in results if r.code == "top_n_by_dim")
    trend = next(r for r in results if r.code == "monthly_trend")
    # top got an LLM insight → persisted insight = LLM
    assert _result_to_row_fields(top)["insights"] == ["LLM 富洞察内容"]
    # trend got none → persisted insight = rule fallback (NEVER empty)
    assert _result_to_row_fields(trend)["insights"] == ["1 月营收 1000 元(rule)"]


@pytest.mark.asyncio
async def test_llm_exception_keeps_rule_insight(monkeypatch):
    results = _make_results()
    _patch_llm(monkeypatch, RuntimeError("all providers exhausted"))

    exec_summary = await lm.generate_llm_insights(results, "restaurant")

    assert exec_summary is None
    for r in results:
        assert r.llm_insight is None
        # persisted insight falls back to rule (no fake/empty)
        if r.applies and not r.error:
            assert _result_to_row_fields(r)["insights"] == [r.insight_text]


@pytest.mark.asyncio
async def test_llm_empty_keeps_rule_insight(monkeypatch):
    results = _make_results()
    _patch_llm(monkeypatch, "   ")
    exec_summary = await lm.generate_llm_insights(results, "restaurant")
    assert exec_summary is None
    assert all(r.llm_insight is None for r in results)


@pytest.mark.asyncio
async def test_llm_unparseable_keeps_rule_insight(monkeypatch):
    results = _make_results()
    _patch_llm(monkeypatch, "这不是 JSON 而是一段自然语言, 无法解析。")
    exec_summary = await lm.generate_llm_insights(results, "restaurant")
    assert exec_summary is None
    assert all(r.llm_insight is None for r in results)


@pytest.mark.asyncio
async def test_partial_output_only_fills_provided_codes(monkeypatch):
    results = _make_results()
    # Only top_n_by_dim provided; monthly_trend missing + empty-string guard.
    _patch_llm(monkeypatch, json.dumps({
        "executive_summary": "",  # empty → exec_summary None
        "insights": {
            "top_n_by_dim": "有效洞察",
            "monthly_trend": "",       # empty string must NOT clobber rule
            "bogus_code": "irrelevant",  # unknown code ignored
        },
    }, ensure_ascii=False))

    exec_summary = await lm.generate_llm_insights(results, "restaurant")

    assert exec_summary is None  # empty exec summary not accepted
    top = next(r for r in results if r.code == "top_n_by_dim")
    trend = next(r for r in results if r.code == "monthly_trend")
    assert top.llm_insight == "有效洞察"
    assert trend.llm_insight is None  # empty string rejected → rule survives
    assert _result_to_row_fields(trend)["insights"] == ["1 月营收 1000 元(rule)"]


@pytest.mark.asyncio
async def test_no_api_key_skips_generation(monkeypatch):
    class _S:
        llm_api_key = ""
        llm_insight_model = "qwen3.5-flash"
    monkeypatch.setattr("config.get_settings", lambda: _S())

    # call_llm should NOT be invoked; patch it to blow up if it is.
    async def boom(*a, **k):
        raise AssertionError("call_llm must not run without api key")
    monkeypatch.setattr("smartbi.services.insights.llm_client.call_llm", boom)

    results = _make_results()
    exec_summary = await lm.generate_llm_insights(results, "restaurant")
    assert exec_summary is None
    assert all(r.llm_insight is None for r in results)


@pytest.mark.asyncio
async def test_no_applicable_templates_returns_none(monkeypatch):
    async def boom(*a, **k):
        raise AssertionError("call_llm must not run with no applicable templates")
    monkeypatch.setattr("smartbi.services.insights.llm_client.call_llm", boom)

    results = [
        TemplateResult(code="x", title="X", data={}, applies=False),
        TemplateResult(code="y", title="Y", data={}, applies=False, error=True),
    ]
    exec_summary = await lm.generate_llm_insights(results, "restaurant")
    assert exec_summary is None


# --- Distillation data pipeline capture (Steve 2026-05-31) ---

class _FakeConn:
    def __init__(self, sink):
        self._sink = sink

    async def execute(self, sql, *args):
        self._sink.append((sql, args))
        return "INSERT 0 1"

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _FakePool:
    def __init__(self, sink):
        self._sink = sink

    def acquire(self):
        return _FakeConn(self._sink)


@pytest.mark.asyncio
async def test_distillation_sample_captured_on_success(monkeypatch):
    """On usable LLM output, a materialization sample is INSERTed with the
    structured input + teacher output, tagged by business_type + factory_id."""
    sink: list = []

    async def _pool():
        return _FakePool(sink)
    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setenv("SMARTBI_DISTILL_CAPTURE", "1")

    results = _make_results()
    _patch_llm(monkeypatch, json.dumps({
        "executive_summary": "整体营收稳健。" * 3,
        "insights": {"top_n_by_dim": "门店 A 因核心商圈贡献过半; 建议复制选址。"},
    }, ensure_ascii=False))

    await lm.generate_llm_insights(results, "restaurant", factory_id="RES_TEST_001")

    assert len(sink) == 1, "exactly one distillation row should be written"
    sql, args = sink[0]
    assert "smart_bi_distillation_samples" in sql
    assert "ON CONFLICT (input_hash)" in sql
    # positional args (shared helper order): source, business_type, factory_id,
    #   task_type, template_codes, system_prompt, input_text, teacher_model,
    #   teacher_output, input_hash, quality, metadata
    assert args[0] == "materialization"
    assert args[1] == "restaurant"
    assert args[2] == "RES_TEST_001"
    assert args[3] == "insights"
    assert "top_n_by_dim" in args[4]
    # teacher_model 记的是**槽名**而不是具体模型, 这是刻意的:
    # llm_client.call_llm() 只返回文本, 路由在内部解析实际 provider, 调用方拿不到。
    # 与其硬编一个可能与实际不符的模型名, 不如如实记 "router:<SLOT>" ——
    # 对齐 CLAUDE.md 原则 1「禁止降级处理: 不返回假数据」。
    # ⚠️ 本用例原断言 "qwen3-max", 即那个**可能是假的**的硬编名字, 挂在
    #    ci-gate-excludes.txt 上。真要拿到实际模型, 得先改 call_llm 的返回契约,
    #    那是另一件事; 在那之前, 这里断言诚实标注而不是断言一个好看的谎。
    assert args[7] == "router:INSIGHTS"
    assert "核心商圈" in args[8]  # teacher_output carried through
    assert len(args[9]) == 64  # sha256 hex


@pytest.mark.asyncio
async def test_distillation_capture_disabled_by_env(monkeypatch):
    """SMARTBI_DISTILL_CAPTURE=0 → no DB write even on success."""
    sink: list = []

    async def _pool():
        return _FakePool(sink)
    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setenv("SMARTBI_DISTILL_CAPTURE", "0")

    results = _make_results()
    _patch_llm(monkeypatch, json.dumps({
        "executive_summary": "s" * 30,
        "insights": {"top_n_by_dim": "x 富洞察"},
    }, ensure_ascii=False))

    await lm.generate_llm_insights(results, "restaurant", factory_id="F1")
    assert sink == [], "capture must be skipped when disabled by env"
