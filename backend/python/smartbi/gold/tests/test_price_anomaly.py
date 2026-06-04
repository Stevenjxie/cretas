"""Unit tests for Wave2 价格异常威慑引擎 — price anomaly detection + ack.

Uses a fake asyncpg pool/connection (no real DB). Validates:
- detect_price_anomalies: latest vs trailing-N avg, ε tolerance, direction,
  risk_level escalation from consecutive same-direction anomalies, shape.
- record_anomaly_ack: idempotent upsert, reason_code validation (OTHER needs note),
  RLS tenant set, rejects None factory.
- list_anomaly_acks: returns acks for dedup/display.

Run:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_price_anomaly.py -v
"""
from __future__ import annotations

import asyncio
from datetime import date
from decimal import Decimal

import pytest

from smartbi.gold.price_anomaly import (
    detect_price_anomalies,
    record_anomaly_ack,
    list_anomaly_acks,
    VALID_REASON_CODES,
    _classify_risk,
)


# ---------------------------------------------------------------------------
# Fake pool / connection helpers (mirror test_supplier_price_etl.py)
# ---------------------------------------------------------------------------

class _FakeRecord(dict):
    def keys(self):
        return super().keys()


class _FakeConn:
    """Records every SQL + args. fetch returns canned rows per call index."""

    def __init__(self, fetch_results):
        # fetch_results: list of row-lists, popped per fetch() call in order.
        self._fetch_results = list(fetch_results)
        self.sql_log = []
        self.args_log = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def execute(self, sql, *a, **k):
        self.sql_log.append(sql)
        self.args_log.append(a)
        return None

    async def fetch(self, sql, *a, **k):
        self.sql_log.append(sql)
        self.args_log.append(a)
        if self._fetch_results:
            return [_FakeRecord(r) for r in self._fetch_results.pop(0)]
        return []

    async def fetchrow(self, sql, *a, **k):
        self.sql_log.append(sql)
        self.args_log.append(a)
        if self._fetch_results:
            rows = self._fetch_results.pop(0)
            return _FakeRecord(rows[0]) if rows else None
        return None

    async def fetchval(self, sql, *a, **k):
        self.sql_log.append(sql)
        self.args_log.append(a)
        if self._fetch_results:
            rows = self._fetch_results.pop(0)
            if rows:
                first = rows[0]
                return next(iter(first.values())) if isinstance(first, dict) else first
        return None

    def transaction(self):
        return _FakeTxn()


class _FakeTxn:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _FakePool:
    def __init__(self, fetch_results):
        self._conn = _FakeConn(fetch_results)

    def acquire(self):
        return self._conn

    @property
    def conn(self):
        return self._conn


# ---------------------------------------------------------------------------
# _classify_risk (pure)
# ---------------------------------------------------------------------------

def test_classify_risk_levels():
    # 0 prior same-direction acks → first anomaly is MEDIUM (just a heads-up)
    assert _classify_risk(0) == "MEDIUM"
    # 1 prior consecutive → 2nd time, still MEDIUM under threshold 3
    assert _classify_risk(1) == "MEDIUM"
    # 2 prior consecutive → this is the 3rd → HIGH (邓总: 连续三次)
    assert _classify_risk(2) == "HIGH"
    assert _classify_risk(5) == "HIGH"


# ---------------------------------------------------------------------------
# detect_price_anomalies
# ---------------------------------------------------------------------------

def test_detect_flags_jump_above_tolerance():
    """洗洁精 110→150: latest 150 vs trailing avg ~110 → +36% > ε(5%) → anomaly UP."""
    # fetch #1: per-ingredient price history rows (newest first within each food).
    history = [
        # 洗洁精: trailing 110, latest 150 (jump up)
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 6, 3), "unit_price": Decimal("150.0")},
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 5, 20), "unit_price": Decimal("110.0")},
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 5, 10), "unit_price": Decimal("110.0")},
    ]
    # fetch #2: prior consecutive same-direction ack counts per (food, supplier) → none yet
    ack_counts = []
    pool = _FakePool([history, ack_counts])
    out = asyncio.run(detect_price_anomalies(pool, "F006", trailing_n=3, epsilon_pct=5.0))
    assert len(out) == 1
    a = out[0]
    assert a["normalizedName"] == "洗洁精"
    assert a["newPrice"] == 150.0
    # trailing avg over prior 2 = (110+110)/2 = 110
    assert a["trailingAvg"] == 110.0
    assert a["oldPrice"] == 110.0  # immediately-prior price
    assert a["direction"] == "UP"
    assert a["deltaPct"] > 5.0
    assert a["riskLevel"] == "MEDIUM"
    assert a["consecutiveAnomalyCount"] == 1  # this is the 1st


def test_detect_ignores_within_tolerance():
    """青菜 2.5→2 is -20% (anomaly), but a 2% wiggle is within ε and ignored."""
    history = [
        {"normalized_name": "豆腐", "ingredient_name": "豆腐",
         "supplier_id": "sup-2", "supplier_name": "李记", "unit": "kg",
         "delivery_date": date(2026, 6, 3), "unit_price": Decimal("4.08")},
        {"normalized_name": "豆腐", "ingredient_name": "豆腐",
         "supplier_id": "sup-2", "supplier_name": "李记", "unit": "kg",
         "delivery_date": date(2026, 5, 20), "unit_price": Decimal("4.00")},
        {"normalized_name": "豆腐", "ingredient_name": "豆腐",
         "supplier_id": "sup-2", "supplier_name": "李记", "unit": "kg",
         "delivery_date": date(2026, 5, 10), "unit_price": Decimal("4.00")},
    ]
    pool = _FakePool([history, []])
    out = asyncio.run(detect_price_anomalies(pool, "F006", trailing_n=3, epsilon_pct=5.0))
    assert out == []  # 2% < 5% tolerance


def test_detect_flags_drop_青菜():
    """青菜 2.5→2: -20% drop > ε → anomaly DOWN (邓总另一案例)."""
    history = [
        {"normalized_name": "青菜", "ingredient_name": "青菜",
         "supplier_id": "sup-3", "supplier_name": "王记", "unit": "kg",
         "delivery_date": date(2026, 6, 3), "unit_price": Decimal("2.0")},
        {"normalized_name": "青菜", "ingredient_name": "青菜",
         "supplier_id": "sup-3", "supplier_name": "王记", "unit": "kg",
         "delivery_date": date(2026, 5, 20), "unit_price": Decimal("2.5")},
        {"normalized_name": "青菜", "ingredient_name": "青菜",
         "supplier_id": "sup-3", "supplier_name": "王记", "unit": "kg",
         "delivery_date": date(2026, 5, 10), "unit_price": Decimal("2.5")},
    ]
    pool = _FakePool([history, []])
    out = asyncio.run(detect_price_anomalies(pool, "F006", trailing_n=3, epsilon_pct=5.0))
    assert len(out) == 1
    assert out[0]["direction"] == "DOWN"
    assert out[0]["deltaPct"] < 0


def test_detect_escalates_to_high_on_third_consecutive():
    """连续三次同向异常 → HIGH (邓总: '连续三次跟我这样玩他就不敢乱来')."""
    history = [
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 6, 3), "unit_price": Decimal("150.0")},
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 5, 20), "unit_price": Decimal("110.0")},
    ]
    # 2 prior same-direction UP acks already on record for this (food, supplier)
    ack_counts = [{"normalized_name": "洗洁精", "supplier_id": "sup-1",
                   "prior_count": 2}]
    pool = _FakePool([history, ack_counts])
    out = asyncio.run(detect_price_anomalies(pool, "F006", trailing_n=3, epsilon_pct=5.0))
    assert len(out) == 1
    assert out[0]["consecutiveAnomalyCount"] == 3  # 2 prior + this one
    assert out[0]["riskLevel"] == "HIGH"


def test_detect_skips_ingredient_with_no_history():
    """A food with only 1 price point has no trailing baseline → skipped (no false anomaly)."""
    history = [
        {"normalized_name": "新食材", "ingredient_name": "新食材",
         "supplier_id": "sup-9", "supplier_name": "新供应商", "unit": "kg",
         "delivery_date": date(2026, 6, 3), "unit_price": Decimal("99.0")},
    ]
    pool = _FakePool([history, []])
    out = asyncio.run(detect_price_anomalies(pool, "F006", trailing_n=3, epsilon_pct=5.0))
    assert out == []


# ---------------------------------------------------------------------------
# detect_price_anomalies — 90-day moving-average mode (邓总 locked basis)
# ---------------------------------------------------------------------------

def test_detect_days_mode_baseline_is_90d_moving_avg():
    """baseline_mode='days', window_days=90: latest vs avg of ALL prior points
    within the trailing 90 days (邓总: '自身 90 天移动均价').

    洗洁精: latest 150 on 6-3; prior points 110/110/120 all within 90d →
    moving avg = (110+110+120)/3 = 113.33 → +32.4% > ε → anomaly UP.
    A 4th point far outside the 90d window (older than 90 days) is EXCLUDED.
    """
    history = [
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 6, 3), "unit_price": Decimal("150.0")},
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 5, 20), "unit_price": Decimal("110.0")},
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 5, 1), "unit_price": Decimal("110.0")},
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 4, 1), "unit_price": Decimal("120.0")},
        # Outside the 90d window from 6-3 (older than 2026-03-05) → excluded.
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2025, 12, 1), "unit_price": Decimal("500.0")},
    ]
    pool = _FakePool([history, []])
    out = asyncio.run(detect_price_anomalies(
        pool, "F006", baseline_mode="days", window_days=90, epsilon_pct=5.0))
    assert len(out) == 1
    a = out[0]
    # moving avg over the 3 in-window prior points = (110+110+120)/3 = 113.3333
    assert a["trailingAvg"] == 113.3333
    assert a["newPrice"] == 150.0
    assert a["oldPrice"] == 110.0  # immediately-prior price still the latest-1
    assert a["direction"] == "UP"
    assert a["deltaPct"] > 5.0
    # The 500 point (outside window) did NOT inflate the baseline.
    assert a["trailingAvg"] < 200.0


def test_detect_days_mode_skips_when_no_prior_point_in_window():
    """If the only prior price points are all OLDER than window_days, there is
    no in-window baseline → skipped (no false anomaly off a stale price)."""
    history = [
        {"normalized_name": "陈醋", "ingredient_name": "陈醋",
         "supplier_id": "sup-7", "supplier_name": "恒顺", "unit": "瓶",
         "delivery_date": date(2026, 6, 3), "unit_price": Decimal("20.0")},
        # 6 months old — outside a 90d window
        {"normalized_name": "陈醋", "ingredient_name": "陈醋",
         "supplier_id": "sup-7", "supplier_name": "恒顺", "unit": "瓶",
         "delivery_date": date(2025, 12, 1), "unit_price": Decimal("10.0")},
    ]
    pool = _FakePool([history, []])
    out = asyncio.run(detect_price_anomalies(
        pool, "F006", baseline_mode="days", window_days=90, epsilon_pct=5.0))
    assert out == []


def test_detect_days_mode_within_tolerance_ignored():
    """90d moving avg with a tiny wiggle stays within ε → not flagged."""
    history = [
        {"normalized_name": "豆腐", "ingredient_name": "豆腐",
         "supplier_id": "sup-2", "supplier_name": "李记", "unit": "kg",
         "delivery_date": date(2026, 6, 3), "unit_price": Decimal("4.08")},
        {"normalized_name": "豆腐", "ingredient_name": "豆腐",
         "supplier_id": "sup-2", "supplier_name": "李记", "unit": "kg",
         "delivery_date": date(2026, 5, 20), "unit_price": Decimal("4.00")},
        {"normalized_name": "豆腐", "ingredient_name": "豆腐",
         "supplier_id": "sup-2", "supplier_name": "李记", "unit": "kg",
         "delivery_date": date(2026, 5, 1), "unit_price": Decimal("4.00")},
    ]
    pool = _FakePool([history, []])
    out = asyncio.run(detect_price_anomalies(
        pool, "F006", baseline_mode="days", window_days=90, epsilon_pct=5.0))
    assert out == []


def test_detect_days_mode_escalates_to_high_on_third_consecutive():
    """Days-mode anomaly still escalates to HIGH via the ack-count join (威慑)."""
    history = [
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 6, 3), "unit_price": Decimal("150.0")},
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 5, 20), "unit_price": Decimal("110.0")},
    ]
    ack_counts = [{"normalized_name": "洗洁精", "supplier_id": "sup-1",
                   "prior_count": 2}]
    pool = _FakePool([history, ack_counts])
    out = asyncio.run(detect_price_anomalies(
        pool, "F006", baseline_mode="days", window_days=90, epsilon_pct=5.0))
    assert len(out) == 1
    assert out[0]["consecutiveAnomalyCount"] == 3
    assert out[0]["riskLevel"] == "HIGH"


def test_detect_default_mode_is_count_legacy_unchanged():
    """Default baseline_mode='count' preserves the legacy trailing-N behaviour
    (so existing callers / the web-admin board are unaffected)."""
    history = [
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 6, 3), "unit_price": Decimal("150.0")},
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 5, 20), "unit_price": Decimal("110.0")},
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 5, 10), "unit_price": Decimal("110.0")},
    ]
    pool = _FakePool([history, []])
    # No baseline_mode arg → count mode → trailing-2 baseline = 110
    out = asyncio.run(detect_price_anomalies(pool, "F006", trailing_n=3, epsilon_pct=5.0))
    assert len(out) == 1
    assert out[0]["trailingAvg"] == 110.0


def test_detect_rejects_none_factory():
    pool = _FakePool([[], []])
    with pytest.raises(ValueError):
        asyncio.run(detect_price_anomalies(pool, None))


def test_detect_sets_rls_tenant():
    history = [
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 6, 3), "unit_price": Decimal("150.0")},
        {"normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农", "unit": "瓶",
         "delivery_date": date(2026, 5, 20), "unit_price": Decimal("110.0")},
    ]
    pool = _FakePool([history, []])
    asyncio.run(detect_price_anomalies(pool, "F006"))
    joined = " ".join(pool.conn.sql_log)
    assert "set_config('app.factory_id'" in joined


# ---------------------------------------------------------------------------
# record_anomaly_ack
# ---------------------------------------------------------------------------

def test_record_ack_basic():
    """A standard SEASONAL ack inserts a row, sets RLS, returns the ack id."""
    pool = _FakePool([[{"id": 42}]])
    ack_id = asyncio.run(record_anomaly_ack(
        pool, "F006",
        normalized_name="洗洁精",
        ingredient_name="洗洁精",
        supplier_id="sup-1",
        supplier_name="鑫农",
        anomaly_delivery_date=date(2026, 6, 3),
        old_price=Decimal("110.0"),
        new_price=Decimal("150.0"),
        delta_pct=Decimal("36.36"),
        reason_code="SEASONAL",
        reason_note=None,
        acknowledged_by="zhangquan",
    ))
    assert ack_id == 42
    joined = " ".join(pool.conn.sql_log)
    assert "set_config('app.factory_id'" in joined
    assert "INSERT INTO price_anomaly_ack" in joined
    # idempotent upsert
    assert "ON CONFLICT" in joined


def test_record_ack_other_requires_note():
    """reason_code=OTHER without a note → ValueError (防呆 Rule 3)."""
    pool = _FakePool([[{"id": 1}]])
    with pytest.raises(ValueError):
        asyncio.run(record_anomaly_ack(
            pool, "F006",
            normalized_name="洗洁精",
            ingredient_name="洗洁精",
            supplier_id="sup-1",
            supplier_name="鑫农",
            anomaly_delivery_date=date(2026, 6, 3),
            old_price=Decimal("110.0"),
            new_price=Decimal("150.0"),
            delta_pct=Decimal("36.36"),
            reason_code="OTHER",
            reason_note="   ",  # blank
            acknowledged_by="zhangquan",
        ))


def test_record_ack_invalid_reason_code():
    pool = _FakePool([[{"id": 1}]])
    with pytest.raises(ValueError):
        asyncio.run(record_anomaly_ack(
            pool, "F006",
            normalized_name="洗洁精",
            ingredient_name="洗洁精",
            supplier_id="sup-1",
            supplier_name="鑫农",
            anomaly_delivery_date=date(2026, 6, 3),
            old_price=Decimal("110.0"),
            new_price=Decimal("150.0"),
            delta_pct=Decimal("36.36"),
            reason_code="BOGUS",
            reason_note=None,
            acknowledged_by="zhangquan",
        ))


def test_record_ack_rejects_none_factory():
    pool = _FakePool([[{"id": 1}]])
    with pytest.raises(ValueError):
        asyncio.run(record_anomaly_ack(
            pool, None,
            normalized_name="x",
            ingredient_name="x",
            supplier_id=None,
            supplier_name=None,
            anomaly_delivery_date=date(2026, 6, 3),
            old_price=None,
            new_price=Decimal("1.0"),
            delta_pct=None,
            reason_code="SEASONAL",
            reason_note=None,
            acknowledged_by="x",
        ))


def test_valid_reason_codes_contract():
    assert "SEASONAL" in VALID_REASON_CODES
    assert "MARKET_RISE" in VALID_REASON_CODES
    assert "SPEC_CHANGE" in VALID_REASON_CODES
    assert "OTHER" in VALID_REASON_CODES


# ---------------------------------------------------------------------------
# list_anomaly_acks
# ---------------------------------------------------------------------------

def test_list_acks_shape():
    pool = _FakePool([[
        {"id": 1, "normalized_name": "洗洁精", "ingredient_name": "洗洁精",
         "supplier_id": "sup-1", "supplier_name": "鑫农",
         "anomaly_delivery_date": date(2026, 6, 3),
         "old_price": Decimal("110.0"), "new_price": Decimal("150.0"),
         "delta_pct": Decimal("36.36"), "reason_code": "SEASONAL",
         "reason_note": None, "acknowledged_by": "zhangquan",
         "created_at": None},
    ]])
    out = asyncio.run(list_anomaly_acks(pool, "F006"))
    assert len(out) == 1
    assert out[0]["normalizedName"] == "洗洁精"
    assert out[0]["reasonCode"] == "SEASONAL"
    assert out[0]["newPrice"] == 150.0


def test_list_acks_rejects_none_factory():
    pool = _FakePool([[]])
    with pytest.raises(ValueError):
        asyncio.run(list_anomaly_acks(pool, None))
