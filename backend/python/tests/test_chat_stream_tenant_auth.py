from __future__ import annotations

import inspect
from types import SimpleNamespace

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from fastapi.testclient import TestClient

import smartbi.api.chat as chat_mod
import smartbi.gold.restaurant_intent as restaurant_intent
import smartbi.gold.restaurant_intent_service as restaurant_intent_service


class _Request:
    def __init__(self, factory_id=None, user_id=None, role=None):
        self.state = SimpleNamespace(
            factory_id=factory_id,
            user_id=user_id,
            role=role,
        )


class _Acquire:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, exc_type, exc, tb):
        return False


class _Conn:
    def __init__(self, row):
        self.row = row
        self.calls = []

    async def fetchrow(self, sql, *args):
        self.calls.append((sql, args))
        return self.row


class _Pool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return _Acquire(self.conn)


def test_stream_missing_tenant_is_http_403_before_streaming_response():
    app = FastAPI()
    app.include_router(chat_mod.router, prefix="/api/chat")

    response = TestClient(app).post(
        "/api/chat/general-analysis-stream",
        json={"query": "q"},
    )

    assert response.status_code == 403
    assert response.json() == {"detail": "TRUSTED_TENANT_REQUIRED"}
    assert response.headers["content-type"].startswith("application/json")


async def test_stream_missing_tenant_fails_before_upload_validation(monkeypatch):
    async def _unexpected_validation(*_args, **_kwargs):
        pytest.fail("upload validation must not run without a trusted tenant")

    monkeypatch.setattr(chat_mod, "_require_owned_upload_id", _unexpected_validation)

    with pytest.raises(HTTPException) as exc:
        await chat_mod.general_analysis_stream(
            chat_mod.GeneralAnalysisRequest(sheet_id="77"),
            _Request(),
        )

    assert (exc.value.status_code, exc.value.detail) == (
        403,
        "TRUSTED_TENANT_REQUIRED",
    )


async def test_stream_cross_tenant_and_missing_sheet_share_404(monkeypatch):
    # RLS/joint ownership lookup returns no row for both cases, by design.
    conn = _Conn(row=None)

    async def _pool():
        return _Pool(conn)

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)

    with pytest.raises(HTTPException) as exc:
        await chat_mod.general_analysis_stream(
            chat_mod.GeneralAnalysisRequest(sheet_id="77", query="q"),
            _Request("F001"),
        )

    assert (exc.value.status_code, exc.value.detail) == (404, "UPLOAD_NOT_FOUND")
    sql, args = conn.calls[0]
    assert "WHERE id = $1 AND factory_id = $2" in sql
    assert args == (77, "F001")


async def test_stream_valid_trusted_tenant_returns_stream_only_after_validation(monkeypatch):
    conn = _Conn(row={"id": 77})

    async def _pool():
        return _Pool(conn)

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(
        chat_mod,
        "get_sheet_data",
        lambda _sheet_id: pytest.fail("generator must not run while response is created"),
    )

    response = await chat_mod.general_analysis_stream(
        chat_mod.GeneralAnalysisRequest(sheet_id="77", query="q"),
        _Request("F001"),
    )

    assert isinstance(response, StreamingResponse)
    assert response.status_code == 200
    assert len(conn.calls) == 1
    assert conn.calls[0][1] == (77, "F001")


def test_general_analysis_routes_have_no_tenant_null_global_latest_sql():
    source = inspect.getsource(chat_mod.general_analysis)
    stream_source = inspect.getsource(chat_mod.general_analysis_stream)

    banned_global_predicate = "WHERE upload_status = 'COMPLETED'"
    assert banned_global_predicate not in source
    assert banned_global_predicate not in stream_source


async def _consume_stream(response):
    chunks = []
    async for chunk in response.body_iterator:
        chunks.append(chunk.decode() if isinstance(chunk, bytes) else chunk)
    return "".join(chunks)


def _configure_cached_tiered_result(monkeypatch, intent_code, clarification_needed):
    pending_calls = []

    async def _restaurant_tenant(*_args, **_kwargs):
        return True

    async def _pending_pop(*_args, **_kwargs):
        pending_calls.append("pop")
        return None

    async def _pending_put(*_args, **_kwargs):
        pending_calls.append("put")

    async def _resolve(*_args, **_kwargs):
        return SimpleNamespace(
            answer_text="trusted tiered answer",
            charts=[],
            kpis=[],
            title="trusted tiered result",
            meta={},
        )

    async def _capture(*_args, **_kwargs):
        return None

    monkeypatch.setattr(restaurant_intent, "match_restaurant_ops", lambda _query: None)
    monkeypatch.setattr(restaurant_intent, "_is_restaurant_tenant", _restaurant_tenant)
    monkeypatch.setattr(
        restaurant_intent,
        "_cache_get",
        lambda *_args: {
            "code": intent_code,
            "confidence": 0.91,
            "tier": "vector",
            "clarification_needed": clarification_needed,
            "clarification_question": (
                "trusted clarification only" if clarification_needed else None
            ),
        },
    )
    monkeypatch.setattr(restaurant_intent, "_pending_pop", _pending_pop)
    monkeypatch.setattr(restaurant_intent, "_pending_put", _pending_put)
    monkeypatch.setattr(restaurant_intent_service, "_resolve_tiered", _resolve)
    monkeypatch.setattr(restaurant_intent_service, "log_intent_capture", _capture)
    monkeypatch.setattr(
        restaurant_intent_service._contract,
        "validate",
        lambda *_args, **_kwargs: SimpleNamespace(passed=True, missing=[]),
    )
    return pending_calls


async def test_stream_false_policy_executes_without_tenant_fallback_services(
    monkeypatch,
):
    class _NoAutoSelectPool:
        def acquire(self):
            pytest.fail("false policy must not auto-select or load an upload")

    async def _pool():
        return _NoAutoSelectPool()

    async def _no_gold(*_args, **_kwargs):
        return None

    async def _no_template(*_args, **_kwargs):
        pytest.fail("false policy must not match a materialized upload template")

    async def _no_bundle(*_args, **_kwargs):
        pytest.fail("false policy must not load a materialized aggregate bundle")

    async def _no_llm_cache(*_args, **_kwargs):
        pytest.fail("false policy must not read or write the dedicated LLM cache")

    async def _llm_stream(*_args, **_kwargs):
        yield "fresh answer"

    async def _no_distillation(*_args, **_kwargs):
        return None

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_ops_router.match_restaurant_ops",
        lambda _query: "NOOP_NON_TREND",
    )
    monkeypatch.setattr(
        "smartbi.gold.restaurant_ops_router.resolve_by_code",
        _no_gold,
    )
    monkeypatch.setattr(
        "smartbi.agent.synthesis_router.match_comprehensive_synthesis",
        lambda _query: False,
    )
    monkeypatch.setattr(
        "smartbi.services.materialized_analytics.query_router.match_template_hybrid",
        _no_template,
    )
    monkeypatch.setattr(
        "smartbi.services.upload_aggregate_cache.load_bundle_from_db",
        _no_bundle,
    )
    monkeypatch.setattr(
        "smartbi.services.llm_answer_cache.LlmAnswerCache.get",
        _no_llm_cache,
    )
    monkeypatch.setattr(
        "smartbi.services.llm_answer_cache.LlmAnswerCache.set",
        _no_llm_cache,
    )
    monkeypatch.setattr(
        chat_mod.InsightGenerator,
        "_call_llm_stream_text",
        _llm_stream,
    )
    monkeypatch.setattr(chat_mod, "_capture_qa_distillation", _no_distillation)

    response = await chat_mod.general_analysis_stream(
        chat_mod.GeneralAnalysisRequest(
            query="zz",
            allow_tenant_data_fallback=False,
        ),
        _Request("F001", user_id="7", role="operator"),
    )
    body = await _consume_stream(response)

    assert "fresh answer" in body
    assert 'event: done' in body


@pytest.mark.parametrize("user_id", [None, "", "not-numeric", "0", "-1"])
@pytest.mark.parametrize(
    "tiered_path,intent_code,clarification_needed,expected_answer",
    [
        (
            "trend",
            "RESTAURANT_OPS_TREND_ANALYSIS",
            False,
            "trusted tiered answer",
        ),
        ("general", "", True, "trusted clarification only"),
    ],
)
async def test_stream_invalid_trusted_user_disables_both_tiered_clarification_paths(
    monkeypatch,
    user_id,
    tiered_path,
    intent_code,
    clarification_needed,
    expected_answer,
):
    pending_calls = _configure_cached_tiered_result(
        monkeypatch,
        intent_code,
        clarification_needed,
    )
    observed_session_keys = []
    original_tiered = chat_mod._try_tiered_restaurant_intent

    async def _observe_tiered(*args, session_key=None, **kwargs):
        observed_session_keys.append(session_key)
        return await original_tiered(
            *args,
            session_key=session_key,
            **kwargs,
        )

    async def _pool():
        return object()

    async def _unexpected_session_call(*_args, **_kwargs):
        pytest.fail("session lookup/writeback requires a positive numeric trusted user")

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_ops_router.match_restaurant_ops",
        lambda _query: None,
    )
    monkeypatch.setattr(
        "smartbi.agent.synthesis_router.match_comprehensive_synthesis",
        lambda _query: False,
    )
    monkeypatch.setattr(chat_mod, "_try_tiered_restaurant_intent", _observe_tiered)
    monkeypatch.setattr(
        "smartbi.services.chat_session_service.ChatSessionService.lookup",
        _unexpected_session_call,
    )
    monkeypatch.setattr(
        "smartbi.services.chat_session_service.ChatSessionService.upsert",
        _unexpected_session_call,
    )

    response = await chat_mod.general_analysis_stream(
        chat_mod.GeneralAnalysisRequest(
            query=f"tiered {tiered_path} without trusted user",
            session_id="shared-device-session",
            allow_tenant_data_fallback=False,
        ),
        _Request("F001", user_id=user_id, role="operator"),
    )
    body = await _consume_stream(response)

    assert expected_answer in body
    assert 'event: done' in body
    assert observed_session_keys == [None]
    assert pending_calls == []


@pytest.mark.parametrize("user_id", [None, "", "not-numeric", "0", "-1"])
async def test_stream_invalid_trusted_user_disables_session_lookup_and_writeback(
    monkeypatch,
    user_id,
):
    async def _pool():
        return object()

    async def _gold(*_args, **_kwargs):
        return SimpleNamespace(
            title="safe result",
            answer_text="role-safe answer",
            charts=[],
            kpis=[],
        )

    async def _unexpected_session_call(*_args, **_kwargs):
        pytest.fail("session lookup/writeback requires a positive numeric trusted user")

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_ops_router.match_restaurant_ops",
        lambda _query: "RESTAURANT_OPS_REVENUE",
    )
    monkeypatch.setattr(
        "smartbi.gold.restaurant_ops_router.resolve_by_code",
        _gold,
    )
    monkeypatch.setattr(
        "smartbi.services.chat_session_service.ChatSessionService.lookup",
        _unexpected_session_call,
    )
    monkeypatch.setattr(
        "smartbi.services.chat_session_service.ChatSessionService.upsert",
        _unexpected_session_call,
    )

    response = await chat_mod.general_analysis_stream(
        chat_mod.GeneralAnalysisRequest(
            query="revenue",
            session_id="session-shared-device",
            allow_tenant_data_fallback=False,
        ),
        _Request("F001", user_id=user_id, role="operator"),
    )
    body = await _consume_stream(response)

    assert "role-safe answer" in body
    assert 'event: done' in body
