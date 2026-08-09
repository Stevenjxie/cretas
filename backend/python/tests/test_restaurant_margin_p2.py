"""#58 Phase 2 unit tests — margin (毛利) COGS / forecast / pace alert / RBAC.

Margin = 营收 − 食材成本 (COGS). COGS = Σ(POS qty × agg_restaurant_product_cost.food_cost).
Graceful degradation: when #61 name-resolution / recipe pricing is sparse, margin is
unreliable → null amounts + honest message, NEVER fake.

Mirrors the FakeConn/FakePool mocking style of test_restaurant_targets_p1.py (no real DB).
"""
from __future__ import annotations

import pathlib
from datetime import date, timedelta
from decimal import Decimal

import pytest

# ── Migration file assertions ────────────────────────────────────────────────
MIGRATION_PATH = (
    pathlib.Path(__file__).parent.parent
    / "smartbi/database/migrations"
    / "V20260925_01__restaurant_target_margin_config.sql"
)


def test_migration_file_exists():
    assert MIGRATION_PATH.exists()


def test_migration_grant_dml_rls_and_unique():
    sql = MIGRATION_PATH.read_text(encoding="utf-8")
    assert ("GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_target_margin_config "
            "TO smartbi_user") in sql
    assert ("GRANT USAGE, SELECT ON SEQUENCE restaurant_target_margin_config_id_seq "
            "TO smartbi_user") in sql
    assert "ENABLE ROW LEVEL SECURITY" in sql
    assert "FORCE  ROW LEVEL SECURITY" in sql
    assert "tenant_isolation" in sql
    assert "current_setting('app.factory_id', true)" in sql
    # store nullable → partial unique indexes (NOT COALESCE-in-constraint).
    assert "uq_margin_config_store" in sql and "uq_margin_config_nostore" in sql
    assert "WHERE store_id IS NOT NULL" in sql and "WHERE store_id IS NULL" in sql
    # range check + sensible default
    assert "target_margin_rate >= 0 AND target_margin_rate <= 1" in sql
    assert "DEFAULT 0.5500" in sql


# ── Fake DB harness (mirror P1) ───────────────────────────────────────────────
class _FakeConn:
    def __init__(self, *, fetch_map=None, fetchrow_map=None, fetchval_map=None):
        self._fetch_map = fetch_map or {}
        self._fetchrow_map = fetchrow_map or {}
        self._fetchval_map = fetchval_map or {}
        self.inserts = []

    async def execute(self, sql, *args):
        if "INSERT INTO" in sql or "UPDATE" in sql:
            self.inserts.append((sql, args))

    async def fetch(self, sql, *args):
        best, best_len = [], -1
        for key, rows in self._fetch_map.items():
            if key in sql and len(key) > best_len:
                best, best_len = rows, len(key)
        return best

    async def fetchrow(self, sql, *args):
        best, best_len = None, -1
        for key, row in self._fetchrow_map.items():
            if key in sql and len(key) > best_len:
                best, best_len = row, len(key)
        return best

    async def fetchval(self, sql, *args):
        for key, val in self._fetchval_map.items():
            if key in sql:
                return val
        return 0

    def transaction(self):
        return _FakeTx(self)


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


# ── Coverage assessment (pure) ────────────────────────────────────────────────
from smartbi.services.target_margin import (  # noqa: E402
    CogsCoverage,
    DEFAULT_TARGET_MARGIN_RATE,
    MIN_PRICED_DISH_COUNT,
    MIN_REVENUE_COVERAGE,
    assess_cost_coverage,
    compute_cogs_from_rows,
)


def test_assess_cost_coverage_sufficient():
    cov = CogsCoverage(
        total_dish_count=10, resolved_dish_count=8, priced_dish_count=6,
        period_revenue=Decimal("100000"), priced_revenue=Decimal("80000"),
    )
    assert assess_cost_coverage(cov) is True
    assert cov.revenue_coverage == pytest.approx(0.8, abs=1e-6)


def test_assess_cost_coverage_too_few_priced_dishes():
    cov = CogsCoverage(
        total_dish_count=10, resolved_dish_count=2, priced_dish_count=2,
        period_revenue=Decimal("100000"), priced_revenue=Decimal("90000"),
    )
    # priced_dish_count 2 < MIN_PRICED_DISH_COUNT → insufficient even though coverage high
    assert MIN_PRICED_DISH_COUNT >= 3
    assert assess_cost_coverage(cov) is False


def test_assess_cost_coverage_low_revenue_coverage():
    cov = CogsCoverage(
        total_dish_count=10, resolved_dish_count=8, priced_dish_count=5,
        period_revenue=Decimal("100000"), priced_revenue=Decimal("30000"),
    )
    # coverage 0.30 < MIN_REVENUE_COVERAGE (0.80) → insufficient
    assert cov.revenue_coverage == pytest.approx(0.3, abs=1e-6)
    assert MIN_REVENUE_COVERAGE == pytest.approx(0.8)
    assert assess_cost_coverage(cov) is False


def test_assess_cost_coverage_zero_revenue_insufficient():
    cov = CogsCoverage(
        total_dish_count=0, resolved_dish_count=0, priced_dish_count=0,
        period_revenue=Decimal("0"), priced_revenue=Decimal("0"),
    )
    assert cov.revenue_coverage == 0.0
    assert assess_cost_coverage(cov) is False


# ── COGS computation (pure) ───────────────────────────────────────────────────
def test_compute_cogs_only_priced_positive_food_cost():
    """COGS sums qty × food_cost ONLY for dishes with a priced cost row > 0."""
    # pos rows: (normalized_name, total_qty) per day already grouped by date elsewhere;
    # here we test the per-name rollup against a name→pk map + cost-by-pk map.
    pos_rows = [
        {"normalized_name": "宫保鸡丁", "total_qty": Decimal("10")},
        {"normalized_name": "麻婆豆腐", "total_qty": Decimal("5")},
        {"normalized_name": "无配方菜", "total_qty": Decimal("100")},  # unresolved
        {"normalized_name": "零成本菜", "total_qty": Decimal("3")},     # food_cost 0 → skip
    ]
    name_to_pk = {"宫保鸡丁": "pk1", "麻婆豆腐": "pk2", "零成本菜": "pk3"}
    cost_by_pk = {
        "pk1": Decimal("12.5000"),
        "pk2": Decimal("8.0000"),
        "pk3": Decimal("0"),  # zero food cost → does not contribute
    }
    cogs = compute_cogs_from_rows(pos_rows, name_to_pk, cost_by_pk)
    # 10*12.5 + 5*8.0 = 125 + 40 = 165 ; unresolved + zero-cost skipped
    assert cogs == Decimal("165.0000")


def test_compute_cogs_empty_rows_zero():
    assert compute_cogs_from_rows([], {}, {}) == Decimal("0")


def test_compute_cogs_decimal_precision_scale4():
    pos_rows = [{"normalized_name": "x", "total_qty": Decimal("3")}]
    name_to_pk = {"x": "pk"}
    cost_by_pk = {"pk": Decimal("9.9999")}
    cogs = compute_cogs_from_rows(pos_rows, name_to_pk, cost_by_pk)
    assert cogs == Decimal("29.9997")


# ── Margin forecast ───────────────────────────────────────────────────────────
from smartbi.services.target_margin import (  # noqa: E402
    compute_margin_forecast_core,
)


def _rev_series(values, start=date(2026, 1, 1)):
    return [(start + timedelta(days=i), float(v)) for i, v in enumerate(values)]


def test_margin_forecast_core_subtracts_cogs():
    """margin series = revenue − cogs per day; forecast over the margin series."""
    rev = _rev_series([2000] * 30)
    # cogs flat 800/day → margin flat 1200
    cogs = {d: Decimal("800") for d, _ in rev}
    res = compute_margin_forecast_core(rev, cogs, anchor=date(2026, 1, 30), horizon_days=10)
    # 毛利序列和营收序列共用 target_forecast.compute_rolling_forecast, 2026-08-10
    # 起带周内项。30 天连续 → 七个星期几各 4 次以上, 因子生效; 序列全平 → 因子
    # 都是 1.0, 预测值不变。
    assert res["model_type"] == "linear_trend_dow"
    assert all(abs(p["forecast_amount"] - 1200.0) < 0.01 for p in res["points"])


def test_margin_forecast_core_missing_cogs_day_treated_zero():
    """A day with revenue but no cogs entry contributes margin == revenue (cogs 0)."""
    rev = _rev_series([1000] * 20)
    cogs = {}  # no cogs at all → margin == revenue
    res = compute_margin_forecast_core(rev, cogs, anchor=date(2026, 1, 20), horizon_days=5)
    # 20 天连续 → 七个星期几各 ≥3 次(周内项生效); 全平 → 因子 1.0, 预测值不变。
    assert res["model_type"] == "linear_trend_dow"
    assert all(abs(p["forecast_amount"] - 1000.0) < 0.01 for p in res["points"])


def test_margin_forecast_core_empty_revenue_no_data():
    res = compute_margin_forecast_core([], {}, anchor=date(2026, 6, 1))
    assert res["model_type"] == "no_data"
    assert res["points"] == []


# ── Margin pace alert (target margin = revenue target × rate) ──────────────────
from smartbi.services.target_margin import (  # noqa: E402
    compute_margin_pace_core,
)


def test_margin_pace_core_ok_ahead():
    # revenue target 100000, rate 0.55 → margin target 55000.
    # margin actual 35000, elapsed 0.5 → completion 0.6364 >= 0.5 → OK
    res = compute_margin_pace_core(
        margin_actual=Decimal("35000"),
        revenue_target=Decimal("100000"),
        margin_rate=Decimal("0.55"),
        period_first=date(2026, 6, 1), period_last=date(2026, 6, 30),
        today=date(2026, 6, 15),
    )
    assert res["alert_level"] == "OK"
    assert res["target_margin"] == pytest.approx(55000.0)
    assert res["margin_amount"] == pytest.approx(35000.0)


def test_margin_pace_core_crit_behind():
    # margin target 55000; actual 15000; elapsed 0.5; completion 0.2727 < 0.5*0.7 → CRIT
    res = compute_margin_pace_core(
        margin_actual=Decimal("15000"),
        revenue_target=Decimal("100000"),
        margin_rate=Decimal("0.55"),
        period_first=date(2026, 6, 1), period_last=date(2026, 6, 30),
        today=date(2026, 6, 15),
    )
    assert res["alert_level"] == "CRIT"


def test_margin_pace_core_no_revenue_target():
    res = compute_margin_pace_core(
        margin_actual=Decimal("10000"),
        revenue_target=None,
        margin_rate=Decimal("0.55"),
        period_first=date(2026, 6, 1), period_last=date(2026, 6, 30),
        today=date(2026, 6, 15),
    )
    assert res["alert_level"] == "NO_TARGET"
    assert res["target_margin"] is None


def test_margin_rate_default_constant():
    assert DEFAULT_TARGET_MARGIN_RATE == Decimal("0.55")


# ── Margin rate config read (default when no row) ─────────────────────────────
from smartbi.services.target_margin import get_margin_rate  # noqa: E402


@pytest.mark.asyncio
async def test_get_margin_rate_default_when_no_row():
    conn = _FakeConn(fetchrow_map={"restaurant_target_margin_config": None})
    pool = _FakePool(conn)
    rate = await get_margin_rate(pool, "RES_T", store_id=None)
    assert rate == DEFAULT_TARGET_MARGIN_RATE


@pytest.mark.asyncio
async def test_get_margin_rate_reads_stored():
    conn = _FakeConn(
        fetchrow_map={
            "restaurant_target_margin_config": {"target_margin_rate": Decimal("0.6200")},
        }
    )
    pool = _FakePool(conn)
    rate = await get_margin_rate(pool, "RES_T", store_id=None)
    assert rate == Decimal("0.6200")


# ── RBAC strip: margin/cogs amounts nulled for non-price role ─────────────────
def test_rbac_strip_nulls_margin_money_for_non_price_role():
    from smartbi.api.restaurant_targets_p1 import (
        _strip_forecast_money, _FORECAST_MONEY_KEYS,
    )
    # the margin keys must be in the strip set
    assert {"margin_amount", "cogs_amount", "target_margin"} <= set(_FORECAST_MONEY_KEYS)

    payload = {
        "marginForecast": {
            "points": [{"date": "2026-06-02", "forecast_amount": 1200.0,
                        "lower_bound": 1000.0, "upper_bound": 1400.0}],
        },
        "marginPace": {
            "margin_amount": 35000.0, "target_margin": 55000.0,
            "cogs_amount": 20000.0, "completion_pct": 0.63,
            "priced_dish_count": 6, "revenue_coverage": 0.8,
        },
    }
    _strip_forecast_money(payload, "operator")
    mp = payload["marginPace"]
    assert mp["margin_amount"] is None
    assert mp["target_margin"] is None
    assert mp["cogs_amount"] is None
    # directional / diagnostic fields survive
    assert mp["completion_pct"] == 0.63
    assert mp["priced_dish_count"] == 6
    assert mp["revenue_coverage"] == 0.8
    # forecast bounds nulled too
    assert payload["marginForecast"]["points"][0]["forecast_amount"] is None
    assert payload["marginForecast"]["points"][0]["lower_bound"] is None


def test_rbac_strip_passthrough_margin_for_price_role():
    from smartbi.api.restaurant_targets_p1 import _strip_forecast_money
    payload = {"marginPace": {"margin_amount": 35000.0, "target_margin": 55000.0}}
    _strip_forecast_money(payload, "factory_super_admin")
    assert payload["marginPace"]["margin_amount"] == 35000.0
    assert payload["marginPace"]["target_margin"] == 55000.0


# ── set_margin_rate upsert + range guard ──────────────────────────────────────
from smartbi.services.target_margin import set_margin_rate  # noqa: E402


@pytest.mark.asyncio
async def test_set_margin_rate_upserts():
    conn = _FakeConn()
    pool = _FakePool(conn)
    stored = await set_margin_rate(pool, "RES_T", Decimal("0.60"), store_id=None,
                                   updated_by="admin")
    assert stored == Decimal("0.60")
    # an INSERT ... ON CONFLICT was issued
    assert any("restaurant_target_margin_config" in sql for sql, _ in conn.inserts)


@pytest.mark.asyncio
async def test_set_margin_rate_rejects_out_of_range():
    conn = _FakeConn()
    pool = _FakePool(conn)
    with pytest.raises(ValueError):
        await set_margin_rate(pool, "RES_T", Decimal("1.5"))


# ── compute_margin_forecast: cost-coverage gating (orchestration) ─────────────
from smartbi.services.target_margin import (  # noqa: E402
    compute_margin_forecast,
    compute_margin_pace_alert,
)


class _DualConn(_FakeConn):
    """FakeConn that also serves agg_daily MAX(date) + POS/cost rows by SQL key."""


@pytest.mark.asyncio
async def test_compute_margin_forecast_insufficient_cost_data(monkeypatch):
    """Sparse priced dishes → honest cost_data_insufficient (no fake margin)."""
    # smartbi conn: anchor date + revenue series + POS rows + cost rows
    smartbi_conn = _FakeConn(
        fetchrow_map={"MAX(date) AS mx": {"mx": date(2026, 6, 30)}},
        fetch_map={
            # daily revenue (P1 _fetch_daily_revenue) — give 30 days
            "SUM(net_amount)::numeric(18,2) AS rev": [
                {"date": date(2026, 6, d), "rev": Decimal("2000")}
                for d in range(1, 31)
            ],
            # POS dish lines — only 1 dish, low coverage
            "GROUP BY t.date, p.normalized_name": [
                {"record_date": date(2026, 6, 1), "normalized_name": "宫保鸡丁",
                 "total_qty": Decimal("5"), "total_amount": Decimal("100")},
            ],
            # food cost rows
            "FROM agg_restaurant_product_cost": [
                {"product_source_pk": "pk1", "food_cost": Decimal("12.0"),
                 "has_price_data": True},
            ],
        },
    )
    cretas_conn = _FakeConn(
        fetch_map={
            "FROM product_types": [{"id": "pk1", "name": "宫保鸡丁"}],
            "FROM dim_product_alias": [],
        },
    )
    smartbi_pool = _FakePool(smartbi_conn)
    cretas_pool = _FakePool(cretas_conn)

    res = await compute_margin_forecast(smartbi_pool, cretas_pool, "RES_T")
    # 1 priced dish < MIN 3 → insufficient
    assert res["model_type"] == "cost_data_insufficient"
    assert res["points"] == []
    assert res["cost_data_sufficient"] is False
    assert "成本数据不足" in res["message"]
    assert res["coverage"]["priced_dish_count"] == 1


@pytest.mark.asyncio
async def test_compute_margin_forecast_sufficient_produces_trend():
    """Enough priced dishes + coverage → real linear_trend margin forecast."""
    # revenue 2000/day for 30 days; COGS ~800/day from 4 priced dishes
    rev_rows = [
        {"date": date(2026, 6, d), "rev": Decimal("2000")} for d in range(1, 31)
    ]
    pos_rows = []
    for d in range(1, 31):
        for name, qty, amt in [
            ("宫保鸡丁", Decimal("10"), Decimal("580")),
            ("麻婆豆腐", Decimal("8"), Decimal("320")),
            ("鱼香肉丝", Decimal("6"), Decimal("300")),
            ("米饭", Decimal("20"), Decimal("60")),
        ]:
            pos_rows.append({
                "record_date": date(2026, 6, d), "normalized_name": name,
                "total_qty": qty, "total_amount": amt,
            })
    smartbi_conn = _FakeConn(
        fetchrow_map={"MAX(date) AS mx": {"mx": date(2026, 6, 30)}},
        fetch_map={
            "SUM(net_amount)::numeric(18,2) AS rev": rev_rows,
            "GROUP BY t.date, p.normalized_name": pos_rows,
            "FROM agg_restaurant_product_cost": [
                {"product_source_pk": "pk1", "food_cost": Decimal("18.0"), "has_price_data": True},
                {"product_source_pk": "pk2", "food_cost": Decimal("9.0"), "has_price_data": True},
                {"product_source_pk": "pk3", "food_cost": Decimal("12.0"), "has_price_data": True},
                {"product_source_pk": "pk4", "food_cost": Decimal("1.0"), "has_price_data": True},
            ],
        },
    )
    cretas_conn = _FakeConn(
        fetch_map={
            "FROM product_types": [
                {"id": "pk1", "name": "宫保鸡丁"}, {"id": "pk2", "name": "麻婆豆腐"},
                {"id": "pk3", "name": "鱼香肉丝"}, {"id": "pk4", "name": "米饭"},
            ],
            "FROM dim_product_alias": [],
        },
    )
    res = await compute_margin_forecast(
        _FakePool(smartbi_conn), _FakePool(cretas_conn), "RES_T", horizon_days=7
    )
    assert res["cost_data_sufficient"] is True
    assert res["model_type"] == "linear_trend"
    assert len(res["points"]) == 7
    # margin per day = 2000 - (10*18 + 8*9 + 6*12 + 20*1) = 2000 - 344 = 1656
    assert all(abs(p["forecast_amount"] - 1656.0) < 1.0 for p in res["points"])
    assert res["coverage"]["priced_dish_count"] == 4


@pytest.mark.asyncio
async def test_compute_margin_pace_alert_insufficient_cost():
    """Sparse cost → COST_DATA_INSUFFICIENT, amounts None (no fake margin)."""
    smartbi_conn = _FakeConn(
        fetchrow_map={
            "FROM restaurant_target_hierarchy": {"target_value": Decimal("300000")},
            "SUM(net_amount)::numeric(18,2) AS rev": {"rev": Decimal("150000")},
            "restaurant_target_margin_config": None,  # default rate
        },
        fetch_map={
            "GROUP BY t.date, p.normalized_name": [
                {"record_date": date(2026, 6, 1), "normalized_name": "x",
                 "total_qty": Decimal("1"), "total_amount": Decimal("50")},
            ],
            "FROM agg_restaurant_product_cost": [
                {"product_source_pk": "pkx", "food_cost": Decimal("5.0"), "has_price_data": True},
            ],
        },
    )
    cretas_conn = _FakeConn(
        fetch_map={
            "FROM product_types": [{"id": "pkx", "name": "x"}],
            "FROM dim_product_alias": [],
        },
    )
    res = await compute_margin_pace_alert(
        _FakePool(smartbi_conn), _FakePool(cretas_conn), "RES_T",
        period_type="month", today=date(2026, 6, 15),
    )
    assert res["alert_level"] == "COST_DATA_INSUFFICIENT"
    assert res["margin_amount"] is None
    assert res["cogs_amount"] is None
    assert res["target_margin"] is None
    assert res["cost_data_sufficient"] is False
    assert "成本数据不足" in res["message"]
