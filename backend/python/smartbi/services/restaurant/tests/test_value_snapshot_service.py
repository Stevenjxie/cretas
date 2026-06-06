"""#56 价值可视化回馈回路 — ValueSnapshotService 单元测试 (fake pool, no DB)。

覆盖 spec §测试计划:
  - 幂等 upsert: 同一 (factory, period, store) 第二次调用不增行 (ON CONFLICT DO UPDATE)。
  - 数据不足 → 金额 None 不报错 (禁降级)。
  - get_value_summary 未命中返 None; 命中返 {month, annual} 两口径 (D3)。
  - compute 异常 try/except 只记日志, 不抛 (fire-and-forget 安全)。
"""
from __future__ import annotations

import asyncio
from typing import Optional

from smartbi.services.restaurant import value_snapshot_service as svc
from smartbi.services.restaurant.value_signal_extractor import ValueSignal


class _FakeRecord(dict):
    def keys(self):
        return super().keys()


class _CapturingConn:
    """Captures execute/fetchrow calls; simulates ON CONFLICT idempotency.

    Keyed by (factory_id, period_month, COALESCE(store_id,'')) → row count.
    """

    def __init__(self, existing_row: Optional[dict] = None):
        self.execute_calls: list[tuple] = []
        self.fetchrow_result: Optional[dict] = existing_row
        self._store: dict[tuple, int] = {}
        self.insert_count = 0
        self.update_count = 0

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def execute(self, sql, *args, **kwargs):
        self.execute_calls.append((sql, args))
        if "INSERT INTO restaurant_value_snapshots" in sql:
            # args: factory_id, period_month, store_id, ... → key
            key = (args[0], args[1], args[2] or "")
            if key in self._store:
                self.update_count += 1
            else:
                self._store[key] = 1
                self.insert_count += 1
        return "INSERT 0 1"

    async def fetchrow(self, sql, *args, **kwargs):
        return _FakeRecord(self.fetchrow_result) if self.fetchrow_result else None

    async def fetch(self, sql, *args, **kwargs):
        return []


class _FakePool:
    def __init__(self, conn: _CapturingConn):
        self._conn = conn

    def acquire(self):
        return self._conn


def _signals_deng() -> list[ValueSignal]:
    return [
        ValueSignal("labor_rigidity", "人工刚性节省", 18349.0, "estimate", "month"),
        ValueSignal("food_cost_savings", "食材成本改善空间", 20000.0, "estimate", "month"),
        ValueSignal("shrinkage_variance", "档口损溢超标 (本月实测)", 12500.0, "measured", "month"),
        ValueSignal("food_cost_savings", "食材", None, "estimate", "month"),  # null 信号
    ]


# ── compute_and_upsert_snapshot ──────────────────────────


def test_upsert_inserts_one_row():
    conn = _CapturingConn()
    pool = _FakePool(conn)
    result = asyncio.run(svc.compute_and_upsert_snapshot(
        pool, factory_id="F-DENG", period_month="2026-02", store_id=None,
        signals=_signals_deng(), diagnosis_count=3, critical_count=1, rx_action_count=2,
    ))
    assert conn.insert_count == 1
    assert result["success"] is True


def test_upsert_idempotent_second_call_no_new_row():
    """同一 key 第二次 upsert → ON CONFLICT DO UPDATE, 不增行。"""
    conn = _CapturingConn()
    pool = _FakePool(conn)
    asyncio.run(svc.compute_and_upsert_snapshot(
        pool, "F-DENG", "2026-02", None, _signals_deng(), 3, 1, 2,
    ))
    asyncio.run(svc.compute_and_upsert_snapshot(
        pool, "F-DENG", "2026-02", None, _signals_deng(), 3, 1, 2,
    ))
    assert conn.insert_count == 1
    assert conn.update_count == 1  # second call hit ON CONFLICT


def test_upsert_aggregates_month_total_excludes_null():
    """total_est_month = sum(month signals 有金额的), None 信号不计入。"""
    conn = _CapturingConn()
    pool = _FakePool(conn)
    asyncio.run(svc.compute_and_upsert_snapshot(
        pool, "F-DENG", "2026-02", None, _signals_deng(), 3, 1, 2,
    ))
    # Find the INSERT call and inspect the total_est_month arg.
    insert_call = next(c for c in conn.execute_calls if "INSERT INTO restaurant_value_snapshots" in c[0])
    args = insert_call[1]
    # total_est_month should be 18349 + 20000 + 12500 = 50849 (null excluded)
    assert any(abs((a if a is not None else -1) - 50849.0) < 0.01 for a in args if isinstance(a, (int, float)))


def test_upsert_all_null_signals_total_is_none_not_zero():
    """全部信号金额 None → total_est_month = None (禁降级填 0)。"""
    conn = _CapturingConn()
    pool = _FakePool(conn)
    null_signals = [ValueSignal("food_cost_savings", "食材", None, "estimate", "month")]
    asyncio.run(svc.compute_and_upsert_snapshot(
        pool, "F-X", "2026-02", None, null_signals, 1, 0, 0,
    ))
    assert any("INSERT INTO restaurant_value_snapshots" in c[0] for c in conn.execute_calls)
    total = svc._aggregate_total(null_signals)  # direct helper assertion
    assert total is None  # NOT 0.0


def test_aggregate_total_with_amounts():
    sigs = [
        ValueSignal("a", "a", 100.0, "estimate", "month"),
        ValueSignal("b", "b", None, "estimate", "month"),
        ValueSignal("c", "c", 50.0, "measured", "month"),
    ]
    assert svc._aggregate_total(sigs) == 150.0


def test_aggregate_total_empty_is_none():
    assert svc._aggregate_total([]) is None


def test_annualize_month_total():
    """年化 = 月度 estimate-类 × 12; measured (本月实测损溢) 不年化。"""
    sigs = [
        ValueSignal("labor_rigidity", "人工", 1000.0, "estimate", "month"),
        ValueSignal("shrinkage_variance", "损溢", 500.0, "measured", "month"),
    ]
    annual = svc._aggregate_annual_total(sigs)
    # estimate 1000*12=12000 + measured 500 (不年化) = 12500
    assert annual == 12500.0


# ── get_value_summary ────────────────────────────────────


def test_get_value_summary_miss_returns_none():
    conn = _CapturingConn(existing_row=None)
    pool = _FakePool(conn)
    result = asyncio.run(svc.get_value_summary(pool, "F-NONE", period_month="2026-02"))
    assert result is None


def test_get_value_summary_hit_returns_both_periods():
    """D3: 命中返 {month: {...}, annual: {...}} 两口径。"""
    row = {
        "factory_id": "F-DENG", "period_month": "2026-02", "store_id": None,
        "labor_rigidity_annual_est": 220188.0,
        "shrinkage_variance_amount": 12500.0,
        "food_cost_savings_est": 20000.0,
        "discount_savings_est": None,
        "total_est_month": 50849.0,
        "total_est_annual": 472688.0,
        "diagnosis_count": 3, "critical_count": 1, "rx_action_count": 2,
        "signal_sources": "[]", "confidence_note": "预估口径",
        "computed_at": None,
    }
    conn = _CapturingConn(existing_row=row)
    pool = _FakePool(conn)
    result = asyncio.run(svc.get_value_summary(pool, "F-DENG", period_month="2026-02"))
    assert result is not None
    assert "month" in result and "annual" in result
    assert result["month"]["total"] == 50849.0
    assert result["annual"]["total"] == 472688.0
    assert result["criticalCount"] == 1


# ── 异常安全 ──────────────────────────────────────────────


def test_compute_swallows_db_error():
    """compute 内部 DB 异常 → 返回 success:False, 不抛 (fire-and-forget 安全)。"""
    class _BoomConn(_CapturingConn):
        async def execute(self, sql, *args, **kwargs):
            raise RuntimeError("db boom")

    conn = _BoomConn()
    pool = _FakePool(conn)
    result = asyncio.run(svc.compute_and_upsert_snapshot(
        pool, "F-DENG", "2026-02", None, _signals_deng(), 3, 1, 2,
    ))
    assert result["success"] is False
    assert "boom" in result["message"].lower() or "失败" in result["message"]
