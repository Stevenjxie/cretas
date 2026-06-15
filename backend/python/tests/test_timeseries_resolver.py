from __future__ import annotations

import pytest

from smartbi.services.timeseries_resolver import query_timeseries


class _FakeTransaction:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        self.conn.transaction_entered = True
        return self.conn

    async def __aexit__(self, *exc):
        self.conn.transaction_exited = True
        return False


class _FakeConn:
    def __init__(self, rows=None, *, raise_on_fetch=False, tenant_rows=None):
        self.rows = list(rows or [])
        self.raise_on_fetch = raise_on_fetch
        self.tenant_rows = tenant_rows
        self.executed = []
        self.fetch_sql = None
        self.fetch_args = None
        self.transaction_entered = False
        self.transaction_exited = False
        self.current_factory_id = None

    def transaction(self):
        return _FakeTransaction(self)

    async def execute(self, sql, *args):
        self.executed.append((sql, args))
        if "set_config" in sql and "app.factory_id" in sql:
            self.current_factory_id = args[0]
        return "SELECT 1"

    async def fetch(self, sql, *args):
        self.fetch_sql = sql
        self.fetch_args = args
        if self.raise_on_fetch:
            raise RuntimeError("simulated read failure")
        if self.tenant_rows is not None:
            return self.tenant_rows.get(self.current_factory_id, [])
        return self.rows


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


@pytest.fixture
def patch_pool(monkeypatch):
    def _patch(conn):
        pool = _FakePool(conn)

        async def fake_get_pg_pool():
            return pool

        import smartbi.config

        monkeypatch.setattr(smartbi.config, "get_pg_pool", fake_get_pg_pool)
        return pool

    return _patch


@pytest.mark.asyncio
async def test_multi_upload_fusion_returns_continuous_series(patch_pool):
    rows = [
        {
            "period": "2026-01",
            "canonical_field": "output_quantity",
            "dims": {"product": "A"},
            "value_num": 10.0,
        },
        {
            "period": "2026-02",
            "canonical_field": "output_quantity",
            "dims": {"product": "A"},
            "value_num": 22.0,
        },
        {
            "period": "2026-03",
            "canonical_field": "output_quantity",
            "dims": {"product": "A"},
            "value_num": 30.0,
        },
    ]
    conn = _FakeConn(rows)
    patch_pool(conn)

    result = await query_timeseries("F001", template_key="prod_v1", domain="production")

    assert result == rows
    assert conn.fetch_args == ("F001", "prod_v1", "production")
    assert "ORDER BY period" in conn.fetch_sql


@pytest.mark.asyncio
async def test_time_window_filter_uses_period_between(patch_pool):
    conn = _FakeConn([])
    patch_pool(conn)

    await query_timeseries("F001", start="2026-02", end="2026-04")

    assert "period BETWEEN $2 AND $3" in conn.fetch_sql
    assert conn.fetch_args == ("F001", "2026-02", "2026-04")


@pytest.mark.asyncio
async def test_domain_template_fields_and_dims_filters_are_bound(patch_pool):
    conn = _FakeConn([])
    patch_pool(conn)

    await query_timeseries(
        "F001",
        template_key="prod_v1",
        domain="production",
        canonical_fields=["output_quantity", "yield_rate"],
        dims_filter={"product": "A"},
    )

    assert "template_key = $2" in conn.fetch_sql
    assert "domain = $3" in conn.fetch_sql
    assert "canonical_field = ANY($4)" in conn.fetch_sql
    assert "dims @> $5::jsonb" in conn.fetch_sql
    assert conn.fetch_args == (
        "F001",
        "prod_v1",
        "production",
        ["output_quantity", "yield_rate"],
        '{"product":"A"}',
    )


@pytest.mark.asyncio
async def test_empty_table_returns_empty_list(patch_pool):
    conn = _FakeConn([])
    patch_pool(conn)

    assert await query_timeseries("F001") == []


@pytest.mark.asyncio
async def test_read_exception_fail_open_returns_empty_list(patch_pool):
    conn = _FakeConn(raise_on_fetch=True)
    patch_pool(conn)

    assert await query_timeseries("F001") == []


@pytest.mark.asyncio
async def test_sets_factory_guc_inside_transaction_before_fetch(patch_pool):
    conn = _FakeConn([])
    patch_pool(conn)

    await query_timeseries("F001")

    assert conn.transaction_entered is True
    assert conn.executed == [
        ("SELECT set_config('app.factory_id', $1, true)", ("F001",))
    ]
    assert conn.transaction_exited is True


@pytest.mark.asyncio
async def test_other_tenant_isolated_by_guc_returns_empty_list(patch_pool):
    conn = _FakeConn(
        tenant_rows={
            "F001": [
                {
                    "period": "2026-01",
                    "canonical_field": "output_quantity",
                    "dims": {},
                    "value_num": 10.0,
                }
            ],
            "F002": [],
        }
    )
    patch_pool(conn)

    assert await query_timeseries("F002") == []
    assert conn.executed == [
        ("SELECT set_config('app.factory_id', $1, true)", ("F002",))
    ]
    assert conn.fetch_args == ("F002",)
