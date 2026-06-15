"""
TimeseriesExtractor — Phase 2 Task 2 (pure function, no DB).

Converts raw dynamic_data rows + field_mappings into typed TimeseriesRow objects
that are ready for upsert into smart_bi_timeseries.

Key rules (from spec §3):
  measure   = category ∈ {amount, rate, quantity}  → emits value row with value_num
  dimension = category == 'category' OR canonical is a named dimension
              → goes into dims dict
  period    = canonical == 'period'                 → axis; NOT added to dims
  id        = category == 'id'                      → discarded entirely

Non-numeric measure values are skipped (not emitted).
Rows where period_of() returns falsy are skipped entirely.

field_mappings supports two forms:
  dict[str, str]       {original_col -> canonical}
  list[FieldMapping]   normalised internally to dict before processing
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Union

# ---------------------------------------------------------------------------
# Named dimension canonicals that always go into dims regardless of category
# (spec §3: "明确维度 canonical")
# ---------------------------------------------------------------------------
_DIM_CANONICALS = frozenset(
    {
        "department",
        "product",
        "region",
        "customer_name",
        "supplier_name",
        "material_name",
        "warehouse",
    }
)

# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------


@dataclass
class FieldMapping:
    """Simple container for list-form field mapping entries."""

    original_col: str
    canonical: str


@dataclass
class TimeseriesRow:
    """Typed output row, ready for upsert into smart_bi_timeseries."""

    factory_id: str
    template_key: str
    domain: Optional[str]
    period: str
    canonical_field: str
    value_num: Optional[float]
    dims: Dict[str, Any]
    dims_hash: str
    source_upload_id: int


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _normalise_field_mappings(
    field_mappings: Union[Dict[str, str], List[FieldMapping]]
) -> Dict[str, str]:
    """Normalise list[FieldMapping] or dict to a plain dict {orig → canonical}."""
    if isinstance(field_mappings, dict):
        return field_mappings
    result: Dict[str, str] = {}
    for fm in field_mappings:
        result[fm.original_col] = fm.canonical
    return result


def _compute_dims_hash(dims: Dict[str, Any]) -> str:
    """Deterministic sha256 of dims dict, sorted keys, UTF-8."""
    serialised = json.dumps(dims, sort_keys=True, ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(serialised).hexdigest()


def _to_float(value: Any) -> Optional[float]:
    """Try to convert value to float; return None if not numeric."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    try:
        return float(str(value))
    except (ValueError, TypeError):
        return None


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

_MEASURE_CATEGORIES = frozenset({"amount", "rate", "quantity"})


def extract_timeseries(
    rows: List[Dict[str, Any]],
    field_mappings: Union[Dict[str, str], List[FieldMapping]],
    *,
    factory_id: str,
    template_key: str,
    domain: Optional[str],
    source_upload_id: int,
    period_of: Callable[[Dict[str, Any]], Optional[str]],
    category_of: Callable[[str], str],
) -> List[TimeseriesRow]:
    """Convert raw rows + field_mappings into typed TimeseriesRow objects.

    Parameters
    ----------
    rows:
        Raw data rows from dynamic_data (list of dicts, original column names).
    field_mappings:
        Mapping from original column names to canonical field names.
        Accepts either a plain dict or a list of FieldMapping dataclasses.
    factory_id, template_key, domain, source_upload_id:
        Provenance fields written verbatim into each TimeseriesRow.
    period_of:
        Callable(row) -> period string (e.g. '2026-01').
        Return None or falsy to skip the entire row.
    category_of:
        Callable(canonical_field) -> category string.
        Expected values: 'amount', 'rate', 'quantity', 'category', 'time', 'id'.

    Returns
    -------
    list[TimeseriesRow]
        One row per (input_row × measure_field).  Rows with no valid period
        are skipped.  Non-numeric measure values are skipped.
    """
    fm_dict = _normalise_field_mappings(field_mappings)
    result: List[TimeseriesRow] = []

    for row in rows:
        # ── 1. Period check: skip entire row if no period ────────────────────
        period = period_of(row)
        if not period:
            continue

        # ── 2. Classify each mapped column ───────────────────────────────────
        dims: Dict[str, Any] = {}
        measures: List[tuple[str, Any]] = []  # (canonical, raw_value)

        for orig_col, canonical in fm_dict.items():
            raw_value = row.get(orig_col)
            category = category_of(canonical)

            if canonical == "period":
                # Time axis — not added to dims, not a measure
                continue

            if category in _MEASURE_CATEGORIES:
                measures.append((canonical, raw_value))

            elif category == "category" or canonical in _DIM_CANONICALS:
                # Dimension: collect into dims
                if raw_value is not None:
                    dims[canonical] = raw_value

            elif category == "id":
                # Discard entirely
                continue

            # Other time-category canonicals (production_date, sales_date, …)
            # are not axis and not measure → treat as dimension in dims
            # (spec only says 'period' canonical is the axis)
            elif category == "time":
                # non-period time canonicals go into dims
                if raw_value is not None:
                    dims[canonical] = raw_value

        # ── 3. Compute dims_hash once per row ────────────────────────────────
        dims_hash = _compute_dims_hash(dims)

        # ── 4. Emit one TimeseriesRow per valid measure ───────────────────────
        for canonical_field, raw_value in measures:
            value_num = _to_float(raw_value)
            if value_num is None:
                # Non-numeric measure → skip this measure row
                continue

            result.append(
                TimeseriesRow(
                    factory_id=factory_id,
                    template_key=template_key,
                    domain=domain,
                    period=period,
                    canonical_field=canonical_field,
                    value_num=value_num,
                    dims=dims,
                    dims_hash=dims_hash,
                    source_upload_id=source_upload_id,
                )
            )

    return result
