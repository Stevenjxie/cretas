"""
Tests for TimeseriesExtractor (Phase 2 Task 2).

Contract: extract_timeseries() uses field_kind(standard_name) -> 'measure'|'dimension'|'time'|'skip'
to classify columns.  This replaces the old category_of(canonical) -> category-string approach which
relied on STANDARD_FIELDS and would silently drop identity-mapped columns like "产出数量" that have
no STANDARD_FIELDS entry but carry is_measure=True in smart_bi_pg_field_definitions.

Key regression: an identity-mapped is_measure column MUST produce value rows, never be dropped.
"""
import hashlib
import json


from smartbi.services.timeseries_extractor import extract_timeseries


# ---------------------------------------------------------------------------
# Helper: build field_kind callable from an explicit {standard_name -> kind} map
# ---------------------------------------------------------------------------
def make_kind(mapping: dict):
    """Return field_kind(standard_name) -> kind, defaulting to 'skip'."""
    return lambda standard_name: mapping.get(standard_name, "skip")


# ---------------------------------------------------------------------------
# Test 1: measure and dimension split (field_kind-based)
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
    kind = {
        "period": "time",
        "product": "dimension",
        "output_quantity": "measure",
        "yield_rate": "measure",
        "batch_number": "skip",
    }
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain="production",
        source_upload_id=7,
        period_of=lambda r: r["期间"],
        field_kind=make_kind(kind),
    )
    # Only measure fields produce rows; skip (batch_number) is discarded
    assert {r.canonical_field for r in out} == {"output_quantity", "yield_rate"}
    # product=dimension → goes into dims; period=time → axis, not in dims; batch_number=skip → gone
    assert all(r.dims == {"product": "A"} for r in out)
    assert all(r.period == "2026-01" and r.source_upload_id == 7 for r in out)
    # Both rows share the same dims_hash (same dims dict)
    assert out[0].dims_hash == out[1].dims_hash
    assert out[0].dims_hash  # non-empty


# ---------------------------------------------------------------------------
# Test 2: non-numeric measure value → row skipped
# ---------------------------------------------------------------------------
def test_non_numeric_measure_skipped():
    rows = [{"期间": "2026-01", "产出数量": ""}]
    fm = {"期间": "period", "产出数量": "output_quantity"}
    kind = {"period": "time", "output_quantity": "measure"}
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain=None,
        source_upload_id=1,
        period_of=lambda r: r["期间"],
        field_kind=make_kind(kind),
    )
    # Empty string is non-numeric → the measure row is not emitted
    assert out == []


# ---------------------------------------------------------------------------
# Test 3: row with no period → entire row skipped
# ---------------------------------------------------------------------------
def test_no_period_row_skipped():
    rows = [{"期间": None, "产出数量": 50}]
    fm = {"期间": "period", "产出数量": "output_quantity"}
    kind = {"period": "time", "output_quantity": "measure"}
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain=None,
        source_upload_id=2,
        period_of=lambda r: r["期间"],  # returns None → skip
        field_kind=make_kind(kind),
    )
    assert out == []


# ---------------------------------------------------------------------------
# Test 4: dims_hash is deterministic (same dims → same hash every call)
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
    kind = {
        "period": "time",
        "product": "dimension",
        "department": "dimension",
        "output_quantity": "measure",
    }
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain="production",
        source_upload_id=3,
        period_of=lambda r: r["期间"],
        field_kind=make_kind(kind),
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
        field_kind=make_kind(kind),
    )
    assert out2[0].dims_hash == expected_hash


# ---------------------------------------------------------------------------
# Test 5: list[FieldMapping] form normalises to same result as dict form
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
    kind = {"period": "time", "output_quantity": "measure"}

    common_kwargs = dict(
        factory_id="F2",
        template_key="tk2",
        domain="production",
        source_upload_id=4,
        period_of=lambda r: r["期间"],
        field_kind=make_kind(kind),
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
# Test 6: 'measure' kind for amount-like field produces value rows
# ---------------------------------------------------------------------------
def test_amount_kind_is_measure():
    rows = [{"期间": "2026-01", "收入": 1000.5}]
    fm = {"期间": "period", "收入": "revenue"}
    kind = {"period": "time", "revenue": "measure"}
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain="finance",
        source_upload_id=5,
        period_of=lambda r: r["期间"],
        field_kind=make_kind(kind),
    )
    assert len(out) == 1
    assert out[0].canonical_field == "revenue"
    assert out[0].value_num == 1000.5


# ---------------------------------------------------------------------------
# Test 7: falsy period string from period_of → skip row
# ---------------------------------------------------------------------------
def test_period_of_returning_falsy_string_skips_row():
    """period_of returning empty string (falsy) should skip the row."""
    rows = [{"期间": "", "产出数量": 99}]
    fm = {"期间": "period", "产出数量": "output_quantity"}
    kind = {"period": "time", "output_quantity": "measure"}
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain=None,
        source_upload_id=6,
        period_of=lambda r: r["期间"],  # returns "" → falsy
        field_kind=make_kind(kind),
    )
    assert out == []


# ---------------------------------------------------------------------------
# Test 8 (KEY REGRESSION): identity-mapped is_measure column must NOT be dropped
#
# Root cause of bug: mapper preserves original name as standard_name for
# amount/quantity columns to avoid canonical-name collisions (e.g. "产出数量"
# identity-maps to standard_name="产出数量").  STANDARD_FIELDS has no entry for
# "产出数量" → old category_of returned None → was not in _MEASURE_CATEGORIES →
# silently dropped.  field_kind reads is_measure=True from field_def_rows →
# returns 'measure' → correctly emits value row.
# ---------------------------------------------------------------------------
def test_identity_measure_column_not_dropped():
    """
    Column where standard_name == original_name (identity mapping) with field_kind='measure'
    MUST produce a value row, never be silently dropped.

    This is the primary regression this fix addresses: "产出数量" (output quantity)
    was the main production measure but was being discarded by the old STANDARD_FIELDS
    category lookup because STANDARD_FIELDS has no entry for the identity name.
    """
    # Simulate: mapper kept original name "产出数量" as standard_name (identity)
    # because renaming would collide.  field_def has is_measure=True for it.
    rows = [{"月份": "2026-05", "工序": "卤制", "产出数量": 150.0}]
    fm = {
        "月份": "time_period",   # is_time=True in field_def (non-"period" time axis)
        "工序": "process_name",  # is_dimension=True
        "产出数量": "产出数量",   # identity mapping — standard_name == original_name
    }
    kind = {
        "time_period": "time",
        "process_name": "dimension",
        "产出数量": "measure",   # built from is_measure=True in field_def_rows
    }
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F006",
        template_key="liushanmen_production",
        domain="production",
        source_upload_id=99,
        period_of=lambda r: r["月份"],
        field_kind=make_kind(kind),
    )
    # Must emit exactly one value row for the identity-named measure
    assert len(out) == 1, (
        f"Expected 1 value row for identity-mapped measure '产出数量', got {len(out)}. "
        "If 0: field_kind('产出数量') is not returning 'measure' — check _kind_map construction."
    )
    row = out[0]
    assert row.canonical_field == "产出数量"
    assert row.value_num == 150.0
    assert row.period == "2026-05"
    assert row.factory_id == "F006"
    assert row.source_upload_id == 99
    # "工序" is dimension → in dims; "月份" is time → NOT in dims
    assert row.dims == {"process_name": "卤制"}


# ---------------------------------------------------------------------------
# Test 9: field_kind='skip' (id-like column) → discarded, not in dims or measures
# ---------------------------------------------------------------------------
def test_skip_kind_discarded():
    """field_kind='skip' columns (e.g. IDs) must not appear in dims or produce value rows."""
    rows = [{"期间": "2026-01", "批次号": "B001", "产出数量": 80}]
    fm = {
        "期间": "period",
        "批次号": "batch_number",
        "产出数量": "output_quantity",
    }
    kind = {
        "period": "time",
        "batch_number": "skip",
        "output_quantity": "measure",
    }
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain="production",
        source_upload_id=10,
        period_of=lambda r: r["期间"],
        field_kind=make_kind(kind),
    )
    assert len(out) == 1
    assert out[0].canonical_field == "output_quantity"
    assert out[0].value_num == 80.0
    # batch_number must NOT appear in dims
    assert "batch_number" not in out[0].dims
    assert out[0].dims == {}


# ---------------------------------------------------------------------------
# Test 10: unknown standard_name defaults to 'skip' (field_kind returns 'skip')
# ---------------------------------------------------------------------------
def test_unknown_standard_name_defaults_to_skip():
    """field_kind for an unknown standard_name should default to 'skip' (not crash)."""
    rows = [{"期间": "2026-01", "未知字段": "some_value", "产出数量": 50}]
    fm = {
        "期间": "period",
        "未知字段": "unknown_col",
        "产出数量": "output_quantity",
    }
    # "unknown_col" not in kind map → make_kind returns 'skip' by default
    kind = {
        "period": "time",
        "output_quantity": "measure",
    }
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain=None,
        source_upload_id=11,
        period_of=lambda r: r["期间"],
        field_kind=make_kind(kind),
    )
    assert len(out) == 1
    assert out[0].canonical_field == "output_quantity"
    # unknown_col must not appear in dims
    assert "unknown_col" not in out[0].dims


# ===========================================================================
# Phase 2 real-data robustness tests (live production report findings)
# ===========================================================================

# ---------------------------------------------------------------------------
# _to_float coercion tests (unit-level, imported directly)
# ---------------------------------------------------------------------------
from smartbi.services.timeseries_extractor import _to_float, normalize_period


class TestToFloatCoercion:
    """Unit tests for _to_float measure-value coercion."""

    def test_plain_int(self):
        assert _to_float(100) == 100.0

    def test_plain_float(self):
        assert _to_float(3.14) == 3.14

    def test_plain_numeric_string(self):
        assert _to_float("19466") == 19466.0

    def test_percentage_string(self):
        """'97.85%' → 97.85 (value preserved, % stripped, not divided by 100)."""
        assert _to_float("97.85%") == 97.85

    def test_percentage_waste_rate(self):
        assert _to_float("2.15%") == 2.15

    def test_thousands_comma(self):
        assert _to_float("1,234.5") == 1234.5

    def test_thousands_comma_large(self):
        assert _to_float("1,000,000") == 1_000_000.0

    def test_currency_yen_prefix(self):
        assert _to_float("¥500") == 500.0

    def test_currency_dollar_prefix(self):
        assert _to_float("$1,200") == 1200.0

    def test_yuan_suffix(self):
        assert _to_float("500元") == 500.0

    def test_non_numeric_after_cleaning(self):
        """'abc' remains non-numeric after cleaning → None (measure skipped)."""
        assert _to_float("abc") is None

    def test_empty_string(self):
        assert _to_float("") is None

    def test_none_input(self):
        assert _to_float(None) is None

    def test_whitespace_only(self):
        assert _to_float("   ") is None

    def test_percentage_with_whitespace(self):
        assert _to_float("  97.85%  ") == 97.85


# ---------------------------------------------------------------------------
# normalize_period tests (unit-level)
# ---------------------------------------------------------------------------


class TestNormalizePeriod:
    """Unit tests for normalize_period helper."""

    # Chinese year-month formats
    def test_chinese_single_digit_month(self):
        assert normalize_period("2025年1月") == "2025-01"

    def test_chinese_double_digit_month(self):
        assert normalize_period("2025年12月") == "2025-12"

    def test_chinese_zero_padded_month(self):
        assert normalize_period("2025年01月") == "2025-01"

    def test_chinese_with_day_truncated_to_month(self):
        assert normalize_period("2025年1月15日") == "2025-01"

    # Non-zero-padded separator formats
    def test_slash_single_digit_month(self):
        assert normalize_period("2025/1") == "2025-01"

    def test_dot_single_digit_month(self):
        assert normalize_period("2025.1") == "2025-01"

    def test_dash_single_digit_month(self):
        assert normalize_period("2025-1") == "2025-01"

    # Already canonical — must pass through unchanged
    def test_already_iso_month(self):
        assert normalize_period("2025-01") == "2025-01"

    def test_already_iso_date(self):
        assert normalize_period("2025-01-15") == "2025-01-15"

    def test_already_iso_week(self):
        assert normalize_period("2025-W03") == "2025-W03"

    # Unparseable — safe no-op
    def test_unparseable_passthrough(self):
        assert normalize_period("Q1-2025") == "Q1-2025"

    def test_empty_string_passthrough(self):
        assert normalize_period("") == ""

    # Sort order regression: Jan through Dec should sort 01..12 after normalisation
    def test_sort_order_jan_to_dec(self):
        raw = [f"2025年{m}月" for m in range(1, 13)]
        normalised = [normalize_period(p) for p in raw]
        assert normalised == sorted(normalised), (
            f"Normalised periods should sort correctly: {normalised}"
        )
        assert normalised[0] == "2025-01"
        assert normalised[-1] == "2025-12"


# ---------------------------------------------------------------------------
# Integration: extract_timeseries with Chinese period + percent measure
# (mirrors 绿源食品 12月 production report structure)
# ---------------------------------------------------------------------------


def test_chinese_period_normalised_in_output():
    """'月份=2025年1月' via period_of → TimeseriesRow.period == '2025-01'."""
    rows = [{"月份": "2025年1月", "合格率": "97.85%", "产出数量": 19466}]
    fm = {
        "月份": "time_period",
        "合格率": "pass_rate",
        "产出数量": "output_quantity",
    }
    kind = {
        "time_period": "time",
        "pass_rate": "measure",
        "output_quantity": "measure",
    }
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F999",
        template_key="production_quality",
        domain="production",
        source_upload_id=42,
        period_of=lambda r: r["月份"],
        field_kind=make_kind(kind),
    )
    assert len(out) == 2, f"Expected 2 measure rows (pass_rate + output_quantity), got {len(out)}"
    by_field = {r.canonical_field: r for r in out}

    # Period must be normalised
    assert by_field["pass_rate"].period == "2025-01"
    assert by_field["output_quantity"].period == "2025-01"

    # pass_rate: "97.85%" → 97.85 (not dropped, not 0.9785)
    assert by_field["pass_rate"].value_num == 97.85

    # output_quantity: plain int
    assert by_field["output_quantity"].value_num == 19466.0


def test_percent_measure_emitted_not_dropped():
    """
    Regression: 合格率/废品率 with '%' suffix must be emitted as numeric measures,
    not silently dropped.  Before this fix they were non-numeric → skipped.
    """
    rows = [{"期间": "2025-12", "合格率": "97.85%", "废品率": "2.15%"}]
    fm = {"期间": "period", "合格率": "pass_rate", "废品率": "waste_rate"}
    kind = {"period": "time", "pass_rate": "measure", "waste_rate": "measure"}
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain="quality",
        source_upload_id=1,
        period_of=lambda r: r["期间"],
        field_kind=make_kind(kind),
    )
    assert len(out) == 2, f"Expected 2 rows, got {len(out)} — percent measures being dropped?"
    by_field = {r.canonical_field: r for r in out}
    assert by_field["pass_rate"].value_num == 97.85
    assert by_field["waste_rate"].value_num == 2.15


def test_period_deduplication_after_normalise():
    """
    Two rows with '2025年1月' and '2025-01' are the same period after normalisation.
    Both should produce rows with period == '2025-01', enabling downstream dedup.
    """
    rows = [
        {"月份": "2025年1月", "产出数量": 100},
        {"月份": "2025-01", "产出数量": 200},
    ]
    fm = {"月份": "time_period", "产出数量": "output_quantity"}
    kind = {"time_period": "time", "output_quantity": "measure"}
    out = extract_timeseries(
        rows,
        fm,
        factory_id="F1",
        template_key="tk",
        domain="production",
        source_upload_id=1,
        period_of=lambda r: r["月份"],
        field_kind=make_kind(kind),
    )
    assert len(out) == 2
    assert all(r.period == "2025-01" for r in out), (
        f"All rows should have period '2025-01', got: {[r.period for r in out]}"
    )
