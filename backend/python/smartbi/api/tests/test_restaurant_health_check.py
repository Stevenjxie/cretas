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


def test_supplier_price_anomaly_becomes_pushable_diagnosis(client, monkeypatch):
    """反回扣: UP 进价异常 → pushable warning/critical diagnosis; DOWN skipped."""
    async def _ok_pool():
        return object()

    async def _empty_build(self, **kwargs):
        return _bundle(metrics={}, coverage={})

    async def _fake_anomalies(pool, factory_id, **kwargs):
        return [
            {"ingredientName": "鲈鱼", "normalizedName": "鲈鱼", "supplierName": "四通",
             "supplierId": "S1", "newPrice": 43.2, "trailingAvg": 35.65, "deltaPct": 19.1,
             "direction": "UP", "riskLevel": "MEDIUM"},
            {"ingredientName": "牛肉", "normalizedName": "牛肉", "supplierName": "通际名联",
             "supplierId": "S2", "newPrice": 52.8, "trailingAvg": 65.18, "deltaPct": -19.6,
             "direction": "DOWN", "riskLevel": "HIGH"},  # 降价 → 非反回扣, 跳过
            {"ingredientName": "藤椒", "normalizedName": "藤椒", "supplierName": "网新恒天",
             "supplierId": "S3", "newPrice": 46.0, "trailingAvg": 40.0, "deltaPct": 15.0,
             "direction": "UP", "riskLevel": "HIGH"},  # 连续 → critical
            {"ingredientName": "青菜", "normalizedName": "青菜", "supplierName": "某供应商",
             "supplierId": None, "newPrice": 5.0, "trailingAvg": 4.0, "deltaPct": 25.0,
             "direction": "UP", "riskLevel": "MEDIUM"},  # F2: 无 supplier_id → 不可靠归因 → 跳过
        ]

    monkeypatch.setattr(hc, "get_pg_pool", _ok_pool)
    monkeypatch.setattr(hc.HealthCheckMetricsBuilder, "build", _empty_build)
    monkeypatch.setattr("smartbi.gold.price_anomaly.detect_price_anomalies", _fake_anomalies)

    resp = client.get(
        "/api/smartbi/restaurant/RES_3101_009/health-check-report",
        headers={"x-test-factory": "RES_3101_009"},
    )
    assert resp.status_code == 200
    diags = resp.json()["data"]["diagnoses"]
    supplier = [d for d in diags if d["metricKey"].startswith("supplier_price_anomaly:")]
    assert len(supplier) == 2  # 2 UP-with-id kept; DOWN dropped; NULL-id dropped
    keys = {d["metricKey"] for d in supplier}
    assert "supplier_price_anomaly:鲈鱼:S1" in keys      # unique per (食材,供应商)
    assert "supplier_price_anomaly:藤椒:S3" in keys
    assert not any("牛肉" in k for k in keys)            # DOWN excluded
    assert not any("青菜" in k for k in keys)            # F2: NULL supplier_id excluded
    # F1: detect succeeded → not flagged unavailable
    assert resp.json()["data"]["supplierAnomalyUnavailable"] is False
    by_key = {d["metricKey"]: d for d in supplier}
    assert by_key["supplier_price_anomaly:鲈鱼:S1"]["severity"] == "warning"   # MEDIUM
    assert by_key["supplier_price_anomaly:藤椒:S3"]["severity"] == "critical"  # HIGH → pushable SMS
    for d in supplier:
        assert d["metricNameZh"] == "供应商进价异常"
        assert "请核对" in d["descriptionZh"] and "回扣" in d["descriptionZh"]  # 威慑非指控
    # critical count reflects the HIGH-risk supplier anomaly
    assert resp.json()["data"]["summary"]["criticalCount"] >= 1


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


# ── spec §3.1 卡 C1: plan-alert append + unavailable 前缀泛化 ──────────────

def _ok_pool_and_build(monkeypatch):
    async def _ok_pool():
        return object()

    async def _fake_build(self, **kwargs):
        return _bundle(metrics={}, coverage={"food_cost_ratio": "ok"})

    monkeypatch.setattr(hc, "get_pg_pool", _ok_pool)
    monkeypatch.setattr(hc.HealthCheckMetricsBuilder, "build", _fake_build)


def _get(client):
    return client.get(
        "/api/smartbi/restaurant/RES_3101_009/health-check-report",
        headers={"x-test-factory": "RES_3101_009"},
    )


def test_report_appends_plan_alert_diagnoses(client, monkeypatch):
    """plan-alert 诊断进**同一个** diagnoses 列表 (不建第二套告警通道)."""
    _ok_pool_and_build(monkeypatch)

    async def _fake_run(pool, factory_id, **kwargs):
        return ([{
            "metricKey": "plan_alert:weekly_revenue_drop",
            "metricNameZh": "营收环比下滑预警",
            "severity": "warning",
            "status": "环比异常",
            "descriptionZh": "本月对比上月：营收环比变化率 -22.0%（非实时监控）",
            "estimated": False,
            "rxActions": [{"actionZh": "核对本月经营动作"}],
        }], [])

    monkeypatch.setattr(
        "smartbi.gold.restaurant.plan_alert.run_plan_alerts", _fake_run
    )

    data = _get(client).json()["data"]
    keys = {d["metricKey"] for d in data["diagnoses"]}
    assert "plan_alert:weekly_revenue_drop" in keys
    # 计入 summary 统计, 与 DiagnosticsEngine 诊断同权
    assert data["summary"]["warningCount"] >= 1
    # 规则都判定成功 -> 没有 plan_alert 前缀被豁免。
    # (supplier_price_anomaly: 会在, 因为假 pool 没有 .acquire — 那是 F1 的
    #  既有语义, 与本用例无关。)
    assert not [
        p for p in data["unavailableMetricPrefixes"] if p.startswith("plan_alert")
    ]


def test_report_propagates_per_rule_unavailable_prefix(client, monkeypatch):
    """单条规则不可判定 -> 只豁免它自己的 metricKey (auto-resolve flap 防护)."""
    _ok_pool_and_build(monkeypatch)

    async def _fake_run(pool, factory_id, **kwargs):
        return ([], ["plan_alert:broken_rule"])

    monkeypatch.setattr(
        "smartbi.gold.restaurant.plan_alert.run_plan_alerts", _fake_run
    )

    data = _get(client).json()["data"]
    prefixes = data["unavailableMetricPrefixes"]
    # 只有那一条规则被豁免 —— 不是整族 "plan_alert:"
    assert "plan_alert:broken_rule" in prefixes
    assert "plan_alert:" not in prefixes


def test_report_plan_alert_crash_exempts_whole_family(client, monkeypatch):
    """plan-alert 整体异常 -> 整族豁免, 且体检报告本身照常返回."""
    _ok_pool_and_build(monkeypatch)

    async def _boom(pool, factory_id, **kwargs):
        raise RuntimeError("unexpected")

    monkeypatch.setattr(
        "smartbi.gold.restaurant.plan_alert.run_plan_alerts", _boom
    )

    resp = _get(client)
    assert resp.status_code == 200, "预警失败不能拖垮体检报告"
    data = resp.json()["data"]
    assert "plan_alert:" in data["unavailableMetricPrefixes"]


def test_report_keeps_legacy_supplier_flag_and_mirrors_it_into_prefixes(
    client, monkeypatch
):
    """旧布尔字段保留 (向后兼容旧版 Java 桥接), 同时镜像进新前缀列表."""
    _ok_pool_and_build(monkeypatch)

    async def _boom_supplier(pool, factory_id):
        raise RuntimeError("price_anomaly_ack missing")

    async def _no_rules(pool, factory_id, **kwargs):
        return ([], [])

    monkeypatch.setattr(
        "smartbi.gold.price_anomaly.detect_price_anomalies", _boom_supplier
    )
    monkeypatch.setattr(
        "smartbi.gold.restaurant.plan_alert.run_plan_alerts", _no_rules
    )

    data = _get(client).json()["data"]
    assert data["supplierAnomalyUnavailable"] is True
    assert "supplier_price_anomaly:" in data["unavailableMetricPrefixes"]
