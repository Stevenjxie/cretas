"""Generate monthly-progressive demo uploads to exercise Phase 2 fusion.

Unlike the snapshot fixtures in tests/generate_*.py (one file per scenario,
re-uploaded identically), this produces **same-template, month-by-month**
single-sheet Excels with realistic trends — the exact 同模板按月累积 scenario
that the unified-source timeseries fusion is built for.

Columns are drawn from the controlled vocab (domain_standard_fields synonyms),
so the Phase 0 mapper tags them canonical and the Phase 2 extractor/writer
populate smart_bi_timeseries per (period, canonical_field, dims).

Usage (on server, against test 8084 by default):
    venv38/bin/python -m smartbi.scripts.gen_fusion_demo_data \
        --base http://localhost:8084 --factory DEMO_FUSION_F --months 6
Each domain × month = one upload (monthly report). Fusion then stitches them.
"""
from __future__ import annotations
import argparse
import json
import os
import subprocess
import tempfile
import time

import pandas as pd

# Period column uses a `period`-canonical synonym (月份) so it becomes the axis.
# Each domain: dimension column(s) + measure columns, all controlled-vocab synonyms.
DOMAINS = {
    "production": {
        "dims": {"工序": ["焯水", "油炸", "包装"]},
        # name -> (base, monthly_growth)  measures
        "measures": {
            "投入数量": (1000, 0.05),
            "产出数量": (900, 0.06),
            "合格数量": (860, 0.07),
            "出成率": (0.90, 0.008),   # rate, gentle climb
            "不良率": (0.05, -0.05),   # rate, declines
        },
    },
    "sales": {
        "dims": {"客户名称": ["华东超市", "华南连锁"]},
        "measures": {
            "销售额": (50000, 0.08),
            "销售量": (1200, 0.06),
            "单价": (41.6, 0.01),
        },
    },
    "purchase": {
        "dims": {"供应商": ["北京飞熊", "山东海产"]},
        "measures": {
            "采购金额": (30000, 0.04),
            "采购量": (800, 0.03),
            "采购单价": (37.5, 0.01),
        },
    },
    "inventory": {
        "dims": {"物料名称": ["冻虾仁", "包装盒"]},
        "measures": {
            "库存数量": (2000, 0.02),
            "库存金额": (60000, 0.03),
            "周转率": (4.2, 0.05),
        },
    },
}


def _month_labels(n: int) -> list[str]:
    return [f"2026-{m:02d}" for m in range(1, n + 1)]


def _value(base: float, growth: float, month_idx: int, dim_idx: int) -> float:
    # Trend over months + small per-dim offset; keep rates sane.
    v = base * (1 + growth * month_idx) * (1 + 0.07 * dim_idx)
    if base <= 1.0:  # a rate
        return round(min(max(v, 0.0), 0.999), 4)
    return round(v, 2)


def build_excel(domain: str, month: str, month_idx: int, path: str) -> int:
    cfg = DOMAINS[domain]
    (dim_col, dim_vals), = cfg["dims"].items()
    rows = []
    for di, dv in enumerate(dim_vals):
        row = {"月份": month, dim_col: dv}
        for mname, (base, growth) in cfg["measures"].items():
            row[mname] = _value(base, growth, month_idx, di)
        rows.append(row)
    pd.DataFrame(rows).to_excel(path, index=False)
    return len(rows)


def upload(base: str, factory: str, path: str) -> int | None:
    out = subprocess.run(
        ["curl", "-s", "-m", "60", "-F", f"file=@{path}",
         "-F", f"factory_id={factory}", f"{base}/api/smartbi/excel/auto-parse-async"],
        capture_output=True, text=True,
    ).stdout
    try:
        return json.loads(out)["uploadId"]
    except Exception:
        print("  upload failed:", out[:200])
        return None


def poll(base: str, factory: str, upload_id: int, timeout: int = 40) -> str:
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = subprocess.run(
            ["curl", "-s", "-m", "10",
             f"{base}/api/smartbi/excel/auto-parse-status/{upload_id}?factory_id={factory}"],
            capture_output=True, text=True,
        ).stdout
        try:
            st = json.loads(out).get("status")
        except Exception:
            st = None
        if st in ("COMPLETED", "FAILED"):
            return st
        time.sleep(2)
    return "TIMEOUT"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8084")
    ap.add_argument("--factory", default="DEMO_FUSION_F")
    ap.add_argument("--months", type=int, default=6)
    ap.add_argument("--domains", default=",".join(DOMAINS))
    args = ap.parse_args()

    months = _month_labels(args.months)
    domains = [d.strip() for d in args.domains.split(",") if d.strip() in DOMAINS]
    total_uploads = total_ok = 0
    tmpdir = tempfile.mkdtemp(prefix="fusion_demo_")
    for domain in domains:
        for i, month in enumerate(months):
            path = os.path.join(tmpdir, f"{domain}_{month}.xlsx")
            nrows = build_excel(domain, month, i, path)
            uid = upload(args.base, args.factory, path)
            total_uploads += 1
            if uid is None:
                continue
            st = poll(args.base, args.factory, uid)
            ok = st == "COMPLETED"
            total_ok += ok
            print(f"  {domain} {month}: rows={nrows} upload={uid} {st}")
    print(f"\nDone: {total_ok}/{total_uploads} uploads COMPLETED "
          f"(factory={args.factory}, domains={domains}, months={months})")


if __name__ == "__main__":
    main()
