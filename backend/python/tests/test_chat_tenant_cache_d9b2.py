from __future__ import annotations

from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from fastapi.responses import StreamingResponse

import smartbi.api.chat as chat_mod


class _Request:
    def __init__(self, factory_id=None, user_id=None, role=None):
        self.state = SimpleNamespace(
            factory_id=factory_id,
            user_id=user_id,
            role=role,
        )

    async def is_disconnected(self):
        return False


class _Acquire:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, exc_type, exc, tb):
        return False


class _Pool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return _Acquire(self.conn)


class _OwnershipConn:
    def __init__(self, row):
        self.row = row
        self.calls = []

    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, args))
        return self.row


def _drill_request():
    return chat_mod.DrillDownRequest(
        sheet_id="77",
        dimension="region",
        measures=["amount"],
        data=[{"region": "north", "amount": 10}],
    )


def _root_request():
    return chat_mod.RootCauseRequest(
        sheet_id="77",
        kpi="revenue",
        data=[{"revenue": 10, "cost": 3}],
    )


def _benchmark_request():
    return chat_mod.BenchmarkRequest(
        sheet_id="77",
        industry="food",
        metrics={"margin": 0.2},
    )


def _multi_request():
    return chat_mod.MultiDimensionRequest(
        sheet_id="77",
        data=[{"region": "north", "amount": 10}],
    )


_TENANT_ROUTES = [
    ("drill_down", _drill_request),
    ("root_cause", _root_request),
    ("benchmark", _benchmark_request),
    ("multi_dimension_analysis", _multi_request),
    ("drill_down_stream", _drill_request),
    ("root_cause_stream", _root_request),
    ("benchmark_stream", _benchmark_request),
    ("multi_dimension_analysis_stream", _multi_request),
]


@pytest.mark.parametrize("route_name,request_factory", _TENANT_ROUTES)
async def test_each_route_requires_tenant_before_cache_data_or_upload_validation(
    monkeypatch,
    route_name,
    request_factory,
):
    monkeypatch.setattr(
        chat_mod,
        "_chat_cache_get",
        lambda _key: pytest.fail("response cache must follow trusted tenant"),
    )
    monkeypatch.setattr(
        chat_mod,
        "get_sheet_data",
        lambda *_args: pytest.fail("sheet cache must follow trusted tenant"),
    )

    async def _unexpected_ownership(*_args, **_kwargs):
        pytest.fail("ownership lookup must follow trusted tenant")

    monkeypatch.setattr(chat_mod, "_require_owned_upload_id", _unexpected_ownership)

    route = getattr(chat_mod, route_name)
    with pytest.raises(HTTPException) as exc:
        await route(request_factory(), _Request())

    assert (exc.value.status_code, exc.value.detail) == (
        403,
        "TRUSTED_TENANT_REQUIRED",
    )


@pytest.mark.parametrize("route_name,request_factory", _TENANT_ROUTES)
@pytest.mark.parametrize("visibility_case", ["cross-tenant", "missing"])
async def test_each_route_hides_cross_tenant_and_missing_uploads_as_same_404(
    monkeypatch,
    route_name,
    request_factory,
    visibility_case,
):
    del visibility_case  # Both cases deliberately have the same observable DB result.
    conn = _OwnershipConn(row=None)

    async def _pool():
        return _Pool(conn)

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(
        chat_mod,
        "_chat_cache_get",
        lambda _key: pytest.fail("cache lookup must follow upload ownership"),
    )
    monkeypatch.setattr(
        chat_mod,
        "get_sheet_data",
        lambda *_args: pytest.fail("sheet cache must follow upload ownership"),
    )

    route = getattr(chat_mod, route_name)
    with pytest.raises(HTTPException) as exc:
        await route(request_factory(), _Request("F001"))

    assert (exc.value.status_code, exc.value.detail) == (404, "UPLOAD_NOT_FOUND")
    assert len(conn.calls) == 1
    _, sql, args = conn.calls[0]
    assert "WHERE id = $1 AND factory_id = $2" in sql
    assert args == (77, "F001")


def test_full_order_sensitive_data_digest_distinguishes_late_row_changes():
    common_rows = [{"row": index, "value": index * 10} for index in range(5)]
    first = [*common_rows, {"row": 5, "value": 50}]
    changed_after_old_prefix = [*common_rows, {"row": 5, "value": 999}]

    first_key = chat_mod._make_chat_cache_key("analysis", data=first)
    changed_key = chat_mod._make_chat_cache_key(
        "analysis",
        data=changed_after_old_prefix,
    )
    reordered_key = chat_mod._make_chat_cache_key(
        "analysis",
        data=list(reversed(first)),
    )

    assert first_key != changed_key
    assert first_key != reordered_key


def test_sheet_cache_is_tenant_scoped_even_for_same_sheet_id():
    chat_mod._sheet_data_cache.clear()
    first_data = [{"tenant": "F001"}]
    second_data = [{"tenant": "F002"}]

    chat_mod.cache_sheet_data("F001", "77", first_data)
    assert chat_mod.get_sheet_data("F001", "77") == first_data
    assert chat_mod.get_sheet_data("F002", "77") is None

    chat_mod.cache_sheet_data("F002", "77", second_data)
    assert chat_mod.get_sheet_data("F001", "77") == first_data
    assert chat_mod.get_sheet_data("F002", "77") == second_data


async def test_same_benchmark_request_never_reuses_another_tenants_response_cache(
    monkeypatch,
):
    conn = _OwnershipConn(row={"id": 77})
    cache = {}
    service_calls = []

    async def _pool():
        return _Pool(conn)

    async def _compare(_self, **_kwargs):
        sequence = len(service_calls) + 1
        service_calls.append(sequence)
        return SimpleNamespace(
            success=True,
            error=None,
            data_sources=[],
            to_dict=lambda: {"sequence": sequence},
        )

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(chat_mod, "_chat_cache_get", cache.get)
    monkeypatch.setattr(
        chat_mod,
        "_chat_cache_set",
        lambda key, payload: cache.__setitem__(key, dict(payload)),
    )
    monkeypatch.setattr(
        chat_mod.IndustryBenchmark,
        "compare_with_industry",
        _compare,
    )

    first = await chat_mod.benchmark(_benchmark_request(), _Request("F001"))
    second = await chat_mod.benchmark(_benchmark_request(), _Request("F002"))
    repeat_first = await chat_mod.benchmark(
        _benchmark_request(),
        _Request("F001"),
    )

    assert first.result == {"sequence": 1}
    assert second.result == {"sequence": 2}
    assert repeat_first.result == {"sequence": 1}
    assert service_calls == [1, 2]


async def test_valid_owned_nonstream_and_consumed_stream_paths_still_work(
    monkeypatch,
):
    conn = _OwnershipConn(row={"id": 77})

    async def _pool():
        return _Pool(conn)

    async def _llm_stream(*_args, **_kwargs):
        yield "tenant-safe streamed answer"

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(chat_mod, "_chat_cache_get", lambda _key: None)
    monkeypatch.setattr(chat_mod, "_chat_cache_set", lambda *_args: None)
    monkeypatch.setattr(chat_mod, "_stream_llm_response", _llm_stream)

    nonstream = await chat_mod.drill_down(_drill_request(), _Request("F001"))
    assert nonstream.success is True

    streamed = await chat_mod.multi_dimension_analysis_stream(
        _multi_request(),
        _Request("F001"),
    )
    assert isinstance(streamed, StreamingResponse)
    body = ""
    async for chunk in streamed.body_iterator:
        body += chunk.decode() if isinstance(chunk, bytes) else chunk

    assert "tenant-safe streamed answer" in body
    assert "event: done" in body


async def test_general_stream_field_definitions_join_upload_factory_ownership(
    monkeypatch,
):
    class _FieldDefinitionConn:
        def __init__(self):
            self.calls = []

        async def fetchrow(self, sql, *args):
            self.calls.append(("fetchrow", sql, args))
            return {"id": 77}

        async def fetch(self, sql, *args):
            self.calls.append(("fetch", sql, args))
            if "smart_bi_dynamic_data" in sql:
                return [{"row_data": {"metric": 10}}]
            if "smart_bi_pg_field_definitions fd" in sql:
                return [{
                    "original_name": "metric",
                    "standard_name": "metric",
                    "is_measure": True,
                    "is_dimension": False,
                    "is_time": False,
                }]
            pytest.fail("unexpected query before field-definition ownership proof")

    conn = _FieldDefinitionConn()

    async def _pool():
        return _Pool(conn)

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    chat_mod._sheet_data_cache.clear()

    response = await chat_mod.general_analysis_stream(
        chat_mod.GeneralAnalysisRequest(sheet_id="77", query=""),
        _Request("F001"),
    )
    iterator = response.body_iterator
    try:
        async for _chunk in iterator:
            if any(
                "smart_bi_pg_field_definitions fd" in sql
                for _kind, sql, _args in conn.calls
            ):
                break
    finally:
        await iterator.aclose()

    field_calls = [
        call for call in conn.calls
        if "smart_bi_pg_field_definitions fd" in call[1]
    ]
    assert len(field_calls) == 1
    _, sql, args = field_calls[0]
    assert "JOIN smart_bi_pg_excel_uploads u" in sql
    assert "u.id = fd.upload_id" in sql
    assert "fd.upload_id = $1" in sql
    assert "u.factory_id = $2" in sql
    assert args == (77, "F001")
