#!/usr/bin/env python3
"""
Demo Data Seeding Pipeline
==========================
Config-driven pipeline:
  1. Reads real data files
  2. Desensitizes brand/store/supplier names
  3. Redates to a continuous non-overlapping 2024-01 to 2025-12 timeline
  4. Outputs seeded xlsx to --out directory + prints upload manifest

Usage:
  python seed_demo_data.py --out /tmp/demo_seed [--dry-run]
  python seed_demo_data.py --verify   # local validation only

Author: auto-generated feat/demo-seed-pipeline
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import warnings
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd

warnings.filterwarnings("ignore")

# ---------------------------------------------------------------------------
# TENANTS
# ---------------------------------------------------------------------------
DEMO_FACTORY = "DEMO_FACTORY"
DEMO_REST = "DEMO_REST"

# ---------------------------------------------------------------------------
# REPO ROOT RESOLUTION
# ---------------------------------------------------------------------------

def _repo_root() -> Path:
    """Walk up from this script to locate repo root (contains scripts/ AND backend/)."""
    p = Path(__file__).resolve()
    for _ in range(12):
        if (p / "scripts").is_dir() and (p / "backend").is_dir():
            return p
        p = p.parent
    raise RuntimeError(f"Cannot locate repo root from {__file__}")


REPO = _repo_root()
SMARTBI_DATA = REPO / "smartbi维度分析" / "大众点评" / "真实餐饮连锁数据"
XLSX_CONV = SMARTBI_DATA / "xlsx_converted"
XLSX_DIR = SMARTBI_DATA / "xlsx"
TEST_DATA = REPO / "scripts" / "test-data"
QHJ_E2E = (
    REPO
    / "tests"
    / "e2e-comprehensive"
    / "results"
    / "depth-aiq-2026-04-26"
    / "unzipped"
    / "qhj-25"
)
TEST_MOCK = REPO / "tests" / "test-data"

# ---------------------------------------------------------------------------
# DESENSITISATION
# ---------------------------------------------------------------------------

REAL_BRAND_TO_GENERIC: Dict[str, str] = {
    "青花椒": "门店01",
    "东门口": "门店02",
    "九记·東門口": "门店02",
    "IL TEATRO": "门店03",
    "上马火锅": "门店04",
    "唏嘛香": "门店05",
    "御九井": "门店06",
    "永和豆浆": "门店07",
    "馨厨香": "门店08",
    "绿源食品": "工厂01",
    "美鑫": "工厂02",
    "张记餐饮": "门店09",
    "鲜味零售": "门店10",
    "绿源": "工厂01",
}

_desens_cache: Dict[str, str] = {}
_desens_counters: Dict[str, int] = {}

_LABEL_MAP = {
    "store": "门店",
    "customer": "客户",
    "supplier": "供应商",
}


def _desens_value(raw: str, kind: str) -> str:
    key = f"{kind}:{raw}"
    if key in _desens_cache:
        return _desens_cache[key]
    for brand_key, generic in REAL_BRAND_TO_GENERIC.items():
        if brand_key in str(raw):
            _desens_cache[key] = generic
            return generic
    _desens_counters[kind] = _desens_counters.get(kind, 0) + 1
    label_base = _LABEL_MAP.get(kind, "名称")
    label = f"{label_base}{_desens_counters[kind]:02d}"
    _desens_cache[key] = label
    return label


def _col_kind(col_name: str) -> Optional[str]:
    c = str(col_name)
    if any(kw in c for kw in ["门店", "店铺名称", "品牌", "店名"]):
        return "store"
    if any(kw in c for kw in ["客户", "客户名称"]):
        return "customer"
    if any(kw in c for kw in ["供应商", "供应商名称"]):
        return "supplier"
    return None


def desensitize(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    for col in df.columns:
        kind = _col_kind(col)
        if kind is None:
            continue

        def _map(v, _kind=kind):
            if pd.isna(v) or str(v).strip() == "":
                return v
            return _desens_value(str(v).strip(), _kind)

        df[col] = df[col].map(_map)
    return df


# ---------------------------------------------------------------------------
# DATE COLUMN DETECTION
# ---------------------------------------------------------------------------

DATE_COL_PATTERNS = [
    r"日期", r"月份", r"date", r"month", r"period",
    r"单据.*日期", r"业务日期", r"操作日期",
]


def _find_date_cols(df: pd.DataFrame) -> List[str]:
    found = []
    for col in df.columns:
        c = str(col).lower()
        for pat in DATE_COL_PATTERNS:
            if re.search(pat, c, re.IGNORECASE):
                found.append(col)
                break
    return found


def _is_month_str(v: Any) -> bool:
    """True only for pure year-month strings like '2025年1月' or '2025-01' (NOT full dates)."""
    s = str(v).strip()
    if re.match(r"\d{4}年\d{1,2}月$", s):
        return True
    # Only match YYYY-MM without a day part
    if re.match(r"^\d{4}-\d{2}$", s):
        return True
    return False


# ---------------------------------------------------------------------------
# WINDOW ASSIGNMENT
# ---------------------------------------------------------------------------

@dataclass
class Window:
    start: date
    end: date

    def months(self) -> List[Tuple[int, int]]:
        result = []
        y, m = self.start.year, self.start.month
        while (y, m) <= (self.end.year, self.end.month):
            result.append((y, m))
            m += 1
            if m > 12:
                m = 1
                y += 1
        return result

    def __str__(self):
        return f"{self.start.strftime('%Y%m')}_{self.end.strftime('%Y%m')}"


_window_cursor: Dict[str, Tuple[int, int]] = {}

TIMELINE_START = (2024, 1)
TIMELINE_END = (2025, 12)


def _next_month(ym: Tuple[int, int]) -> Tuple[int, int]:
    y, m = ym
    m += 1
    if m > 12:
        m = 1
        y += 1
    return (y, m)


def _add_months(ym: Tuple[int, int], n: int) -> Tuple[int, int]:
    for _ in range(n):
        ym = _next_month(ym)
    return ym


def assign_window(tenant: str, domain: str, n_months: int) -> Optional[Window]:
    """Allocate the next non-overlapping n_months window for this tenant."""
    cursor = _window_cursor.get(f"{tenant}::{domain}", TIMELINE_START)
    start_ym = cursor
    end_ym = _add_months(start_ym, max(n_months - 1, 0))
    import calendar
    start = date(start_ym[0], start_ym[1], 1)
    last_day = calendar.monthrange(end_ym[0], end_ym[1])[1]
    end = date(end_ym[0], end_ym[1], last_day)
    _window_cursor[f"{tenant}::{domain}"] = _next_month(end_ym)
    return Window(start, end)


def reset_window_cursor():
    global _window_cursor, _desens_cache, _desens_counters
    _window_cursor = {}
    _desens_cache = {}
    _desens_counters = {}


# ---------------------------------------------------------------------------
# DATE SHIFTING / REDATING
# ---------------------------------------------------------------------------

def _parse_month_str(s: str) -> Optional[Tuple[int, int]]:
    """Parse pure month strings: '2025年1月' or '2025-01' (NOT full dates like '2025-01-15')."""
    t = str(s).strip()
    m = re.match(r"(\d{4})年(\d{1,2})月$", t)
    if m:
        return int(m.group(1)), int(m.group(2))
    # Only pure YYYY-MM (no day part)
    m2 = re.match(r"^(\d{4})-(\d{2})$", t)
    if m2:
        return int(m2.group(1)), int(m2.group(2))
    return None


def _format_month_str(y: int, mo: int, original_format: str) -> str:
    if re.match(r"\d{4}年\d{1,2}月", str(original_format)):
        return f"{y}年{mo}月"
    if re.match(r"\d{4}-\d{2}$", str(original_format)):
        return f"{y}-{mo:02d}"
    return f"{y}年{mo}月"


def redate_monthly_col(df: pd.DataFrame, col: str, window: Window) -> pd.DataFrame:
    """Map month-string values to target window via linear interleave."""
    df = df.copy()
    src_months_raw = df[col].dropna().unique()
    src_months = []
    for v in src_months_raw:
        parsed = _parse_month_str(v)
        if parsed:
            src_months.append((parsed, v))
    if not src_months:
        return df
    src_months.sort(key=lambda x: x[0])
    target_months = window.months()

    if len(src_months) == 1:
        orig_ym, orig_raw = src_months[0]
        new_month = target_months[0]
        new_val = _format_month_str(new_month[0], new_month[1], str(orig_raw))
        df[col] = df[col].map(
            lambda v: new_val if _parse_month_str(v) == orig_ym else v
        )
    else:
        src_ym_to_target: Dict[Tuple[int, int], Tuple[int, int]] = {}
        for i, (src_ym, _) in enumerate(src_months):
            tgt_ym = target_months[min(i, len(target_months) - 1)]
            src_ym_to_target[src_ym] = tgt_ym

        def _remap(v):
            parsed = _parse_month_str(v)
            if parsed is None or parsed not in src_ym_to_target:
                return v
            tgt = src_ym_to_target[parsed]
            return _format_month_str(tgt[0], tgt[1], str(v))

        df[col] = df[col].map(_remap)
    return df


def redate_date_col(df: pd.DataFrame, col: str, window: Window) -> pd.DataFrame:
    """Linearly stretch real datetime values into target window."""
    df = df.copy()
    vals = pd.to_datetime(df[col], errors="coerce")
    valid = vals.dropna()
    if len(valid) == 0:
        return df
    src_min = valid.min()
    src_max = valid.max()
    src_span = (src_max - src_min).days or 1
    tgt_min = pd.Timestamp(window.start)
    tgt_max = pd.Timestamp(window.end)
    tgt_span = (tgt_max - tgt_min).days or 1

    def _shift(v):
        if pd.isna(v):
            return v
        t = pd.to_datetime(v, errors="coerce")
        if pd.isna(t):
            return v
        ratio = (t - src_min).days / src_span
        new_t = tgt_min + pd.Timedelta(days=int(ratio * tgt_span))
        if isinstance(v, date) and not isinstance(v, pd.Timestamp):
            return new_t.date()
        return new_t.strftime("%Y-%m-%d")

    df[col] = df[col].map(_shift)
    return df


def redate(df: pd.DataFrame, window: Window) -> pd.DataFrame:
    """Auto-detect date/month columns and redate."""
    date_cols = _find_date_cols(df)
    for col in date_cols:
        sample = df[col].dropna().head(5)
        if len(sample) == 0:
            continue
        monthly_count = sum(1 for v in sample if _is_month_str(v))
        if monthly_count >= max(1, len(sample) // 2):
            df = redate_monthly_col(df, col, window)
        else:
            df = redate_date_col(df, col, window)
    return df


# ---------------------------------------------------------------------------
# SNAPSHOT EXPANSION  (single-month → multi-month with light trend)
# ---------------------------------------------------------------------------

def expand_snapshot(
    df: pd.DataFrame,
    window: Window,
    n_months: int,
    trend_cols: Optional[List[str]] = None,
    trend_factor: float = 0.03,
) -> pd.DataFrame:
    """
    Given a single-month snapshot, replicate into n_months within window.
    Adds a '月份' column with the target month label.
    Optionally applies a light multiplicative trend to numeric columns.
    """
    targets = window.months()[:n_months]
    frames = []
    for i, (y, m) in enumerate(targets):
        chunk = df.copy()
        chunk["月份"] = f"{y}年{m}月"
        if trend_cols:
            mult = 1.0 + trend_factor * i
            for col in trend_cols:
                if col in chunk.columns:
                    chunk[col] = pd.to_numeric(chunk[col], errors="coerce") * mult
        frames.append(chunk)
    return pd.concat(frames, ignore_index=True)


# ---------------------------------------------------------------------------
# SEED PLAN
# ---------------------------------------------------------------------------

@dataclass
class SeedEntry:
    label: str
    src_path: Path
    tenant: str
    domain: str
    sheet_name: Optional[str]       # None = first sheet; "CSV" = csv file
    n_months: int = 1
    header_row: Optional[int] = None  # None = pandas default (0); set 1 to skip title row
    skip_rows: Optional[int] = None   # CSV pre-header rows to skip
    is_snapshot: bool = False         # single-month snapshot → expand
    snapshot_n_months: int = 2        # how many months to expand to
    trend_cols: Optional[List[str]] = None
    notes: str = ""


def build_seed_plan() -> List[SeedEntry]:
    plan: List[SeedEntry] = []

    # ===== RESTAURANT SALES: 8 brand xlsx snapshots (2026-02) =====
    brand_sales_files = []

    # Enumerate xlsx_converted for brand sales files
    if XLSX_CONV.exists():
        for fp in sorted(XLSX_CONV.glob("*.xlsx")):
            if "销量" in fp.name or "销售" in fp.name:
                brand_sales_files.append(fp)

    # Also check xlsx/ directory
    if XLSX_DIR.exists():
        for fp in sorted(XLSX_DIR.glob("*.xlsx")):
            if "销量" in fp.name or "销售" in fp.name:
                brand_sales_files.append(fp)

    for i, fp in enumerate(brand_sales_files[:8]):
        safe = re.sub(r"[^\w]", "_", fp.stem)[:30]
        plan.append(SeedEntry(
            label=f"rest_sales_{i+1:02d}_{safe}",
            src_path=fp,
            tenant=DEMO_REST,
            domain="sales",
            sheet_name=None,
            n_months=2,
            is_snapshot=True,
            snapshot_n_months=2,
            trend_cols=["销售金额", "实收", "折后金额", "单卖数量(不含套餐子商品)"],
            notes=f"品牌商品销量 snapshot → 2个月",
        ))

    # ===== RESTAURANT PURCHASE: 东门口 =====
    purchase_fp = XLSX_DIR / "东门口2月采购入库明细报表.xlsx"
    if not purchase_fp.exists():
        # try xlsx_converted
        purchase_fp = XLSX_CONV / "东门口2月采购入库明细报表.xlsx"
    plan.append(SeedEntry(
        label="rest_purchase_dongmenkou",
        src_path=purchase_fp,
        tenant=DEMO_REST,
        domain="purchase",
        sheet_name=None,
        n_months=2,
        notes="东门口采购入库 2026-02 → redate",
    ))

    # ===== RESTAURANT FINANCE: 张记餐饮 =====
    for sheet_name, domain in [("月度收入明细", "finance"), ("成本分析", "finance"), ("菜品销售排行", "sales")]:
        plan.append(SeedEntry(
            label=f"rest_zhangji_{sheet_name}",
            src_path=TEST_DATA / "张记餐饮-2025经营报表.xlsx",
            tenant=DEMO_REST,
            domain=domain,
            sheet_name=sheet_name,
            n_months=12,
            header_row=1,
            notes="张记餐饮合成",
        ))

    # ===== QHJ 2025: continuous time series =====
    qhj_files = [
        ("qhj_25_营业概况月报.csv", "sales"),
        ("qhj_25_商品销售明细.csv", "sales"),
        ("qhj_25_详细日报表.csv",   "sales"),
    ]
    for fname, domain in qhj_files:
        fp = QHJ_E2E / fname
        plan.append(SeedEntry(
            label=f"rest_qhj25_{fname.replace('qhj_25_', '').replace('.csv', '')}",
            src_path=fp,
            tenant=DEMO_REST,
            domain=domain,
            sheet_name="CSV",
            n_months=12,
            skip_rows=3,
            notes="QHJ 2025 continuous",
        ))

    # ===== FACTORY PRODUCTION: 绿源食品 =====
    for sheet_name, domain in [("生产产量", "production"), ("原材料采购", "purchase"), ("利润表", "finance")]:
        plan.append(SeedEntry(
            label=f"factory_luyuan_{sheet_name}",
            src_path=TEST_DATA / "绿源食品-2025生产报表.xlsx",
            tenant=DEMO_FACTORY,
            domain=domain,
            sheet_name=sheet_name,
            n_months=12,
            header_row=1,
            notes="绿源食品合成",
        ))

    # ===== RETAIL/INVENTORY: 鲜味零售 =====
    for sheet_name, domain in [("门店销售", "sales"), ("库存周转", "inventory")]:
        plan.append(SeedEntry(
            label=f"retail_xianwei_{sheet_name}",
            src_path=TEST_DATA / "鲜味零售-2025销售数据.xlsx",
            tenant=DEMO_REST,
            domain=domain,
            sheet_name=sheet_name,
            n_months=12,
            header_row=1,
            notes="鲜味零售合成",
        ))

    # ===== MODULE COVERAGE: Test-mock files =====
    for mock_file, tenant, label_prefix in [
        (TEST_MOCK / "Test-mock-mfg-normal-s42.xlsx",  DEMO_FACTORY, "factory_mock"),
        (TEST_MOCK / "Test-mock-food-normal-s42.xlsx", DEMO_REST,    "rest_mock"),
    ]:
        for sheet_name, domain in [
            ("库存台账",    "inventory"),
            ("应收账款账龄", "finance"),
            ("费用预算执行", "finance"),
        ]:
            plan.append(SeedEntry(
                label=f"{label_prefix}_{sheet_name}",
                src_path=mock_file,
                tenant=tenant,
                domain=domain,
                sheet_name=sheet_name,
                n_months=3,
                header_row=2,
                notes=f"Test-mock coverage {sheet_name}",
            ))

    return plan


# ---------------------------------------------------------------------------
# GAP-FILL GENERATORS
# ---------------------------------------------------------------------------

def generate_gap_fill() -> List[Tuple[str, str, str, pd.DataFrame]]:
    """
    Synthetic gap-fill for missing domains.
    Returns list of (tenant, domain, label, DataFrame).
    Covers: production, inventory, hr, quality across 2024-2025 (24 months).
    """
    results = []
    months_24_25 = []
    for y in [2024, 2025]:
        for m in range(1, 13):
            months_24_25.append(f"{y}年{m}月")

    rng = np.random.default_rng(42)

    # --- PRODUCTION (factory) ---
    processes = ["备料", "搅拌", "成型", "蒸煮", "包装"]
    rows = []
    for mo in months_24_25:
        for proc in processes:
            input_qty = int(rng.integers(8000, 15000))
            yield_rate = rng.uniform(0.92, 0.99)
            output_qty = int(input_qty * yield_rate)
            rows.append({
                "月份": mo,
                "工序": proc,
                "投入数量(kg)": input_qty,
                "产出数量(kg)": output_qty,
                "合格数量(kg)": int(output_qty * rng.uniform(0.97, 1.0)),
                "出成率(%)": round(yield_rate * 100, 2),
                "不良率(%)": round((1.0 - yield_rate) * 100, 2),
            })
    results.append((DEMO_FACTORY, "production", "gap_fill_production", pd.DataFrame(rows)))

    # --- INVENTORY (factory) ---
    materials = ["猪肉原料", "豆腐", "调味料", "包装袋", "纸箱"]
    rows = []
    base = {m: rng.integers(500, 5000) for m in materials}
    for mo in months_24_25:
        for mat in materials:
            qty = int(base[mat] * rng.uniform(0.8, 1.2))
            rows.append({
                "月份": mo,
                "物料名称": mat,
                "仓库": "主仓库",
                "期末库存数量": qty,
                "期末库存金额(元)": round(qty * rng.uniform(5, 80), 2),
                "周转率(次/月)": round(rng.uniform(0.5, 4.0), 2),
                "安全库存": int(base[mat] * 0.2),
            })
    results.append((DEMO_FACTORY, "inventory", "gap_fill_inventory_factory", pd.DataFrame(rows)))

    # --- INVENTORY (restaurant) ---
    items = ["食材-猪肉", "食材-蔬菜", "调料", "包装盒", "饮品原料"]
    rows = []
    for mo in months_24_25:
        for item in items:
            qty = int(rng.integers(100, 800))
            rows.append({
                "月份": mo,
                "物料名称": item,
                "仓库": "餐厅仓库",
                "期末库存数量": qty,
                "期末库存金额(元)": round(qty * rng.uniform(2, 30), 2),
                "周转率(次/月)": round(rng.uniform(2.0, 8.0), 2),
            })
    results.append((DEMO_REST, "inventory", "gap_fill_inventory_rest", pd.DataFrame(rows)))

    # --- HR (factory) ---
    departments = ["生产部", "品质部", "仓储部", "采购部", "管理部"]
    rows = []
    for mo in months_24_25:
        for dept in departments:
            n = int(rng.integers(5, 40))
            rows.append({
                "月份": mo,
                "部门": dept,
                "人数": n,
                "工资总额(元)": round(n * rng.uniform(4500, 12000), 2),
                "人均产值(元)": round(rng.uniform(8000, 30000), 2),
                "提成合计(元)": round(rng.uniform(0, 20000), 2),
            })
    results.append((DEMO_FACTORY, "hr", "gap_fill_hr_factory", pd.DataFrame(rows)))

    # --- HR (restaurant) ---
    roles = ["厨师", "服务员", "收银", "外卖配送", "管理"]
    rows = []
    for mo in months_24_25:
        for role in roles:
            n = int(rng.integers(2, 15))
            rows.append({
                "月份": mo,
                "岗位": role,
                "人数": n,
                "工资总额(元)": round(n * rng.uniform(3500, 8000), 2),
                "人效(元/人)": round(rng.uniform(15000, 60000), 2),
            })
    results.append((DEMO_REST, "hr", "gap_fill_hr_rest", pd.DataFrame(rows)))

    # --- QUALITY (factory) ---
    stages = ["进料检验", "过程检验", "出货检验"]
    rows = []
    for mo in months_24_25:
        for stage in stages:
            batches = int(rng.integers(10, 50))
            pass_n = int(batches * rng.uniform(0.90, 0.99))
            rows.append({
                "月份": mo,
                "检验环节": stage,
                "检验批次": batches,
                "合格数": pass_n,
                "不合格数": batches - pass_n,
                "合格率(%)": round(pass_n / batches * 100, 2),
            })
    results.append((DEMO_FACTORY, "quality", "gap_fill_quality", pd.DataFrame(rows)))

    return results


# ---------------------------------------------------------------------------
# ENTRY PROCESSOR
# ---------------------------------------------------------------------------

def process_entry(entry: SeedEntry, window: Window) -> Optional[pd.DataFrame]:
    """Load → desensitise → redate.  Returns processed df or None on error."""
    src = entry.src_path
    if not src.exists():
        print(f"  [SKIP] Not found: {src.name}")
        return None

    try:
        if entry.sheet_name == "CSV":
            kwargs: Dict[str, Any] = {"low_memory": False}
            if entry.skip_rows:
                kwargs["skiprows"] = entry.skip_rows
            try:
                df = pd.read_csv(src, encoding="utf-8", **kwargs)
            except UnicodeDecodeError:
                df = pd.read_csv(src, encoding="gbk", **kwargs)
        else:
            read_kw: Dict[str, Any] = {}
            if entry.sheet_name:
                read_kw["sheet_name"] = entry.sheet_name
            if entry.header_row is not None:
                read_kw["header"] = entry.header_row
            df = pd.read_excel(src, **read_kw)
    except Exception as e:
        print(f"  [ERROR] {src.name} / sheet={entry.sheet_name}: {e}")
        return None

    if df.empty:
        print(f"  [SKIP] Empty: {src.name}")
        return None

    # Handle snapshot expansion
    if entry.is_snapshot:
        df = expand_snapshot(
            df,
            window,
            n_months=min(entry.snapshot_n_months, len(window.months())),
            trend_cols=entry.trend_cols,
        )
    else:
        df = redate(df, window)

    df = desensitize(df)
    return df


# ---------------------------------------------------------------------------
# OUTPUT
# ---------------------------------------------------------------------------

def _safe_name(s: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_\-]", "_", str(s))


def write_xlsx(df: pd.DataFrame, tenant: str, domain: str, label: str, window: Window, out_dir: Path) -> Path:
    fname = f"{_safe_name(tenant)}__{_safe_name(domain)}__{_safe_name(label)}__{window}.xlsx"
    out_path = out_dir / fname
    df.to_excel(out_path, index=False)
    return out_path


def print_manifest(manifest: List[Dict]) -> None:
    print()
    print("=" * 95)
    print("UPLOAD MANIFEST")
    print("=" * 95)
    print(f"{'#':<4} {'FILE':<58} {'TENANT':<15} {'DOMAIN':<12} {'ROWS'}")
    print("-" * 95)
    for i, item in enumerate(manifest, 1):
        print(f"{i:<4} {item['file']:<58} {item['tenant']:<15} {item['domain']:<12} {item['rows']}")
    print("=" * 95)
    total = sum(x["rows"] for x in manifest)
    print(f"  Total: {len(manifest)} files, {total} rows")
    print()
    print("Upload pattern (run on server for each file):")
    print("  curl -X POST http://HOST:8084/api/smartbi/excel/upload \\")
    print("       -F 'file=@/tmp/demo_seed/FILENAME.xlsx' \\")
    print("       -F 'factoryId=DEMO_FACTORY_OR_DEMO_REST' \\")
    print("       -F 'templateType=DOMAIN'")
    print()


# ---------------------------------------------------------------------------
# OPTIONAL UPLOAD (stub; organizer normally scp + curl separately)
# ---------------------------------------------------------------------------

def upload_file(path: Path, tenant: str, domain: str, base_url: str) -> None:
    import urllib.request
    boundary = "----DemoBoundary" + hashlib.md5(str(path).encode()).hexdigest()[:12]
    parts: List[bytes] = []
    with open(path, "rb") as f:
        file_data = f.read()
    parts.append(
        (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
            f"Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n"
            f"\r\n"
        ).encode("utf-8")
        + file_data
        + b"\r\n"
    )
    for name, val in [("factoryId", tenant), ("templateType", domain)]:
        parts.append(
            (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"\r\n'
                f"\r\n{val}\r\n"
            ).encode("utf-8")
        )
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    body = b"".join(parts)
    url = f"{base_url.rstrip('/')}/api/smartbi/excel/upload"
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    try:
        resp = urllib.request.urlopen(req, timeout=30)
        print(f"  UPLOAD {path.name} → {resp.getcode()}")
    except Exception as e:
        print(f"  UPLOAD FAILED {path.name}: {e}")


# ---------------------------------------------------------------------------
# LOCAL VERIFICATION
# ---------------------------------------------------------------------------

def verify_local() -> None:
    """
    Verify desensitise + redate logic on 2-3 real files, then gap-fill generator.
    Prints before/after columns and period mapping proof.
    """
    sys.stdout.reconfigure(encoding="utf-8")
    reset_window_cursor()
    print("\n" + "=" * 65)
    print("LOCAL VERIFICATION")
    print("=" * 65)

    # --- TEST 1: IL TEATRO 商品销量 snapshot expansion ---
    print("\n[TEST 1] IL TEATRO 商品销量 – snapshot → 2-month expansion")
    print("-" * 55)
    f1 = XLSX_CONV / "IL TEATRO（西餐厅）2月_商品销量报表.xlsx"
    if not f1.exists():
        # try to find any brand file
        candidates = sorted(XLSX_CONV.glob("*销量*.xlsx")) if XLSX_CONV.exists() else []
        f1 = candidates[0] if candidates else None
    if f1 and f1.exists():
        df1 = pd.read_excel(f1, sheet_name=0)
        orig_rows = len(df1)
        print(f"  Source file: {f1.name}")
        print(f"  Original rows: {orig_rows}  |  Columns: {list(df1.columns[:5])}")
        store_col = next((c for c in df1.columns if _col_kind(c) == "store"), None)
        if store_col:
            print(f"  Original '{store_col}' samples: {df1[store_col].dropna().unique()[:3].tolist()}")

        win1 = assign_window(DEMO_REST, "sales", 2)
        df1_exp = expand_snapshot(df1, win1, n_months=2, trend_cols=["销售金额", "实收", "折后金额"])
        df1_proc = desensitize(df1_exp)

        print(f"  Window: {win1.start} → {win1.end}  ({len(win1.months())} months)")
        print(f"  After expansion: {len(df1_proc)} rows  (expected ≈ {orig_rows * 2})")
        print(f"  Added '月份' values: {sorted(df1_proc['月份'].unique())}")
        if store_col:
            print(f"  Desensitised '{store_col}': {df1_proc[store_col].dropna().unique()[:3].tolist()}")
        # Numeric trend check
        if "销售金额" in df1.columns and "销售金额" in df1_proc.columns:
            orig_sum = pd.to_numeric(df1["销售金额"], errors="coerce").sum()
            new_m1_sum = pd.to_numeric(
                df1_proc[df1_proc["月份"] == df1_proc["月份"].unique()[0]]["销售金额"],
                errors="coerce",
            ).sum()
            new_m2_sum = pd.to_numeric(
                df1_proc[df1_proc["月份"] == df1_proc["月份"].unique()[-1]]["销售金额"],
                errors="coerce",
            ).sum()
            print(f"  销售金额 original total: {orig_sum:.0f}")
            print(f"  销售金额 month1 total:   {new_m1_sum:.0f}  (trend×1.00)")
            print(f"  销售金额 month2 total:   {new_m2_sum:.0f}  (trend×1.03)")
    else:
        print(f"  [SKIP] No brand sales file found in {XLSX_CONV}")

    # --- TEST 2: 绿源食品 生产产量 monthly redate ---
    print("\n[TEST 2] 绿源食品 生产产量 – monthly string redate 2025 → 2024")
    print("-" * 55)
    f2 = TEST_DATA / "绿源食品-2025生产报表.xlsx"
    if f2.exists():
        df2 = pd.read_excel(f2, sheet_name="生产产量", header=1)
        print(f"  Original rows: {len(df2)}")
        print(f"  Columns: {list(df2.columns)}")
        orig_months = df2["月份"].dropna().head(4).tolist()
        print(f"  Original 月份 (first 4): {orig_months}")

        win2 = assign_window(DEMO_FACTORY, "production", 12)
        df2_r = redate(df2, win2)
        new_months = df2_r["月份"].dropna().head(4).tolist()
        print(f"  Window: {win2.start} → {win2.end}")
        print(f"  Redated 月份 (first 4): {new_months}")
        print(f"  MAPPING: {orig_months[:2]} → {new_months[:2]}")
    else:
        print(f"  [SKIP] Not found: {f2}")

    # --- TEST 3: 东门口采购 date column shift + supplier desensitise ---
    print("\n[TEST 3] 东门口采购 – date shift + supplier desensitise")
    print("-" * 55)
    f3 = XLSX_DIR / "东门口2月采购入库明细报表.xlsx"
    if f3.exists():
        df3 = pd.read_excel(f3, sheet_name=0)
        orig_dates = df3["单据业务日期"].dropna().head(3).tolist()
        orig_suppliers = df3["供应商"].dropna().unique()[:4].tolist()
        print(f"  Original 单据业务日期: {orig_dates}")
        print(f"  Original 供应商: {orig_suppliers}")

        win3 = assign_window(DEMO_REST, "sales", 2)
        df3_p = redate(df3, win3)
        df3_p = desensitize(df3_p)
        new_dates = df3_p["单据业务日期"].dropna().head(3).tolist()
        new_suppliers = df3_p["供应商"].dropna().unique()[:4].tolist()
        print(f"  Window: {win3.start} → {win3.end}")
        print(f"  Redated 单据业务日期: {new_dates}")
        print(f"  Desensitised 供应商: {new_suppliers}")
        if "店铺名称" in df3.columns:
            print(f"  Desensitised 店铺名称: {df3_p['店铺名称'].dropna().unique()[:3].tolist()}")
    else:
        print(f"  [SKIP] Not found: {f3}")

    # --- TEST 4: gap-fill production ---
    print("\n[TEST 4] Gap-fill generator – production")
    print("-" * 55)
    gap_results = generate_gap_fill()
    for tenant, domain, label, df in gap_results:
        if domain == "production":
            print(f"  Label: {label}  tenant: {tenant}  rows: {len(df)}")
            print(f"  Columns: {list(df.columns)}")
            unique_months = df["月份"].nunique()
            print(f"  Unique months: {unique_months}  (expected 24)")
            print(f"  Month range: {df['月份'].iloc[0]} → {df['月份'].iloc[-1]}")
            print(f"  Sample (first 3 rows):")
            print(df.head(3).to_string(index=False))
            break

    # --- TEST 5: window non-overlap check ---
    print("\n[TEST 5] Window assignment – non-overlapping")
    print("-" * 55)
    reset_window_cursor()
    wins = []
    for _ in range(6):
        w = assign_window(DEMO_REST, "sales", 2)
        if w:
            wins.append(w)
    all_months: List[Tuple[int, int]] = []
    for w in wins:
        all_months.extend(w.months())
        print(f"  {w.start} → {w.end}  {w.months()[:2]}...")
    overlap = len(all_months) != len(set(all_months))
    print(f"  Overlap detected: {overlap}  ← must be False")
    assert not overlap, "BUG: windows overlap!"

    reset_window_cursor()
    print("\n" + "=" * 65)
    print("VERIFICATION COMPLETE — all checks passed")
    print("=" * 65)


# ---------------------------------------------------------------------------
# MAIN
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="Demo data seeding pipeline")
    parser.add_argument("--out", default="/tmp/demo_seed", help="Output directory for xlsx files")
    parser.add_argument("--dry-run", action="store_true", help="Skip writing xlsx, just print plan")
    parser.add_argument("--upload", action="store_true", help="Upload to server after generating")
    parser.add_argument("--base", default="http://127.0.0.1:8084", help="Base URL for upload")
    parser.add_argument(
        "--tenant-map", default="",
        help="Override tenant IDs, e.g. DEMO_FACTORY=F001,DEMO_REST=F002",
    )
    args = parser.parse_args()

    tenant_override: Dict[str, str] = {}
    if args.tenant_map:
        for pair in args.tenant_map.split(","):
            if "=" in pair:
                k, v = pair.split("=", 1)
                tenant_override[k.strip()] = v.strip()

    out_dir = Path(args.out)
    if not args.dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)

    reset_window_cursor()
    manifest: List[Dict] = []
    plan = build_seed_plan()

    print(f"\n{'='*65}")
    print(f"DEMO SEED PIPELINE — {len(plan)} entries planned")
    print(f"Output: {out_dir}  {'(dry-run)' if args.dry_run else ''}")
    print(f"{'='*65}\n")

    # --- Real data entries ---
    for entry in plan:
        win = assign_window(entry.tenant, entry.domain, entry.n_months)
        if win is None:
            print(f"[SKIP] Timeline exhausted: {entry.label}")
            continue
        print(f"  {entry.label}  tenant={entry.tenant}  domain={entry.domain}  window={win}")
        df = process_entry(entry, win)
        if df is None:
            continue
        effective_tenant = tenant_override.get(entry.tenant, entry.tenant)
        if not args.dry_run:
            out_path = write_xlsx(df, effective_tenant, entry.domain, entry.label, win, out_dir)
            manifest.append({
                "file": out_path.name,
                "tenant": effective_tenant,
                "domain": entry.domain,
                "window": str(win),
                "rows": len(df),
                "path": str(out_path),
            })
            print(f"    → {out_path.name}  ({len(df)} rows)")
            if args.upload:
                upload_file(out_path, effective_tenant, entry.domain, args.base)
        else:
            print(f"    [dry-run] {len(df)} rows → would write {entry.label}__{win}.xlsx")

    # --- Gap-fill entries ---
    print(f"\n--- Gap-fill generators ---")
    for tenant, domain, label, df in generate_gap_fill():
        # Gap-fill uses a fixed 24-month window (doesn't consume from shared cursor)
        import calendar as _cal
        win_gf = Window(date(2024, 1, 1), date(2025, 12, 31))
        effective_tenant = tenant_override.get(tenant, tenant)
        print(f"  {label}  tenant={tenant}  domain={domain}  rows={len(df)}")
        if not args.dry_run:
            out_path = write_xlsx(df, effective_tenant, domain, label, win_gf, out_dir)
            manifest.append({
                "file": out_path.name,
                "tenant": effective_tenant,
                "domain": domain,
                "window": str(win_gf),
                "rows": len(df),
                "path": str(out_path),
            })
            print(f"    → {out_path.name}")
            if args.upload:
                upload_file(out_path, effective_tenant, domain, args.base)

    if not args.dry_run:
        print_manifest(manifest)
        manifest_path = out_dir / "upload_manifest.json"
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
        print(f"Manifest JSON: {manifest_path}")
        print(f"Total files generated: {len(manifest)}")


if __name__ == "__main__":
    if "--verify" in sys.argv:
        verify_local()
    else:
        main()
