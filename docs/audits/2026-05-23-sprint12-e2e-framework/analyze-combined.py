#!/usr/bin/env python3
"""Sprint 12 combined analyzer — aggregates baseline+boundary (60) + real-data (60) = 120 paths.

Usage:
  PYTHONIOENCODING=utf-8 python3 analyze-combined.py <BASELINE_DIR> <DATA_DIR>

Produces:
  - markdown summary (stdout): per-Workdesk strict % for each category + combined
  - {DATA_DIR}/combined-analysis.json: machine-readable per-path verdicts
"""
import json
import os
import re
import sys
from collections import defaultdict

if len(sys.argv) < 3:
    print("Usage: analyze-combined.py <BASELINE_DIR> <DATA_DIR>", file=sys.stderr)
    sys.exit(2)

BASELINE_DIR = sys.argv[1].rstrip("/").rstrip("\\")
DATA_DIR = sys.argv[2].rstrip("/").rstrip("\\")

NEGATIVE_MARKERS = ["暂不支持", "_toolCount", "_executionOrder", "_reasoning"]
UNDERSCORE_KEY_RE = re.compile(r'_[a-zA-Z]+":')
BARE_DONE_RE = re.compile(r'^查询完成[\s。\.]*$')
JSON_BRACE_RATIO_THRESHOLD = 0.05
ALL_CHINESE_RE = re.compile(r'[一-鿿]')
DOMAIN_KEYWORDS_RE = re.compile(
    r'¥\s*\d|N件|N个|本月|今日|订单|批次|客户|供应商|今天|昨天|本周|本季|去年|同比|环比|应收|应付|采购|入库|出库|质检|跟进|商机|经营|月度|周度|日度|采购单|放行|HACCP|告警|微信|通话|销售|入库单|物料|批号|追溯|季度|上季|下季|营收|业绩|利润|毛利|净利|KPI|OEE|生产率|合格率|交付|生产批次|进行中|产量|今日产量|kg|收货|进货|缺料|补货|盘亏|库龄|准时率|节约|偏差|纠正措施|内审|放行批次'
)
BARE_TEMPLATE_RE = re.compile(r'^查询完成\s*\n?\s*包含\s*\d+\s*项数据指标')

WORKDESKS = ["sales-owner", "finance-manager", "quality-manager",
             "warehouse-keeper", "purchaser", "quality-chief"]


def analyze_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        body = json.load(f)
    data = body.get("data") or {}
    status = data.get("status")
    formattedText = data.get("formattedText") or ""
    message = data.get("message") or ""
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

    strict_verdict = "PASS"
    if negatives:
        strict_verdict = "FAIL"
    if not chinese_run_ok or not domain_keyword_match:
        if strict_verdict == "PASS":
            strict_verdict = "FAIL"
    if status == "FAILED" or status is None:
        strict_verdict = "FAIL"

    operational_verdict = strict_verdict
    if status == "NEED_CLARIFICATION":
        choice_pattern = re.search(r'1\.\s*\S+.*?2\.\s*\S+', user_text, re.DOTALL)
        if choice_pattern:
            operational_verdict = "USEFUL_NEED_CLARIFY"

    return {
        "status": status,
        "content_len": content_len,
        "negatives": negatives,
        "chinese_run_ok": chinese_run_ok,
        "domain_kw": domain_keyword_match.group(0) if domain_keyword_match else None,
        "strict_verdict": strict_verdict,
        "operational_verdict": operational_verdict,
    }


results = {}
for label, run_dir in [("baseline", BASELINE_DIR), ("data", DATA_DIR)]:
    if not os.path.isdir(run_dir):
        print(f"WARN: {run_dir} not a dir", file=sys.stderr)
        continue
    for fn in sorted(os.listdir(run_dir)):
        if not fn.startswith("raw-") or not fn.endswith(".json"):
            continue
        base = fn[len("raw-"):-len(".json")]
        workdesk = None
        path_id = None
        for wd in WORKDESKS:
            if base.startswith(wd + "-"):
                workdesk = wd
                path_id = base[len(wd) + 1:]
                break
        if not workdesk:
            continue
        r = analyze_file(os.path.join(run_dir, fn))
        r["workdesk"] = workdesk
        r["path_id"] = path_id
        r["category"] = label
        results[f"{label}-{workdesk}-{path_id}"] = r

# Aggregate per-Workdesk per-category
agg = defaultdict(lambda: defaultdict(lambda: {"total": 0, "strict_pass": 0, "operational_useful": 0}))
for key, r in results.items():
    wd = r["workdesk"]
    cat = r["category"]
    agg[wd][cat]["total"] += 1
    agg[wd]["combined"]["total"] += 1
    if r["strict_verdict"] == "PASS":
        agg[wd][cat]["strict_pass"] += 1
        agg[wd]["combined"]["strict_pass"] += 1
    if r["operational_verdict"] in {"PASS", "USEFUL_NEED_CLARIFY"}:
        agg[wd][cat]["operational_useful"] += 1
        agg[wd]["combined"]["operational_useful"] += 1

print("# Sprint 12 Combined Audit — 120 paths (baseline 60 + real-data 60)")
print()
print(f"## Per-Workdesk Strict % by Category")
print()
print("| Workdesk | Baseline strict % | Data strict % | Combined strict % | Total |")
print("|---|---:|---:|---:|---:|")
for wd in WORKDESKS:
    b = agg[wd]["baseline"]
    d = agg[wd]["data"]
    c = agg[wd]["combined"]
    bp = (b["strict_pass"] / b["total"] * 100) if b["total"] else 0
    dp = (d["strict_pass"] / d["total"] * 100) if d["total"] else 0
    cp = (c["strict_pass"] / c["total"] * 100) if c["total"] else 0
    print(f"| {wd} | {bp:.1f}% ({b['strict_pass']}/{b['total']}) | {dp:.1f}% ({d['strict_pass']}/{d['total']}) | **{cp:.1f}%** ({c['strict_pass']}/{c['total']}) | {c['total']} |")

# Combined total
t_total = sum(agg[wd]["combined"]["total"] for wd in WORKDESKS)
t_pass = sum(agg[wd]["combined"]["strict_pass"] for wd in WORKDESKS)
t_op = sum(agg[wd]["combined"]["operational_useful"] for wd in WORKDESKS)
t_strict_pct = (t_pass / t_total * 100) if t_total else 0
t_op_pct = (t_op / t_total * 100) if t_total else 0
print(f"| **TOTAL** | | | **{t_strict_pct:.1f}%** ({t_pass}/{t_total}) | {t_total} |")
print()
print(f"## Close-gate Rows")
print(f"- E2E total rounds: **{t_total}** (target ≥120: {'PASS' if t_total >= 120 else 'FAIL'})")
print(f"- Strict useful rate: **{t_strict_pct:.1f}%** (target ≥80%: {'PASS' if t_strict_pct >= 80 else 'FAIL'})")
print(f"- Operational useful rate: **{t_op_pct:.1f}%** (target 100%: {'PASS' if t_op_pct >= 100 else f'gap {100-t_op_pct:.1f}pp'})")

with open(os.path.join(DATA_DIR, "combined-analysis.json"), 'w', encoding='utf-8') as f:
    json.dump(results, f, ensure_ascii=False, indent=2)

print(f"\nSaved {DATA_DIR}/combined-analysis.json ({len(results)} paths)")
