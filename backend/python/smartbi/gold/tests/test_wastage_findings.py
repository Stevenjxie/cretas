"""Unit tests for restaurant wastage finding rules.

Fake asyncpg pool/conn — no real DB. Run with:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_wastage_findings.py -v
"""
from __future__ import annotations

import asyncio

import pytest

from smartbi.gold.restaurant.wastage_findings import (
    ACTIONABLE_WASTAGE_TYPES,
    detect_type_concentration,
)


class _FakeRecord(dict):
    def keys(self):
        return super().keys()


class _SeqConn:
    """Returns queued responses in call order — the rules issue several
    different queries, so one fixed row-set (as in test_gold_reads_restaurant)
    would feed the wrong rows to the wrong query."""

    def __init__(self, fetch_responses, fetchrow_responses=None):
        self._fetch = list(fetch_responses)
        self._fetchrow = list(fetchrow_responses or [])
        self.sqls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def execute(self, *a, **k):
        return None

    async def fetch(self, sql, *a, **k):
        self.sqls.append(sql)
        return [_FakeRecord(r) for r in self._fetch.pop(0)]

    async def fetchrow(self, sql, *a, **k):
        self.sqls.append(sql)
        row = self._fetchrow.pop(0)
        return _FakeRecord(row) if row is not None else None


class _SeqPool:
    def __init__(self, fetch_responses, fetchrow_responses=None):
        self.conn = _SeqConn(fetch_responses, fetchrow_responses)

    def acquire(self):
        return self.conn


def _run(coro):
    return asyncio.run(coro)


# ── R2: type concentration ────────────────────────────────────────────

def test_r2_reports_only_actionable_type_over_threshold():
    """MOCK_REST 实测形状：加工损耗 52.9% 结构性不报，变质 37.2% 可行动要报。"""
    pool = _SeqPool([[
        {"wastage_type": "加工损耗", "cost": 413206.52},
        {"wastage_type": "变质", "cost": 291112.44},
        {"wastage_type": "客诉退菜", "cost": 77403.93},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["applicable"] is True
    assert out["skip_reason"] is None
    names = [f["subject_name"] for f in out["findings"]]
    assert names == ["变质"], f"加工损耗是结构性的不该报, 客诉退菜 9.9% 未过闸: {names}"
    assert out["findings"][0]["code"] == "WASTAGE_TYPE_CONCENTRATION"


def test_r2_structural_type_never_reported_even_at_high_share():
    """加工损耗占 90% 也不报 —— 它是切配常态, 店长知道也动不了。"""
    pool = _SeqPool([[
        {"wastage_type": "加工损耗", "cost": 900.0},
        {"wastage_type": "变质", "cost": 100.0},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["findings"] == []
    assert out["applicable"] is True


def test_r2_unknown_type_defaults_to_actionable():
    """未知新类型宁多报不漏报。"""
    pool = _SeqPool([[
        {"wastage_type": "运输破损", "cost": 800.0},
        {"wastage_type": "变质", "cost": 200.0},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert [f["subject_name"] for f in out["findings"]] == ["运输破损"]


def test_r2_below_30pct_not_reported():
    pool = _SeqPool([[
        {"wastage_type": "变质", "cost": 299.0},
        {"wastage_type": "加工损耗", "cost": 701.0},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["findings"] == []


def test_r2_no_rows_is_applicable_with_no_findings():
    """真的没有 —— 不是 skip, 不是失败。"""
    pool = _SeqPool([[]], fetchrow_responses=[{"total_cost": 0.0}])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["applicable"] is True
    assert out["skip_reason"] is None
    assert out["findings"] == []


def test_r2_skips_when_by_type_kpi_unmaterialized():
    """by_type 全零但 totals 有钱 = materialize 没跑过。不得对一堆零排序。"""
    pool = _SeqPool([[]], fetchrow_responses=[{"total_cost": 894270.0}])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["applicable"] is False
    assert "未物化" in out["skip_reason"]
    assert out["findings"] == []


def test_r2_severity_warning_above_half():
    pool = _SeqPool([[
        {"wastage_type": "变质", "cost": 600.0},
        {"wastage_type": "加工损耗", "cost": 400.0},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["findings"][0]["severity"] == "WARNING"


def test_r2_window_boundary_is_exclusive_on_the_old_side():
    """`>=` 会取到 8 天并与基线窗重叠 —— 断言 SQL 用的是 `>`。"""
    pool = _SeqPool([[{"wastage_type": "变质", "cost": 100.0}]])

    _run(detect_type_concentration(pool, "MOCK_REST"))

    sql = pool.conn.sqls[0]
    assert "date > CURRENT_DATE" in sql
    assert "date >= CURRENT_DATE" not in sql


def test_actionable_config_is_the_only_definition():
    """配置在这个模块里, 不许别处再写一份。"""
    assert ACTIONABLE_WASTAGE_TYPES["变质"] is True
    assert ACTIONABLE_WASTAGE_TYPES["客诉退菜"] is True
    assert ACTIONABLE_WASTAGE_TYPES["加工损耗"] is False


# ── R1: share spike ───────────────────────────────────────────────────

from smartbi.gold.restaurant.wastage_findings import detect_share_spike  # noqa: E402


def _spike_pool(cur_rows, base_rows, base_days=21):
    """R1 依次发 3 个查询: cur 按食材 / base 按食材 / base 天数。"""
    return _SeqPool(
        [cur_rows, base_rows],
        fetchrow_responses=[{"days": base_days}],
    )


def test_r1_flags_ingredient_growing_faster_than_the_store():
    """份额 5% -> 10% = 放大 2 倍, 过 1.4 闸。"""
    cur = [
        {"dim_value_id": 1, "name": "鸡腿肉", "unit": "kg", "cost": 100.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 900.0},
    ]
    base = [
        {"dim_value_id": 1, "name": "鸡腿肉", "unit": "kg", "cost": 50.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 950.0},
    ]
    out = _run(detect_share_spike(_spike_pool(cur, base), "MOCK_REST"))

    assert out["applicable"] is True
    assert [f["subject_name"] for f in out["findings"]] == ["鸡腿肉"]
    assert out["findings"][0]["code"] == "WASTAGE_SHARE_SPIKE"
    assert out["findings"][0]["facts"]["amplification"] == 2.0


def test_r1_skips_on_the_2026_07_30_regime_change():
    """🔴 真实形状: base 13 种食材, cur 25 种 (12 种是 08-01 才出现的)。
    Jaccard = 13/25 = 0.52 < 0.8 -> 必须 skip, 不得喷 25 条。"""
    base = [
        {"dim_value_id": i, "name": f"食材{i}", "unit": "kg", "cost": 5000.0}
        for i in range(1, 14)
    ]
    cur = [
        {"dim_value_id": i, "name": f"食材{i}", "unit": "kg", "cost": 118000.0}
        for i in range(1, 26)
    ]
    out = _run(detect_share_spike(_spike_pool(cur, base), "MOCK_REST"))

    assert out["applicable"] is False, "食材名单从 13 变 25, 两期不可比"
    assert "名单不可比" in out["skip_reason"]
    assert "25" in out["skip_reason"] and "13" in out["skip_reason"]
    assert out["findings"] == []


def test_r1_uniform_scale_up_produces_no_findings():
    """全店一起涨 24 倍 (07-30 的幅度) 但名单不变 -> 份额不变 -> 0 条。
    这是份额归一化本身的作用, 与 Jaccard 闸无关。"""
    base = [
        {"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 100.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 300.0},
    ]
    cur = [
        {"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 2400.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 7200.0},
    ]
    out = _run(detect_share_spike(_spike_pool(cur, base), "MOCK_REST"))

    assert out["applicable"] is True
    assert out["findings"] == []


def test_r1_skips_when_baseline_history_too_short():
    cur = [{"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 100.0}]
    base = [{"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 50.0}]
    out = _run(detect_share_spike(_spike_pool(cur, base, base_days=6), "MOCK_REST"))

    assert out["applicable"] is False
    assert "历史不足" in out["skip_reason"]
    assert "6" in out["skip_reason"]


def test_r1_ingredient_without_baseline_is_dropped_not_infinite():
    """只在 cur 出现的食材没有基线, 不参与计算 —— 除零得不到「涨了无穷倍」。"""
    base = [
        {"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 500.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 500.0},
        {"dim_value_id": 3, "name": "虾", "unit": "kg", "cost": 500.0},
        {"dim_value_id": 4, "name": "蟹", "unit": "kg", "cost": 500.0},
    ]
    cur = base + [{"dim_value_id": 5, "name": "新食材", "unit": "kg", "cost": 9000.0}]
    out = _run(detect_share_spike(_spike_pool(cur, base), "MOCK_REST"))

    # Jaccard = 4/5 = 0.8 恰好过闸
    assert out["applicable"] is True
    assert "新食材" not in [f["subject_name"] for f in out["findings"]]


def test_r1_below_5pct_share_not_reported():
    """放大 10 倍但当前份额只有 1% —— 金额太小, 不值得占用提示位。"""
    cur = [
        {"dim_value_id": 1, "name": "红糖", "unit": "kg", "cost": 10.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 990.0},
    ]
    base = [
        {"dim_value_id": 1, "name": "红糖", "unit": "kg", "cost": 1.0},
        {"dim_value_id": 2, "name": "牛肉", "unit": "kg", "cost": 999.0},
    ]
    out = _run(detect_share_spike(_spike_pool(cur, base), "MOCK_REST"))

    assert out["findings"] == []


def test_r1_window_boundaries_are_exclusive_and_non_overlapping():
    cur = [{"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 100.0}]
    base = [{"dim_value_id": 1, "name": "鲈鱼", "unit": "kg", "cost": 50.0}]
    pool = _spike_pool(cur, base)

    _run(detect_share_spike(pool, "MOCK_REST"))

    cur_sql, base_sql = pool.conn.sqls[0], pool.conn.sqls[1]
    assert "date >= CURRENT_DATE" not in cur_sql
    assert "date >= CURRENT_DATE" not in base_sql
    # base 的新端 = cur 的旧端, 用 <= / > 拼接, 不重叠
    assert "date <= CURRENT_DATE - $3::int" in base_sql


# ── 注册与路由 ────────────────────────────────────────────────────────

def test_both_rules_importable_from_package_root():
    """gold_reads 从 smartbi.gold 导入 —— import 块和 __all__ 缺一不可。"""
    import smartbi.gold as g

    assert hasattr(g, "detect_share_spike")
    assert hasattr(g, "detect_type_concentration")
    assert "detect_share_spike" in g.__all__
    assert "detect_type_concentration" in g.__all__


def test_endpoint_registered_on_gold_router():
    import smartbi.api.gold_reads as gr

    paths = {r.path for r in gr.router.routes}
    assert "/gold/restaurant-wastage-findings" in paths
