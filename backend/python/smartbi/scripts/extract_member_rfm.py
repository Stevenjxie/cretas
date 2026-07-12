"""One-off ops script — extract 二维火 会员 exports for the 有滋有味 chain
into intermediate JSONLs for CRM P0's full-RFM member analytics dimension.

Background
----------
member_profile.py (V20261007_*, sourced from 青花椒) is explicitly NOT full
RFM — that chain's export has no per-card consumption-ranking report, only
a card snapshot + recharge transactions, so Recency/Frequency cannot be
derived. 有滋有味 (a DIFFERENT real customer chain) exports a THIRD report
type — 卡消费排行 (card consumption ranking) — which IS a per-card
cumulative-consumption snapshot: R (最近消费日期), F (累计卡消费次数), M
(累计卡消费金额) all come straight off the row. This script extracts that
report (REQUIRED — feeds fact_member_consumption, V20261008_01) plus two
secondary reports for optionally refreshing the existing member_profile
dimension (画像 + 充值趋势) under the SAME 有滋有味 cohort, so the two
member-analytics cards read from a consistent source ("cohort 换成有滋有味
(RFM/画像/储值同源一致)").

Three real 二维火 exports (all UTF-8-with-BOM, 3 preamble rows before the
real header row — 标题/门店名称/查询条件 — exactly like
extract_shift_member_2026.py's 青花椒 exports):

  A. 卡消费排行 (REQUIRED — RFM primary source, ~7477 rows, one row per
     card). Real columns (verified against the 有滋有味 export):
     卡号/会员/手机号/卡类型/当前本金余额(元)/当前赠送余额(元)/当前余额(元)/
     累计本金消费/累计赠送金消费/累计卡消费金额/累计卡消费次数/最近消费日期/
     消费间隔(天)/发卡日期/卡所属门店/发卡门店.

     🔒 PII: 卡号 (card_no) and 会员 (member_name) ARE written to the
     detail-RFM output JSONL (needed as fact_member_consumption's natural
     key + future per-card drill-down join key — see V20261008_01's header
     note for why this differs from member_profile's stricter
     never-persist-any-PII design). 手机号 (phone) is read only to validate
     row shape and is NEVER written to the output.

  B. 卡详情一览（兼容卡余额信息） (OPTIONAL secondary source — 画像, ~24315
     rows). Same conceptual shape as member_profile.py's existing dim_member
     source, but a DIFFERENT chain's export with a wider column set. Only
     the same subset member_profile.py's dim_member table needs is
     extracted: 等级/性别/生日(→birth_month only)/当前余额(元)/发卡日期/
     发卡门店. 🔒 PII: 卡号/会员(昵称)/手机 are read only to validate row
     shape and skip malformed rows — NEVER written to the output. 生日 is
     reduced to birth_month (1-12) immediately; day/year are discarded here,
     permanently, before the data reaches a file.

  C. 卡充值报表 → 卡充值明细.csv (OPTIONAL secondary source — 储值趋势,
     ~715 rows, PER-TRANSACTION detail, not the report's own pre-aggregated
     卡充值统计.csv sibling — see module note below on why 明细 was chosen).
     Real columns: 日期/交易流水号/卡号/卡类型/会员名/手机号/操作/支付方式
     （本金）/充值本金/充值赠送金/总金额/储值扣点比例/手续费/实收金额/操作人/
     操作渠道/销售/卡所属门店/操作门店/券码. Real 操作 values observed in
     this export: 充值 / 充值红冲 / 退卡 / 清零 / '' — ONLY 充值 rows are
     aggregated (reversals/cancellations excluded, matching "how much did
     members actually recharge" semantics). Aggregated client-side (this
     script) to (操作门店, date, 操作渠道) grain to match
     fact_member_recharge's existing shape (V20261007_01) — 🔒 PII: 卡号/
     会员名/手机号 are read only to validate row shape, never written
     (the aggregated output has zero per-card granularity, same as
     member_profile.py's existing 卡充值统计-sourced recharge facts).

     ⚠️ Why 卡充值明细.csv and not the report's own pre-aggregated
     卡充值统计.csv sibling (which is already in (充值门店, 日期, ...) grain
     and would need zero aggregation code)? Per the CRM P0 task brief, this
     script deliberately derives the aggregate from the raw per-transaction
     detail rather than trusting the POS's own pre-rollup — the detail file
     lets this script apply an EXPLICIT 操作=='充值' filter (excluding
     charge-backs/cancellations) that the pre-aggregated 卡充值统计.csv may
     already be baking in with unknown/undocumented rules. If a future
     audit shows 卡充值统计.csv's numbers already match this script's output
     bit for bit, switching to it would be a safe simplification.

Date decision: NO +1-year shift (unlike extract_shift_member_2026.py's
青花椒 pipeline). The 有滋有味 source dates already sit naturally before
DEMO_REST's "today" (最近消费日期 up to 2026-04-21, this export having been
pulled ~2026-04-22; DEMO_REST's "today" is 2026-07-11) — using them AS-IS
gives an honest 3-8-month-old recency distribution (exactly what produces a
non-trivial, non-fabricated 沉睡/流失 lifecycle split — see
materialized_analytics/member_rfm.py's module docstring for why lifecycle
classification recomputes recency at MATERIALIZATION time rather than
trusting the source's own stale 消费间隔(天) column).

This script only EXTRACTS + WRITES three intermediate JSONL files. It does
NOT touch any database. `load_member_rfm_demo_rest.py` (sister script, NOT
run by this session — the organizer runs it against prod) reads these
JSONLs and writes to Postgres.

Usage:
    cd backend/python
    python -m smartbi.scripts.extract_member_rfm \
        --consume-zip "C:/path/to/..._卡消费排行.zip" \
        --detail-zip "C:/path/to/..._卡详情一览（兼容卡余额信息）.zip" \
        --recharge-zip "C:/path/to/..._卡充值报表.zip"

Output (next to this file):
    smartbi/scripts/_demo_rest_member_rfm_2026.jsonl        (REQUIRED)
    smartbi/scripts/_demo_rest_member_profile_detail.jsonl  (optional)
    smartbi/scripts/_demo_rest_member_profile_recharge.jsonl (optional)
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import zipfile
from collections import Counter, defaultdict
from datetime import date, datetime
from pathlib import Path
from typing import Any, Dict, Iterator, Optional

# ─── Constants ──────────────────────────────────────────────────────────
_DEFAULT_CONSUME_ZIP = (
    r"C:/Users/Steve/my-prototype-logistics/smartbi维度分析/大众点评/"
    r"真实餐饮连锁数据/有滋有味5个月/有滋有味5个月/"
    r"20260422110837655_b09c0289731_卡消费排行.zip"
)
_DEFAULT_DETAIL_ZIP = (
    r"C:/Users/Steve/my-prototype-logistics/smartbi维度分析/大众点评/"
    r"真实餐饮连锁数据/有滋有味5个月/有滋有味5个月/"
    r"20260422110925276_b3f99a2d341_卡详情一览（兼容卡余额信息）.zip"
)
_DEFAULT_RECHARGE_ZIP = (
    r"C:/Users/Steve/my-prototype-logistics/smartbi维度分析/大众点评/"
    r"真实餐饮连锁数据/有滋有味5个月/有滋有味5个月/"
    r"20260422110738934_9522be88bc3_卡充值报表.zip"
)
_OUTPUT_DIR = Path(__file__).resolve().parent
_OUTPUT_RFM_PATH = _OUTPUT_DIR / "_demo_rest_member_rfm_2026.jsonl"
_OUTPUT_DETAIL_PATH = _OUTPUT_DIR / "_demo_rest_member_profile_detail.jsonl"
_OUTPUT_RECHARGE_PATH = _OUTPUT_DIR / "_demo_rest_member_profile_recharge.jsonl"

_CONSUME_HEADER_MARKERS = ("卡号",)
_DETAIL_HEADER_MARKERS = ("卡号",)
_RECHARGE_DETAIL_HEADER_MARKERS = ("日期",)

# ── 卡消费排行 column indices (verified against the real 有滋有味 export) ──
_C_COL_CARD_NO = "卡号"
_C_COL_MEMBER = "会员"
_C_COL_TIER = "卡类型"
_C_COL_PRINCIPAL_BAL = "当前本金余额(元)"
_C_COL_BONUS_BAL = "当前赠送余额(元)"
_C_COL_TOTAL_BAL = "当前余额(元)"
_C_COL_CUM_PRINCIPAL = "累计本金消费"
_C_COL_CUM_BONUS = "累计赠送金消费"
_C_COL_CUM_SPEND = "累计卡消费金额"
_C_COL_SPEND_COUNT = "累计卡消费次数"
_C_COL_LAST_SPEND = "最近消费日期"
_C_COL_SPEND_INTERVAL = "消费间隔(天)"
_C_COL_ISSUE_DATE = "发卡日期"
_C_COL_STORE = "卡所属门店"
_C_COL_ISSUE_STORE = "发卡门店"
_C_MIN_COLS = 16

# ── 卡详情一览（兼容卡余额信息） column indices ──────────────────────────
_D_COL_TIER = "等级"
_D_COL_GENDER = "性别"
_D_COL_BALANCE = "当前余额(元)"
_D_COL_BIRTHDAY = "生日"
_D_COL_ISSUE_AT = "发卡日期"
_D_COL_ISSUE_STORE = "发卡门店"
_D_MIN_COLS = 25

# ── 卡充值明细 column indices ────────────────────────────────────────────
_R_COL_DATETIME = "日期"
_R_COL_OPERATION = "操作"
_R_COL_PRINCIPAL = "充值本金"
_R_COL_BONUS = "充值赠送金"
_R_COL_FEE = "手续费"
_R_COL_ACTUAL = "实收金额"
_R_COL_CHANNEL = "操作渠道"
_R_COL_OP_STORE = "操作门店"
_R_MIN_COLS = 19
_R_RECHARGE_OP = "充值"  # only this 操作 value counts as a real recharge


def _detect_header_row(reader: "csv._reader", markers: tuple) -> list:
    """Consume rows from `reader` until the real header row is found
    (first cell matches a known marker). Returns the header row (list of
    column names). Raises ValueError if no marker found in first 30 rows."""
    for i, row in enumerate(reader):
        if not row:
            continue
        first = row[0].strip().strip('"').strip()
        if first in markers:
            return row
        if i > 30:
            break
    raise ValueError(
        f"could not find header row (looked for first cell in {markers} "
        "within first 30 rows) — CSV structure differs from expected 二维火 shape"
    )


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


def _parse_date_only(raw: Optional[str]) -> Optional[date]:
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    for fmt in ("%Y-%m-%d", "%Y/%m/%d"):
        try:
            return datetime.strptime(s, fmt).date()
        except ValueError:
            continue
    # Defensive: some 发卡日期 cells carry a full timestamp even in files
    # where the sample rows showed a bare date — try datetime then take date().
    dt = _parse_datetime(raw)
    return dt.date() if dt else None


def _parse_datetime(raw: Optional[str]) -> Optional[datetime]:
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(s, fmt)
        except ValueError:
            continue
    return None


# ── A. 卡消费排行 (REQUIRED — RFM) ──────────────────────────────────────


def _iter_consume_rows(zip_path: Path) -> Iterator[Dict[str, Any]]:
    with zipfile.ZipFile(zip_path) as z:
        members = z.namelist()
        if len(members) != 1:
            raise ValueError(
                f"expected exactly 1 CSV member in zip, found {len(members)}: {members}"
            )
        with z.open(members[0]) as fh:
            text_stream = io.TextIOWrapper(fh, encoding="utf-8-sig", newline="")
            reader = csv.reader(text_stream)
            header = _detect_header_row(reader, _CONSUME_HEADER_MARKERS)
            for row in reader:
                if not row or len(row) < _C_MIN_COLS:
                    continue
                yield dict(zip(header, row))


def _build_rfm_record(row_dict: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Convert one raw 卡消费排行 row -> the fact_member_consumption intake
    schema. Returns None if a required field is missing. 手机号 is read
    only implicitly (never referenced — not persisted, see module docstring)."""
    card_no = (row_dict.get(_C_COL_CARD_NO) or "").strip()
    store_name = (row_dict.get(_C_COL_STORE) or "").strip()
    issue_store_name = (row_dict.get(_C_COL_ISSUE_STORE) or "").strip()
    last_spend_dt = _parse_datetime(row_dict.get(_C_COL_LAST_SPEND))
    issue_dt = _parse_date_only(row_dict.get(_C_COL_ISSUE_DATE))

    if not card_no or not store_name or not issue_store_name or last_spend_dt is None or issue_dt is None:
        return None

    spend_count_raw = _parse_float(row_dict.get(_C_COL_SPEND_COUNT))
    spend_interval_raw = _parse_float(row_dict.get(_C_COL_SPEND_INTERVAL))

    return {
        "card_no": card_no,
        "member_name": (row_dict.get(_C_COL_MEMBER) or "").strip() or None,
        "tier": (row_dict.get(_C_COL_TIER) or "").strip() or None,
        "store_name": store_name,           # 卡所属门店
        "issue_store_name": issue_store_name,  # 发卡门店
        "principal_balance": _parse_float(row_dict.get(_C_COL_PRINCIPAL_BAL)),
        "bonus_balance": _parse_float(row_dict.get(_C_COL_BONUS_BAL)),
        "total_balance": _parse_float(row_dict.get(_C_COL_TOTAL_BAL)),
        "cum_principal_spend": _parse_float(row_dict.get(_C_COL_CUM_PRINCIPAL)),
        "cum_bonus_spend": _parse_float(row_dict.get(_C_COL_CUM_BONUS)),
        "cum_spend": _parse_float(row_dict.get(_C_COL_CUM_SPEND)),
        "spend_count": int(spend_count_raw) if spend_count_raw is not None else 0,
        "last_spend_date": last_spend_dt.isoformat(),
        "spend_interval_days": int(spend_interval_raw) if spend_interval_raw is not None else None,
        "issue_date": issue_dt.isoformat(),
    }


def _run_rfm(zip_path: Path, output_path: Path) -> None:
    tier_counts: Counter = Counter()
    store_counts: Counter = Counter()
    skipped = 0
    written = 0
    n_seen = 0

    with output_path.open("w", encoding="utf-8") as out_f:
        for row_dict in _iter_consume_rows(zip_path):
            n_seen += 1
            record = _build_rfm_record(row_dict)
            if record is None:
                skipped += 1
                continue
            tier_counts[record["tier"] or "未知"] += 1
            store_counts[record["store_name"]] += 1
            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")
            written += 1

    print("=== 卡消费排行 (member consumption ranking — RFM source) ===")
    print(f"source zip:            {zip_path}")
    print(f"output jsonl:          {output_path}")
    print(f"rows scanned:          {n_seen}")
    print(f"rows written:          {written}")
    print(f"rows skipped (missing required field): {skipped}")
    print()
    print("tier (卡类型) distribution:")
    for tier, cnt in tier_counts.most_common(10):
        print(f"  {tier}: {cnt}")
    print()
    print(f"distinct 卡所属门店: {len(store_counts)}")
    for store, cnt in store_counts.most_common(10):
        print(f"  {store}: {cnt}")
    print()
    print("sample rows (card_no + member_name ARE present here — see module docstring 🔒 PII note):")
    with output_path.open("r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i >= 3:
                break
            print(f"  {line.strip()}")
    print()


# ── B. 卡详情一览（兼容卡余额信息） (optional — 画像) ──────────────────


def _iter_detail_rows(zip_path: Path) -> Iterator[Dict[str, Any]]:
    with zipfile.ZipFile(zip_path) as z:
        members = z.namelist()
        if len(members) != 1:
            raise ValueError(
                f"expected exactly 1 CSV member in zip, found {len(members)}: {members}"
            )
        with z.open(members[0]) as fh:
            text_stream = io.TextIOWrapper(fh, encoding="utf-8-sig", newline="")
            reader = csv.reader(text_stream)
            header = _detect_header_row(reader, _DETAIL_HEADER_MARKERS)
            for row in reader:
                if not row or len(row) < _D_MIN_COLS:
                    continue
                yield dict(zip(header, row))


def _build_profile_detail_record(row_dict: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """PII-minimized at extract time (mirrors extract_shift_member_2026.py):
    card_no/member_name/phone are read to validate shape only, never written.
    生日 reduced to birth_month immediately, day/year discarded permanently."""
    store_name = (row_dict.get(_D_COL_ISSUE_STORE) or "").strip()
    issue_dt = _parse_datetime(row_dict.get(_D_COL_ISSUE_AT))

    if not store_name or issue_dt is None:
        return None

    birthday = _parse_date_only(row_dict.get(_D_COL_BIRTHDAY))
    birth_month = birthday.month if birthday is not None else None

    return {
        "store_name": store_name,
        "tier": (row_dict.get(_D_COL_TIER) or "").strip() or None,
        "gender": (row_dict.get(_D_COL_GENDER) or "").strip() or None,
        "birth_month": birth_month,  # 1-12 or None — day/year discarded here, PERMANENTLY
        "balance": _parse_float(row_dict.get(_D_COL_BALANCE)),
        "issue_at": issue_dt.isoformat(),  # NO +1yr shift — see module docstring
    }


def _run_detail(zip_path: Path, output_path: Path) -> None:
    tier_counts: Counter = Counter()
    gender_counts: Counter = Counter()
    skipped = 0
    written = 0
    n_seen = 0

    with output_path.open("w", encoding="utf-8") as out_f:
        for row_dict in _iter_detail_rows(zip_path):
            n_seen += 1
            record = _build_profile_detail_record(row_dict)
            if record is None:
                skipped += 1
                continue
            tier_counts[record["tier"] or "未知"] += 1
            gender_counts[record["gender"] or "未知"] += 1
            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")
            written += 1

    print("=== 卡详情一览（兼容卡余额信息） (member profile — optional 画像) ===")
    print(f"source zip:            {zip_path}")
    print(f"output jsonl:          {output_path}")
    print(f"rows scanned:          {n_seen}")
    print(f"rows written:          {written}")
    print(f"rows skipped (missing required field): {skipped}")
    print()
    print("tier (等级) distribution (top 10):")
    for tier, cnt in tier_counts.most_common(10):
        print(f"  {tier}: {cnt}")
    print("gender distribution:")
    for gender, cnt in gender_counts.most_common():
        print(f"  {gender}: {cnt}")
    print()


# ── C. 卡充值明细 (optional — 储值趋势) ─────────────────────────────────


def _iter_recharge_detail_rows(zip_path: Path) -> Iterator[Dict[str, Any]]:
    """The 卡充值报表 zip has TWO members (明细 + 统计) — pick the 明细 one
    explicitly by filename substring (see module docstring for why 明细,
    not the pre-aggregated 统计 sibling, is used)."""
    with zipfile.ZipFile(zip_path) as z:
        members = [n for n in z.namelist() if "明细" in n]
        if len(members) != 1:
            raise ValueError(
                f"expected exactly 1 明细 CSV member in zip, found {len(members)}: {z.namelist()}"
            )
        with z.open(members[0]) as fh:
            text_stream = io.TextIOWrapper(fh, encoding="utf-8-sig", newline="")
            reader = csv.reader(text_stream)
            header = _detect_header_row(reader, _RECHARGE_DETAIL_HEADER_MARKERS)
            for row in reader:
                if not row or len(row) < _R_MIN_COLS:
                    continue
                yield dict(zip(header, row))


def _run_recharge(zip_path: Path, output_path: Path) -> None:
    """Aggregate 卡充值明细 rows (per-transaction) client-side to
    (操作门店, date, 操作渠道) grain — matching fact_member_recharge's
    existing shape (V20261007_01). Only 操作=='充值' rows count."""
    op_counts: Counter = Counter()
    skipped_missing_required = 0
    skipped_non_recharge_op = 0
    n_seen = 0

    # key: (store_name, date_iso, channel) -> accumulators
    buckets: Dict[tuple, Dict[str, Any]] = defaultdict(
        lambda: {"recharge_count": 0, "principal": 0.0, "bonus": 0.0, "fee": 0.0, "actual_received": 0.0}
    )

    for row_dict in _iter_recharge_detail_rows(zip_path):
        n_seen += 1
        op = (row_dict.get(_R_COL_OPERATION) or "").strip()
        op_counts[op or "(空)"] += 1
        if op != _R_RECHARGE_OP:
            skipped_non_recharge_op += 1
            continue

        store_name = (row_dict.get(_R_COL_OP_STORE) or "").strip()
        dt = _parse_datetime(row_dict.get(_R_COL_DATETIME))
        if not store_name or dt is None:
            skipped_missing_required += 1
            continue

        channel = (row_dict.get(_R_COL_CHANNEL) or "").strip()
        key = (store_name, dt.date().isoformat(), channel)
        bucket = buckets[key]
        bucket["recharge_count"] += 1
        bucket["principal"] += _parse_float(row_dict.get(_R_COL_PRINCIPAL)) or 0.0
        bucket["bonus"] += _parse_float(row_dict.get(_R_COL_BONUS)) or 0.0
        bucket["fee"] += _parse_float(row_dict.get(_R_COL_FEE)) or 0.0
        bucket["actual_received"] += _parse_float(row_dict.get(_R_COL_ACTUAL)) or 0.0

    written = 0
    with output_path.open("w", encoding="utf-8") as out_f:
        for (store_name, date_iso, channel), bucket in sorted(buckets.items()):
            record = {
                "store_name": store_name,
                "date": date_iso,
                "channel": channel or None,
                "recharge_count": bucket["recharge_count"],
                "principal": round(bucket["principal"], 2),
                "bonus": round(bucket["bonus"], 2),
                "other_amount": None,  # no analogous "其它" column in the 明细 file
                "fee": round(bucket["fee"], 2),
                "actual_received": round(bucket["actual_received"], 2),
            }
            out_f.write(json.dumps(record, ensure_ascii=False) + "\n")
            written += 1

    print("=== 卡充值明细 -> aggregated 充值趋势 (optional 储值趋势) ===")
    print(f"source zip:            {zip_path}")
    print(f"output jsonl:          {output_path}")
    print(f"transaction rows scanned: {n_seen}")
    print(f"操作 value distribution:  {dict(op_counts)}")
    print(f"rows skipped (non-充值 操作): {skipped_non_recharge_op}")
    print(f"rows skipped (missing required field): {skipped_missing_required}")
    print(f"aggregated buckets written (store+date+channel): {written}")
    print()
    print("sample aggregated rows:")
    with output_path.open("r", encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i >= 3:
                break
            print(f"  {line.strip()}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--consume-zip", default=_DEFAULT_CONSUME_ZIP, help="path to the 二维火 卡消费排行 export zip (REQUIRED — RFM source)")
    ap.add_argument("--detail-zip", default=_DEFAULT_DETAIL_ZIP, help="path to the 二维火 卡详情一览（兼容卡余额信息） export zip (optional — 画像)")
    ap.add_argument("--recharge-zip", default=_DEFAULT_RECHARGE_ZIP, help="path to the 二维火 卡充值报表 export zip (optional — 储值趋势)")
    ap.add_argument("--output-rfm", default=str(_OUTPUT_RFM_PATH), help="output JSONL path for RFM records (required)")
    ap.add_argument("--output-detail", default=str(_OUTPUT_DETAIL_PATH), help="output JSONL path for profile/画像 records (optional)")
    ap.add_argument("--output-recharge", default=str(_OUTPUT_RECHARGE_PATH), help="output JSONL path for recharge/储值趋势 records (optional)")
    ap.add_argument("--skip-profile", action="store_true", help="skip extracting 卡详情一览/卡充值明细 (画像+储值趋势) — only extract the required RFM source")
    args = ap.parse_args()

    consume_zip = Path(args.consume_zip)
    if not consume_zip.exists():
        raise SystemExit(f"zip not found: {consume_zip}")
    _run_rfm(consume_zip, Path(args.output_rfm))

    if args.skip_profile:
        print("--skip-profile: not extracting 卡详情一览/卡充值明细 (画像/储值趋势 optional sources).")
        return

    print()
    detail_zip = Path(args.detail_zip)
    if detail_zip.exists():
        _run_detail(detail_zip, Path(args.output_detail))
    else:
        print(f"⚠️ detail zip not found, skipping 画像 extraction: {detail_zip}")

    print()
    recharge_zip = Path(args.recharge_zip)
    if recharge_zip.exists():
        _run_recharge(recharge_zip, Path(args.output_recharge))
    else:
        print(f"⚠️ recharge zip not found, skipping 储值趋势 extraction: {recharge_zip}")


if __name__ == "__main__":
    main()
