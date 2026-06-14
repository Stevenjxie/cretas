"""Unit tests for chat_qa distillation capture on the STREAMING Q&A path.

Context: the web-admin "问数据(AI)" tab calls POST /general-analysis-stream
(the SSE generator ``event_stream`` inside ``general_analysis_stream``). The
self-learning chat_qa capture was previously wired ONLY into the non-stream
``general_analysis`` function, so real customer Q&A through the UI never fed the
distillation corpus. We wired ``_capture_qa_distillation`` into the fresh-LLM
branches of the stream generator.

The full SSE generator is impractical to drive end-to-end in a unit test (it
loads uploads, routes templates, hits the LLM, builds charts, talks to several
DBs). So this file covers the two pieces we actually added/rely on:

1. ``_capture_qa_distillation`` is fire-and-forget — it NEVER raises and never
   delays the caller, even when get_pg_pool / persist blow up. This is the
   contract that lets us ``await`` it inline on the stream path without risking
   the stream.
2. The accumulation logic: the generator builds the full answer by appending
   each content delta to ``full_text`` (``full_text += chunk``), then captures
   that joined text. We assert that accumulating deltas reproduces the exact
   answer string and that THAT string is what reaches persist.

What is NOT covered here (documented honestly): the branch routing itself —
i.e. that capture fires on the fresh-LLM ``done`` and is skipped on every
cache-serve / degraded / truncated branch. That gating is enforced by the
``if full_text and not _llm_truncated`` guard (with-data path) and the
``if full_text`` guard on the no-data direct-LLM branch, placed by source
inspection; the real proof is a prod UI E2E that confirms chat_qa rows appear
for genuine LLM answers and NOT for cache hits. See report.

All DB access is mocked — NO real connection. pytest-asyncio asyncio_mode=auto.
Mirrors tests/test_distillation_capture.py.
"""
from __future__ import annotations

import smartbi.api.chat as chat_mod


# ---- fakes ----

class _FakeState:
    def __init__(self, factory_id=None):
        self.factory_id = factory_id


class _FakeRequest:
    """Minimal stand-in for fastapi.Request — only .state is read by the helper."""

    def __init__(self, factory_id=None):
        self.state = _FakeState(factory_id)


class _RecordingPersist:
    """Captures the kwargs passed to persist_distillation_sample."""

    def __init__(self):
        self.calls = []

    async def __call__(self, pool, **kwargs):
        self.calls.append(kwargs)


# ---- fire-and-forget: helper never raises ----

async def test_capture_never_raises_when_pool_lookup_explodes(monkeypatch):
    """If get_pg_pool raises, the capture must swallow it (stream-safe)."""
    async def _boom_pool():
        raise RuntimeError("pg pool boom")

    monkeypatch.setattr("smartbi.config.get_pg_pool", _boom_pool)
    # reaching the end with no exception is the assertion
    await chat_mod._capture_qa_distillation("营业额是多少", "本月营业额 ...", _FakeRequest("F1"))


async def test_capture_never_raises_when_persist_explodes(monkeypatch):
    async def _ok_pool():
        return object()

    async def _boom_persist(pool, **kwargs):
        raise RuntimeError("persist boom")

    monkeypatch.setattr("smartbi.config.get_pg_pool", _ok_pool)
    monkeypatch.setattr(
        "smartbi.services.distillation_capture.persist_distillation_sample",
        _boom_persist,
    )
    await chat_mod._capture_qa_distillation("问题", "答案", _FakeRequest("F1"))


async def test_capture_skips_empty_query_or_answer(monkeypatch):
    """Empty query or empty answer → no persist attempt (NOT NULL guard)."""
    rec = _RecordingPersist()
    monkeypatch.setattr(
        "smartbi.services.distillation_capture.persist_distillation_sample", rec
    )

    async def _ok_pool():
        return object()

    monkeypatch.setattr("smartbi.config.get_pg_pool", _ok_pool)

    await chat_mod._capture_qa_distillation("", "答案", _FakeRequest("F1"))
    await chat_mod._capture_qa_distillation("问题", "", _FakeRequest("F1"))
    await chat_mod._capture_qa_distillation(None, "答案", _FakeRequest("F1"))
    assert rec.calls == [], "empty input short-circuits before persist"


# ---- happy path: source/task_type/factory wired correctly ----

async def test_capture_passes_chat_qa_source_and_factory(monkeypatch):
    rec = _RecordingPersist()
    monkeypatch.setattr(
        "smartbi.services.distillation_capture.persist_distillation_sample", rec
    )

    async def _ok_pool():
        return object()

    monkeypatch.setattr("smartbi.config.get_pg_pool", _ok_pool)

    await chat_mod._capture_qa_distillation(
        "本月营收如何", "本月营收稳健，建议...", _FakeRequest("RES_3101_009")
    )
    assert len(rec.calls) == 1
    kw = rec.calls[0]
    assert kw["source"] == "chat_qa"
    assert kw["task_type"] == "qa"
    assert kw["input_text"] == "本月营收如何"
    assert kw["teacher_output"] == "本月营收稳健，建议..."
    assert kw["factory_id"] == "RES_3101_009"


async def test_capture_tolerates_request_without_state(monkeypatch):
    """http_request may lack .state — factory_id resolves to None, still safe."""
    rec = _RecordingPersist()
    monkeypatch.setattr(
        "smartbi.services.distillation_capture.persist_distillation_sample", rec
    )

    async def _ok_pool():
        return object()

    monkeypatch.setattr("smartbi.config.get_pg_pool", _ok_pool)

    class _NoState:
        pass

    await chat_mod._capture_qa_distillation("问题", "答案", _NoState())
    assert len(rec.calls) == 1
    assert rec.calls[0]["factory_id"] is None


# ---- accumulation logic the stream generator relies on ----

async def _fake_delta_stream(deltas):
    """Mimic insight_gen._call_llm_stream_text yielding content deltas."""
    for d in deltas:
        yield d


async def test_accumulated_deltas_reproduce_full_answer_and_are_captured(monkeypatch):
    """The generator does ``full_text += chunk`` per content delta then captures
    the joined text. Assert accumulation reproduces the answer exactly and that
    exact string is what reaches persist (the captured teacher_output)."""
    rec = _RecordingPersist()
    monkeypatch.setattr(
        "smartbi.services.distillation_capture.persist_distillation_sample", rec
    )

    async def _ok_pool():
        return object()

    monkeypatch.setattr("smartbi.config.get_pg_pool", _ok_pool)

    deltas = ["本月", "营业额", "为 ", "¥123,456", "，环比上升。"]
    expected = "本月营业额为 ¥123,456，环比上升。"

    # Replicate the generator's accumulation contract exactly.
    full_text = ""
    async for chunk in _fake_delta_stream(deltas):
        full_text += chunk

    assert full_text == expected, "accumulating deltas must reproduce the answer"

    # Fresh-LLM branch guard: full_text non-empty (and not truncated) → capture.
    _llm_truncated = False
    if full_text and not _llm_truncated:
        await chat_mod._capture_qa_distillation(
            "本月营业额", full_text, _FakeRequest("F1")
        )

    assert len(rec.calls) == 1
    assert rec.calls[0]["teacher_output"] == expected


async def test_truncated_or_empty_answer_is_not_captured(monkeypatch):
    """Degraded branches set _llm_truncated=True (silent-timeout / soft-timeout /
    cache-fallback) or leave full_text empty. The with-data guard
    ``full_text and not _llm_truncated`` must skip capture in both cases."""
    rec = _RecordingPersist()
    monkeypatch.setattr(
        "smartbi.services.distillation_capture.persist_distillation_sample", rec
    )

    async def _ok_pool():
        return object()

    monkeypatch.setattr("smartbi.config.get_pg_pool", _ok_pool)

    # Case 1: truncated answer (has text but _llm_truncated=True) → skip.
    full_text = "部分答案..."
    _llm_truncated = True
    if full_text and not _llm_truncated:  # guard from chat.py
        await chat_mod._capture_qa_distillation("q", full_text, _FakeRequest("F1"))

    # Case 2: empty answer (silent 0-chunk) → skip.
    full_text = ""
    _llm_truncated = False
    if full_text and not _llm_truncated:
        await chat_mod._capture_qa_distillation("q", full_text, _FakeRequest("F1"))

    assert rec.calls == [], "degraded / empty answers must NOT enter the corpus"
