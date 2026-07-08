from __future__ import annotations

from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

import smartbi.agent.synthesis_engine as se
from smartbi.agent.factbook import FactBook
from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine, compute_weather_attribution
from smartbi.gold.queries import weather_daily


class _Acquire:
    def __init__(self, conn: "_Conn") -> None:
        self._conn = conn

    async def __aenter__(self) -> "_Conn":
        return self._conn

    async def __aexit__(self, exc_type, exc, tb) -> None:
        return None


class _Pool:
    def __init__(self, rows: list[dict]) -> None:
        self.conn = _Conn(rows)

    def acquire(self) -> _Acquire:
        return _Acquire(self.conn)


class _Conn:
    def __init__(self, rows: list[dict]) -> None:
        self.rows = rows
        self.calls: list[tuple[str, list]] = []

    async def fetch(self, sql: str, *params):
        self.calls.append((sql, list(params)))
        return self.rows


def _migrations_dir() -> Path:
    return Path(__file__).resolve().parents[1] / "smartbi" / "database" / "migrations"


def test_weather_seed_migration_uses_locked_internal_source_and_formula():
    sql = (_migrations_dir() / "V20260709_03__demo_rest_weather_seed.sql").read_text(encoding="utf-8")

    assert "source_type IN ('official_stat', 'public_poi', 'industry_report', 'authorized_export', 'third_party', 'weather')" in sql
    assert "access_mode IN ('open_web', 'official_api', 'manual_upload', 'licensed_api', 'authorized_export', 'seed')" in sql
    assert "'internal_seed_weather','内部模拟天气','weather','seed','internal_seed'" in sql
    assert "SET app.factory_id = 'DEMO_REST';" in sql
    assert "metric_code, metric_name, metric_value, metric_unit" in sql
    assert "'rain_mm','日降水量'" in sql
    assert "(extract(doy from d)::int * 7 + 3) % 10 < 6 THEN 0" in sql
    assert "(extract(doy from d)::int * 7 + 3) % 10 < 9 THEN 5 + ((extract(doy from d)::int)%20)" in sql
    assert "ELSE 30 + ((extract(doy from d)::int)%50) END" in sql
    assert "'temp_c','日均气温'" in sql
    assert "FROM (SELECT DISTINCT date AS d FROM agg_daily WHERE factory_id='DEMO_REST') s" in sql
    assert "ON CONFLICT DO NOTHING;" in sql


@pytest.mark.asyncio
async def test_weather_daily_reads_seeded_rain_and_temperature_by_date():
    pool = _Pool([
        {"date": date(2026, 7, 1), "rain_mm": Decimal("0"), "temp_c": Decimal("27.5")},
        {"date": date(2026, 7, 2), "rain_mm": Decimal("12"), "temp_c": Decimal("28.0")},
    ])

    out = await weather_daily(pool, "DEMO_REST", (date(2026, 7, 1), date(2026, 7, 2)))

    sql, params = pool.conn.calls[0]
    assert "external_benchmark_observation" in sql
    assert "internal_seed_weather" in sql
    assert "rain_mm" in sql and "temp_c" in sql
    assert params == ["DEMO_REST", date(2026, 7, 1), date(2026, 7, 2)]
    assert out["days"] == [
        {"date": "2026-07-01", "rain_mm": 0.0, "temp_c": 27.5},
        {"date": "2026-07-02", "rain_mm": 12.0, "temp_c": 28.0},
    ]


def test_compute_weather_attribution_buckets_and_daily_average_identity():
    daily_rows = [
        {"date": "2026-07-01", "revenue": 100.0, "bill_count": 10, "avg_bill_value": 10.0},
        {"date": "2026-07-02", "revenue": 200.0, "bill_count": 20, "avg_bill_value": 10.0},
        {"date": "2026-07-03", "revenue": 80.0, "bill_count": 8, "avg_bill_value": 10.0},
        {"date": "2026-07-04", "revenue": 100.0, "bill_count": 10, "avg_bill_value": 10.0},
        {"date": "2026-07-05", "revenue": 50.0, "bill_count": 5, "avg_bill_value": 10.0},
    ]
    weather_rows = [
        {"date": "2026-07-01", "rain_mm": 0.0},
        {"date": "2026-07-02", "rain_mm": 0.0},
        {"date": "2026-07-03", "rain_mm": 5.0},
        {"date": "2026-07-04", "rain_mm": 24.0},
        {"date": "2026-07-05", "rain_mm": 30.0},
    ]

    out = compute_weather_attribution(daily_rows, weather_rows)
    buckets = {b["cond"]: b for b in out["buckets"]}

    assert buckets["晴天"]["n_days"] == 2
    assert buckets["晴天"]["avg_rev"] == 150.0
    assert buckets["雨天"]["n_days"] == 2
    assert buckets["雨天"]["avg_rev"] == 90.0
    assert buckets["暴雨"]["n_days"] == 1
    assert buckets["暴雨"]["avg_rev"] == 50.0
    assert buckets["暴雨"]["small_sample"] is True
    assert buckets["晴天"]["avg_rev"] == (100.0 + 200.0) / 2
    assert buckets["雨天"]["avg_bills"] == (8 + 10) / 2
    assert out["rain_vs_sunny_delta"] == -60.0
    assert out["rain_vs_sunny_pct"] == -40.0
    assert out["extreme_days"] == [{"date": "2026-07-05", "rain_mm": 30.0, "revenue": 50.0}]
    assert out["caveat"] == "天气对比为相关关系，不等于因果；小样本档需谨慎解读。"


def test_weather_plan_cue_requires_revenue_context_and_does_not_open_all():
    eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=object(), cache=object())

    weather_plan = eng.plan_dimensions("为啥这段生意差，跟天气有关吗")
    assert weather_plan["weather"] is True
    assert weather_plan["review"] is False
    assert weather_plan["sales"] is False

    neutral_plan = eng.plan_dimensions("哪天开会")
    assert neutral_plan["weather"] is False


def test_factbook_renders_weather_and_exposes_weather_fact_index():
    fb = FactBook(weather={
        "buckets": [
            {"cond": "晴天", "n_days": 2, "avg_rev": 150.0, "avg_bills": 15.0, "avg_ticket": 10.0},
            {"cond": "雨天", "n_days": 2, "avg_rev": 90.0, "avg_bills": 9.0, "avg_ticket": 10.0},
        ],
        "rain_vs_sunny_delta": -60.0,
        "rain_vs_sunny_pct": -40.0,
        "extreme_days": [],
        "caveat": "天气对比为相关关系，不等于因果；小样本档需谨慎解读。",
    })

    text = fb.to_prompt_text()
    idx = fb.to_facts_index()

    assert "雨天(2天)日均" in text
    assert "比晴天(2天)" in text
    assert "低40.0%" in text
    assert "相关关系，不等于因果" in text
    assert idx["雨天日均营收"] == 90.0
    assert idx["晴天日均营收"] == 150.0
    assert idx["天气影响率"] == -40.0


def test_build_factbook_pulls_weather_dimension(monkeypatch):
    async def fake_daily_trend(pool, fid, dr):
        return {"points": [
            {"date": "2026-07-01", "revenue": 100.0, "bill_count": 10, "avg_bill_value": 10.0},
            {"date": "2026-07-02", "revenue": 80.0, "bill_count": 8, "avg_bill_value": 10.0},
        ]}

    async def fake_weather_daily(pool, fid, dr):
        return {"days": [
            {"date": "2026-07-01", "rain_mm": 0.0, "temp_c": 28.0},
            {"date": "2026-07-02", "rain_mm": 12.0, "temp_c": 27.0},
        ]}

    monkeypatch.setattr(se, "daily_trend", fake_daily_trend)
    monkeypatch.setattr(se, "weather_daily", fake_weather_daily)

    eng = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=object(), cache=object())
    plan = {"review": False, "finance": False, "sales": False, "attribution": False, "weather": True, "cross": []}
    fb = __import__("asyncio").run(
        eng._build_factbook("DEMO_REST", (date(2026, 7, 1), date(2026, 7, 2)), plan, period="2026-07")
    )

    assert fb.weather is not None
    assert fb.weather["rain_vs_sunny_delta"] == -20.0
    assert "雨天日均营收" in fb.to_facts_index()
