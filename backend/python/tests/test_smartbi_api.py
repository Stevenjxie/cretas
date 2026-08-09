"""
SmartBI API E2E Tests

Group 0: Service Health Checks (3 tests)
Group 1: Python API Tests (8 tests)
Group 9: Edge Cases / Boundary Tests (5 tests)

Run:
  cd backend/python
  python -m pytest tests/test_smartbi_api.py -v --timeout=60
"""
import pytest
import httpx

# ⚠️ 2026-08-10: 补 `e2e` marker。本文件的 docstring 第一行就写着「E2E Tests」,
#    fixture 是指向**活服务**的 httpx.AsyncClient(Python :8083 + Java :10010),
#    没有服务时命中的是别的东西 → 404 / KeyError / 200≠400 这类**看起来像契约
#    缺陷**的读数, 而不是干净的连接失败。
#    python-gate.yml 用 `-k "not e2e and not integration"` 筛 E2E, 但 `-k` 匹配的是
#    名字与 marker —— 本文件的模块名/类名/用例名里一个 "e2e" 都没有, 所以从来没被
#    筛掉, 只能整文件挂进 ci-gate-excludes.txt。
#    判据: **「靠名字筛」的机制, 要么给被筛对象一个显式 marker, 要么它迟早漏掉。**
#    ⛔ 这不是把问题藏起来: 文件仍然可收集、可单独跑(`pytest -k e2e`), 只是不再在
#       没有活服务的门禁里制造假失败。
pytestmark = [pytest.mark.asyncio, pytest.mark.e2e]


# ═══════════════════════════════════════════════════════════════
# Group 0: Service Health Checks
# ═══════════════════════════════════════════════════════════════

class TestHealthChecks:
    """Group 0: Verify all services are reachable."""

    async def test_python_health(self, client: httpx.AsyncClient):
        """0.1 — Python service serves docs (FastAPI)."""
        r = await client.get("/docs")
        assert r.status_code == 200

    async def test_java_health(self, java_client: httpx.AsyncClient):
        """0.2 — Java backend /health returns 200."""
        try:
            r = await java_client.get("/api/mobile/health")
        except httpx.ConnectError:
            pytest.skip("Java backend not running on localhost:10010")
        # Accept 200 or 404 (endpoint may not exist in all versions)
        assert r.status_code in (200, 404)

    async def test_quick_summary_reachable(self, client: httpx.AsyncClient):
        """0.3 — quick-summary endpoint accepts minimal data."""
        r = await client.post("/api/insight/quick-summary", json=[{"a": 1}])
        assert r.status_code == 200
        body = r.json()
        assert body["success"] is True
        assert body["rowCount"] == 1


# ═══════════════════════════════════════════════════════════════
# Group 1: Python API Tests
# ═══════════════════════════════════════════════════════════════

class TestChartAPI:
    """Group 1.1–1.3: Chart build, batch, themes, recommend."""

    async def test_bar_chart_build_with_anomaly(
        self, client: httpx.AsyncClient, sample_bar_data
    ):
        """1.1 — Bar chart build succeeds; anomaly detection returns outlier data.
        Tests both the API response AND the service layer directly.
        """
        payload = {
            "chartType": "bar",
            "data": sample_bar_data,
            "xField": "category",
            "yFields": ["value"],
            "title": "Test Bar Chart",
        }
        r = await client.post("/api/chart/build", json=payload)
        assert r.status_code == 200
        body = r.json()
        assert body["success"] is True
        assert body["config"] is not None
        assert "series" in body["config"]

        # Anomaly detection at service layer (API may not expose field
        # if response model was cached without it)
        import sys
        import os
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "smartbi"))
        from services.chart_builder import ChartBuilder

        cb = ChartBuilder()
        result = cb.build("bar", sample_bar_data, x_field="category", y_fields=["value"])
        anomalies = result.get("anomalies", {})
        assert "value" in anomalies, "ChartBuilder._detect_chart_anomalies should find outlier 9999"
        assert len(anomalies["value"]["outliers"]) >= 1
        outlier_values = [o["value"] for o in anomalies["value"]["outliers"]]
        assert 9999.0 in outlier_values

    async def test_batch_chart_build(
        self, client: httpx.AsyncClient, sample_bar_data, sample_line_data
    ):
        """1.2 — Batch build returns 2 charts."""
        requests = [
            {
                "chartType": "bar",
                "data": sample_bar_data,
                "xField": "category",
                "yFields": ["value"],
            },
            {
                "chartType": "line",
                "data": sample_line_data,
                "xField": "month",
                "yFields": ["revenue"],
            },
        ]
        r = await client.post("/api/chart/batch", json=requests)
        assert r.status_code == 200
        body = r.json()
        assert body["success"] is True
        assert body["totalCharts"] == 2
        for chart in body["charts"]:
            assert chart.get("config") is not None

    async def test_chart_themes_available(self, client: httpx.AsyncClient):
        """1.3 — Themes endpoint returns multiple themes with color arrays."""
        r = await client.get("/api/chart/themes")
        assert r.status_code == 200
        body = r.json()
        themes = body["themes"]
        assert len(themes) >= 2, f"Expected >=2 themes, got {len(themes)}"
        for theme in themes:
            assert "id" in theme
            assert "colors" in theme
            assert len(theme["colors"]) >= 3, f"Theme '{theme['id']}' has <3 colors"

        # Verify business theme brand colors if present
        business = next((t for t in themes if t["id"] == "business"), None)
        if business:
            expected = ["#2563eb", "#059669", "#d97706"]
            for color in expected:
                assert color in business["colors"], f"{color} missing from business palette"

        # Verify ChartBuilder.THEME_PALETTES has business colors (service layer)
        import sys
        import os
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "smartbi"))
        from services.chart_builder import ChartBuilder
        palette = ChartBuilder.THEME_PALETTES["business"]["charts"]
        assert "#2563eb" in palette
        assert "#059669" in palette
        assert "#d97706" in palette

    async def test_chart_recommend(
        self, client: httpx.AsyncClient, sample_bar_data
    ):
        """1.8 — Chart recommend returns bar for categorical+numeric data.
        Note: FastAPI expects {data: [...]} when endpoint has multiple body params.
        """
        # The /recommend endpoint has params: data + fields — wrap in object
        r = await client.post(
            "/api/chart/recommend",
            json={"data": sample_bar_data, "fields": None},
        )
        assert r.status_code == 200
        body = r.json()
        assert body["success"] is True
        chart_types = [rec["chartType"] for rec in body["recommendations"]]
        assert "bar" in chart_types


class TestForecastAPI:
    """Group 1.4–1.5: Forecast with confidence intervals."""

    async def test_forecast_with_confidence(self, client: httpx.AsyncClient):
        """1.4 — Forecast 10 points → 3 predictions with confidence bounds."""
        payload = {
            "data": [100, 110, 105, 120, 130, 125, 140, 150, 145, 160],
            "periods": 3,
            "confidenceLevel": 0.95,
        }
        r = await client.post("/api/forecast/predict", json=payload)
        assert r.status_code == 200
        body = r.json()
        assert body["success"] is True
        assert len(body["predictions"]) == 3
        assert len(body["lowerBound"]) == 3
        assert len(body["upperBound"]) == 3
        # lowerBound[i] <= predictions[i] <= upperBound[i]
        for i in range(3):
            assert body["lowerBound"][i] <= body["predictions"][i], (
                f"lowerBound[{i}]={body['lowerBound'][i]} > predictions[{i}]={body['predictions'][i]}"
            )
            assert body["predictions"][i] <= body["upperBound"][i], (
                f"predictions[{i}]={body['predictions'][i]} > upperBound[{i}]={body['upperBound'][i]}"
            )

    async def test_forecast_insufficient_data(self, client: httpx.AsyncClient):
        """1.5 — 2 data points → HTTP 422 (pydantic min_items=3)."""
        payload = {"data": [100, 110], "periods": 3}
        r = await client.post("/api/forecast/predict", json=payload)
        assert r.status_code == 422


class TestInsightAPI:
    """Group 1.6–1.7: Insight generation and quick summary."""

    @pytest.mark.timeout(90)
    async def test_insight_structured_meta(
        self, client: httpx.AsyncClient, sample_profit_data
    ):
        """1.6 — Insight generate returns _meta with executive_summary, risk_alerts, opportunities.
        Note: depends on LLM API — may fall back to statistical method.
        """
        payload = {
            "data": sample_profit_data,
            "analysisContext": "产品利润分析报告",
            "maxInsights": 5,
        }
        r = await client.post(
            "/api/insight/generate", json=payload, timeout=90.0
        )
        assert r.status_code == 200
        body = r.json()
        assert body["success"] is True
        assert len(body["insights"]) >= 1
        # Check for _meta type if LLM method was used
        if body.get("method") == "llm":
            meta = next(
                (i for i in body["insights"] if i["type"] == "_meta"), None
            )
            if meta:
                assert meta.get("executive_summary") is not None
                assert isinstance(meta.get("risk_alerts"), list)
                assert isinstance(meta.get("opportunities"), list)

    async def test_quick_summary_statistics(
        self, client: httpx.AsyncClient, sample_quick_summary_data
    ):
        """1.7 — Quick summary returns correct row/col counts and sums.
        NOTE: Sends RAW array, NOT {data: [...]}.
        """
        r = await client.post(
            "/api/insight/quick-summary", json=sample_quick_summary_data
        )
        assert r.status_code == 200
        body = r.json()
        assert body["success"] is True
        assert body["rowCount"] == 3
        assert body["columnCount"] == 3
        # Find revenue column sum
        revenue_col = next(
            c for c in body["columns"] if c["name"] == "revenue"
        )
        assert revenue_col["sum"] == 4500.0
        # Find cost column sum
        cost_col = next(c for c in body["columns"] if c["name"] == "cost")
        assert cost_col["sum"] == 2100.0


# ═══════════════════════════════════════════════════════════════
# Group 9: Edge / Boundary Cases
# ═══════════════════════════════════════════════════════════════

class TestEdgeCases:
    """Group 9: Boundary conditions and error handling."""

    async def test_empty_data_quick_summary(self, client: httpx.AsyncClient):
        """9.1 — Empty array → 400."""
        r = await client.post("/api/insight/quick-summary", json=[])
        assert r.status_code == 400

    async def test_text_only_chart_build(self, client: httpx.AsyncClient):
        """9.2 — Pure text data chart build → graceful failure or empty series."""
        payload = {
            "chartType": "bar",
            "data": [
                {"label": "alpha", "note": "hello"},
                {"label": "beta", "note": "world"},
            ],
            "xField": "label",
            "yFields": ["note"],
        }
        r = await client.post("/api/chart/build", json=payload)
        assert r.status_code == 200
        body = r.json()
        # Should either succeed with empty-ish series or report failure
        if body["success"]:
            assert body["config"] is not None
        else:
            assert body.get("error") is not None

    async def test_constant_data_forecast(self, client: httpx.AsyncClient):
        """9.3 — All-constant values → predictions near that constant."""
        payload = {
            "data": [100.0] * 10,
            "periods": 3,
        }
        r = await client.post("/api/forecast/predict", json=payload)
        assert r.status_code == 200
        body = r.json()
        assert body["success"] is True
        for pred in body["predictions"]:
            assert abs(pred - 100.0) < 50, f"Prediction {pred} too far from constant 100"

    async def test_batch_mixed_valid_invalid(self, client: httpx.AsyncClient):
        """9.4 — Batch with valid + unusual request → both produce output."""
        requests = [
            {
                "chartType": "bar",
                "data": [{"x": "A", "y": 10}, {"x": "B", "y": 20}],
                "xField": "x",
                "yFields": ["y"],
            },
            {
                "chartType": "bar",
                "data": [{"text": "no numbers here"}],
                "xField": "text",
                "yFields": ["nonexistent"],
            },
        ]
        r = await client.post("/api/chart/batch", json=requests)
        assert r.status_code == 200
        body = r.json()
        assert body["totalCharts"] == 2

    async def test_large_forecast_periods(self, client: httpx.AsyncClient):
        """9.5 — Requesting max periods=24 with minimal data."""
        payload = {
            "data": [100, 200, 150, 300, 250],
            "periods": 24,
        }
        r = await client.post("/api/forecast/predict", json=payload)
        assert r.status_code == 200
        body = r.json()
        assert body["success"] is True
        assert len(body["predictions"]) == 24
