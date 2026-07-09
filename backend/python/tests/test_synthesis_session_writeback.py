from __future__ import annotations

from datetime import date
from types import SimpleNamespace

import pytest

import smartbi.api.synthesis as syn


class _FakeResponse:
    answer = "净赚约219万，利润率22.2%。"
    charts = [{"type": "bar"}]
    source = "thin_restate"
    plan = {"intent": "profit"}
    tokens = 123
    fact_check = {"ok": True}

    def to_dict(self):
        return {
            "answer": self.answer,
            "charts": self.charts,
            "source": self.source,
            "plan": self.plan,
            "tokens": self.tokens,
            "fact_check": self.fact_check,
        }


class _FakeEngine:
    def __init__(self, pool):
        self.pool = pool

    async def synthesize(self, factory_id, question, window, conversation_history=None):
        assert factory_id == "DEMO_REST"
        assert question
        assert window == (date(2026, 5, 1), date(2026, 6, 30))
        assert conversation_history is None
        return _FakeResponse()


@pytest.fixture
def synthesis_mocks(monkeypatch):
    calls = []

    async def fake_get_pg_pool():
        return object()

    async def fake_resolve_window(pool, factory_id, start_date, end_date, *, question=None):
        assert factory_id == "DEMO_REST"
        return date(2026, 5, 1), date(2026, 6, 30)

    async def fake_lookup_history(pool, session_id, factory_id):
        return None

    class FakeChatSessionService:
        should_raise = False

        def __init__(self, pool):
            self.pool = pool

        async def upsert(self, **kwargs):
            calls.append(kwargs)
            if self.should_raise:
                raise RuntimeError("writeback unavailable")

    monkeypatch.setattr(syn, "get_pg_pool", fake_get_pg_pool)
    monkeypatch.setattr(syn, "_resolve_window", fake_resolve_window)
    monkeypatch.setattr(syn, "_lookup_conversation_history", fake_lookup_history)
    monkeypatch.setattr(syn, "ComprehensiveSynthesisEngine", _FakeEngine)
    monkeypatch.setattr(
        "smartbi.services.chat_session_service.ChatSessionService",
        FakeChatSessionService,
    )
    return calls, FakeChatSessionService


def _request():
    return SimpleNamespace(state=SimpleNamespace(factory_id="DEMO_REST"))


@pytest.mark.asyncio
async def test_comprehensive_writes_back_when_session_id_present(synthesis_mocks):
    calls, _service = synthesis_mocks
    body = syn.SynthesisRequest(question="这两个月赚钱没", session_id="sid-001")

    response = await syn.comprehensive(_request(), body)

    assert response["answer"] == _FakeResponse.answer
    assert calls == [
        {
            "session_id": "sid-001",
            "factory_id": "DEMO_REST",
            "parent_query": "这两个月赚钱没",
            "parent_answer_summary": _FakeResponse.answer,
            "parent_template_code": "COMPREHENSIVE_SYNTHESIS",
        }
    ]


@pytest.mark.asyncio
async def test_comprehensive_skips_writeback_without_session_id(synthesis_mocks):
    calls, _service = synthesis_mocks
    body = syn.SynthesisRequest(question="这两个月赚钱没")

    response = await syn.comprehensive(_request(), body)

    assert response["answer"] == _FakeResponse.answer
    assert calls == []


@pytest.mark.asyncio
async def test_comprehensive_writeback_error_does_not_break_response(synthesis_mocks):
    calls, service = synthesis_mocks
    service.should_raise = True
    body = syn.SynthesisRequest(question="这两个月赚钱没", session_id="sid-err")

    response = await syn.comprehensive(_request(), body)

    assert response["answer"] == _FakeResponse.answer
    assert len(calls) == 1


@pytest.mark.asyncio
async def test_writeback_skips_degraded_source(synthesis_mocks):
    calls, _service = synthesis_mocks

    await syn._write_conversation_turn(
        object(),
        "sid-degraded",
        "DEMO_REST",
        "question",
        "placeholder answer",
        source="degraded",
    )

    assert calls == []


@pytest.mark.asyncio
async def test_comprehensive_stream_writes_back_after_done_event(synthesis_mocks):
    calls, _service = synthesis_mocks
    body = syn.SynthesisRequest(question="哪家分店最拖后腿", session_id="sid-stream")

    response = await syn.comprehensive_stream(_request(), body)
    iterator = response.body_iterator

    chunks = []
    async for chunk in iterator:
        chunks.append(chunk)
        if 'event: done' in chunk:
            assert calls == []

    assert any('event: done' in chunk for chunk in chunks)
    assert calls == [
        {
            "session_id": "sid-stream",
            "factory_id": "DEMO_REST",
            "parent_query": "哪家分店最拖后腿",
            "parent_answer_summary": _FakeResponse.answer,
            "parent_template_code": "COMPREHENSIVE_SYNTHESIS",
        }
    ]
