"""Unit tests for restaurant target cascade (G2 spec)."""
from __future__ import annotations
import pathlib
import pytest

MIGRATION_PATH = (
    pathlib.Path(__file__).parent.parent
    / "smartbi/database/migrations/V20260916_02__restaurant_target_tables.sql"
)


def test_migration_file_exists():
    assert MIGRATION_PATH.exists(), "Migration file must be created before other tests"


def test_migration_contains_grant_dml():
    sql = MIGRATION_PATH.read_text(encoding="utf-8")
    # review fix: 必须含 SELECT — 原迁移漏 SELECT 致所有 GET 读 permission denied (grant gap 第 3 次复发)
    assert "GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_target_hierarchy TO smartbi_user" in sql
    assert "GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_alert_config TO smartbi_user" in sql
    assert "GRANT USAGE, SELECT ON SEQUENCE restaurant_target_hierarchy_id_seq TO smartbi_user" in sql
    assert "GRANT USAGE, SELECT ON SEQUENCE restaurant_alert_config_id_seq TO smartbi_user" in sql


def test_migration_contains_rls():
    sql = MIGRATION_PATH.read_text(encoding="utf-8")
    assert "ENABLE ROW LEVEL SECURITY" in sql
    assert "tenant_isolation" in sql
    assert "current_setting('app.factory_id', true)" in sql


def test_migration_has_unique_constraint():
    sql = MIGRATION_PATH.read_text(encoding="utf-8")
    # review fix: 幂等唯一性改 partial unique index 分 store_id 有/无两路
    # (否则 PG NULLS DISTINCT 下 store_id IS NULL upsert 不幂等)
    assert "uq_target_grain_store" in sql
    assert "uq_target_grain_nostore" in sql
    assert "WHERE store_id IS NOT NULL" in sql
    assert "WHERE store_id IS NULL" in sql
    assert "(factory_id, kpi_kind, level, period_key, store_id)" in sql
    assert "uq_alert_config_store" in sql
    assert "uq_alert_config_nostore" in sql


# ─── Task 2: query function tests ──────────────────────────────────────────────
from datetime import date  # noqa: E402
from decimal import Decimal  # noqa: E402


class _FakeConn:
    def __init__(self, *, fetch_map=None, fetchrow_map=None, execute_calls=None):
        self._fetch_map = fetch_map or {}
        self._fetchrow_map = fetchrow_map or {}
        self.execute_calls = execute_calls if execute_calls is not None else []

    async def fetch(self, sql, *args):
        if isinstance(self._fetch_map, list):
            return self._fetch_map
        # Match the most specific key (longest substring) present in the SQL so
        # that e.g. "restaurant_target_hierarchy" wins over a generic key.
        best = None
        best_len = -1
        for key, rows in self._fetch_map.items():
            if key in sql and len(key) > best_len:
                best = rows
                best_len = len(key)
        return best if best is not None else []

    async def fetchrow(self, sql, *args):
        for key, row in self._fetchrow_map.items():
            if key in sql:
                return row
        return None

    async def execute(self, sql, *args):
        self.execute_calls.append(sql)


class _FakeTx:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *exc):
        return False


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()

    def transaction(self):
        return _FakeTx(self._conn)


# ── Import query functions (will fail until Task 2 implemented) ──────────────
from smartbi.gold.queries import (  # noqa: E402
    daily_achievement_summary,
    hierarchy_rollup,
    alert_preview,
)


@pytest.mark.asyncio
async def test_achievement_rate_normal():
    """Normal case: actual/target computed with Decimal ROUND_HALF_UP (Rule 10/12)."""
    conn = _FakeConn(
        fetch_map={
            "restaurant_target_hierarchy": [
                {"period_key": "2026-06-01", "target_value": Decimal("50000.00"), "store_id": None}
            ],
            "agg_daily": [
                {"date": date(2026, 6, 1), "actual": Decimal("48200.00")}
            ],
        }
    )
    pool = _FakePool(conn)
    result = await daily_achievement_summary(
        pool, "RES_TEST", (date(2026, 6, 1), date(2026, 6, 1)),
        kpi_kind="revenue", level="day",
    )
    assert result["kpi_kind"] == "revenue"
    assert result["level"] == "day"
    assert len(result["points"]) == 1
    pt = result["points"][0]
    assert pt["period_key"] == "2026-06-01"
    assert pt["target"] == 50000.00
    assert pt["actual"] == 48200.00
    assert pt["data_missing"] is False
    # achievement_rate = 48200 / 50000 = 0.964 (ROUND_HALF_UP 3 dp)
    assert abs(pt["achievement_rate"] - 0.964) < 0.001


@pytest.mark.asyncio
async def test_achievement_rate_zero_target():
    """target=0 must return achievement_rate=null, not raise ZeroDivisionError."""
    conn = _FakeConn(
        fetch_map={
            "restaurant_target_hierarchy": [
                {"period_key": "2026-06-01", "target_value": Decimal("0"), "store_id": None}
            ],
            "agg_daily": [
                {"date": date(2026, 6, 1), "actual": Decimal("10000")}
            ],
        }
    )
    pool = _FakePool(conn)
    result = await daily_achievement_summary(
        pool, "RES_TEST", (date(2026, 6, 1), date(2026, 6, 1)),
        kpi_kind="revenue", level="day",
    )
    pt = result["points"][0]
    assert pt["achievement_rate"] is None


@pytest.mark.asyncio
async def test_achievement_data_missing():
    """agg_daily has no row for a date → actual=null, data_missing=True."""
    conn = _FakeConn(
        fetch_map={
            "restaurant_target_hierarchy": [
                {"period_key": "2026-06-01", "target_value": Decimal("50000"), "store_id": None}
            ],
            # no agg_daily row for 2026-06-01
            "agg_daily": [],
        }
    )
    pool = _FakePool(conn)
    result = await daily_achievement_summary(
        pool, "RES_TEST", (date(2026, 6, 1), date(2026, 6, 1)),
        kpi_kind="revenue", level="day",
    )
    pt = result["points"][0]
    assert pt["actual"] is None
    assert pt["achievement_rate"] is None
    assert pt["data_missing"] is True


@pytest.mark.asyncio
async def test_alert_preview_no_config():
    """No alert_config row → config_exists=False, timeline empty, no error."""
    conn = _FakeConn(
        fetch_map={
            "restaurant_target_hierarchy": [],
            "agg_daily": [],
            "restaurant_alert_config": [],
        }
    )
    pool = _FakePool(conn)
    result = await alert_preview(pool, "RES_TEST", lookback_days=7, kpi_kind="revenue")
    assert result["config_exists"] is False
    assert result["timeline"] == []
    assert result["summary"] == {}


@pytest.mark.asyncio
async def test_alert_preview_critical():
    """actual < critical_threshold * target → status=CRITICAL."""
    conn = _FakeConn(
        fetch_map={
            "restaurant_alert_config": [
                {
                    "kpi_kind": "revenue", "level": "day",
                    "warn_threshold": Decimal("0.80"),
                    "critical_threshold": Decimal("0.60"),
                    "store_id": None,
                }
            ],
            "restaurant_target_hierarchy": [
                {"period_key": date.today().isoformat(), "target_value": Decimal("50000"), "store_id": None}
            ],
            "agg_daily": [
                {"date": date.today(), "actual": Decimal("25000")}
            ],
        }
    )
    pool = _FakePool(conn)
    result = await alert_preview(pool, "RES_TEST", lookback_days=1, kpi_kind="revenue")
    assert result["config_exists"] is True
    # achievement_rate = 0.5 < critical_threshold 0.60 → CRITICAL
    timeline = result["timeline"]
    assert len(timeline) == 1
    assert timeline[0]["status"] == "CRITICAL"


@pytest.mark.asyncio
async def test_hierarchy_rollup_empty():
    """No target rows → year_target=null, months=[]."""
    conn = _FakeConn(
        fetch_map={
            "restaurant_target_hierarchy": [],
            "agg_daily": [],
        }
    )
    pool = _FakePool(conn)
    result = await hierarchy_rollup(pool, "RES_TEST", 2026, kpi_kind="revenue")
    assert result["year_target"] is None
    assert result["months"] == []


def test_period_key_week_calendar_year_boundary():
    """Rule 2: week period_key uses d.year (calendar), not isocalendar()[0] (ISO)."""
    from smartbi.gold.queries import _period_key_for_target
    d = date(2024, 12, 30)  # Monday; ISO year=2025, week=1; calendar year=2024
    iso_year, iso_week, _ = d.isocalendar()
    assert iso_year == 2025  # confirm ISO differs
    key = _period_key_for_target(d, "week")
    assert key == "2024-W01"  # calendar year 2024, not 2025


def test_decimal_serialization_not_string():
    """Rule 4: _compute_achievement_rate returns float, not str/Decimal."""
    from smartbi.gold.queries import _compute_achievement_rate
    rate = _compute_achievement_rate(Decimal("48200"), Decimal("50000"))
    assert isinstance(rate, float)
    assert _compute_achievement_rate(Decimal("100"), None) is None
    assert _compute_achievement_rate(None, Decimal("100")) is None
    assert _compute_achievement_rate(Decimal("100"), Decimal("0")) is None


# ── Fix 3: in-progress period flag (under-counted partial period) ──


def test_period_bounds_month():
    """_period_bounds('2026-06', 'month') → (2026-06-01, 2026-06-30)."""
    from smartbi.gold.queries import _period_bounds
    first, last = _period_bounds("2026-06", "month")
    assert first == date(2026, 6, 1)
    assert last == date(2026, 6, 30)


def test_period_bounds_week_calendar_year():
    """_period_bounds for a week key returns Mon..Sun spanning 7 days."""
    from smartbi.gold.queries import _period_bounds, _period_key_for_target
    # Pick a real day, derive its key, ensure bounds round-trip to that key.
    d = date(2026, 6, 3)
    key = _period_key_for_target(d, "week")
    first, last = _period_bounds(key, "week")
    assert (last - first).days == 6
    assert first <= d <= last
    assert _period_key_for_target(first, "week") == key
    assert _period_key_for_target(last, "week") == key


def test_period_bounds_day_and_year():
    from smartbi.gold.queries import _period_bounds
    assert _period_bounds("2026-06-03", "day") == (date(2026, 6, 3), date(2026, 6, 3))
    assert _period_bounds("2026", "year") == (date(2026, 1, 1), date(2026, 12, 31))


@pytest.mark.asyncio
async def test_in_progress_month_period_flagged(monkeypatch):
    """A month period whose last day > today → in_progress=True with
    days_elapsed/days_total, so a partial-month low rate is not a false alarm."""
    import smartbi.gold.queries as q

    # Freeze "today" to mid-month so the month period is incomplete.
    frozen_today = date(2026, 6, 3)

    class _FrozenDate(date):
        @classmethod
        def today(cls):
            return frozen_today

    monkeypatch.setattr(q, "date", _FrozenDate)

    conn = _FakeConn(
        fetch_map={
            "restaurant_target_hierarchy": [
                {"period_key": "2026-06", "target_value": Decimal("1000000"), "store_id": None}
            ],
            # only 3 days of actuals exist (mid-month query)
            "agg_daily": [
                {"date": date(2026, 6, 1), "actual": Decimal("33000")},
                {"date": date(2026, 6, 2), "actual": Decimal("34000")},
                {"date": date(2026, 6, 3), "actual": Decimal("33000")},
            ],
        }
    )
    pool = _FakePool(conn)
    result = await q.daily_achievement_summary(
        pool, "RES_TEST", (date(2026, 6, 1), date(2026, 6, 3)),
        kpi_kind="revenue", level="month",
    )
    pt = result["points"][0]
    assert pt["period_key"] == "2026-06"
    assert pt["in_progress"] is True, "incomplete month must be flagged in_progress"
    assert pt["period_complete"] is False
    assert pt["days_total"] == 30
    assert pt["days_elapsed"] == 3
    # achievement_rate still computed (not nulled), just annotated
    assert pt["achievement_rate"] is not None


@pytest.mark.asyncio
async def test_complete_past_period_not_in_progress(monkeypatch):
    """A fully-elapsed past month → in_progress=False, period_complete=True."""
    import smartbi.gold.queries as q

    frozen_today = date(2026, 6, 3)

    class _FrozenDate(date):
        @classmethod
        def today(cls):
            return frozen_today

    monkeypatch.setattr(q, "date", _FrozenDate)

    conn = _FakeConn(
        fetch_map={
            "restaurant_target_hierarchy": [
                {"period_key": "2026-05", "target_value": Decimal("1000000"), "store_id": None}
            ],
            "agg_daily": [
                {"date": date(2026, 5, 15), "actual": Decimal("980000")},
            ],
        }
    )
    pool = _FakePool(conn)
    result = await q.daily_achievement_summary(
        pool, "RES_TEST", (date(2026, 5, 1), date(2026, 5, 31)),
        kpi_kind="revenue", level="month",
    )
    pt = result["points"][0]
    assert pt["period_key"] == "2026-05"
    assert pt["in_progress"] is False
    assert pt["period_complete"] is True


# ─── Task 3: REST endpoint contract tests ──────────────────────────────────────
import datetime as _dtmod  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from fastapi import FastAPI  # noqa: E402
from unittest.mock import AsyncMock, patch  # noqa: E402


def _make_test_app(role: str = "factory_super_admin"):
    """Minimal FastAPI app with restaurant_targets router wired + fake auth."""
    from smartbi.api.restaurant_targets import router
    app = FastAPI()
    app.include_router(router, prefix="/api/smartbi")

    @app.middleware("http")
    async def fake_auth(request, call_next):
        request.state.factory_id = "RES_TEST"
        request.state.username = "test_admin"
        request.state.role = role
        return await call_next(request)

    return app


def _fake_pool_with_conn(fetchrow_return=None, fetch_return=None):
    """Build a fake asyncpg pool whose acquire() yields a fake conn."""
    conn = AsyncMock()
    conn.execute = AsyncMock()
    conn.fetchrow = AsyncMock(return_value=fetchrow_return)
    conn.fetch = AsyncMock(return_value=fetch_return or [])

    class _AcquireCtx:
        async def __aenter__(self):
            return conn

        async def __aexit__(self, *exc):
            return False

    class _Tx:
        async def __aenter__(self):
            return conn

        async def __aexit__(self, *exc):
            return False

    pool = AsyncMock()
    pool.acquire = lambda: _AcquireCtx()
    conn.transaction = lambda: _Tx()
    return pool, conn


def test_upsert_target_returns_success():
    """POST /restaurant-targets with valid body → success:true + id in data."""
    app = _make_test_app()
    client = TestClient(app)

    pool, _conn = _fake_pool_with_conn(fetchrow_return={
        "id": 1, "period_key": "2026-06", "target_value": 500000.00,
        "updated_at": _dtmod.datetime(2026, 6, 3, 10, 0, 0),
    })

    with patch("smartbi.api.restaurant_targets._get_pool", new=AsyncMock(return_value=pool)):
        resp = client.post("/api/smartbi/restaurant-targets", json={
            "kpiKind": "revenue",
            "level": "month",
            "periodKey": "2026-06",
            "targetValue": 500000.00,
        })
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["success"] is True
    assert body["data"]["id"] == 1
    # POST upsert response uses explicit camelCase keys (plan §Task3)
    assert body["data"]["periodKey"] == "2026-06"
    assert body["data"]["targetValue"] == 500000.0


def test_get_alerts_no_config_returns_empty():
    """GET /restaurant-targets/alerts with no alert_config → configExists:false."""
    app = _make_test_app()
    client = TestClient(app)

    pool, _conn = _fake_pool_with_conn()

    fake_alert = AsyncMock(return_value={
        "factory_id": "RES_TEST", "kpi_kind": "revenue",
        "lookback_days": 7, "config_exists": False,
        "timeline": [], "summary": {},
    })

    with patch("smartbi.api.restaurant_targets._get_pool", new=AsyncMock(return_value=pool)), \
         patch("smartbi.gold.queries.alert_preview", new=fake_alert):
        resp = client.get("/api/smartbi/restaurant-targets/alerts?kpi_kind=revenue&lookback_days=7")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["success"] is True
    # GET endpoints return the raw query dict (snake_case); the FE pythonFetch
    # transformKeys() converts snake→camel client-side.
    assert body["data"]["config_exists"] is False


def test_upsert_target_rejects_zero_value():
    """POST with targetValue=0 → 422 Unprocessable Entity."""
    app = _make_test_app()
    client = TestClient(app)
    resp = client.post("/api/smartbi/restaurant-targets", json={
        "kpiKind": "revenue",
        "level": "month",
        "periodKey": "2026-06",
        "targetValue": 0,
    })
    assert resp.status_code == 422


# ─── Task 5: AI Gold Tool tests ────────────────────────────────────────────────
def test_restaurant_target_tool_has_correct_name():
    """Tool name must match the DB intent binding: restaurant_target_achievement."""
    from smartbi.gold.restaurant_target_tool import RestaurantTargetAchievementTool
    tool = RestaurantTargetAchievementTool()
    assert tool.tool_name == "restaurant_target_achievement"


def test_restaurant_target_tool_description_contains_context():
    """Description must mention 达成率 to satisfy Rule 2 context requirement."""
    from smartbi.gold.restaurant_target_tool import RestaurantTargetAchievementTool
    tool = RestaurantTargetAchievementTool()
    assert "达成率" in tool.description
