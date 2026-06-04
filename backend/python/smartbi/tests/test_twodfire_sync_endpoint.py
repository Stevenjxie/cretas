"""Tests for the 二维火 manual-trigger sync endpoint (SKELETON).

POST /api/smartbi/{factory_id}/ingest/2dfire/sync

When creds are NOT configured (the current reality — we have no 二维火 API
keys yet) the endpoint returns a fool-proof Rule 5 "dead-end with next
action" payload (HTTP 200, success:false, actionHint). NO network.

The configured path (future) would run pull → normalize → handoff and is
left stubbed — this test only asserts the not-configured response shape,
since that is the only behavior the skeleton actually implements.
"""
from __future__ import annotations

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from smartbi.api import twodfire_ingest


@pytest.fixture()
def client(monkeypatch):
    # Force not-configured: clear the env triplet so is_configured() is False.
    for k in ("TWODFIRE_APP_KEY", "TWODFIRE_APP_SECRET", "TWODFIRE_SHOP_ID"):
        monkeypatch.delenv(k, raising=False)

    app = FastAPI()

    # Bypass auth: stub the factory-match dependency so the test doesn't need a JWT.
    monkeypatch.setattr(
        twodfire_ingest, "_enforce_factory_match",
        lambda factory_id, request: factory_id,
    )
    app.include_router(twodfire_ingest.router)
    return TestClient(app)


def test_sync_not_configured_returns_dead_end_with_next_action(client):
    resp = client.post("/api/smartbi/F001/ingest/2dfire/sync", json={})
    assert resp.status_code == 200  # fool-proof: 200, success:false (not a 4xx/5xx)
    body = resp.json()
    assert body["success"] is False
    assert body["data"]["configured"] is False
    # Rule 5: message names the EXACT env vars + carries an actionHint.
    assert "TWODFIRE_APP_KEY" in body["data"]["message"]
    assert "TWODFIRE_APP_SECRET" in body["data"]["message"]
    assert "TWODFIRE_SHOP_ID" in body["data"]["message"]
    assert body["data"]["actionHint"]  # non-empty next-action hint


def test_sync_not_configured_does_not_touch_db_or_network(client, monkeypatch):
    """Not-configured short-circuits before any pool acquire / HTTP call.

    If the endpoint tried to get a DB pool we'd see it; assert it never does
    by making _get_pool blow up — the test passing proves it's never called."""
    def _boom():
        raise AssertionError("_get_pool must NOT be called on the not-configured path")

    monkeypatch.setattr(twodfire_ingest, "_get_pool", _boom)
    resp = client.post("/api/smartbi/F001/ingest/2dfire/sync", json={})
    assert resp.status_code == 200
    assert resp.json()["data"]["configured"] is False
