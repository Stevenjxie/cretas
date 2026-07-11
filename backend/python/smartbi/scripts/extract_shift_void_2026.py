"""One-off ops script — extract + time-shift real 2025 二维火 撤单报表
(order-void report) rows into DEMO_REST's 2026-01..07 window.

Background
----------
New restaurant analytics dimension: 撤单率 / 撤单稽核 (order void rate + staff
audit) — greenfield, zero prior void handling in this codebase. DEMO_REST
already carries real-shifted bill-grain data for 2026-01..07 (see
extract_shift_pos_2026_q.py's sister loader, load_billgrain_demo_rest.py).
This script closes the matching void-report gap for the SAME window with
DERIVED-FROM-REAL data (not fabricated rows), using the SAME +1 year,
same-month/day shift pattern.

Source file: a real customer's (青花椒) 二维火 "撤单报表" export — UTF-8 with
BOM, 3 preamble rows (title / 门店名称 filter / 查询条件) before the real
header row (first cell == 门店名称). Columns verified against the real file
(6264 data rows): 门店名称/账单号/桌位/开单时间/撤单时间/撤单时间差(分钟)/
撤单原因/操作人.

Column data-quality notes (from the real export, informs the loader's
idempotency design — see V20261005_01__fact_pos_void.sql):
  - 账单号 (bill_no):  blank in 1113/6264 rows (~17.8%)
  - 桌位 (table_no):    NEVER blank (doubles as delivery-channel label:
                        京东外卖/美团外卖/饿了么/... for delivery orders)
  - 开单时间/撤单时间:  NEVER blank
  - 撤单原因 (reason): blank in 4096/6264 rows (~65%) — honest, not
                        fabricated; POS staff frequently skip this field
  - 操作人 (staff):    blank in only 20/6264 rows (~0.3%)

This script only EXTRACTS + SHIFTS + WRITES an intermediate JSONL. It does
NOT touch any database. `load_void_demo_rest.py` (sister script, NOT run by
this session — the organizer runs it against prod) reads the JSONL and
writes to Postgres directly via DimResolver + raw asyncpg INSERT into
fact_pos_void (NOT SilverNormalizer — that class's CanonicalRow is shaped
for fact_pos_transaction, not void events). See that script's module
docstring for why it does NOT call the Gold materializer.

Usage:
    cd backend/python
    python -m smartbi.scripts.extract_shift_void_2026 \
        --zip-path "C:/path/to/20260422103139771_35a97db7261_撤单报表.zip"

Output: smartbi/scripts/_demo_rest_void_2026.jsonl (next to this file).
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
    r"C:/Users/Steve/my-prototype-logistics/smartbi维度分析/大众点评/"
    r"真实餐饮连锁数据/青花椒25年/青花椒25年/"
    r"20260422103139771_35a97db7261_撤单报表.zip"
)
_OUTPUT_PATH = Path(__file__).resolve().parent / "_demo_rest_void_2026.jsonl"

# 二维火 export prepends metadata rows before the real header; the real
# header row's first cell is one of these markers (mirrors
# smartbi/ingestion/pos_ingest.py:_detect_header_row's marker set, and
# extract_shift_pos_2026_q.py's _HEADER_MARKERS).
_HEADER_MARKERS = ("门店名称", "门店", "店铺", "账单号", "订单号")

# Target source months (2025) -> shifted target months (2026), +1 year,
# same month/day. Matches DEMO_REST's existing bill-grain window
# (2026-01..07, see extract_shift_pos_2026_q.py). Filtered on 撤单时间
# (void_at) — that's the column agg_daily_void buckets by (the operational
# void-event date), not 开单时间.
_TARGET_SOURCE_MONTHS = (
    "2025-01", "2025-02", "2025-03", "2025-04",
    "2025-05", "2025-06", "2025-07",
)

_COL_STORE_NAME = "门店名称"
_COL_BILL_NO = "账单号"
_COL_TABLE_NO = "桌位"
_COL_ORDER_OPEN_AT = "开单时间"
_COL_VOID_AT = "撤单时间"
_COL_VOID_DIFF_MINUTES = "撤单时间差(分钟)"
_COL_VOID_REASON = "撤单原因"
_COL_STAFF_NAME = "操作人"

_MIN_COLS = 8  # 门店名称..操作人 (index 0..7)


def _detect_header_row(reader: "csv._reader") -> list:
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


def _parse_datetime(raw: Optional[str]) -> Optional[datetime]:
    """开单时间 has seconds ('2025-05-19 11:55:47'); 撤单时间 often doesn't
    ('2025-05-20 10:56'). Try both formats."""
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            return datetime.strptime(s, fmt)
        except ValueError:
            continue
    return None


def _parse_float(raw: Optional[str]) -> Optional[float]:
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    try:
        return float(s.replace(",", ""))
    except ValueError:
        return None


def _shift_datetime(dt: datetime, years: int = 1) -> datetime:
    """Shift a datetime by `years`, same month/day/time. None of the target
    months (01-07) contain Feb 29 boundary issues relevant to this shift
    (2025 -> 2026, neither is itself Feb 29 source data since 2025 is not
    a leap year)."""
    return dt.replace(year=dt.year + years)


def _iter_target_rows(zip_path: Path) -> Iterator[Dict[str, Any]]:
    """Yield one dict per void event (raw column name -> raw string value)
    for rows whose 撤单时间 falls in _TARGET_SOURCE_MONTHS. Streams the CSV
    — never loads the whole file into memory at once."""
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
            for row in reader:
                if not row or len(row) < _MIN_COLS:
                    continue
                row_dict = dict(zip(header, row))
                void_at_raw = (row_dict.get(_COL_VOID_AT) or "").strip()
                if void_at_raw[:7] not in _TARGET_SOURCE_MONTHS:
                    continue
                yield row_dict


def _build_shifted_record(row_dict: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Convert one raw row dict -> the narrow intermediate schema, with
    开单时间/撤单时间 both shifted +1 year. Returns None if a required field
    is missing (store_name / order_open_at / void_at)."""
    store_name = (row_dict.get(_COL_STORE_NAME) or "").strip()
    bill_no = (row_dict.get(_COL_BILL_NO) or "").strip()
    table_no = (row_dict.get(_COL_TABLE_NO) or "").strip()

    order_open_dt = _parse_datetime(row_dict.get(_COL_ORDER_OPEN_AT))
    void_dt = _parse_datetime(row_dict.get(_COL_VOID_AT))

    if not store_name or order_open_dt is None or void_dt is None:
        return None

    shifted_open = _shift_datetime(order_open_dt, years=1)
    shifted_void = _shift_datetime(void_dt, years=1)

    return {
        "store_name": store_name,
        "bill_no": bill_no,       # '' preserved as '' (NOT None) — see fact_pos_void migration note
        "table_no": table_no or None,
        "order_open_at": shifted_open.isoformat(),
        "void_at": shifted_void.isoformat(),
        "void_diff_minutes": _parse_float(row_dict.get(_COL_VOID_DIFF_MINUTES)),
        "void_reason": (row_dict.get(_COL_VOID_REASON) or "").strip() or None,
        "staff_name": (row_dict.get(_COL_STAFF_NAME) or "").strip() or None,
    }


def run(zip_path: Path, output_path: Path) -> None:
    month_counts: Counter = Counter()
    store_counts: Counter = Counter()
    reason_counts: Counter = Counter()
    skipped_missing_required = 0
    total_written = 0
    bill_no_present = 0
    staff_present = 0
    reason_present = 0
    n_seen = 0

    with output_path.open("w", encoding="utf-8") as out_f:
        for row_dict in _iter_target_rows(zip_path):
            n_seen += 1
            record = _build_shifted_record(row_dict)
            if record is None:
                skipped_missing_required += 1
                continue
            month_counts[record["void_at"][:7]] += 1
            store_counts[record["store_name"]] += 1
            if record["bill_no"]:
                bill_no_present += 1
            if record["staff_name"]:
                staff_present += 1
            if record["void_reason"]:
                reason_present += 1
                reason_counts[record["void_reason"]] += 1
            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")
            total_written += 1

    # ── Summary ─────────────────────────────────────────────────────
    print(f"source zip:            {zip_path}")
    print(f"output jsonl:          {output_path}")
    print(f"rows scanned (target months, pre-filter): {n_seen}")
    print(f"rows written:          {total_written}")
    print(f"rows skipped (missing required field):     {skipped_missing_required}")
    print()
    print("per-month counts (shifted, 2026, by void_at):")
    for k in sorted(month_counts):
        print(f"  {k}: {month_counts[k]}")
    print()
    print(f"distinct stores:       {len(store_counts)}")
    for store, cnt in store_counts.most_common():
        print(f"  {store}: {cnt}")
    print()
    pct_bill = (bill_no_present / total_written * 100) if total_written else 0.0
    pct_staff = (staff_present / total_written * 100) if total_written else 0.0
    pct_reason = (reason_present / total_written * 100) if total_written else 0.0
    print(f"bill_no present:       {bill_no_present}/{total_written} ({pct_bill:.1f}%)")
    print(f"staff_name present:    {staff_present}/{total_written} ({pct_staff:.1f}%)")
    print(f"void_reason present:   {reason_present}/{total_written} ({pct_reason:.1f}%)")
    print()
    print("top void_reason values:")
    for reason, cnt in reason_counts.most_common(10):
        print(f"  {reason!r}: {cnt}")

    print()
    print("sample rows:")
    with output_path.open("r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i >= 3:
                break
            print(f"  {line.strip()}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--zip-path", default=_DEFAULT_ZIP_PATH, help="path to the 二维火 撤单报表 export zip")
    ap.add_argument("--output", default=str(_OUTPUT_PATH), help="output JSONL path")
    args = ap.parse_args()

    zip_path = Path(args.zip_path)
    if not zip_path.exists():
        raise SystemExit(f"zip not found: {zip_path}")

    run(zip_path, Path(args.output))


if __name__ == "__main__":
    main()
