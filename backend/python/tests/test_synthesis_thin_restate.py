import asyncio
import datetime as dt

import pytest

import smartbi.agent.synthesis_engine as se
from smartbi.agent.budget_tracker import BudgetCheckResult
from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine


class FakeBudget:
    async def check_budget(self, factory_id, today=None):
        return BudgetCheckResult(blocked=False, tokens_used=10, tokens_cap=50000)

    async def consume(self, factory_id, tokens, today=None):
        return BudgetCheckResult(blocked=False, tokens_used=10 + tokens, tokens_cap=50000)


class FakeCache:
    def __init__(self):
        self.put_calls = []

    async def get(self, factory_id, q_hash):
        return None

    async def put(self, factory_id, q_hash, answer, chart_config, tokens, ttl_hours=24):
        self.put_calls.append({"answer": answer, "chart_config": chart_config, "tokens": tokens})


def _engine():
    return ComprehensiveSynthesisEngine(
        pool=object(),
        budget_tracker=FakeBudget(),
        cache=FakeCache(),
    )


def _date_range():
    return (dt.date(2026, 1, 1), dt.date(2026, 1, 31))


async def _fake_store_comparison(pool, fid, dr):
    return {
        "stores": [
            {"name": "A店", "revenue": 1000000.0, "orderCount": 7000, "avgTicket": 142.9},
            {"name": "B店", "revenue": 600000.0, "orderCount": 7000, "avgTicket": 85.7},
            {"name": "C店", "revenue": 1400000.0, "orderCount": 7000, "avgTicket": 200.0},
        ]
    }


@pytest.fixture(autouse=True)
def attribution_data(monkeypatch):
    monkeypatch.setattr(se, "store_comparison", _fake_store_comparison)


def test_thin_restate_reverse_whitelist_allows_only_attribution_numbers():
    attribution = se.compute_store_attribution(asyncio.run(_fake_store_comparison(None, "F", _date_range()))["stores"])

    assert se._thin_restate_numbers_allowed("B店营收 600000，客单价效应 -400000。", attribution)
    assert not se._thin_restate_numbers_allowed("B店营收 600000，但还有 9999 个隐藏问题。", attribution)


def test_pure_attribution_uses_thin_restate_and_small_token_budget(monkeypatch):
    calls = []

    async def fake_call_chain(slot, payload, chain=None, timeout=30.0):
        calls.append(payload)
        return {
            "choices": [{"message": {"content": "B店拖后腿：营收 600000，客流效应 0，客单价效应 -400000。"}}],
            "usage": {"total_tokens": 335},
        }

    monkeypatch.setattr(se, "call_chain", fake_call_chain)

    resp = asyncio.run(_engine().synthesize("DEMO_REST", "哪家店拖后腿，是客流还是客单价", _date_range()))

    assert resp.source == "thin_restate"
    assert resp.tokens == 335
    assert "B店拖后腿" in resp.answer
    assert resp.fact_check is not None
    assert calls
    assert calls[0]["max_tokens"] <= 450


def test_thin_restate_rejects_unknown_number_and_falls_back_template(monkeypatch):
    async def fake_call_chain(slot, payload, chain=None, timeout=30.0):
        return {
            "choices": [{"message": {"content": "B店拖后腿：营收 600000，但我还看到 9999 个新问题。"}}],
            "usage": {"total_tokens": 335},
        }

    monkeypatch.setattr(se, "call_chain", fake_call_chain)

    resp = asyncio.run(_engine().synthesize("DEMO_REST", "哪家店拖后腿，是客流还是客单价", _date_range()))

    assert resp.source == "template"
    assert resp.tokens == 335
    assert "9999" not in resp.answer
    assert "B店" in resp.answer
    assert "客单价" in resp.answer
    assert resp.fact_check is not None


def test_multidim_question_stays_on_full_synthesis(monkeypatch):
    async def fake_finance(pool, fid, dr, *, top_n_stores=10):
        return {"total_revenue": 1000000.0, "bill_count": 1000, "avg_bill_value": 1000.0}

    async def fake_call_chain(slot, payload, chain=None, timeout=30.0):
        return {"choices": [{"message": {"content": "综合分析经营。"}}], "usage": {"total_tokens": 2800}}

    monkeypatch.setattr(se, "finance_summary", fake_finance)
    monkeypatch.setattr(se, "call_chain", fake_call_chain)

    resp = asyncio.run(_engine().synthesize("DEMO_REST", "综合分析经营和哪家店拖后腿", _date_range()))

    assert resp.source == "llm"
    assert resp.tokens == 2800
