#!/usr/bin/env python3
"""Sprint 12 expanded Workdesk E2E analyzer — 60 paths (vs baseline 12).

Per Sprint 12 close-gate "Strict useful rate ≥80%": applies same DOUBLE-assertion
verdict as the original 2026-05-22 audit but extended to 10 paths per Workdesk.

Usage:
  PYTHONIOENCODING=utf-8 python3 analyze-expanded.py <RUN_DIR>

Output:
  - markdown summary table (stdout)
  - $RUN_DIR/analysis.json (machine-readable per-path details)
  - $RUN_DIR/summary.md (per-Workdesk aggregate)
"""
import json
import os
import re
import sys
from collections import defaultdict

if len(sys.argv) < 2:
    print("Usage: analyze-expanded.py <RUN_DIR>", file=sys.stderr)
    sys.exit(2)

RUN_DIR = sys.argv[1].rstrip("/").rstrip("\\")

# Verdict checks (same as 2026-05-22 baseline analyzer)
NEGATIVE_MARKERS = ["暂不支持", "_toolCount", "_executionOrder", "_reasoning"]
UNDERSCORE_KEY_RE = re.compile(r'_[a-zA-Z]+":')
BARE_DONE_RE = re.compile(r'^查询完成[\s。\.]*$')
JSON_BRACE_RATIO_THRESHOLD = 0.05
ALL_CHINESE_RE = re.compile(r'[一-鿿]')
DOMAIN_KEYWORDS_RE = re.compile(
    r'¥\s*\d|N件|N个|本月|今日|订单|批次|客户|供应商|今天|昨天|本周|本季|去年|同比|环比|应收|应付|采购|入库|出库|质检|跟进|商机|经营|月度|周度|日度|采购单|放行|HACCP|告警|微信|通话|销售|入库单|物料|批号|追溯|季度|上季|下季|营收|业绩|利润|毛利|净利|KPI|OEE|生产率|合格率|交付|生产批次|进行中|产量|今日产量|kg'
)
BARE_TEMPLATE_RE = re.compile(r'^查询完成\s*\n?\s*包含\s*\d+\s*项数据指标')

# Path-id categories
PATH_CATEGORIES = {
    "A-base": "baseline",
    "B-base": "baseline",
    "B-syn1": "synonym",
    "B-syn2": "synonym",
    "B-syn3": "synonym",
    "Bd-empty": "boundary",
    "Bd-large": "boundary",
    "Bd-period": "boundary",
    "Bd-cross-fac": "boundary",
    "Bd-vague": "boundary",
    # Real-data paths (runner-data.sh)
    "rd-today": "real-data",
    "rd-month": "real-data",
    "rd-quarter": "real-data",
    "rd-alert": "real-data",
    "rd-golden-pass": "real-data",
    "rd-named": "real-data",
    "rd-opp": "real-data",
    "rd-renew": "real-data",
    "rd-rank": "real-data",
    "rd-stuck": "real-data",
    "rd-payable": "real-data",
    "rd-invoice": "real-data",
    "rd-cashflow": "real-data",
    "rd-cost": "real-data",
    "rd-margin": "real-data",
    "rd-trace": "real-data",
    "rd-supplier": "real-data",
    "rd-defect": "real-data",
    "rd-rework": "real-data",
    "rd-cert": "real-data",
    "rd-inventory": "real-data",
    "rd-aging": "real-data",
    "rd-loss": "real-data",
    "rd-pending": "real-data",
    "rd-price": "real-data",
    "rd-savings": "real-data",
    "rd-overdue": "real-data",
    "rd-trend": "real-data",
    "rd-haccp": "real-data",
    "rd-deviation": "real-data",
    "rd-corrective": "real-data",
    "rd-audit": "real-data",
}

WORKDESKS = ["sales-owner", "finance-manager", "quality-manager",
             "warehouse-keeper", "purchaser", "quality-chief"]


def analyze_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        body = json.load(f)
    data = body.get("data") or {}
    status = data.get("status")
    formattedText = data.get("formattedText") or ""
    message = data.get("message") or ""
    resultData = data.get("resultData")
    user_text = formattedText or message
    content_len = len(user_text)
    text_visible = formattedText or message

    negatives = []
    for m in NEGATIVE_MARKERS:
        if m in text_visible:
            negatives.append(m)
    if UNDERSCORE_KEY_RE.search(text_visible):
        for f_ in set(UNDERSCORE_KEY_RE.findall(text_visible)):
            negatives.append(f_.rstrip('":') + "(underscore-key)")
    if BARE_DONE_RE.match(user_text.strip()):
        negatives.append("bare-查询完成")
    if BARE_TEMPLATE_RE.match(user_text.strip()):
        negatives.append("bare-template-N项数据指标")
    if content_len > 0:
        brace_count = user_text.count('{') + user_text.count('}')
        ratio = brace_count / content_len
        if ratio > JSON_BRACE_RATIO_THRESHOLD:
            negatives.append(f"json-dump({ratio:.2%})")

    chinese_count = len(ALL_CHINESE_RE.findall(user_text))
    chinese_run_ok = chinese_count >= 20
    domain_keyword_match = DOMAIN_KEYWORDS_RE.search(user_text)
    domain_kw = domain_keyword_match.group(0) if domain_keyword_match else None

    # Strict verdict
    strict_verdict = "PASS"
    if negatives:
        strict_verdict = "FAIL"
    if not chinese_run_ok or not domain_kw:
        if strict_verdict == "PASS":
            strict_verdict = "FAIL"
    if status == "FAILED":
        strict_verdict = "FAIL"

    # Operational verdict (NEED_CLARIFICATION counts as useful if has 2+ choices)
    operational_verdict = strict_verdict
    if status == "NEED_CLARIFICATION":
        # Look for inline numbered choices "1. X\n2. Y" or "1. X 2. Y"
        choice_pattern = re.search(r'1\.\s*\S+.*?2\.\s*\S+', user_text, re.DOTALL)
        if choice_pattern:
            operational_verdict = "USEFUL_NEED_CLARIFY"
        # Boundary cases (cross-factory, vague) NEED_CLARIFICATION is expected behavior
        # and a clarification with choices is still useful operationally

    return {
        "status": status,
        "content_len": content_len,
        "preview": user_text[:200],
        "negatives": negatives,
        "chinese_run_ok": chinese_run_ok,
        "domain_kw": domain_kw,
        "strict_verdict": strict_verdict,
        "operational_verdict": operational_verdict,
    }


# Collect all files
results = {}
for fn in sorted(os.listdir(RUN_DIR)):
    if not fn.startswith("raw-") or not fn.endswith(".json"):
        continue
    base = fn[len("raw-"):-len(".json")]
    parts = base.rsplit("-", 1)
    if len(parts) != 2:
        continue
    workdesk, path_id = parts
    if workdesk not in WORKDESKS:
        # Handle multi-segment workdesk like "sales-owner-A-base"
        for wd in WORKDESKS:
            if base.startswith(wd + "-"):
                workdesk = wd
                path_id = base[len(wd) + 1:]
                break
    r = analyze_file(os.path.join(RUN_DIR, fn))
    r["workdesk"] = workdesk
    r["path_id"] = path_id
    r["category"] = PATH_CATEGORIES.get(path_id, "unknown")
    results[f"{workdesk}-{path_id}"] = r

# Aggregate per-Workdesk
agg = defaultdict(lambda: {"total": 0, "strict_pass": 0, "operational_useful": 0})
for key, r in results.items():
    wd = r["workdesk"]
    agg[wd]["total"] += 1
    if r["strict_verdict"] == "PASS":
        agg[wd]["strict_pass"] += 1
    if r["operational_verdict"] in {"PASS", "USEFUL_NEED_CLARIFY"}:
        agg[wd]["operational_useful"] += 1

# Print
print(f"# Sprint 12 Workdesk E2E Expanded — {os.path.basename(RUN_DIR)}")
print()
print("## Per-Workdesk Summary")
print()
print("| Workdesk | Total | Strict PASS | Strict % | Operational | Op % |")
print("|---|---:|---:|---:|---:|---:|")
total_all = 0
strict_all = 0
op_all = 0
for wd in WORKDESKS:
    a = agg[wd]
    total_all += a["total"]
    strict_all += a["strict_pass"]
    op_all += a["operational_useful"]
    sp = (a["strict_pass"] / a["total"] * 100) if a["total"] else 0
    op = (a["operational_useful"] / a["total"] * 100) if a["total"] else 0
    print(f"| {wd} | {a['total']} | {a['strict_pass']} | {sp:.1f}% | {a['operational_useful']} | {op:.1f}% |")
sp_all = (strict_all / total_all * 100) if total_all else 0
op_total = (op_all / total_all * 100) if total_all else 0
print(f"| **TOTAL** | **{total_all}** | **{strict_all}** | **{sp_all:.1f}%** | **{op_all}** | **{op_total:.1f}%** |")

print()
print("## Close-gate row: Strict useful rate ≥80%")
print(f"- Strict overall: **{sp_all:.1f}%** ({'PASS' if sp_all >= 80 else 'FAIL — gap %.1fpp' % (80 - sp_all)})")
print(f"- Operational overall: **{op_total:.1f}%** ({'PASS' if op_total >= 100 else 'FAIL — %d FAILED' % (total_all - op_all)})")

print()
print("## Per-path detail (FAIL only)")
print()
print("| Workdesk | Path | Category | Status | Len | Strict | Op | Negative | CN run | Domain kw |")
print("|---|---|---|---|---:|---|---|---|---|---|")
for key in sorted(results.keys()):
    r = results[key]
    if r["strict_verdict"] == "PASS":
        continue
    neg = ", ".join(r["negatives"]) or "—"
    cn = "✓" if r["chinese_run_ok"] else "✗"
    kw = r["domain_kw"] or "✗"
    print(f"| {r['workdesk']} | {r['path_id']} | {r['category']} | {r['status']} | {r['content_len']} | **{r['strict_verdict']}** | {r['operational_verdict']} | {neg[:50]} | {cn} | `{kw}` |")

# Save JSON
with open(os.path.join(RUN_DIR, "analysis.json"), 'w', encoding='utf-8') as f:
    json.dump(results, f, ensure_ascii=False, indent=2)

print(f"\n\nSaved {RUN_DIR}/analysis.json")
