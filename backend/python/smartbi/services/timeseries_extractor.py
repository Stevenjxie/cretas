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
import re
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
    """Try to convert value to float; return None if not numeric.

    Handles real-data robustness cases found in live production reports:
    - Trailing % suffix: "97.85%" → 97.85 (value is the rate itself, not divided by 100)
    - Thousands comma: "1,234.5" → 1234.5
    - Currency/unit wrappers: "¥500", "$500" (prefix), "500元" (suffix) → 500
    - Leading/trailing whitespace stripped.
    - Already int/float → returned as-is.
    - After cleaning, still non-numeric → return None (measure row silently skipped).
    """
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    s = str(value).strip()
    # Strip common currency prefixes
    s = s.lstrip("¥$€£")
    # Strip trailing % (percentage stored as the number itself, e.g. 97.85 not 0.9785)
    s = s.rstrip("%")
    # Strip trailing unit suffixes common in Chinese reports
    for suffix in ("元", "万元", "亿元", "kg", "KG", "g", "个", "件", "次", "吨", "t"):
        if s.endswith(suffix):
            s = s[: -len(suffix)]
            break
    # Remove thousands separators
    s = s.replace(",", "")
    s = s.strip()
    if not s:
        return None
    try:
        return float(s)
    except (ValueError, TypeError):
        return None


# _CHINESE_YEAR_MONTH matches "2025年1月" / "2025年01月" / "2025年1月15日"
_CHINESE_YEAR_MONTH_RE = re.compile(r"^(\d{4})年(\d{1,2})月")

# _SLASH_DOT_PERIOD matches "2025/1", "2025.1", "2025-1" (single-digit month without zero-pad)
_SLASH_DOT_PERIOD_RE = re.compile(r"^(\d{4})[/.\-](\d{1,2})$")


def normalize_period(raw: str) -> str:
    """Normalise a raw period string to a canonical form for timeseries storage.

    Designed for real production reports where period cells may carry Chinese
    year-month text or non-zero-padded formats that prevent correct string sort
    and cross-upload deduplication.

    Transformations (in priority order):
    1. Chinese  ``"2025年1月"`` / ``"2025年01月"`` → ``"2025-01"``
       Chinese  ``"2025年1月15日"``                → ``"2025-01"``  (month granularity)
    2. Slash/dot/dash: ``"2025/1"`` / ``"2025.1"`` / ``"2025-1"`` → ``"2025-01"``
       Note: ``"2025-01"`` (already zero-padded) passes through step 2 unchanged
       because the regex requires a single-digit month (``\\d{1,2}$``), but
       ``"2025-01"`` ends with two digits, so it does NOT match — it falls through
       to the passthrough rule and is returned as-is.
    3. ``"2025-01"`` / ``"2025-01-15"`` / ``"2025-W03"`` → returned as-is.
    4. Anything else that cannot be parsed → returned as-is (safe no-op).

    This helper is intentionally scoped to the timeseries extractor only.
    It does NOT modify ``_fmt_period`` in excel_async, which handles dynamic_data
    period storage (different scope, different constraints).
    """
    if not raw:
        return raw

    # 1. Chinese year-month (most specific — try first)
    m = _CHINESE_YEAR_MONTH_RE.match(raw)
    if m:
        year, month = m.group(1), m.group(2)
        return f"{year}-{int(month):02d}"

    # 2. Non-zero-padded month with separator (only matches if month < 10, single digit)
    m = _SLASH_DOT_PERIOD_RE.match(raw)
    if m:
        year, month = m.group(1), int(m.group(2))
        return f"{year}-{month:02d}"

    # 3 & 4. Already canonical or unparseable — return as-is
    return raw


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

    Real-data robustness (Phase 2, live report fixes):
    - Measure values with trailing ``%`` (e.g. ``"97.85%"``) are coerced to
      their numeric equivalent (97.85); thousands commas and common currency
      prefixes/unit suffixes are also stripped before float conversion.
    - Period strings from ``period_of`` are normalised via ``normalize_period``
      before storage, converting Chinese year-month text (``"2025年1月"``) and
      non-zero-padded formats (``"2025/1"``) to ``"2025-01"``.
    """
    fm_dict = _normalise_field_mappings(field_mappings)
    result: List[TimeseriesRow] = []

    for row in rows:
        # ── 1. Period check: skip entire row if no period ────────────────────
        period_raw = period_of(row)
        if not period_raw:
            continue
        period = normalize_period(period_raw)

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
