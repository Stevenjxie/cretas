"""
Tests for TimeseriesExtractor (Phase 2 Task 2).

TDD: these tests are written first. They define the contract for extract_timeseries().
"""
import hashlib
import json

import pytest

from smartbi.services.timeseries_extractor import TimeseriesRow, extract_timeseries


# ---------------------------------------------------------------------------
# Helper: build category_of from an explicit mapping dict
# ---------------------------------------------------------------------------
def make_cat(mapping: dict):
    return lambda canonical: mapping[canonical]


# ---------------------------------------------------------------------------
# Plan-specified Test 1: measure and dimension split
# ---------------------------------------------------------------------------
def test_measure_and_dim_split():
    rows = [{"期间": "2026-01", "产品": "A", "产出数量": 100, "出成率": 0.9, "批次号": "B1"}]
    fm = {
        "期间": "period",
        "产品": "product",
        "产出数量": "output_quantity",
        "出成率": "yield_rate",
        "批次号": "batch_number",
    }
    cat = {
        "period": "time",
        "product": "category",
        "output_quantity": "quantity",
        "yield_rate": "rate",
        "batch_number": "id",
    }
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain="production",
        source_upload_id=7,
        period_of=lambda r: r["期间"],
        category_of=make_cat(cat),
    )
    # Only measure canonicals (quantity + rate) should produce rows; id is discarded
    assert {r.canonical_field for r in out} == {"output_quantity", "yield_rate"}
    # product=category → goes into dims; period=time → axis not in dims; batch_number=id → discarded
    assert all(r.dims == {"product": "A"} for r in out)
    assert all(r.period == "2026-01" and r.source_upload_id == 7 for r in out)
    # Both rows share the same dims_hash (same dims dict)
    assert out[0].dims_hash == out[1].dims_hash
    assert out[0].dims_hash  # non-empty


# ---------------------------------------------------------------------------
# Plan-specified Test 2: non-numeric measure value → row skipped
# ---------------------------------------------------------------------------
def test_non_numeric_measure_skipped():
    rows = [{"期间": "2026-01", "产出数量": ""}]
    fm = {"期间": "period", "产出数量": "output_quantity"}
    cat = {"period": "time", "output_quantity": "quantity"}
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain=None,
        source_upload_id=1,
        period_of=lambda r: r["期间"],
        category_of=make_cat(cat),
    )
    # Empty string is non-numeric → the measure row is not emitted
    assert out == []


# ---------------------------------------------------------------------------
# Plan-specified Test 3: row with no period → entire row skipped
# ---------------------------------------------------------------------------
def test_no_period_row_skipped():
    rows = [{"期间": None, "产出数量": 50}]
    fm = {"期间": "period", "产出数量": "output_quantity"}
    cat = {"period": "time", "output_quantity": "quantity"}
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain=None,
        source_upload_id=2,
        period_of=lambda r: r["期间"],  # returns None → skip
        category_of=make_cat(cat),
    )
    assert out == []


# ---------------------------------------------------------------------------
# Extra Test 4: dims_hash is deterministic (same dims → same hash every call)
# ---------------------------------------------------------------------------
def test_dims_hash_deterministic():
    dims = {"product": "猪蹄", "department": "生产部"}
    expected_hash = hashlib.sha256(
        json.dumps(dims, sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()

    rows = [{"期间": "2026-01", "产品": "猪蹄", "部门": "生产部", "产出数量": 100}]
    fm = {
        "期间": "period",
        "产品": "product",
        "部门": "department",
        "产出数量": "output_quantity",
    }
    cat = {
        "period": "time",
        "product": "category",
        "department": "category",
        "output_quantity": "quantity",
    }
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain="production",
        source_upload_id=3,
        period_of=lambda r: r["期间"],
        category_of=make_cat(cat),
    )
    assert len(out) == 1
    assert out[0].dims_hash == expected_hash

    # Call again — must produce same hash
    out2 = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain="production",
        source_upload_id=3,
        period_of=lambda r: r["期间"],
        category_of=make_cat(cat),
    )
    assert out2[0].dims_hash == expected_hash


# ---------------------------------------------------------------------------
# Extra Test 5: list[FieldMapping] form normalises to same result as dict form
# ---------------------------------------------------------------------------
def test_field_mappings_list_form():
    """field_mappings as list[FieldMapping] must produce same output as dict form."""
    from smartbi.services.timeseries_extractor import FieldMapping

    rows = [{"期间": "2026-02", "产出数量": 200}]
    fm_dict = {"期间": "period", "产出数量": "output_quantity"}
    fm_list = [
        FieldMapping(original_col="期间", canonical="period"),
        FieldMapping(original_col="产出数量", canonical="output_quantity"),
    ]
    cat = {"period": "time", "output_quantity": "quantity"}

    common_kwargs = dict(
        factory_id="F2",
        template_key="tk2",
        domain="production",
        source_upload_id=4,
        period_of=lambda r: r["期间"],
        category_of=make_cat(cat),
    )

    out_dict = extract_timeseries(rows, fm_dict, **common_kwargs)
    out_list = extract_timeseries(rows, fm_list, **common_kwargs)

    assert len(out_dict) == 1
    assert len(out_list) == 1
    assert out_dict[0].canonical_field == out_list[0].canonical_field == "output_quantity"
    assert out_dict[0].value_num == out_list[0].value_num == 200.0
    assert out_dict[0].period == out_list[0].period == "2026-02"
    assert out_dict[0].dims_hash == out_list[0].dims_hash


# ---------------------------------------------------------------------------
# Extra Test 6: amount category also produces measure rows
# ---------------------------------------------------------------------------
def test_amount_category_is_measure():
    rows = [{"期间": "2026-01", "收入": 1000.5}]
    fm = {"期间": "period", "收入": "revenue"}
    cat = {"period": "time", "revenue": "amount"}
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain="finance",
        source_upload_id=5,
        period_of=lambda r: r["期间"],
        category_of=make_cat(cat),
    )
    assert len(out) == 1
    assert out[0].canonical_field == "revenue"
    assert out[0].value_num == 1000.5


# ---------------------------------------------------------------------------
# Extra Test 7: unknown/None period string from period_of → skip row
# ---------------------------------------------------------------------------
def test_period_of_returning_falsy_string_skips_row():
    """period_of returning empty string (falsy) should skip the row."""
    rows = [{"期间": "", "产出数量": 99}]
    fm = {"期间": "period", "产出数量": "output_quantity"}
    cat = {"period": "time", "output_quantity": "quantity"}
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain=None,
        source_upload_id=6,
        period_of=lambda r: r["期间"],  # returns "" → falsy
        category_of=make_cat(cat),
    )
    assert out == []
