"""Router-level tests for 卡5b AI 飞轮运营台后端 API
(`smartbi/api/flywheel.py`, mounted at /api/smartbi/flywheel).

Uses FastAPI TestClient with a fake auth middleware (sets request.state.role/
factory_id/username/auth_method) + a fake asyncpg pool monkeypatched onto
smartbi.config.get_pg_pool, so no real DB is touched. Covers:

  - **Cross-tenant isolation (2026-07-28 review, the core evidence)**:
    every one of the 9 endpoints must reject `factory_super_admin` /
    `permission_admin` with 403 -- those roles passed the OLD shared
    `require_admin` gate (ADMIN_ROLES includes them) and would have been able
    to read/export every tenant's questions, answers, and negative-feedback
    comments once `_admin_channel_guc`/`aggregate_candidates(factory_id=None)`
    reset the RLS GUC to see all tenants. `_require_platform_admin` (this
    file's own gate, not the shared one) is the fix under test.
  - domain validation (400 for unsupported domain)
  - the RLS GUC contract on `smart_bi_llm_fallback_log`: every platform-wide
    read endpoint calls `SELECT set_config('app.factory_id', '', false)` on
    the borrowed connection BEFORE running its query
  - candidates/misses pass factory_id=None (admin channel) into
    restaurant_intent_promotion.aggregate_candidates/aggregate_misses
  - candidates/approve, candidates/seed-import: delegate to
    `restaurant_intent_promotion.apply_route_promotions` (card2's validated
    write path for `ai_promoted_routes`) -- this file must NOT hand-roll its
    own INSERT/GUC for that table (an earlier draft did, and used the wrong
    GUC value for it; see flywheel.py's module + endpoint docstrings)
  - misses/status, candidates/reject: delegate to the promotion module's
    file-ledger writers
  - dataset/export: JSONL shape, infra-key stripping from `plan`
  - `_read_promoted_routes_summary`: delegates to `list_route_promotions`,
    never returns a `total_hits`/`hit_count`-derived number (card2 confirmed
    `ai_promoted_routes.hit_count` is never incremented -- returning it as a
    metric would be a permanently-fake datapoint)

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


def _noop_promoted_routes_summary(monkeypatch):
    async def _fake(domain):
        return {"available": True, "route_count": 0, "hit_count_instrumented": False}
    monkeypatch.setattr(fw, "_read_promoted_routes_summary", _fake)


async def _async_ret(value):
    return value


# ═══════════════════════════════════════════════════════════════════════
# Cross-tenant isolation (2026-07-28 review: the core deliverable)
# ═══════════════════════════════════════════════════════════════════════
# Every endpoint, every non-platform_admin admin-tier role -> 403. This is
# the regression test proving the vulnerability: `factory_super_admin` and
# `permission_admin` PASSED the old shared `require_admin` (its ADMIN_ROLES
# includes them) and would have reached `_admin_channel_guc`/
# `aggregate_candidates(factory_id=None)`, which unconditionally clears the
# RLS GUC to see every tenant's rows -- cross-tenant leak of questions,
# answers, and negative-feedback comments.

_ISOLATION_CASES = [
    ("GET", "/api/smartbi/flywheel/overview", None),
    ("GET", "/api/smartbi/flywheel/candidates", None),
    ("POST", "/api/smartbi/flywheel/candidates/approve",
     {"domain": "restaurant", "query": "x", "code": "RESTAURANT_OPS_SALES_SUMMARY"}),
    ("POST", "/api/smartbi/flywheel/candidates/reject",
     {"domain": "restaurant", "query": "x", "reason": "y"}),
    ("POST", "/api/smartbi/flywheel/candidates/seed-import",
     {"domain": "restaurant", "entries": [{"query": "x", "code": "RESTAURANT_OPS_SALES_SUMMARY"}]}),
    ("GET", "/api/smartbi/flywheel/misses", None),
    ("POST", "/api/smartbi/flywheel/misses/status",
     {"domain": "restaurant", "query": "x", "status": "planned"}),
    ("GET", "/api/smartbi/flywheel/quality", None),
    ("POST", "/api/smartbi/flywheel/dataset/export", {"domain": "restaurant"}),
]


@pytest.mark.parametrize("method,path,body", _ISOLATION_CASES, ids=[c[1] for c in _ISOLATION_CASES])
def test_factory_super_admin_rejected_on_every_endpoint(monkeypatch, method, path, body):
    """factory_super_admin (single-tenant role) must be rejected with 403 on
    every one of the 9 endpoints -- no DB call should even happen (pool is
    intentionally left unpatched; a 403 must fire before any pool.acquire())."""
    client = make_client(role="factory_super_admin", factory_id="RES_3101_009")
    resp = client.request(method, path, json=body) if body is not None else client.request(method, path)
    assert resp.status_code == 403, f"{method} {path}: expected 403, got {resp.status_code}: {resp.text}"
    assert "platform_admin" in resp.json()["detail"]


@pytest.mark.parametrize("method,path,body", _ISOLATION_CASES, ids=[c[1] for c in _ISOLATION_CASES])
def test_permission_admin_rejected_on_every_endpoint(monkeypatch, method, path, body):
    """Same as above for the other single-tenant admin-tier role."""
    client = make_client(role="permission_admin", factory_id="RES_3101_009")
    resp = client.request(method, path, json=body) if body is not None else client.request(method, path)
    assert resp.status_code == 403


def test_internal_auth_method_bypasses_role_gate(monkeypatch):
    """Java -> Python internal calls (auth_method='internal') are exempt from
    the role gate, same convention as the shared require_admin."""
    conn = _FakeConn(
        fetchrow_results=[{
            "total_queries": 0, "served_count": 0, "contract_pass_count": 0,
            "contract_fail_count": 0, "clarification_needed": 0, "clarify_count": 0,
            "llm_tier_count": 0, "cache_tier_count": 0, "promoted_hit_count": 0,
            "thumbs_up": 0, "thumbs_down": 0,
        }],
        fetch_results=[[]],
    )
    _patch_pool(monkeypatch, conn)
    _noop_promoted_routes_summary(monkeypatch)
    client = make_client(role=None, auth_method="internal")
    resp = client.get("/api/smartbi/flywheel/overview")
    assert resp.status_code == 200


# ─── require_admin gate: no role / unsupported domain ──────────────────────

def test_overview_no_role_401():
    client = make_client(role=None, auth_method="jwt")
    resp = client.get("/api/smartbi/flywheel/overview")
    assert resp.status_code == 401


def test_overview_unsupported_domain_400(monkeypatch):
    conn = _FakeConn()
    _patch_pool(monkeypatch, conn)
    client = make_client()
    resp = client.get("/api/smartbi/flywheel/overview?domain=factory")
    assert resp.status_code == 400
    assert "factory" in resp.json()["detail"]


# ─── overview: RLS GUC contract + real promoted-hit signal ─────────────────

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
        return {"available": True, "route_count": 2, "hit_count_instrumented": False}

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
    # ai_promoted_routes.hit_count is never incremented (card2 confirmed) --
    # the response must NOT surface any hit_count-derived number.
    assert "total_hits" not in data["promoted_routes"]
    assert data["promoted_routes"]["hit_count_instrumented"] is False
    assert data["promoted_routes"]["route_count"] == 2


def test_overview_promoted_hit_count_requires_both_tier_and_authority_markers(monkeypatch):
    """card2 confirmed the real promotion-hit signal is BOTH
    agg_meta.tier='exact' AND planner_authority='promoted_exact' -- the SQL
    filter must check both, not just one (a looser filter would overcount)."""
    conn = _FakeConn(fetchrow_results=[_summary_row()], fetch_results=[[]])
    _patch_pool(monkeypatch, conn)
    _noop_promoted_routes_summary(monkeypatch)
    client = make_client()
    resp = client.get("/api/smartbi/flywheel/overview")
    assert resp.status_code == 200
    sql, _args = conn.fetchrow_calls[0]
    assert "tier') = 'exact'" in sql
    assert "planner_authority') = 'promoted_exact'" in sql


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


# ─── _read_promoted_routes_summary: delegates to list_route_promotions ─────

async def test_read_promoted_routes_summary_delegates_to_list_route_promotions(monkeypatch):
    captured = {}

    async def _fake_list_route_promotions(pool, *, domain):
        captured["domain"] = domain
        return [{"normalized_phrase": "a"}, {"normalized_phrase": "b"}]

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "list_route_promotions", _fake_list_route_promotions)

    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_pg_pool", lambda: _async_ret(object()))

    result = await fw._read_promoted_routes_summary("restaurant")
    assert captured["domain"] == "restaurant"
    assert result == {"available": True, "route_count": 2, "hit_count_instrumented": False}
    assert "total_hits" not in result
    assert "hit_count" not in result


async def test_read_promoted_routes_summary_handles_undefined_table(monkeypatch):
    async def _fake_list_route_promotions(pool, *, domain):
        raise asyncpg.exceptions.UndefinedTableError("nope")

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "list_route_promotions", _fake_list_route_promotions)

    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_pg_pool", lambda: _async_ret(object()))

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

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
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

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "aggregate_candidates", _fake_aggregate_candidates)

    client = make_client()
    resp = client.get("/api/smartbi/flywheel/candidates")
    assert resp.status_code == 200
    assert len(conn.fetch_calls) == 1, "enrichment must be one batched query, not N+1"

    c = resp.json()["data"]["candidates"][0]
    assert c["contract_pass_rate"] == 0.75
    assert c["last_answer_preview"] == "本月营收12万"
    assert c["plan_json"] == {"requested_metrics": ["revenue"]}


# ─── candidates/approve: delegates to apply_route_promotions ───────────────

def test_approve_candidate_invalid_code_400():
    client = make_client()
    resp = client.post("/api/smartbi/flywheel/candidates/approve", json={
        "domain": "restaurant", "query": "某问题", "code": "NOT_A_REAL_CODE",
    })
    assert resp.status_code == 400


def test_approve_candidate_delegates_to_apply_route_promotions(monkeypatch):
    captured = {}

    async def _fake_apply(pool, entries, *, domain, scope, source, reviewed_by):
        captured.update(entries=entries, domain=domain, scope=scope, source=source, reviewed_by=reviewed_by)
        return {"domain": domain, "scope": scope, "source": source,
                "written": [{"query": entries[0]["query"], "normalized_phrase": "哪个菜卖得好", "intent": entries[0]["code"]}],
                "skipped": []}

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "apply_route_promotions", _fake_apply)
    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_pg_pool", lambda: _async_ret(object()))

    client = make_client(username="reviewer_zhang")
    resp = client.post("/api/smartbi/flywheel/candidates/approve", json={
        "domain": "restaurant", "query": "哪个菜卖得好", "code": "RESTAURANT_OPS_GROSS_MARGIN",
    })
    assert resp.status_code == 200
    assert captured["domain"] == "restaurant"
    assert captured["scope"] == "global"
    assert captured["source"] == "flywheel"
    assert captured["reviewed_by"] == "reviewer_zhang"
    assert captured["entries"] == [{"query": "哪个菜卖得好", "code": "RESTAURANT_OPS_GROSS_MARGIN"}]
    assert "plan" not in captured["entries"][0], "no explicit plan given -> must NOT fabricate one from agg_meta"

    body = resp.json()["data"]
    assert body["written"][0]["normalized_phrase"] == "哪个菜卖得好"


def test_approve_candidate_passes_through_explicit_plan(monkeypatch):
    captured = {}

    async def _fake_apply(pool, entries, **kwargs):
        captured["entries"] = entries
        return {"domain": "restaurant", "scope": "global", "source": "flywheel",
                "written": [{"query": entries[0]["query"], "normalized_phrase": "x", "intent": "RESTAURANT_OPS_GROSS_MARGIN"}],
                "skipped": []}

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "apply_route_promotions", _fake_apply)
    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_pg_pool", lambda: _async_ret(object()))

    plan = {"intent": "RESTAURANT_OPS_GROSS_MARGIN", "time_range": None}
    client = make_client()
    resp = client.post("/api/smartbi/flywheel/candidates/approve", json={
        "domain": "restaurant", "query": "哪个菜卖得好", "code": "RESTAURANT_OPS_GROSS_MARGIN", "plan": plan,
    })
    assert resp.status_code == 200
    assert captured["entries"][0]["plan"] == plan


def test_approve_candidate_skipped_by_apply_route_promotions_returns_400(monkeypatch):
    async def _fake_apply(pool, entries, **kwargs):
        return {"domain": "restaurant", "scope": "global", "source": "flywheel",
                "written": [], "skipped": [{"query": entries[0]["query"], "reason": "plan_contains_resolved_dates"}]}

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "apply_route_promotions", _fake_apply)
    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_pg_pool", lambda: _async_ret(object()))

    client = make_client()
    resp = client.post("/api/smartbi/flywheel/candidates/approve", json={
        "domain": "restaurant", "query": "某问题", "code": "RESTAURANT_OPS_GROSS_MARGIN",
    })
    assert resp.status_code == 400
    assert "plan_contains_resolved_dates" in resp.json()["detail"]


def test_approve_candidate_undefined_table_returns_503(monkeypatch):
    async def _fake_apply(pool, entries, **kwargs):
        raise asyncpg.UndefinedTableError("no table")

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "apply_route_promotions", _fake_apply)
    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_pg_pool", lambda: _async_ret(object()))

    client = make_client()
    resp = client.post("/api/smartbi/flywheel/candidates/approve", json={
        "domain": "restaurant", "query": "哪个菜卖得好", "code": "RESTAURANT_OPS_GROSS_MARGIN",
    })
    assert resp.status_code == 503
    assert "ai_promoted_routes" in resp.json()["detail"]


def test_approve_candidate_rls_violation_returns_403_not_500(monkeypatch):
    async def _fake_apply(pool, entries, **kwargs):
        raise asyncpg.exceptions.InsufficientPrivilegeError("new row violates row-level security policy")

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "apply_route_promotions", _fake_apply)
    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_pg_pool", lambda: _async_ret(object()))

    client = make_client()
    resp = client.post("/api/smartbi/flywheel/candidates/approve", json={
        "domain": "restaurant", "query": "哪个菜卖得好", "code": "RESTAURANT_OPS_GROSS_MARGIN",
    })
    assert resp.status_code == 403
    assert resp.status_code != 500


# ─── candidates/reject ──────────────────────────────────────────────────────

def test_reject_candidate_delegates_to_promo_with_rejected_by(monkeypatch):
    captured = {}

    def _fake_reject(query, reason, *, rejected_by=None):
        captured["query"] = query
        captured["reason"] = reason
        captured["rejected_by"] = rejected_by
        return {"ok": True, "already_rejected": False, "ledger_path": "/x/rejected.json",
                "ledger_size": 1, "durable": False}

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "reject_candidate", _fake_reject)

    client = make_client(username="reviewer_li")
    resp = client.post("/api/smartbi/flywheel/candidates/reject", json={
        "domain": "restaurant", "query": "帮我录入今天的报损", "reason": "写操作错接",
    })
    assert resp.status_code == 200
    assert captured == {"query": "帮我录入今天的报损", "reason": "写操作错接", "rejected_by": "reviewer_li"}
    assert resp.json()["data"]["durable"] is False


# ─── candidates/seed-import (卡5 补充契约) ──────────────────────────────────

def test_seed_import_batches_entries_into_one_apply_route_promotions_call(monkeypatch):
    captured = {}

    async def _fake_apply(pool, entries, *, domain, scope, source, reviewed_by):
        captured.update(entries=entries, domain=domain, scope=scope, source=source, reviewed_by=reviewed_by)
        return {
            "domain": domain, "scope": scope, "source": source,
            "written": [{"query": e["query"], "normalized_phrase": e["query"], "intent": e["code"]} for e in entries[:1]],
            "skipped": [{"query": entries[1]["query"], "reason": "unknown_intent:None"}] if len(entries) > 1 else [],
        }

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "apply_route_promotions", _fake_apply)
    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_pg_pool", lambda: _async_ret(object()))

    client = make_client(username="reviewer_seed")
    resp = client.post("/api/smartbi/flywheel/candidates/seed-import", json={
        "domain": "restaurant", "scope": "global",
        "entries": [
            {"query": "常问问题一", "code": "RESTAURANT_OPS_SALES_SUMMARY"},
            {"query": "常问问题二", "code": "RESTAURANT_OPS_GROSS_MARGIN",
             "plan": {"intent": "RESTAURANT_OPS_GROSS_MARGIN"}},
        ],
    })
    assert resp.status_code == 200
    assert captured["source"] == "manual_seed"
    assert captured["scope"] == "global"
    assert captured["reviewed_by"] == "reviewer_seed"
    assert len(captured["entries"]) == 2
    assert "plan" not in captured["entries"][0]
    assert captured["entries"][1]["plan"] == {"intent": "RESTAURANT_OPS_GROSS_MARGIN"}

    data = resp.json()["data"]
    assert len(data["written"]) == 1
    assert len(data["skipped"]) == 1


def test_seed_import_undefined_table_returns_503(monkeypatch):
    async def _fake_apply(pool, entries, **kwargs):
        raise asyncpg.UndefinedTableError("no table")

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "apply_route_promotions", _fake_apply)
    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_pg_pool", lambda: _async_ret(object()))

    client = make_client()
    resp = client.post("/api/smartbi/flywheel/candidates/seed-import", json={
        "domain": "restaurant",
        "entries": [{"query": "x", "code": "RESTAURANT_OPS_SALES_SUMMARY"}],
    })
    assert resp.status_code == 503


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

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "aggregate_misses", _fake_aggregate_misses)
    monkeypatch.setattr(promo, "load_miss_status", lambda: {})

    client = make_client()
    resp = client.get("/api/smartbi/flywheel/misses?limit=50")
    assert resp.status_code == 200
    assert captured["factory_id"] is None
    assert captured["limit"] == 50
    data = resp.json()["data"]
    assert data["count"] == 1
    assert data["misses"][0]["status"] == "unreviewed"


def test_misses_merges_status_from_ledger(monkeypatch):
    _patch_pool(monkeypatch, _FakeConn())

    async def _fake_aggregate_misses(pool, *, limit, factory_id):
        return [{"query": "外卖利润率咋算", "occurrence_count": 2, "reasons": ["should_delegate"],
                  "spec_intents": [], "last_seen": "2026-07-28", "family": "query"}]

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "aggregate_misses", _fake_aggregate_misses)
    monkeypatch.setattr(promo, "load_miss_status", lambda: {
        "外卖利润率咋算": {"status": "planned", "note": "排入 Q3", "updated_at": "2026-07-28T00:00:00Z"},
    })

    client = make_client()
    resp = client.get("/api/smartbi/flywheel/misses")
    m = resp.json()["data"]["misses"][0]
    assert m["status"] == "planned"
    assert m["status_note"] == "排入 Q3"


# ─── misses/status (卡5 补充契约) ───────────────────────────────────────────

def test_set_miss_status_delegates_to_promo(monkeypatch):
    captured = {}

    def _fake_set_status(query, status, *, note=None, reviewed_by=None):
        captured.update(query=query, status=status, note=note, reviewed_by=reviewed_by)
        return {"ok": True, "query": query, "status": status, "ledger_path": "/x/miss_status.json", "durable": False}

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "set_miss_status", _fake_set_status)

    client = make_client(username="reviewer_wang")
    resp = client.post("/api/smartbi/flywheel/misses/status", json={
        "domain": "restaurant", "query": "外卖利润率咋算", "status": "planned", "note": "排入 Q3",
    })
    assert resp.status_code == 200
    assert captured == {"query": "外卖利润率咋算", "status": "planned", "note": "排入 Q3", "reviewed_by": "reviewer_wang"}
    assert resp.json()["data"]["durable"] is False


def test_set_miss_status_invalid_status_400(monkeypatch):
    def _fake_set_status(query, status, *, note=None, reviewed_by=None):
        return {"ok": False, "reason": f"invalid_status:{status!r} (允许值: [...])"}

    import smartbi.gold.restaurant.restaurant_intent_promotion as promo
    monkeypatch.setattr(promo, "set_miss_status", _fake_set_status)

    client = make_client()
    resp = client.post("/api/smartbi/flywheel/misses/status", json={
        "domain": "restaurant", "query": "x", "status": "not_a_real_status",
    })
    assert resp.status_code == 400


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
