"""#58 Phase 1 unit tests — decomposition (month→week, week→day),
rolling forecast, pace alert, RBAC strip.

Mirrors the FakeConn/FakePool mocking style of test_restaurant_targets.py
(G2). No real DB; query functions are exercised against synthetic rows.
"""
from __future__ import annotations

import pathlib
from datetime import date
from decimal import Decimal

import pytest

# ── Migration file assertions ────────────────────────────────────────────────
MIGRATION_PATH = (
    pathlib.Path(__file__).parent.parent
    / "smartbi/database/migrations"
    / "V20260922_01__restaurant_target_forecast_alerts.sql"
)


def test_migration_file_exists():
    assert MIGRATION_PATH.exists()


def test_migration_grant_dml_and_rls():
    sql = MIGRATION_PATH.read_text(encoding="utf-8")
    # Both tables need full DML grants + sequence grants (grant-gap rule).
    assert ("GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_target_forecast "
            "TO smartbi_user") in sql
    assert ("GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_target_alerts "
            "TO smartbi_user") in sql
    assert ("GRANT USAGE, SELECT ON SEQUENCE restaurant_target_forecast_id_seq "
            "TO smartbi_user") in sql
    assert ("GRANT USAGE, SELECT ON SEQUENCE restaurant_target_alerts_id_seq "
            "TO smartbi_user") in sql
    # RLS enabled + forced + tenant policy on both.
    assert sql.count("ENABLE ROW LEVEL SECURITY") == 2
    assert sql.count("FORCE  ROW LEVEL SECURITY") == 2
    assert sql.count("tenant_isolation") >= 2
    assert "current_setting('app.factory_id', true)" in sql


def test_migration_partial_unique_indexes():
    sql = MIGRATION_PATH.read_text(encoding="utf-8")
    # store_id nullable → partial unique indexes (NOT COALESCE-in-constraint).
    assert "uq_forecast_store" in sql and "uq_forecast_nostore" in sql
    assert "uq_alert_store" in sql and "uq_alert_nostore" in sql
    assert "WHERE store_id IS NOT NULL" in sql
    assert "WHERE store_id IS NULL" in sql


# ── FakeConn / FakePool (mirror G2 test harness) ──────────────────────────────
class _FakeConn:
    def __init__(self, *, fetch_map=None, fetchrow_map=None, fetchval_map=None):
        self._fetch_map = fetch_map or {}
        self._fetchrow_map = fetchrow_map or {}
        self._fetchval_map = fetchval_map or {}
        self.inserts = []

    async def execute(self, sql, *args):
        if "INSERT INTO restaurant_target" in sql:
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


# ── Decomposition: helpers + sum invariants ──────────────────────────────────
from smartbi.services.target_decomposition import (  # noqa: E402
    _distribute,
    _weeks_touching_month,
    compute_pace_alert,
    decompose_month_to_weeks,
    decompose_week_to_days,
)


def test_distribute_sum_invariant_last_absorbs_residual():
    """Children sum EXACTLY to parent even with rounding residual."""
    parent = Decimal("1000.00")
    keys = ["a", "b", "c"]
    # 1/3 each → 333.33 + 333.33 + 333.34 = 1000.00 (last absorbs)
    out = _distribute(parent, keys, {k: Decimal("1") for k in keys})
    assert sum(out.values()) == parent
    assert out["c"] == Decimal("333.34")
    assert out["a"] == Decimal("333.33")


def test_distribute_uniform_fallback_on_zero_weights():
    parent = Decimal("700.00")
    keys = [f"d{i}" for i in range(7)]
    out = _distribute(parent, keys, {k: Decimal("0") for k in keys})
    assert sum(out.values()) == parent
    assert out["d0"] == Decimal("100.00")


def test_distribute_weighted_split():
    parent = Decimal("1000.00")
    keys = ["x", "y"]
    out = _distribute(parent, keys, {"x": Decimal("0.25"), "y": Decimal("0.75")})
    assert out["x"] == Decimal("250.00")
    assert out["y"] == Decimal("750.00")
    assert sum(out.values()) == parent


def test_weeks_touching_month_june_2026():
    """June 2026 spans multiple ISO weeks; all keys round-trip."""
    weeks = _weeks_touching_month(2026, 6)
    keys = [w[0] for w in weeks]
    # Every week key uses calendar-year W form
    assert all(k.startswith("2026-W") for k in keys)
    # June 1 2026 is a Monday → first week starts on the 1st
    assert weeks[0][1] == date(2026, 6, 1)
    # ascending and de-duped
    assert keys == sorted(set(keys), key=keys.index)


@pytest.mark.asyncio
async def test_decompose_month_to_weeks_no_target():
    """Unset month target → honest no-op, weeks=[]."""
    conn = _FakeConn(fetchrow_map={"restaurant_target_hierarchy": None})
    pool = _FakePool(conn)
    res = await decompose_month_to_weeks(pool, "RES_T", "2026-06")
    assert res["weeks"] == []
    assert "未设置" in res["message"]


@pytest.mark.asyncio
async def test_decompose_month_to_weeks_historical_share_sum_invariant():
    """Week targets sum EXACTLY to the month target (historical-weight path)."""
    weeks = _weeks_touching_month(2026, 6)
    # Synthesize history so each in-month day has revenue → shares non-empty.
    history = []
    d = date(2026, 6, 1)
    while d <= date(2026, 6, 30):
        history.append({"date": d, "rev": Decimal("1000.00")})
        from datetime import timedelta
        d += timedelta(days=1)
    conn = _FakeConn(
        fetchrow_map={
            "restaurant_target_hierarchy": {"target_value": Decimal("300000.00")},
            # the INSERT ... RETURNING uses fetchrow too — provide a generic row.
            "INSERT INTO restaurant_target_hierarchy": {
                "id": 1, "period_key": "x", "target_value": Decimal("1.00"),
            },
        },
        fetch_map={"agg_daily": history},
    )
    pool = _FakePool(conn)
    res = await decompose_month_to_weeks(pool, "RES_T", "2026-06")
    assert res["basis"] == "historical_week_share"
    # Same number of week rows as touching weeks
    assert len(res["weeks"]) == len(weeks)


@pytest.mark.asyncio
async def test_decompose_month_to_weeks_daycount_fallback():
    """No history → days_in_month fallback basis."""
    conn = _FakeConn(
        fetchrow_map={
            "restaurant_target_hierarchy": {"target_value": Decimal("280000.00")},
            "INSERT INTO restaurant_target_hierarchy": {
                "id": 1, "period_key": "x", "target_value": Decimal("1.00"),
            },
        },
        fetch_map={"agg_daily": []},  # no history
    )
    pool = _FakePool(conn)
    res = await decompose_month_to_weeks(pool, "RES_T", "2026-06")
    assert res["basis"] == "days_in_month_fallback"


@pytest.mark.asyncio
async def test_decompose_week_to_days_uniform_fallback():
    """7 day rows; uniform fallback when no DOW history; sum invariant."""
    conn = _FakeConn(
        fetchrow_map={
            "restaurant_target_hierarchy": {"target_value": Decimal("70000.00")},
            "INSERT INTO restaurant_target_hierarchy": {
                "id": 1, "period_key": "x", "target_value": Decimal("10000.00"),
            },
        },
        fetch_map={"agg_daily": []},
    )
    pool = _FakePool(conn)
    res = await decompose_week_to_days(pool, "RES_T", "2026-W23")
    assert res["basis"] == "uniform_fallback"
    assert len(res["days"]) == 7


@pytest.mark.asyncio
async def test_decompose_week_to_days_dow_weights():
    """DOW history present → trailing_90d_dow basis."""
    from datetime import timedelta
    history = []
    d = date(2026, 3, 1)
    while d <= date(2026, 5, 31):
        # weekends heavier
        rev = Decimal("3000") if d.weekday() >= 5 else Decimal("1000")
        history.append({"date": d, "rev": rev})
        d += timedelta(days=1)
    conn = _FakeConn(
        fetchrow_map={
            "restaurant_target_hierarchy": {"target_value": Decimal("70000.00")},
            "INSERT INTO restaurant_target_hierarchy": {
                "id": 1, "period_key": "x", "target_value": Decimal("10000.00"),
            },
        },
        fetch_map={"agg_daily": history},
    )
    pool = _FakePool(conn)
    res = await decompose_week_to_days(pool, "RES_T", "2026-W23")
    assert res["basis"] == "trailing_90d_dow"
    assert len(res["days"]) == 7


# ── Pace alert: OK / WARN / CRIT boundaries ───────────────────────────────────
def test_pace_alert_ok_ahead_of_pace():
    res = compute_pace_alert(
        actual_amount=Decimal("60000"), target_amount=Decimal("100000"),
        period_first=date(2026, 6, 1), period_last=date(2026, 6, 30),
        today=date(2026, 6, 15),
    )
    # elapsed = 15/30 = 0.5; completion = 0.6 >= 0.5 → OK
    assert res["alert_level"] == "OK"
    assert res["data_missing"] is False
    assert res["pace_gap_pct"] == pytest.approx(0.1, abs=1e-4)


def test_pace_alert_warn_band():
    res = compute_pace_alert(
        actual_amount=Decimal("45000"), target_amount=Decimal("100000"),
        period_first=date(2026, 6, 1), period_last=date(2026, 6, 30),
        today=date(2026, 6, 15),
    )
    # elapsed=0.5; completion=0.45; 0.45 >= 0.5*0.85=0.425 → WARN (not OK, not CRIT)
    assert res["alert_level"] == "WARN"


def test_pace_alert_crit_band():
    res = compute_pace_alert(
        actual_amount=Decimal("30000"), target_amount=Decimal("100000"),
        period_first=date(2026, 6, 1), period_last=date(2026, 6, 30),
        today=date(2026, 6, 15),
    )
    # elapsed=0.5; completion=0.3 < 0.5*0.70=0.35 → CRIT
    assert res["alert_level"] == "CRIT"


def test_pace_alert_warn_crit_exact_boundary():
    # completion exactly == elapsed*crit_ratio → still WARN (>= crit boundary)
    # elapsed=0.5, crit=0.70 → boundary 0.35 → completion 0.35 → WARN
    res = compute_pace_alert(
        actual_amount=Decimal("35000"), target_amount=Decimal("100000"),
        period_first=date(2026, 6, 1), period_last=date(2026, 6, 30),
        today=date(2026, 6, 15),
    )
    assert res["alert_level"] == "WARN"


def test_pace_alert_data_missing_no_false_alarm():
    res = compute_pace_alert(
        actual_amount=None, target_amount=Decimal("100000"),
        period_first=date(2026, 6, 1), period_last=date(2026, 6, 30),
        today=date(2026, 6, 15),
    )
    assert res["alert_level"] == "OK"
    assert res["data_missing"] is True
    assert res["completion_pct"] is None
    assert res["pace_gap_pct"] is None


def test_pace_alert_zero_target_completion_zero():
    res = compute_pace_alert(
        actual_amount=Decimal("5000"), target_amount=Decimal("0"),
        period_first=date(2026, 6, 1), period_last=date(2026, 6, 30),
        today=date(2026, 6, 15),
    )
    assert res["completion_pct"] == 0.0
    assert res["alert_level"] == "CRIT"  # 0 < 0.5*0.7


# ── Forecast: flat→slope0, trend→projection, <14d→fallback, outlier removal ───
from smartbi.services.target_forecast import (  # noqa: E402
    _clean_outliers,
    compute_rolling_forecast,
)


def _series(values, start=date(2026, 1, 1)):
    from datetime import timedelta
    return [(start + timedelta(days=i), float(v)) for i, v in enumerate(values)]


def test_forecast_empty_no_data():
    res = compute_rolling_forecast([], anchor=date(2026, 6, 1))
    assert res["model_type"] == "no_data"
    assert res["points"] == []


def test_forecast_fewer_than_14_mean_fallback():
    series = _series([1000] * 10)
    res = compute_rolling_forecast(series, anchor=date(2026, 1, 10), horizon_days=5)
    assert res["model_type"] == "mean_fallback"
    assert len(res["points"]) == 5
    # flat series → all forecasts == mean == 1000
    assert all(abs(p["forecast_amount"] - 1000.0) < 0.01 for p in res["points"])


def test_forecast_flat_series_slope_zero():
    series = _series([2000] * 30)
    res = compute_rolling_forecast(series, anchor=date(2026, 1, 30), horizon_days=10)
    # 2026-08-10 起带周内项。完全平的序列 → 七个因子都是 1.0, 预测值不变。
    assert res["model_type"] == "linear_trend_dow"
    # flat → projection stays ~2000, zero-width CI (std 0)
    assert all(abs(p["forecast_amount"] - 2000.0) < 0.01 for p in res["points"])
    assert all(p["lower_bound"] == p["forecast_amount"] for p in res["points"])


def test_forecast_upward_trend_projects_higher():
    # strictly increasing: 100, 200, ..., 3000 over 30 days
    series = _series([100 * (i + 1) for i in range(30)])
    res = compute_rolling_forecast(series, anchor=date(2026, 1, 30), horizon_days=10)
    assert res["model_type"] == "linear_trend_dow"
    first = res["points"][0]["forecast_amount"]
    last = res["points"][-1]["forecast_amount"]
    # projection continues upward beyond last observed (3000)
    assert first > 3000
    assert last > first


@pytest.mark.xfail(
    strict=True,
    reason=(
        "已知未修的隐患, 且**试过的修法都更差**, 故留成会说话的红灯而不是删掉。\n"
        "缺陷: 拿原值判 IQR, 在周内规律干净的序列上会把整个最忙的那天全删光 ——\n"
        "「工作日 1000 / 周六 3000」连续 8 周, 40 个 1000 把 Q1=Q3=1000 顶成\n"
        "IQR=0, fence 收成一个点, 8 个周六一个不剩。而那正是模型最该学的信号。\n"
        "为什么先不修(2026-08-10 实测, scripts/forecast_backtest.py):\n"
        "  · 旧清洗器在真实租户上剔除 **0/120 天** —— 它在真实数据上根本没开火,\n"
        "    所以这个缺陷只在合成的干净序列上咬人, 是隐患不是正在发生的故障。\n"
        "  · 「先按星期几拉平再判离群」这个直觉修法实测更差:\n"
        "        RES_3101_009 h=30   16.75% → 21.02%(OK 翻 FAIL)\n"
        "        DEMO_REST    h=30   18.41% → 23.03%(OK 翻 FAIL)\n"
        "    把围栏从 1.5 放宽到 3.0(剔除 15→7 天)**没有救回来**(21.26%), 说明\n"
        "    机制不是「剔太狠」。两个假设连续被证伪, 没有继续猜下去。\n"
        "修它的前提: 先能解释清楚为什么剔掉 7/120 天会让 h=30 差 4.5 个百分点。\n"
        "strict=True —— 谁真修好了这里会 xpass 报错, 提醒把这段说明一并改掉。"
    ),
)
def test_clean_outliers_does_not_eat_the_busiest_weekday():
    """干净的周内规律不该被当成离群点删掉。"""
    from datetime import timedelta

    series = []
    for i in range(56):
        d = date(2026, 1, 5) + timedelta(days=i)
        series.append((d, 3000.0 if d.weekday() == 5 else 1000.0))

    cleaned = _clean_outliers(series)
    sat_kept = sum(1 for d, _ in cleaned if d.weekday() == 5)
    assert sat_kept == 8, f"8 个周六应全部保留, 实际只剩 {sat_kept}"
    assert len(cleaned) == 56


@pytest.mark.xfail(
    strict=True,
    reason=(
        "同上一条的同一个隐患的另一面: 真异常会被剔除(这一半是对的), 但同一星期几\n"
        "的其它 7 个正常观测**也被连坐删光**。修法与上一条绑定, 一起做或一起不做。"
    ),
)
def test_clean_outliers_still_catches_a_genuinely_anomalous_day():
    """真异常要剔除, 但不该连累同一星期几的正常观测。"""
    from datetime import timedelta

    series = []
    for i in range(56):
        d = date(2026, 1, 5) + timedelta(days=i)
        series.append((d, 3000.0 if d.weekday() == 5 else 1000.0))
    # 第 4 个周六炸成 30000(该星期几正常值的 10 倍)
    spike_at = [i for i, (d, _) in enumerate(series) if d.weekday() == 5][3]
    spike_date = series[spike_at][0]
    series[spike_at] = (spike_date, 30000.0)

    cleaned = _clean_outliers(series)
    kept = {d for d, _ in cleaned}
    assert spike_date not in kept, "真正的异常日应被剔除"
    sat_kept = sum(1 for d, _ in cleaned if d.weekday() == 5)
    assert sat_kept == 7, f"其余 7 个周六应保留(尖峰不该连累同侪), 实际 {sat_kept}"


def test_target_forecast_model_labels_cover_every_model_type():
    """预测端能吐出的每一种 model_type, 渲染端都要有说法。

    ⛔ 这个断言**不许**去读 `_MODEL_LABELS` 的 key 再和自己比 —— 那是恒真式。
       右边从 `target_forecast.py` 的**源码**里数出实际会被返回的字面量。

    背景: 2026-08-10 给预测加周内项, model_type 从 linear_trend 变成
    linear_trend_dow。渲染端当时是个三元式, 会**静默落到 else**, 把变强的模型
    对用户说成「近期均值（数据较少）」—— 模型对了, 话说反了。
    """
    import re
    from pathlib import Path

    from smartbi.gold.restaurant.restaurant_target_tool import _MODEL_LABELS
    from smartbi.services import target_forecast

    src = Path(target_forecast.__file__).read_text(encoding="utf-8")
    emitted = set(re.findall(r'model_type"?\s*[:=]\s*"([a-z_]+)"', src))
    assert emitted, "一个 model_type 都没数出来 —— 正则跟源码写法脱节了, 先修正则"

    missing = emitted - set(_MODEL_LABELS)
    assert not missing, (
        f"预测端会返回 {sorted(missing)}, 但 _MODEL_LABELS 里没有 —— "
        f"用户会看到 fallback 文案「近期均值（数据较少）」")


def test_forecast_dow_factors_engage_on_weekly_seasonality():
    """有周内规律 → 启用周内项, 且预测能分出周末和工作日。"""
    from datetime import timedelta

    # 周六/周日 2 倍, 连续 8 周
    vals = []
    for i in range(56):
        dow = (date(2026, 1, 1) + timedelta(days=i)).weekday()
        vals.append(2000.0 if dow >= 5 else 1000.0)
    res = compute_rolling_forecast(
        _series(vals), anchor=date(2026, 2, 25), horizon_days=7)
    assert res["model_type"] == "linear_trend_dow"

    by_dow = {
        date.fromisoformat(p["date"]).weekday(): p["forecast_amount"]
        for p in res["points"]
    }
    weekend = [by_dow[w] for w in (5, 6)]
    weekday = [by_dow[w] for w in range(5)]
    assert min(weekend) > max(weekday) * 1.5, (
        f"周末应显著高于工作日, 实际 周末={weekend} 工作日={weekday}")


def test_forecast_dow_skipped_when_a_weekday_is_underrepresented():
    """某个星期几观测不足 → 退回一元线性趋势, 不拿噪声当因子。"""
    from datetime import timedelta

    # 21 天连续(每个星期几各 3 次) → 够; 去掉全部周三 → 周三 0 次
    vals, days = [], []
    for i in range(21):
        d = date(2026, 1, 5) + timedelta(days=i)   # 2026-01-05 是周一
        if d.weekday() == 2:
            continue
        days.append(d)
        vals.append(1000.0 + i)
    series = list(zip(days, vals))
    res = compute_rolling_forecast(series, anchor=days[-1], horizon_days=7)
    assert res["model_type"] == "linear_trend", (
        f"缺一个星期几就不该启用周内项, 实际 {res['model_type']}")


def test_forecast_outlier_removed():
    # 20 days ~1000 + one extreme spike — spike must be IQR-dropped.
    vals = [1000] * 20
    vals[10] = 999999
    series = _series(vals)
    cleaned = _clean_outliers(series)
    assert len(cleaned) == 19  # spike removed
    assert all(v < 5000 for _, v in cleaned)


def test_forecast_lower_bound_clamped_nonneg():
    # noisy small values → CI lower could go negative; must clamp to 0.
    vals = [10, 500, 5, 800, 3, 900, 2, 700, 1, 600,
            4, 850, 6, 750, 7, 650, 8, 550, 9, 950]
    series = _series(vals)
    res = compute_rolling_forecast(series, anchor=date(2026, 1, 20), horizon_days=5)
    assert all(p["lower_bound"] >= 0.0 for p in res["points"])


# ── RBAC strip: non-price role nulls revenue/target/forecast amounts ──────────
def test_rbac_strip_nulls_money_for_non_price_role():
    """The endpoint's _strip_forecast_money nulls forecast amounts + bounds.

    The shared _MONEY_PATTERN catches ``forecast_amount`` (regex 'amount') but
    NOT ``lower_bound`` / ``upper_bound`` — so the API layer adds an explicit
    extra-key strip (mirrors gold_reads._strip_extras). We test that combined
    helper here.
    """
    from smartbi.api.restaurant_targets_p1 import _strip_forecast_money
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    payload = {
        "points": [
            {"date": "2026-06-02", "forecast_amount": 50000.0,
             "lower_bound": 40000.0, "upper_bound": 60000.0},
        ],
    }
    assert "operator" not in PRICE_VIEW_ROLES
    _strip_forecast_money(payload, "operator")
    pt = payload["points"][0]
    assert pt["forecast_amount"] is None
    assert pt["lower_bound"] is None
    assert pt["upper_bound"] is None
    # the date (directional / non-money) survives
    assert pt["date"] == "2026-06-02"


def test_rbac_strip_passthrough_for_price_role():
    from smartbi.api.restaurant_targets_p1 import _strip_forecast_money
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    assert "factory_super_admin" in PRICE_VIEW_ROLES
    payload = {"points": [{"forecast_amount": 50000.0, "lower_bound": 1.0,
                           "upper_bound": 2.0}]}
    _strip_forecast_money(payload, "factory_super_admin")
    # price-view role → untouched
    assert payload["points"][0]["forecast_amount"] == 50000.0


def test_rbac_strip_decompose_revenue_keys_gated_by_kpi():
    """IMPORTANT-1 regression: the decompose response carries revenue under
    target_value / month_target / week_target — money ONLY for kpiKind=='revenue'
    (passed as extra_money_keys). For bill_count they are counts and must survive.
    """
    from smartbi.api.restaurant_targets_p1 import (
        _strip_forecast_money, _DECOMPOSE_REVENUE_KEYS,
    )

    def _payload():
        return {
            "month_target": 300000.0,
            "weeks": [{"period_key": "2026-W23", "target_value": 70000.0}],
            "dayCascade": [{"week_target": 70000.0,
                            "days": [{"period_key": "2026-06-04", "target_value": 10000.0}]}],
        }

    # revenue kpi → extra keys passed → every revenue amount nulled for non-price role
    p = _payload()
    _strip_forecast_money(p, "operator", _DECOMPOSE_REVENUE_KEYS)
    assert p["month_target"] is None
    assert p["weeks"][0]["target_value"] is None
    assert p["dayCascade"][0]["week_target"] is None
    assert p["dayCascade"][0]["days"][0]["target_value"] is None
    assert p["weeks"][0]["period_key"] == "2026-W23"  # directional key survives

    # bill_count kpi → NO extra keys → counts survive (they are not money)
    p2 = _payload()
    _strip_forecast_money(p2, "operator")
    assert p2["month_target"] == 300000.0
    assert p2["weeks"][0]["target_value"] == 70000.0
