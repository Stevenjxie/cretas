"""G4 — Endpoint tests for GET /restaurant/{factory_id}/health-check-report.

Uses FastAPI TestClient with the builder + pools monkeypatched so no DB is
touched. Verifies tenant guard (403), pool-unavailable (503), empty-data
(200 + empty diagnoses, NOT error), cache-hit flag, and coverage note.
"""
from __future__ import annotations

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

import smartbi.api.restaurant_health_check as hc
from smartbi.services.restaurant.health_check_metrics import HealthCheckBundle


@pytest.fixture
def client(monkeypatch):
    """App with a fake auth middleware that sets request.state.factory_id
    from the X-Test-Factory header (simulating JWT tenant resolution)."""
    app = FastAPI()

    @app.middleware("http")
    async def _fake_auth(request: Request, call_next):
        request.state.factory_id = request.headers.get("x-test-factory")
        return await call_next(request)

    app.include_router(hc.router, prefix="/api/smartbi")
    # reset module cache between tests
    hc._REPORT_CACHE.clear()
    return TestClient(app)


def _bundle(metrics, coverage, period="2026-04", upload_id=99):
    return HealthCheckBundle(
        metrics=metrics, coverage=coverage, period=period, upload_id=upload_id,
    )


def test_get_report_wrong_factory_id_returns_403(client, monkeypatch):
    resp = client.get(
        "/api/smartbi/restaurant/RES_OTHER/health-check-report",
        headers={"x-test-factory": "RES_3101_009"},
    )
    assert resp.status_code == 403
    body = resp.json()
    assert body["success"] is False
    assert "无权" in body["message"]


def test_get_report_missing_tenant_returns_403(client):
    # No x-test-factory header → state.factory_id None → cannot prove access.
    resp = client.get("/api/smartbi/restaurant/RES_3101_009/health-check-report")
    assert resp.status_code in (401, 403)
    assert resp.json()["success"] is False


def test_get_report_pool_unavailable_returns_503(client, monkeypatch):
    async def _no_pool():
        return None

    monkeypatch.setattr(hc, "get_pg_pool", _no_pool)

    resp = client.get(
        "/api/smartbi/restaurant/RES_3101_009/health-check-report",
        headers={"x-test-factory": "RES_3101_009"},
    )
    assert resp.status_code == 503
    body = resp.json()
    assert body["success"] is False
    assert "数据库" in body["message"] or "连接" in body["message"]


def test_get_report_returns_200_with_diagnoses(client, monkeypatch):
    async def _ok_pool():
        return object()  # truthy fake pool

    async def _fake_build(self, **kwargs):
        return _bundle(
            metrics={"food_cost_ratio": 48.3, "delivery_dependency": 0.72},
            coverage={"food_cost_ratio": "ok", "delivery_dependency": "ok",
                      "cost_rigidity": "skipped:环比数据不足",
                      "channel_collection_rate": "ok"},
        )

    monkeypatch.setattr(hc, "get_pg_pool", _ok_pool)
    monkeypatch.setattr(hc.HealthCheckMetricsBuilder, "build", _fake_build)

    resp = client.get(
        "/api/smartbi/restaurant/RES_3101_009/health-check-report?month=2026-04&sub_sector=鱼类餐饮",
        headers={"x-test-factory": "RES_3101_009"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    data = body["data"]
    # diagnoses sorted critical→warning; food_cost_ratio (48.3 vs 鱼类餐饮) + delivery (0.72 critical)
    assert len(data["diagnoses"]) >= 1
    codes = {d["metricKey"] for d in data["diagnoses"]}
    assert "delivery_dependency" in codes  # 0.72 >= 0.70 → critical
    # summary counts present
    assert "criticalCount" in data["summary"]
    assert data["reportMeta"]["factoryId"] == "RES_3101_009"
    assert data["reportMeta"]["period"] == "2026-04"
    assert data["reportMeta"]["cacheHit"] is False
    # coverage note mentions a skipped reason
    assert "弹性" in data["summary"]["coverageNote"] or "环比" in data["summary"]["coverageNote"]


def test_get_report_no_data_returns_empty_diagnoses_not_error(client, monkeypatch):
    async def _ok_pool():
        return object()

    async def _empty_build(self, **kwargs):
        return _bundle(
            metrics={},
            coverage={"food_cost_ratio": "skipped:无财务数据",
                      "delivery_dependency": "skipped:无 POS 数据"},
        )

    monkeypatch.setattr(hc, "get_pg_pool", _ok_pool)
    monkeypatch.setattr(hc.HealthCheckMetricsBuilder, "build", _empty_build)

    resp = client.get(
        "/api/smartbi/restaurant/RES_3101_009/health-check-report",
        headers={"x-test-factory": "RES_3101_009"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    assert body["data"]["diagnoses"] == []
    assert body["data"]["summary"]["criticalCount"] == 0


def test_get_report_cached_returns_cache_hit_flag(client, monkeypatch):
    calls = {"n": 0}

    async def _ok_pool():
        return object()

    async def _counting_build(self, **kwargs):
        calls["n"] += 1
        return _bundle(
            metrics={"delivery_dependency": 0.75},
            coverage={"delivery_dependency": "ok"},
            upload_id=42,
        )

    monkeypatch.setattr(hc, "get_pg_pool", _ok_pool)
    monkeypatch.setattr(hc.HealthCheckMetricsBuilder, "build", _counting_build)

    url = "/api/smartbi/restaurant/RES_3101_009/health-check-report?month=2026-04"
    h = {"x-test-factory": "RES_3101_009"}

    r1 = client.get(url, headers=h)
    assert r1.json()["data"]["reportMeta"]["cacheHit"] is False
    r2 = client.get(url, headers=h)
    assert r2.json()["data"]["reportMeta"]["cacheHit"] is True
    assert calls["n"] == 1  # builder only ran once


def test_subsector_map_default_for_qhj(client, monkeypatch):
    """RES_3101_009 with no sub_sector query → resolved to 鱼类餐饮."""
    captured = {}

    async def _ok_pool():
        return object()

    async def _capture_build(self, **kwargs):
        captured["sub_sector"] = kwargs.get("sub_sector")
        return _bundle(metrics={}, coverage={})

    monkeypatch.setattr(hc, "get_pg_pool", _ok_pool)
    monkeypatch.setattr(hc.HealthCheckMetricsBuilder, "build", _capture_build)

    client.get(
        "/api/smartbi/restaurant/RES_3101_009/health-check-report",
        headers={"x-test-factory": "RES_3101_009"},
    )
    assert captured["sub_sector"] == "鱼类餐饮"


def test_subsector_query_overrides_map(client, monkeypatch):
    captured = {}

    async def _ok_pool():
        return object()

    async def _capture_build(self, **kwargs):
        captured["sub_sector"] = kwargs.get("sub_sector")
        return _bundle(metrics={}, coverage={})

    monkeypatch.setattr(hc, "get_pg_pool", _ok_pool)
    monkeypatch.setattr(hc.HealthCheckMetricsBuilder, "build", _capture_build)

    client.get(
        "/api/smartbi/restaurant/RES_3101_009/health-check-report?sub_sector=火锅",
        headers={"x-test-factory": "RES_3101_009"},
    )
    assert captured["sub_sector"] == "火锅"


# ── 🔒 F2: generic tenant (empty sub_sector) must still fire benchmark alerts ──


def test_generic_tenant_fires_benchmark_alerts_not_fake_healthy(client, monkeypatch):
    """A tenant NOT in FACTORY_SUBSECTOR_MAP resolves to sub_sector="". Before
    F2, DiagnosticsEngine never loaded _common.yaml benchmarks for empty
    sub_sector → food_cost_ratio=55 (way over [35,45]) fired NOTHING and the
    summary falsely reported '均无异常'. F2 forces the 通用 benchmark load."""
    async def _ok_pool():
        return object()

    async def _fake_build(self, **kwargs):
        return _bundle(
            metrics={"food_cost_ratio": 55.0},  # 0-100 scale, way over [35,45]
            coverage={"food_cost_ratio": "ok"},
        )

    monkeypatch.setattr(hc, "get_pg_pool", _ok_pool)
    monkeypatch.setattr(hc.HealthCheckMetricsBuilder, "build", _fake_build)

    # DEMO_REST_XXX not in FACTORY_SUBSECTOR_MAP, no sub_sector query → "".
    resp = client.get(
        "/api/smartbi/restaurant/DEMO_REST_X/health-check-report",
        headers={"x-test-factory": "DEMO_REST_X"},
    )
    assert resp.status_code == 200
    data = resp.json()["data"]
    codes = {d["metricKey"] for d in data["diagnoses"]}
    assert "food_cost_ratio" in codes, "generic tenant must get benchmark alerts (F2)"
    assert data["summary"]["criticalCount"] + data["summary"]["warningCount"] >= 1
    # generic (通用行业) sub_sector echoed
    assert data["reportMeta"]["subSector"] == "通用行业"


# ── 🔒 F3: benchmark-fallback avg_ticket target → severity cap + estimated ──


def test_avg_ticket_estimated_target_caps_severity_and_marks_estimated(client, monkeypatch):
    """When avg_ticket target came from the 通用 median (no tenant-set goal),
    a would-be CRITICAL '客单价严重偏低' must be capped to warning + flagged
    estimated (never a critical accusation against a target nobody set)."""
    async def _ok_pool():
        return object()

    async def _fake_build(self, **kwargs):
        b = _bundle(
            # -0.30 is well past critical inline threshold (< -0.15)
            metrics={"avg_ticket_vs_target": -0.30},
            coverage={"avg_ticket_vs_target": "ok"},
        )
        b.avg_ticket_target_estimated = True
        b.notes["avg_ticket_vs_target"] = "目标=行业通用人均中位数(未配置自定义客单价目标),仅供参考"
        return b

    monkeypatch.setattr(hc, "get_pg_pool", _ok_pool)
    monkeypatch.setattr(hc.HealthCheckMetricsBuilder, "build", _fake_build)

    resp = client.get(
        "/api/smartbi/restaurant/DEMO_REST_X/health-check-report",
        headers={"x-test-factory": "DEMO_REST_X"},
    )
    assert resp.status_code == 200
    data = resp.json()["data"]
    diag = next(d for d in data["diagnoses"] if d["metricKey"] == "avg_ticket_vs_target")
    assert diag["severity"] == "warning", "estimated target must not stay critical"
    assert diag["status"] != "严重偏低"
    assert diag.get("estimated") is True
    assert "未配置自定义客单价目标" in diag["descriptionZh"]
    # capped diagnosis must not count toward criticalCount
    assert data["summary"]["criticalCount"] == 0
    assert data["summary"]["warningCount"] >= 1
