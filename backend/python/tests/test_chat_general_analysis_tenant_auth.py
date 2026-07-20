from __future__ import annotations

from types import SimpleNamespace

import pytest
from fastapi import HTTPException

import smartbi.api.chat as chat_mod
import smartbi.gold.restaurant_intent as restaurant_intent


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


class _Pool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return _Acquire(self.conn)


class _OwnershipConn:
    def __init__(self, owned):
        self.owned = owned
        self.calls = []

    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, args))
        return {"id": args[0]} if self.owned else None


class _FallbackConn:
    def __init__(self):
        self.calls = []

    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, args))
        return {"id": 41}

    async def fetch(self, sql, *args):
        self.calls.append(("fetch", sql, args))
        return []


def _cache_miss(monkeypatch):
    monkeypatch.setattr(chat_mod, "_chat_cache_get", lambda _key: None)
    monkeypatch.setattr(chat_mod, "_chat_cache_set", lambda _key, _payload: None)


def _configure_cached_tiered_clarification(monkeypatch):
    pending_calls = []

    async def _restaurant_tenant(*_args, **_kwargs):
        return True

    async def _pending_pop(*_args, **_kwargs):
        pending_calls.append("pop")
        return None

    async def _pending_put(*_args, **_kwargs):
        pending_calls.append("put")

    monkeypatch.setattr(restaurant_intent, "match_restaurant_ops", lambda _query: None)
    monkeypatch.setattr(restaurant_intent, "_is_restaurant_tenant", _restaurant_tenant)
    monkeypatch.setattr(
        restaurant_intent,
        "_cache_get",
        lambda *_args: {
            "code": "",
            "confidence": 0.2,
            "tier": "llm",
            "clarification_needed": True,
            "clarification_question": "trusted clarification only",
        },
    )
    monkeypatch.setattr(restaurant_intent, "_pending_pop", _pending_pop)
    monkeypatch.setattr(restaurant_intent, "_pending_put", _pending_put)
    return pending_calls


async def test_missing_trusted_tenant_fails_before_any_cache_or_db(monkeypatch):
    monkeypatch.setattr(
        chat_mod,
        "_chat_cache_get",
        lambda _key: pytest.fail("response cache must not be read without a tenant"),
    )

    async def _unexpected_pool():
        pytest.fail("database must not be read without a tenant")

    monkeypatch.setattr("smartbi.config.get_pg_pool", _unexpected_pool)

    with pytest.raises(HTTPException) as exc:
        await chat_mod.general_analysis(
            chat_mod.GeneralAnalysisRequest(
                query="tenant body must not authenticate",
                user_id="body-user",
                factory_id="BODY_FACTORY",
            ),
            _Request(),
        )

    assert exc.value.status_code == 403
    assert exc.value.detail == "TRUSTED_TENANT_REQUIRED"


@pytest.mark.parametrize("owned", [False, None])
async def test_cross_tenant_and_missing_sheet_share_the_same_404(monkeypatch, owned):
    conn = _OwnershipConn(owned=bool(owned))

    async def _pool():
        return _Pool(conn)

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(
        chat_mod,
        "_chat_cache_get",
        lambda _key: pytest.fail("cache lookup must follow ownership validation"),
    )
    monkeypatch.setattr(
        chat_mod,
        "get_sheet_data",
        lambda _sheet_id: pytest.fail("sheet cache must follow ownership validation"),
    )

    with pytest.raises(HTTPException) as exc:
        await chat_mod.general_analysis(
            chat_mod.GeneralAnalysisRequest(sheet_id="77", query="q"),
            _Request("F001"),
        )

    assert (exc.value.status_code, exc.value.detail) == (404, "UPLOAD_NOT_FOUND")
    _, sql, args = conn.calls[0]
    assert "WHERE id = $1 AND factory_id = $2" in sql
    assert args == (77, "F001")


async def test_cache_key_contains_trusted_factory_and_fallback_policy(monkeypatch):
    seen = []

    def _key(query_type, **kwargs):
        seen.append((query_type, kwargs))
        return f"key-{len(seen)}"

    monkeypatch.setattr(chat_mod, "_make_chat_cache_key", _key)
    monkeypatch.setattr(
        chat_mod,
        "_chat_cache_get",
        lambda _key: {"success": True, "answer": "cached"},
    )

    for policy in (True, False):
        response = await chat_mod.general_analysis(
            chat_mod.GeneralAnalysisRequest(
                query="same query",
                session_id="session-7",
                enable_thinking=False,
                thinking_budget=23,
                allow_tenant_data_fallback=policy,
            ),
            _Request("F001", user_id="7", role=" Factory_Super_Admin "),
        )
        assert response.answer == "cached"

    assert [item[1]["factory_id"] for item in seen] == ["F001", "F001"]
    assert [item[1]["allow_tenant_data_fallback"] for item in seen] == [True, False]
    assert [item[1]["trusted_user_id"] for item in seen] == ["7", "7"]
    assert [item[1]["trusted_role"] for item in seen] == [
        "factory_super_admin",
        "factory_super_admin",
    ]
    assert [item[1]["price_view"] for item in seen] == [True, True]
    assert [item[1]["session_id"] for item in seen] == ["session-7", "session-7"]
    assert [item[1]["enable_thinking"] for item in seen] == [False, False]
    assert [item[1]["thinking_budget"] for item in seen] == [23, 23]
    assert [item[1]["expected_intent"] for item in seen] == [None, None]


async def test_trusted_restaurant_intent_is_preserved_across_java_python_boundary(monkeypatch):
    """The inner text matcher must not silently change the metric Java selected."""
    _cache_miss(monkeypatch)
    observed = []

    async def _pool():
        return object()

    async def _resolve(code, pool, factory_id, **kwargs):
        observed.append((code, factory_id, kwargs.get("query")))
        return SimpleNamespace(
            answer_text="毛利率趋势回答",
            charts=[{"chartType": "line"}],
        )

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_ops_router.match_restaurant_ops",
        lambda _query: "RESTAURANT_OPS_TREND_ANALYSIS",
    )
    monkeypatch.setattr("smartbi.gold.restaurant_ops_router.resolve_by_code", _resolve)

    response = await chat_mod.general_analysis(
        chat_mod.GeneralAnalysisRequest(
            query="按月份绘制整体毛利率趋势曲线",
            table_type="restaurant_ops",
            expected_intent="RESTAURANT_OPS_GROSS_MARGIN",
            allow_tenant_data_fallback=False,
        ),
        _Request("F001", user_id="7", role="restaurant_manager"),
    )

    assert response.answer == "毛利率趋势回答"
    assert observed == [(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "F001",
        "按月份绘制整体毛利率趋势曲线",
    )]


async def test_untrusted_expected_intent_is_ignored(monkeypatch):
    _cache_miss(monkeypatch)
    observed = []

    async def _pool():
        return object()

    async def _resolve(code, *_args, **_kwargs):
        observed.append(code)
        return SimpleNamespace(answer_text="正常回答", charts=[])

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_ops_router.match_restaurant_ops",
        lambda _query: "RESTAURANT_OPS_SALES_SUMMARY",
    )
    monkeypatch.setattr("smartbi.gold.restaurant_ops_router.resolve_by_code", _resolve)

    await chat_mod.general_analysis(
        chat_mod.GeneralAnalysisRequest(
            query="整体毛利率是多少",
            table_type="restaurant_ops",
            expected_intent="DROP_TABLES",
            allow_tenant_data_fallback=False,
        ),
        _Request("F001", user_id="7", role="restaurant_manager"),
    )

    assert observed == ["RESTAURANT_OPS_SALES_SUMMARY"]


@pytest.mark.parametrize(
    "role_order",
    [
        ("factory_super_admin", "operator"),
        ("operator", "factory_super_admin"),
    ],
)
async def test_general_response_cache_never_crosses_trusted_roles(
    monkeypatch,
    role_order,
):
    cache = {}
    resolver_roles = []

    monkeypatch.setattr(chat_mod, "_chat_cache_get", cache.get)
    monkeypatch.setattr(
        chat_mod,
        "_chat_cache_set",
        lambda key, payload: cache.__setitem__(key, dict(payload)),
    )

    async def _pool():
        return object()

    async def _resolve(_code, _pool, _factory_id, *, role=None, query=None):
        resolver_roles.append(role)
        can_view = role == "factory_super_admin"
        answer = "revenue=100" if can_view else "revenue=REDACTED"
        return SimpleNamespace(answer_text=answer, charts=[])

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_ops_router.match_restaurant_ops",
        lambda _query: "RESTAURANT_OPS_REVENUE",
    )
    monkeypatch.setattr(
        "smartbi.gold.restaurant_ops_router.resolve_by_code",
        _resolve,
    )

    answers = []
    for role in role_order:
        response = await chat_mod.general_analysis(
            chat_mod.GeneralAnalysisRequest(query="same revenue query"),
            _Request("F001", user_id="7", role=role),
        )
        answers.append(response.answer)

    # The first role now hits its own cache; the resolver must not run again.
    repeat = await chat_mod.general_analysis(
        chat_mod.GeneralAnalysisRequest(query="same revenue query"),
        _Request("F001", user_id="7", role=role_order[0]),
    )

    expected = [
        "revenue=100" if role == "factory_super_admin" else "revenue=REDACTED"
        for role in role_order
    ]
    assert answers == expected
    assert repeat.answer == expected[0]
    assert resolver_roles == list(role_order)


async def test_ownership_db_error_log_does_not_expose_exception_text(
    monkeypatch,
    caplog,
):
    class _FailingConn:
        async def fetchrow(self, *_args, **_kwargs):
            raise RuntimeError("postgres://user:password@private-host/tenant")

    async def _pool():
        return _Pool(_FailingConn())

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)

    with pytest.raises(HTTPException) as exc:
        await chat_mod._require_owned_upload_id("77", "F001")

    assert (exc.value.status_code, exc.value.detail) == (
        503,
        "TENANT_OWNERSHIP_UNAVAILABLE",
    )
    assert "TENANT_OWNERSHIP_DB_ERROR" in caplog.text
    assert "private-host" not in caplog.text
    assert "password" not in caplog.text


def test_restaurant_clarification_session_key_is_bounded_and_user_namespaced():
    first = chat_mod._trusted_restaurant_session_key(
        "F001", 7, "shared-device-session"
    )
    second = chat_mod._trusted_restaurant_session_key(
        "F001", 8, "shared-device-session"
    )

    assert first is not None and second is not None
    assert first != second
    assert len(first) == len(second) == 75
    assert first.startswith("trusted-v1:")
    assert "shared-device-session" not in first
    assert chat_mod._trusted_restaurant_session_key(
        "F001", None, "shared-device-session"
    ) is None


@pytest.mark.parametrize("user_id", [None, "", "not-numeric", "0", "-1"])
async def test_nonstream_invalid_trusted_user_disables_tiered_clarification_session(
    monkeypatch,
    user_id,
):
    _cache_miss(monkeypatch)
    pending_calls = _configure_cached_tiered_clarification(monkeypatch)
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

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_ops_router.match_restaurant_ops",
        lambda _query: None,
    )
    monkeypatch.setattr(chat_mod, "_try_tiered_restaurant_intent", _observe_tiered)

    response = await chat_mod.general_analysis(
        chat_mod.GeneralAnalysisRequest(
            query="tiered clarification without trusted user",
            session_id="shared-device-session",
            allow_tenant_data_fallback=False,
        ),
        _Request("F001", user_id=user_id, role="operator"),
    )

    assert response.answer == "trusted clarification only"
    assert observed_session_keys == [None]
    assert pending_calls == []


async def test_false_policy_does_not_read_sheet_or_upload_fallback(monkeypatch):
    conn = _OwnershipConn(owned=True)

    async def _pool():
        return _Pool(conn)

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    monkeypatch.setattr(
        chat_mod,
        "get_sheet_data",
        lambda _sheet_id: pytest.fail("false policy must not read sheet cache"),
    )
    _cache_miss(monkeypatch)

    response = await chat_mod.general_analysis(
        chat_mod.GeneralAnalysisRequest(
            sheet_id="77",
            allow_tenant_data_fallback=False,
        ),
        _Request("F001"),
    )

    assert response.success is True
    assert len(conn.calls) == 1
    assert "WHERE id = $1 AND factory_id = $2" in conn.calls[0][1]


async def test_latest_upload_fallback_and_dynamic_rows_are_factory_scoped(monkeypatch):
    conn = _FallbackConn()

    async def _pool():
        return _Pool(conn)

    monkeypatch.setattr("smartbi.config.get_pg_pool", _pool)
    _cache_miss(monkeypatch)

    response = await chat_mod.general_analysis(
        chat_mod.GeneralAnalysisRequest(),
        _Request("F001"),
    )

    assert response.success is True
    latest_call, rows_call = conn.calls
    assert latest_call[0] == "fetchrow"
    assert "WHERE factory_id = $1" in latest_call[1]
    assert latest_call[2] == ("F001",)
    assert rows_call[0] == "fetch"
    assert "JOIN smart_bi_pg_excel_uploads" in rows_call[1]
    assert "u.factory_id = $2" in rows_call[1]
    assert rows_call[2] == (41, "F001")


def test_fallback_defaults_to_legacy_trusted_browser_behavior():
    assert chat_mod.GeneralAnalysisRequest().allow_tenant_data_fallback is True
