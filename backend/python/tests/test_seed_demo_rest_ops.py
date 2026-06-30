from __future__ import annotations

from datetime import date

from smartbi.scripts.seed_demo_rest_ops import (
    _build_stocktaking_rows,
    _build_wastage_rows,
)


def test_demo_rest_ops_seed_builds_repeatable_30d_operational_rows():
    materials = [
        {"id": f"DR_rmt{i}", "name": f"食材{i}", "unit": "kg", "price": 10 + i}
        for i in range(1, 13)
    ]
    users = [1, 2, 3]
    end_day = date(2026, 6, 30)

    wastage = _build_wastage_rows(materials, users, end_day)
    stocktaking = _build_stocktaking_rows(materials, users, end_day)

    assert len(wastage) == 30
    assert len(stocktaking) == 20
    assert wastage[0][0] == "demo_rest_wst_20260630_0"
    assert wastage[-1][3] == date(2026, 6, 1)
    assert stocktaking[0][0] == "demo_rest_stk_20260630_0"
    assert stocktaking[-1][3] == date(2026, 6, 2)
    assert all(r[1] == "DEMO_REST" for r in wastage)
    assert all(r[1] == "DEMO_REST" for r in stocktaking)
