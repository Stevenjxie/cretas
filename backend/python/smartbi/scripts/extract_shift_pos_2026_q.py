"""One-off ops script — extract + time-shift real 2025 二维火 POS bills into
DEMO_REST's 2026-05/06/07 gap months.

Background
----------
DEMO_REST already carries real-shifted bill-grain data for 2026-01..04 (a
prior ingestion shifted 2025-01..04 -> 2026-01..04, +1 year, same month/day).
That leaves a gap: 2026-05 empty, 2026-06 only 9 rows, 2026-07 empty. This
script extends the SAME pattern for 2025-05/06/07 -> 2026-05/06/07 so the gap
closes with DERIVED-FROM-REAL data, not fabricated rows.

Source file: a real customer's (青花椒) 二维火 "订单销售明细表" export —
UTF-8 with BOM, 3 preamble rows (title / 门店名称 filter / 查询条件) before
the real header row (first cell == 门店名称). Fields include embedded
commas/newlines inside quoted values (esp. 整单备注) so this MUST be parsed
with a real CSV reader (csv module / pandas), never naive line-splitting.

This script only EXTRACTS + SHIFTS + WRITES an intermediate JSONL. It does
NOT touch any database. `load_billgrain_demo_rest.py` (sister script, NOT
run by this session — the organizer runs it against prod) reads the JSONL
and writes to Postgres via SilverNormalizer.ingest_rows (fact-table-only,
no Gold materialize — see that script's module docstring for why).

Usage:
    cd backend/python
    python -m smartbi.scripts.extract_shift_pos_2026_q \
        --zip-path "C:/Users/Steve/my-prototype-logistics/data/samples/20260205094218554_5f2f58da501_订单销售明细表.zip"

Output: smartbi/scripts/_demo_rest_billgrain_2026_q.jsonl (next to this file).
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import zipfile
from collections import Counter
from datetime import date, datetime
from pathlib import Path
from typing import Any, Dict, Iterator, Optional

# ─── Constants ──────────────────────────────────────────────────────────
_DEFAULT_ZIP_PATH = (
    r"C:/Users/Steve/my-prototype-logistics/data/samples/"
    r"20260205094218554_5f2f58da501_订单销售明细表.zip"
)
_OUTPUT_PATH = Path(__file__).resolve().parent / "_demo_rest_billgrain_2026_q.jsonl"

# 二维火 export prepends metadata rows before the real header; the real
# header row's first cell is one of these markers (mirrors
# smartbi/ingestion/pos_ingest.py:_detect_header_row's marker set).
_HEADER_MARKERS = ("门店名称", "门店", "店铺", "账单号", "订单号")

# Target source months (2025) -> shifted target months (2026), +1 year,
# same month/day. Extend this tuple if the gap grows (e.g. 2026-08 opens up).
_TARGET_SOURCE_MONTHS = ("2025-05", "2025-06", "2025-07")

# fact_pos_transaction.chk_meal_period allowed values (see
# smartbi/database/migrations/V20260513_01__qhj_revenue_silver_gold.sql:22-23).
_MEAL_PERIOD_ALLOWED = {
    "早餐", "午餐", "下午茶", "晚餐", "其他", "午市", "晚市", "未分类",
}
_MEAL_PERIOD_SHIFT_NORMALIZE = {
    # Mirrors scripts/backfill_silver.py's _MEAL_PERIOD_NORMALIZE — 二维火
    # sometimes uses operational shift labels instead of canonical buckets.
    "中班": "午市",
    "晚班": "晚市",
}

# Column -> canonical field, per smartbi/canonical/aliases.py + this task's
# explicit mapping. NOTE: we intentionally do NOT reuse
# scripts.backfill_silver._build_canonical_row's generic ALIAS_TO_ATTR walk
# here — see the loader's module docstring for why (収款金额/actual_receive
# alias-collision bug in aliases.py would silently drop actual_receive).
_COL_STORE_NAME = "门店名称"
_COL_DATE = "营业日期"
_COL_BILL_NO = "账单号"
_COL_GROSS = "营业额"
_COL_DISCOUNT = "折扣额"
_COL_NET = "实收额"
_COL_ACTUAL_RECEIVE = "收款金额"
_COL_CUSTOMER_COUNT = "客流量"
_COL_ORDER_TYPE = "订单类型"
_COL_CHANNEL_ORIGIN = "订单来源"
_COL_MEAL_PERIOD = "班次"


def _detect_header_row(reader: csv.reader) -> list:
    """Consume rows from `reader` until the real header row is found
    (first cell matches a known marker). Returns the header row (list of
    column names). Raises ValueError if no marker found in first 30 rows."""
    for i, row in enumerate(reader):
        if not row:
            continue
        first = row[0].strip().strip('"').strip()
        if first in _HEADER_MARKERS:
            return row
        if i > 30:
            break
    raise ValueError(
        f"could not find header row (looked for first cell in {_HEADER_MARKERS} "
        "within first 30 rows) — CSV structure differs from expected 二维火 shape"
    )


def _parse_float(raw: Optional[str]) -> Optional[float]:
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    try:
        return float(s.replace(",", "").replace("¥", ""))
    except ValueError:
        return None


def _parse_int_from_float_string(raw: Optional[str]) -> Optional[int]:
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    try:
        return int(float(s))
    except ValueError:
        return None


def _shift_date(d: date, years: int = 1) -> date:
    """Shift a date by `years`, same month/day. February 29 would need
    special handling but none of the target months (05/06/07) contain it."""
    return d.replace(year=d.year + years)


def _iter_target_rows(zip_path: Path) -> Iterator[Dict[str, Any]]:
    """Yield one dict per bill (raw column name -> raw string value) for
    rows whose 营业日期 falls in _TARGET_SOURCE_MONTHS. Streams the CSV —
    never loads the whole 200k-row file into memory at once."""
    with zipfile.ZipFile(zip_path) as z:
        members = z.namelist()
        if len(members) != 1:
            raise ValueError(
                f"expected exactly 1 CSV member in zip, found {len(members)}: {members}"
            )
        with z.open(members[0]) as fh:
            text_stream = io.TextIOWrapper(fh, encoding="utf-8-sig", newline="")
            reader = csv.reader(text_stream)
            header = _detect_header_row(reader)
            # Minimum columns needed to reach every field we read (实收额
            # is the rightmost, at index 36) — generous buffer against a
            # genuinely truncated/malformed row. dict(zip(...)) already
            # tolerates a row a few cells shorter than the header (trailing
            # empty-named column in the export), so this only catches rows
            # that are seriously broken.
            _MIN_COLS = 40
            for row in reader:
                if not row or len(row) < _MIN_COLS:
                    continue
                row_dict = dict(zip(header, row))
                date_raw = (row_dict.get(_COL_DATE) or "").strip()
                if date_raw[:7] not in _TARGET_SOURCE_MONTHS:
                    continue
                yield row_dict


def _build_shifted_record(row_dict: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Convert one raw row dict -> the narrow intermediate schema, with
    营业日期 shifted +1 year. Returns None if a required field is missing
    (store_name / source_bill_no / date) — mirrors backfill_silver.py's
    required-field skip semantics."""
    store_name = (row_dict.get(_COL_STORE_NAME) or "").strip()
    bill_no = (row_dict.get(_COL_BILL_NO) or "").strip()
    date_raw = (row_dict.get(_COL_DATE) or "").strip()

    if not store_name or not bill_no or not date_raw:
        return None

    try:
        orig_date = datetime.strptime(date_raw[:10], "%Y-%m-%d").date()
    except ValueError:
        return None
    shifted_date = _shift_date(orig_date, years=1)

    meal_period_raw = (row_dict.get(_COL_MEAL_PERIOD) or "").strip() or None
    if meal_period_raw:
        meal_period_raw = _MEAL_PERIOD_SHIFT_NORMALIZE.get(meal_period_raw, meal_period_raw)

    return {
        "store_name": store_name,
        "date": shifted_date.isoformat(),
        "source_bill_no": bill_no,
        "gross_amount": _parse_float(row_dict.get(_COL_GROSS)),
        "discount_amount": _parse_float(row_dict.get(_COL_DISCOUNT)),
        "net_amount": _parse_float(row_dict.get(_COL_NET)),
        "actual_receive": _parse_float(row_dict.get(_COL_ACTUAL_RECEIVE)),
        "customer_count": _parse_int_from_float_string(row_dict.get(_COL_CUSTOMER_COUNT)),
        "order_type": (row_dict.get(_COL_ORDER_TYPE) or "").strip() or None,
        "channel_origin": (row_dict.get(_COL_CHANNEL_ORIGIN) or "").strip() or None,
        "meal_period_raw": meal_period_raw,
    }


def run(zip_path: Path, output_path: Path) -> None:
    month_counts: Counter = Counter()
    store_counts: Counter = Counter()
    meal_period_outside_check: Counter = Counter()
    skipped_missing_required = 0
    total_written = 0
    customer_count_present = 0
    n_seen = 0

    with output_path.open("w", encoding="utf-8") as out_f:
        for row_dict in _iter_target_rows(zip_path):
            n_seen += 1
            record = _build_shifted_record(row_dict)
            if record is None:
                skipped_missing_required += 1
                continue
            if record["meal_period_raw"] and record["meal_period_raw"] not in _MEAL_PERIOD_ALLOWED:
                meal_period_outside_check[record["meal_period_raw"]] += 1
                record["meal_period_raw"] = "未分类"
            month_counts[record["date"][:7]] += 1
            store_counts[record["store_name"]] += 1
            if record["customer_count"] is not None:
                customer_count_present += 1
            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")
            total_written += 1

    # ── Summary ─────────────────────────────────────────────────────
    print(f"source zip:            {zip_path}")
    print(f"output jsonl:          {output_path}")
    print(f"rows scanned (target months, pre-filter): {n_seen}")
    print(f"rows written:          {total_written}")
    print(f"rows skipped (missing required field):     {skipped_missing_required}")
    print()
    print("per-month counts (shifted, 2026):")
    for k in sorted(month_counts):
        print(f"  {k}: {month_counts[k]}")
    print()
    print(f"distinct stores:       {len(store_counts)}")
    for store, cnt in store_counts.most_common():
        print(f"  {store}: {cnt}")
    print()
    pct = (customer_count_present / total_written * 100) if total_written else 0.0
    print(f"customer_count present: {customer_count_present}/{total_written} ({pct:.1f}%)")
    if meal_period_outside_check:
        print()
        print("⚠️  meal_period values OUTSIDE chk_meal_period allowed set "
              "(mapped to 未分类):")
        for k, v in meal_period_outside_check.most_common():
            print(f"  {k!r}: {v}")
    else:
        print()
        print("meal_period: all values already within chk_meal_period allowed set "
              "(no 未分类 fallback needed).")

    print()
    print("sample rows:")
    with output_path.open("r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i >= 3:
                break
            print(f"  {line.strip()}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--zip-path", default=_DEFAULT_ZIP_PATH, help="path to the 二维火 export zip")
    ap.add_argument("--output", default=str(_OUTPUT_PATH), help="output JSONL path")
    args = ap.parse_args()

    zip_path = Path(args.zip_path)
    if not zip_path.exists():
        raise SystemExit(f"zip not found: {zip_path}")

    run(zip_path, Path(args.output))


if __name__ == "__main__":
    main()
