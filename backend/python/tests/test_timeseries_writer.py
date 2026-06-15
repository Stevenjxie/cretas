from __future__ import annotations

import pytest

from smartbi.services.timeseries_extractor import TimeseriesRow
from smartbi.services.timeseries_writer import write_timeseries

pytestmark = pytest.mark.asyncio


class _FakeTx:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        self.conn.transaction_entered += 1
        return self.conn

    async def __aexit__(self, *exc):
        self.conn.transaction_exited += 1
        return False


class _FakeAcquire:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, *exc):
        return False


class _FakePool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return _FakeAcquire(self.conn)


class _FakeConn:
    def __init__(self):
        self.rows: list[dict] = []
        self.executed: list[tuple[str, tuple]] = []
        self.fetchval_calls: list[tuple[str, tuple]] = []
        self.executemany_calls: list[tuple[str, list[tuple]]] = []
        self.transaction_entered = 0
        self.transaction_exited = 0

    def transaction(self):
        return _FakeTx(self)

    async def fetchval(self, sql, *args):
        self.fetchval_calls.append((sql, args))
        factory_id, template_key, periods = args
        matches = [
            row["source_upload_id"]
            for row in self.rows
            if row["factory_id"] == factory_id
            and row["template_key"] == template_key
            and row["period"] in set(periods)
        ]
        return max(matches) if matches else None

    async def execute(self, sql, *args):
        self.executed.append((sql, args))
        if "DELETE FROM smart_bi_timeseries" not in sql:
            return "OK"

        factory_id, template_key, periods = args
        periods = set(periods)
        before = len(self.rows)
        self.rows = [
            row
            for row in self.rows
            if not (
                row["factory_id"] == factory_id
                and row["template_key"] == template_key
                and row["period"] in periods
            )
        ]
        return f"DELETE {before - len(self.rows)}"

    async def executemany(self, sql, records):
        records = list(records)
        self.executemany_calls.append((sql, records))
        for record in records:
            (
                factory_id,
                template_key,
                domain,
                period,
                canonical_field,
                value_num,
                dims,
                dims_hash,
                source_upload_id,
            ) = record
            self.rows.append(
                {
                    "factory_id": factory_id,
                    "template_key": template_key,
                    "domain": domain,
                    "period": period,
                    "canonical_field": canonical_field,
                    "value_num": value_num,
                    "dims": dims,
                    "dims_hash": dims_hash,
                    "source_upload_id": source_upload_id,
                }
            )


def _row(period: str, upload_id: int, value: float, field: str = "output_quantity"):
    return TimeseriesRow(
        factory_id="F1",
        template_key="tk1",
        domain="production",
        period=period,
        canonical_field=field,
        value_num=value,
        dims={"product": "A"},
        dims_hash=f"hash-{period}-{field}",
        source_upload_id=upload_id,
    )


async def _patch_pool(monkeypatch, conn):
    async def fake_get_pg_pool():
        return _FakePool(conn)

    import smartbi.config

    monkeypatch.setattr(smartbi.config, "get_pg_pool", fake_get_pg_pool)


async def test_per_period_replace_newest_wins(monkeypatch):
    conn = _FakeConn()
    await _patch_pool(monkeypatch, conn)

    first = [_row("2026-01", 1, 10), _row("2026-02", 1, 20)]
    assert await write_timeseries("F1", "tk1", first, 1) == 2

    second = [_row("2026-02", 2, 200), _row("2026-03", 2, 300)]
    assert await write_timeseries("F1", "tk1", second, 2) == 2

    by_period = {row["period"]: row for row in conn.rows}
    assert set(by_period) == {"2026-01", "2026-02", "2026-03"}
    assert by_period["2026-01"]["source_upload_id"] == 1
    assert by_period["2026-02"]["source_upload_id"] == 2
    assert by_period["2026-02"]["value_num"] == 200
    assert by_period["2026-03"]["source_upload_id"] == 2


async def test_older_upload_skipped(monkeypatch):
    conn = _FakeConn()
    await _patch_pool(monkeypatch, conn)

    assert await write_timeseries("F1", "tk1", [_row("2026-02", 5, 500)], 5) == 1
    assert await write_timeseries("F1", "tk1", [_row("2026-02", 3, 300)], 3) == 0

    assert len(conn.rows) == 1
    assert conn.rows[0]["period"] == "2026-02"
    assert conn.rows[0]["source_upload_id"] == 5
    assert conn.rows[0]["value_num"] == 500


async def test_guc_set_config_called_inside_transaction(monkeypatch):
    conn = _FakeConn()
    await _patch_pool(monkeypatch, conn)

    assert await write_timeseries("F1", "tk1", [_row("2026-01", 1, 10)], 1) == 1

    set_config_calls = [
        args for sql, args in conn.executed if "set_config('app.factory_id'" in sql
    ]
    assert set_config_calls == [("F1",)]
    assert conn.transaction_entered == 1
    assert conn.transaction_exited == 1
