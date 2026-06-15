"""
TimeseriesExtractor — Phase 2 Task 2 (pure function, no DB).

Converts raw dynamic_data rows + field_mappings into typed TimeseriesRow objects
that are ready for upsert into smart_bi_timeseries.

Key rules (from spec §3):
  measure   = field_kind == 'measure'  → emits value row with value_num
  dimension = field_kind == 'dimension' → goes into dims dict
  time      = field_kind == 'time'      → axis or non-axis time; NOT added to dims
  skip      = field_kind == 'skip'      → discarded entirely

Classification is driven by ``field_kind(standard_name)`` which reads the
is_measure / is_dimension / is_time flags from smart_bi_pg_field_definitions —
NOT from STANDARD_FIELDS category strings.  This is essential because the mapper
may preserve identity names (e.g. "产出数量") for amount/quantity columns to
avoid canonical-name collisions, meaning STANDARD_FIELDS would return None for
those names and they would be silently dropped.

Non-numeric measure values are skipped (not emitted).
Rows where period_of() returns falsy are skipped entirely.

field_mappings supports two forms:
  dict[str, str]       {original_col -> standard_name}
  list[FieldMapping]   normalised internally to dict before processing
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Optional, Union

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
    """Normalise list[FieldMapping] or dict to a plain dict {orig → standard_name}."""
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


def extract_timeseries(
    rows: List[Dict[str, Any]],
    field_mappings: Union[Dict[str, str], List[FieldMapping]],
    *,
    factory_id: str,
    template_key: str,
    domain: Optional[str],
    source_upload_id: int,
    period_of: Callable[[Dict[str, Any]], Optional[str]],
    field_kind: Callable[[str], str],
) -> List["TimeseriesRow"]:
    """Convert raw rows + field_mappings into typed TimeseriesRow objects.

    Parameters
    ----------
    rows:
        Raw data rows from dynamic_data (list of dicts, original column names).
    field_mappings:
        Mapping from original column names to standard field names (standard_name
        from smart_bi_pg_field_definitions).  Accepts either a plain dict or a
        list of FieldMapping dataclasses.
    factory_id, template_key, domain, source_upload_id:
        Provenance fields written verbatim into each TimeseriesRow.
    period_of:
        Callable(row) -> period string (e.g. '2026-01').
        Return None or falsy to skip the entire row.
    field_kind:
        Callable(standard_name) -> kind string.
        Expected values: 'measure', 'dimension', 'time', 'skip'.
        Built from is_measure / is_dimension / is_time flags on
        smart_bi_pg_field_definitions — NOT from STANDARD_FIELDS category.
        This ensures identity-mapped columns (e.g. standard_name == original_name
        like "产出数量") are correctly classified by their DB flags even when
        STANDARD_FIELDS has no entry for them.

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
        measures: List[tuple] = []  # (standard_name, raw_value)

        for orig_col, standard_name in fm_dict.items():
            raw_value = row.get(orig_col)
            kind = field_kind(standard_name)

            if kind == "time":
                # Time/period columns are the axis or ancillary time fields —
                # not added to dims, not measures.
                continue

            elif kind == "measure":
                measures.append((standard_name, raw_value))

            elif kind == "dimension":
                # Collect into dims
                if raw_value is not None:
                    dims[standard_name] = raw_value

            # kind == 'skip' (id / unknown) → discard entirely

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
