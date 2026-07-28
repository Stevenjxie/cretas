"""Wave2 损耗按人/档口责任制 — sync_fact_wastage 透传 operator_id / section_code.

验证 ETL 从 cretas_db.wastage_records 读 operator_id + section_code,
并通过 UNNEST 传给 fact_restaurant_wastage 的 INSERT。
"""
import pytest
from unittest.mock import AsyncMock, MagicMock


def _make_pool_with_conn(conn):
    pool = MagicMock()
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = MagicMock(return_value=ctx)
    return pool


@pytest.mark.asyncio
async def test_sync_fact_wastage_passes_operator_and_section(monkeypatch):
    from smartbi.gold.restaurant import restaurant_ops_etl as etl

    src_rows = [
        {
            "id": "w-1", "wastage_number": "WST-20260601-001",
            "wastage_date": "2026-06-01", "type": "EXPIRED", "status": "APPROVED",
            "raw_material_type_id": "MAT-001", "quantity": 5.0, "unit": "kg",
            "estimated_cost": 120.0, "reason": "过期",
            "operator_id": 42, "section_code": "SEAFOOD",
        },
        {
            "id": "w-2", "wastage_number": "WST-20260601-002",
            "wastage_date": "2026-06-01", "type": "DAMAGED", "status": "APPROVED",
            "raw_material_type_id": "MAT-002", "quantity": 2.0, "unit": "kg",
            "estimated_cost": 30.0, "reason": None,
            "operator_id": None, "section_code": None,  # 未指定
        },
    ]

    cretas_conn = AsyncMock()
    cretas_conn.fetch = AsyncMock(return_value=src_rows)
    cretas_pool = _make_pool_with_conn(cretas_conn)

    smartbi_conn = AsyncMock()
    # INSERT ... RETURNING id → 2 rows
    smartbi_conn.fetch = AsyncMock(return_value=[{"id": 1}, {"id": 2}])
    # transaction() context manager
    tx = AsyncMock()
    tx.__aenter__ = AsyncMock(return_value=None)
    tx.__aexit__ = AsyncMock(return_value=None)
    smartbi_conn.transaction = MagicMock(return_value=tx)
    smartbi_pool = _make_pool_with_conn(smartbi_conn)

    # ingredient pk map: MAT-001 → 1001, MAT-002 → 1002
    monkeypatch.setattr(
        etl, "_get_ingredient_pk_map",
        AsyncMock(return_value={"MAT-001": 1001, "MAT-002": 1002}),
    )

    count = await etl.sync_fact_wastage(cretas_pool, smartbi_pool, "F006")

    assert count == 2

    # source SELECT must include operator_id + section_code
    select_sql = cretas_conn.fetch.call_args[0][0]
    assert "operator_id" in select_sql
    assert "section_code" in select_sql

    # INSERT args: positional after the SQL string
    insert_args = smartbi_conn.fetch.call_args[0]
    insert_sql = insert_args[0]
    assert "operator_id" in insert_sql
    assert "section_code" in insert_sql
    # operator_ids array (12th positional param, index 12 since args[0] is SQL, args[1] is factory_id)
    # args layout: sql, factory_id, source_pks, numbers, dates, wastage_types, statuses,
    #              ingredient_ids, quantities, units, est_costs, reasons, operator_ids, section_codes
    operator_ids = insert_args[12]
    section_codes = insert_args[13]
    assert operator_ids == [42, None]
    assert section_codes == ["SEAFOOD", None]


@pytest.mark.asyncio
async def test_sync_fact_wastage_empty_returns_zero(monkeypatch):
    from smartbi.gold.restaurant import restaurant_ops_etl as etl

    cretas_conn = AsyncMock()
    cretas_conn.fetch = AsyncMock(return_value=[])
    cretas_pool = _make_pool_with_conn(cretas_conn)
    smartbi_pool = AsyncMock()

    count = await etl.sync_fact_wastage(cretas_pool, smartbi_pool, "F006")
    assert count == 0
