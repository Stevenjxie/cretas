"""Router-level tests for 卡5b AI 飞轮运营台后端 API
(`smartbi/api/flywheel.py`, mounted at /api/smartbi/flywheel).

Uses FastAPI TestClient with a fake auth middleware (sets request.state.role/
factory_id/username/auth_method) + a fake asyncpg pool monkeypatched onto
smartbi.config.get_pg_pool, so no real DB is touched. Covers:

  - require_admin gate (403 for non-admin role, 401 for no role)
  - domain validation (400 for unsupported domain)
  - the RLS GUC contract (organizer 终审重点): every platform-wide read
    endpoint must call `SELECT set_config('app.factory_id', '', false)` on
    the borrowed connection BEFORE running its query
  - candidates/misses pass factory_id=None (admin channel) into
    restaurant_intent_promotion.aggregate_candidates/aggregate_misses
  - candidates/approve: invalid code -> 400, UndefinedTableError -> 503,
    happy path -> normalized_phrase + INSERT args
  - candidates/reject: delegates to promo.reject_candidate with rejected_by
  - dataset/export: JSONL shape, infra-key stripping from `plan`

Run:
    cd backend/python
    python -m pytest smartbi/api/tests/test_flywheel.py -v
"""
from __future__ import annotations

import datetime as dt
import json
from contextlib import asynccontextmanager

import asyncpg
import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

import smartbi.api.flywheel as fw


def _make_app(role="platform_admin", factory_id=None, username="zhangquan", auth_method="jwt"):
    app = FastAPI()

    @app.middleware("http")
    async def _inject_state(request: Request, call_next):
        request.state.role = role
        request.state.factory_id = factory_id
        request.state.username = username
        request.state.auth_method = auth_method
        return await call_next(request)

    app.include_router(fw.router, prefix="/api/smartbi/flywheel")
    return app


def make_client(**kwargs):
    return TestClient(_make_app(**kwargs))


# ─── Fake asyncpg pool/conn doubles ────────────────────────────────────────

class _FakeConn:
    def __init__(self, fetchrow_results=None, fetch_results=None, execute_ok=None):
        # Each is a list consumed in call order (one entry per call site).
        self._fetchrow_results = list(fetchrow_results or [])
        self._fetch_results = list(fetch_results or [])
        self.execute_calls = []
        self.fetchrow_calls = []
        self.fetch_calls = []
        self._execute_side_effect = execute_ok

    async def execute(self, sql, *args):
        self.execute_calls.append((sql, args))
        if self._execute_side_effect is not None:
            raise self._execute_side_effect
        return "SELECT 1"

    async def fetchrow(self, sql, *args):
        self.fetchrow_calls.append((sql, args))
        if not self._fetchrow_results:
            return None
        result = self._fetchrow_results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result

    async def fetch(self, sql, *args):
        self.fetch_calls.append((sql, args))
        if not self._fetch_results:
            return []
        result = self._fetch_results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


class _FakePool:
    def __init__(self, conn: _FakeConn):
        self._conn = conn

    @asynccontextmanager
    async def acquire(self):
        yield self._conn


def _patch_pool(monkeypatch, conn: _FakeConn) -> _FakePool:
    pool = _FakePool(conn)

    async def _get():
        return pool

    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_pg_pool", _get)
    return pool


def _set_config_guc_values(conn: _FakeConn):
    """Extract the 2nd positional arg (the factory_id value) of every
    `SELECT set_config('app.factory_id', ...)` execute() call."""
    return [args[0] for sql, args in conn.execute_calls if "set_config" in sql]


# ─── require_admin gate ─────────────────────────────────────────────────────

def test_overview_requires_admin_role_403():
    # This test mounts the router on a bare FastAPI() app (no main.py's global
    # exception handlers), so HTTPException surfaces FastAPI's default
    # {"detail": ...} body, not the unified {success,...} envelope — the real
    # app (main.py) wraps it via the registered http_exception_handler.
    client = make_client(role="factory_data_operator")
    resp = client.get("/api/smartbi/flywheel/overview")
    assert resp.status_code == 403
    assert "管理员权限" in resp.json()["detail"]


def test_overview_no_role_401():
    client = make_client(role=None, auth_method="jwt")
    resp = client.get("/api/smartbi/flywheel/overview")
    assert resp.status_code == 401


# ─── domain validation ──────────────────────────────────────────────────────

def test_overview_unsupported_domain_400(monkeypatch):
    conn = _FakeConn()
    _patch_pool(monkeypatch, conn)
    client = make_client()
    resp = client.get("/api/smartbi/flywheel/overview?domain=factory")
    assert resp.status_code == 400
    assert "factory" in resp.json()["detail"]


# ─── overview: RLS GUC contract (the organizer's final-review focus) ──────

def _summary_row(**overrides):
    base = {
        "total_queries": 10, "served_count": 9, "contract_pass_count": 8,
        "contract_fail_count": 1, "clarify_count": 2, "llm_tier_count": 6,
        "cache_tier_count": 3, "promoted_hit_count": 1, "thumbs_up": 4, "thumbs_down": 1,
    }
    base.update(overrides)
    return base


def test_overview_resets_admin_guc_to_empty_string(monkeypatch):
    conn = _FakeConn(
        fetchrow_results=[_summary_row()],
        fetch_results=[[{"tier": "llm", "n": 6}, {"tier": "cache", "n": 3}]],
    )
    _patch_pool(monkeypatch, conn)

    async def _fake_promoted_summary(domain):
        return {"available": True, "route_count": 2, "total_hits": 5}

    monkeypatch.setattr(fw, "_read_promoted_routes_summary", _fake_promoted_summary)

    client = make_client()
    resp = client.get("/api/smartbi/flywheel/overview?domain=restaurant&days=7")
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True

    guc_values = _set_config_guc_values(conn)
    assert guc_values, "overview must call set_config('app.factory_id', ...) before querying"
    assert guc_values[0] == "", "admin channel must reset GUC to empty string, not skip/leave residual"

    data = body["data"]
    assert data["total_queries"] == 10
    assert data["cache_hit_count"] == 3
    assert data["promoted_hit_count"] == 1
    assert data["contract_fail_rate"] == round(1 / 9, 4)  # 1 fail / (8 pass + 1 fail)
    assert data["clarify_rate"] == round(2 / 10, 4)
    assert data["token_estimate"] == 6 * fw._AVG_TOKENS_PER_LLM_CALL
    assert data["promoted_routes"]["available"] is True


def test_overview_zero_total_avoids_division_by_zero(monkeypatch):
    conn = _FakeConn(
        fetchrow_results=[_summary_row(total_queries=0, served_count=0, contract_pass_count=0,
                                        contract_fail_count=0, clarify_count=0, llm_tier_count=0,
                                        cache_tier_count=0, promoted_hit_count=0, thumbs_up=0, thumbs_down=0)],
        fetch_results=[[]],
    )
    _patch_pool(monkeypatch, conn)

    async def _fake_promoted_summary(domain):
        return {"available": False, "reason": "ai_promoted_routes 表不存在"}

    monkeypatch.setattr(fw, "_read_promoted_routes_summary", _fake_promoted_summary)

    client = make_client()
    resp = client.get("/api/smartbi/flywheel/overview")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["served_rate"] is None
    assert data["contract_fail_rate"] is None
    assert data["promoted_routes"]["available"] is False


async def test_read_promoted_routes_summary_handles_undefined_table(monkeypatch):
    conn = _FakeConn(fetchrow_results=[asyncpg.exceptions.UndefinedTableError("nope")])
    pool = _FakePool(conn)

    async def _get():
        return pool

    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_pg_pool", _get)

    result = await fw._read_promoted_routes_summary("restaurant")
    assert result["available"] is False
    assert "ai_promoted_routes" in result["reason"]


# ─── candidates: reuses aggregate_candidates via admin channel ─────────────

def test_candidates_calls_aggregate_with_admin_channel(monkeypatch):
    conn = _FakeConn(fetch_results=[[]])
    _patch_pool(monkeypatch, conn)

    captured = {}

    async def _fake_aggregate_candidates(pool, *, min_confidence, min_count, limit, factory_id):
        captured["factory_id"] = factory_id
        captured["min_confidence"] = min_confidence
        return [
            {"query": "这两个月生意咋样", "code": "RESTAURANT_OPS_SALES_SUMMARY",
             "codes": ["RESTAURANT_OPS_SALES_SUMMARY"], "occurrence_count": 3,
             "max_confidence": 0.9, "conflict": False, "recommended": True,
             "family": "query", "last_seen": "2026-07-28"},
        ]

    import smartbi.gold.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "aggregate_candidates", _fake_aggregate_candidates)

    client = make_client()
    resp = client.get("/api/smartbi/flywheel/candidates?domain=restaurant&min_confidence=0.8")
    assert resp.status_code == 200
    assert captured["factory_id"] is None, "candidates must use the admin (cross-tenant) channel"
    assert captured["min_confidence"] == 0.8

    body = resp.json()["data"]
    assert body["count"] == 1
    c = body["candidates"][0]
    assert c["query"] == "这两个月生意咋样"
    # enrichment query ran (no matching row in fake -> None-safe defaults)
    assert c["contract_pass_rate"] is None
    assert c["last_answer_preview"] is None


def test_candidates_enrichment_uses_single_batched_query(monkeypatch):
    enrich_row = {
        "norm_query": "这两个月生意咋样", "total_count": 4, "pass_count": 3,
        "last_answer": "本月营收12万", "last_plan_json": json.dumps({"requested_metrics": ["revenue"]}),
    }
    conn = _FakeConn(fetch_results=[[enrich_row]])
    _patch_pool(monkeypatch, conn)

    async def _fake_aggregate_candidates(pool, **kwargs):
        return [{"query": "这两个月生意咋样", "code": "RESTAURANT_OPS_SALES_SUMMARY",
                  "codes": ["RESTAURANT_OPS_SALES_SUMMARY"], "occurrence_count": 4,
                  "max_confidence": 0.9, "conflict": False, "recommended": True,
                  "family": "query", "last_seen": "2026-07-28"}]

    import smartbi.gold.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "aggregate_candidates", _fake_aggregate_candidates)

    client = make_client()
    resp = client.get("/api/smartbi/flywheel/candidates")
    assert resp.status_code == 200
    assert len(conn.fetch_calls) == 1, "enrichment must be one batched query, not N+1"

    c = resp.json()["data"]["candidates"][0]
    assert c["contract_pass_rate"] == 0.75
    assert c["last_answer_preview"] == "本月营收12万"
    assert c["plan_json"] == {"requested_metrics": ["revenue"]}


# ─── candidates/approve ─────────────────────────────────────────────────────

def test_approve_candidate_invalid_code_400(monkeypatch):
    conn = _FakeConn()
    _patch_pool(monkeypatch, conn)
    client = make_client()
    resp = client.post("/api/smartbi/flywheel/candidates/approve", json={
        "domain": "restaurant", "query": "某问题", "code": "NOT_A_REAL_CODE",
    })
    assert resp.status_code == 400


def test_approve_candidate_undefined_table_returns_503(monkeypatch):
    # plan_json is provided in the request body, so the endpoint skips its
    # "look up most recent capture row" fetchrow and goes straight to the
    # INSERT — only one fetchrow call happens.
    conn = _FakeConn(
        fetchrow_results=[asyncpg.exceptions.UndefinedTableError("no table")],
    )
    _patch_pool(monkeypatch, conn)
    client = make_client()
    resp = client.post("/api/smartbi/flywheel/candidates/approve", json={
        "domain": "restaurant", "query": "哪个菜卖得好", "code": "RESTAURANT_OPS_GROSS_MARGIN",
        "plan_json": {"requested_metrics": ["gross_margin"]},
    })
    assert resp.status_code == 503
    assert "ai_promoted_routes" in resp.json()["detail"]


def test_approve_candidate_success_normalizes_phrase_and_inserts(monkeypatch):
    conn = _FakeConn(
        fetchrow_results=[{"domain": "restaurant", "normalized_phrase": "哪个菜卖得好", "hit_count": 0}],
    )
    _patch_pool(monkeypatch, conn)
    client = make_client(username="reviewer_zhang")
    resp = client.post("/api/smartbi/flywheel/candidates/approve", json={
        "domain": "restaurant", "query": "  哪个菜卖得好？  ", "code": "RESTAURANT_OPS_GROSS_MARGIN",
        "plan_json": {"requested_metrics": ["gross_margin"]}, "plan_version": "3",
    })
    assert resp.status_code == 200
    body = resp.json()["data"]
    assert body["normalized_phrase"] == "哪个菜卖得好"  # whitespace/punctuation normalized
    assert body["scope"] == "global"

    insert_sql, insert_args = conn.fetchrow_calls[0]
    assert "INSERT INTO ai_promoted_routes" in insert_sql
    assert insert_args[0] == "restaurant"
    assert insert_args[1] == "哪个菜卖得好"
    assert insert_args[4] == "global"
    assert insert_args[5] == "reviewer_zhang"


# ─── candidates/reject ──────────────────────────────────────────────────────

def test_reject_candidate_delegates_to_promo_with_rejected_by(monkeypatch):
    captured = {}

    def _fake_reject(query, reason, *, rejected_by=None):
        captured["query"] = query
        captured["reason"] = reason
        captured["rejected_by"] = rejected_by
        return {"ok": True, "already_rejected": False, "ledger_path": "/x/rejected.json",
                "ledger_size": 1, "durable": False}

    import smartbi.gold.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "reject_candidate", _fake_reject)

    client = make_client(username="reviewer_li")
    resp = client.post("/api/smartbi/flywheel/candidates/reject", json={
        "domain": "restaurant", "query": "帮我录入今天的报损", "reason": "写操作错接",
    })
    assert resp.status_code == 200
    assert captured == {"query": "帮我录入今天的报损", "reason": "写操作错接", "rejected_by": "reviewer_li"}
    assert resp.json()["data"]["durable"] is False


# ─── misses ──────────────────────────────────────────────────────────────

def test_misses_calls_aggregate_misses_with_admin_channel(monkeypatch):
    # aggregate_misses itself is monkeypatched below (its own RLS GUC
    # behavior is covered by test_restaurant_intent_promotion.py), but
    # list_misses still resolves a real pool via get_pg_pool() first — patch
    # it so that resolution doesn't attempt a real DB connection.
    _patch_pool(monkeypatch, _FakeConn())
    captured = {}

    async def _fake_aggregate_misses(pool, *, limit, factory_id):
        captured["factory_id"] = factory_id
        captured["limit"] = limit
        return [{"query": "帮我建个领料单", "occurrence_count": 3, "reasons": ["prefilter"],
                  "spec_intents": [], "last_seen": "2026-07-28", "family": "write"}]

    import smartbi.gold.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "aggregate_misses", _fake_aggregate_misses)

    client = make_client()
    resp = client.get("/api/smartbi/flywheel/misses?limit=50")
    assert resp.status_code == 200
    assert captured["factory_id"] is None
    assert captured["limit"] == 50
    assert resp.json()["data"]["count"] == 1


# ─── quality ─────────────────────────────────────────────────────────────

def test_quality_resets_admin_guc_and_returns_both_sections(monkeypatch):
    conn = _FakeConn(fetch_results=[
        [{"id": 1, "query": "q1", "answer_preview": "a1", "tier": "llm", "spec_intent": None,
          "factory_id": "F1", "created_at": dt.datetime(2026, 7, 28)}],
        [{"id": 2, "query": "q2", "answer_preview": "a2", "feedback_comment": "答错了",
          "factory_id": "F2", "created_at": dt.datetime(2026, 7, 28)}],
    ])
    _patch_pool(monkeypatch, conn)
    client = make_client()
    resp = client.get("/api/smartbi/flywheel/quality")
    assert resp.status_code == 200
    guc_values = _set_config_guc_values(conn)
    assert guc_values and guc_values[0] == ""
    data = resp.json()["data"]
    assert len(data["contract_failures"]) == 1
    assert len(data["negative_feedback"]) == 1


# ─── dataset/export ─────────────────────────────────────────────────────

def test_dataset_export_builds_jsonl_and_strips_infra_keys(monkeypatch):
    row = {
        "query": "这两个月生意咋样",
        "answer": "本月营收12万，环比+5%",
        "agg_meta": json.dumps({
            "tier": "llm", "confidence": 0.9, "served": True, "contract_pass": True,
            "source": "chat", "requested_metrics": ["revenue"], "dimensions": [],
        }),
        "user_feedback": 1,
        "feedback_comment": None,
        "created_at": dt.datetime(2026, 7, 28, 10, 0, 0),
    }
    conn = _FakeConn(fetch_results=[[row]])
    _patch_pool(monkeypatch, conn)
    client = make_client()
    resp = client.post("/api/smartbi/flywheel/dataset/export", json={
        "domain": "restaurant", "days": 30, "contract_pass": True, "limit": 100,
    })
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["count"] == 1
    line = json.loads(data["jsonl"].splitlines()[0])
    assert line["query"] == "这两个月生意咋样"
    assert line["plan"] == {"requested_metrics": ["revenue"], "dimensions": []}
    assert "tier" not in line["plan"] and "contract_pass" not in line["plan"]
    assert line["feedback_label"] == 1
    assert line["contract_pass"] is True

    guc_values = _set_config_guc_values(conn)
    assert guc_values and guc_values[0] == ""
