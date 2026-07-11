"""Regression: `_resolve_tenant` must re-point the RLS context to the RESOLVED
tenant, not just rewrite the query's WHERE clause.

Bug (2026-07-11): DEMO_REST is aliased to RES_3101_009 for the query, but the
RLS GUC (app.factory_id) is applied by the pool setup callback from the ContextVar
which still held the un-remapped JWT tenant DEMO_REST. RLS policy
`factory_id = current_setting('app.factory_id')` then filtered out every
RES_3101_009 row -> zero results on kpi-summary / finance-summary / trend-bundle /
daily-trend / data-range / review-*. store-kpi-dashboard escaped only because
compute_store_kpi_dashboard re-set the ContextVar itself. The fix re-points the
RLS context at the single chokepoint `_resolve_tenant`.
"""
from __future__ import annotations

import pytest
from fastapi import HTTPException

import smartbi.api.gold_reads as gr


def _patch_tenant_ctx(monkeypatch, jwt_tenant):
    """Fake tenant_ctx: get_factory_id returns the JWT tenant; set_factory_id
    records the value it was re-pointed to (or stays None if never called)."""
    calls = {"set_to": None}
    monkeypatch.setattr(gr, "get_factory_id", lambda: jwt_tenant)
    monkeypatch.setattr(gr, "set_factory_id", lambda fid: calls.__setitem__("set_to", fid))
    return calls


def test_demo_alias_repoints_rls_context(monkeypatch):
    calls = _patch_tenant_ctx(monkeypatch, "DEMO_REST")
    resolved = gr._resolve_tenant("DEMO_REST")
    assert resolved == "RES_3101_009"          # query WHERE uses the rich demo dataset
    assert calls["set_to"] == "RES_3101_009"    # RLS GUC re-pointed to match — the fix


def test_demo_alias_repoints_when_query_param_omitted(monkeypatch):
    # factory_id query param omitted -> defaults to JWT tenant -> still aliased.
    calls = _patch_tenant_ctx(monkeypatch, "DEMO_REST")
    resolved = gr._resolve_tenant(None)
    assert resolved == "RES_3101_009"
    assert calls["set_to"] == "RES_3101_009"


def test_non_demo_tenant_is_noop(monkeypatch):
    # Real tenant: resolved == JWT tenant, so we must NOT re-point (no-op) —
    # zero cross-tenant risk, RLS already matches the query.
    calls = _patch_tenant_ctx(monkeypatch, "F001")
    resolved = gr._resolve_tenant("F001")
    assert resolved == "F001"
    assert calls["set_to"] is None              # never re-set for a non-aliased tenant


def test_foreign_query_factory_id_still_403(monkeypatch):
    # The JWT-match guard must run BEFORE any re-point.
    calls = _patch_tenant_ctx(monkeypatch, "DEMO_REST")
    with pytest.raises(HTTPException) as ei:
        gr._resolve_tenant("F001")
    assert ei.value.status_code == 403
    assert calls["set_to"] is None              # no RLS re-point on a rejected request
