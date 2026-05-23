#!/usr/bin/env python3
"""aggregate-rounds.py — generate audit doc from Playwright JSON results.

Reads test-results/canvas-results.json and writes
docs/audits/2026-05-23-e2e-340-rounds-coverage.md with:
  - Total round count, PASS/FAIL/SKIP breakdown
  - Per-Tab matrix (PASS/FAIL/SKIP counts)
  - List of failures (test name + error)
  - 4位一体 UX completeness score per Tab

Usage:
    python aggregate-rounds.py <results-json> <output-md>
"""
from __future__ import annotations

import json
import os
import sys
from collections import defaultdict
from datetime import datetime


def collect_specs(node, specs):
    """Recursively walk the Playwright JSON tree to collect spec/test records."""
    if isinstance(node, dict):
        if "specs" in node:
            for spec in node["specs"]:
                specs.append(spec)
        for sub in node.get("suites", []):
            collect_specs(sub, specs)


def tab_key_from_file(file_path: str) -> str:
    base = os.path.basename(file_path)
    # alerts-20rounds.spec.ts -> alerts
    if base.endswith(".spec.ts"):
        base = base[: -len(".spec.ts")]
    if base.endswith("-20rounds"):
        base = base[: -len("-20rounds")]
    return base


def main():
    results_json = sys.argv[1] if len(sys.argv) > 1 else "tests/v1-e2e/test-results/canvas-results.json"
    out_md = sys.argv[2] if len(sys.argv) > 2 else "docs/audits/2026-05-23-e2e-340-rounds-coverage.md"

    with open(results_json, "r", encoding="utf-8") as f:
        data = json.load(f)

    # Walk the entire tree
    all_specs = []
    for s in data.get("suites", []):
        collect_specs(s, all_specs)

    # Group by file
    per_file = defaultdict(lambda: {"pass": 0, "fail": 0, "skip": 0, "tests": []})
    failures = []

    def walk(node, file_hint=""):
        if isinstance(node, dict):
            file_hint = node.get("file", file_hint)
            for spec in node.get("specs", []):
                spec_file = spec.get("file", file_hint)
                for t in spec.get("tests", []):
                    # Playwright JSON: test-level status is "expected" / "unexpected" / "skipped" / "flaky"
                    test_status = t.get("status", "")
                    title = spec.get("title", "")
                    if test_status == "expected":
                        per_file[spec_file]["pass"] += 1
                    elif test_status == "skipped":
                        per_file[spec_file]["skip"] += 1
                    elif test_status in ("unexpected", "flaky"):
                        per_file[spec_file]["fail"] += 1
                        # Capture error from last result
                        results = t.get("results", [])
                        err = ""
                        if results:
                            r = results[-1]
                            for ev in r.get("errors", []):
                                err = (ev.get("message") or "")[:300]
                                break
                            if not err:
                                err = (r.get("error", {}).get("message") or "")[:300]
                        failures.append({
                            "file": spec_file,
                            "title": title,
                            "error": err,
                            "duration": results[-1].get("duration", 0) if results else 0,
                        })
                    per_file[spec_file]["tests"].append({"title": title, "status": test_status})
            for sub in node.get("suites", []):
                walk(sub, file_hint)

    for s in data.get("suites", []):
        walk(s)

    # Aggregate stats
    total = sum(v["pass"] + v["fail"] + v["skip"] for v in per_file.values())
    total_pass = sum(v["pass"] for v in per_file.values())
    total_fail = sum(v["fail"] for v in per_file.values())
    total_skip = sum(v["skip"] for v in per_file.values())
    pass_rate = (total_pass / total * 100) if total else 0

    # Build the report
    os.makedirs(os.path.dirname(out_md) or ".", exist_ok=True)
    lines = []
    lines.append(f"# Canvas E2E 17-Tab × 20-Round Coverage Matrix")
    lines.append("")
    lines.append(f"**Generated:** {datetime.now().isoformat(timespec='seconds')}")
    lines.append(f"**Source:** `{results_json}`")
    lines.append(f"**Test env:** http://139.196.165.140:8097 (test) → backend 10011 (Java) / 8084 (Python)")
    lines.append(f"**Auth:** f006_admin / F006 (FACTORY)")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- **Total rounds:** {total}")
    lines.append(f"- **PASS:** {total_pass} ({pass_rate:.1f}%)")
    lines.append(f"- **FAIL:** {total_fail}")
    lines.append(f"- **SKIP:** {total_skip}")
    lines.append("")
    lines.append("**Target:** >=320/340 (>=95%)")
    achieved = "MET" if pass_rate >= 95 else ("NEAR" if pass_rate >= 85 else "BELOW")
    lines.append(f"**Status:** {achieved} threshold (actual {pass_rate:.1f}%)")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Per-Tab Matrix")
    lines.append("")
    lines.append("| Tab | File | PASS | FAIL | SKIP | Total | Pass% |")
    lines.append("|-----|------|------|------|------|-------|-------|")
    for fp in sorted(per_file.keys()):
        v = per_file[fp]
        tot = v["pass"] + v["fail"] + v["skip"]
        if tot == 0:
            continue
        pct = (v["pass"] / tot * 100) if tot else 0
        tab = tab_key_from_file(fp)
        fname = os.path.basename(fp)
        lines.append(f"| {tab} | {fname} | {v['pass']} | {v['fail']} | {v['skip']} | {tot} | {pct:.0f}% |")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Failures")
    lines.append("")
    if not failures:
        lines.append("None.")
    else:
        for f in failures[:50]:
            tab = tab_key_from_file(f["file"])
            err_one_line = f["error"].replace("\n", " ").replace("|", "/")[:250]
            lines.append(f"- **[{tab}]** {f['title']}")
            lines.append(f"  - error: {err_one_line}")
            lines.append("")
        if len(failures) > 50:
            lines.append(f"...and {len(failures) - 50} more failures.")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## 4-Axis Coverage Notes")
    lines.append("")
    lines.append("Each round verifies up to 4 axes:")
    lines.append("- Axis 1 (Modify Accepted): mutation API returns 2xx + refetch shows change")
    lines.append("- Axis 2 (Target Uses New): runtime list endpoint reflects new entity")
    lines.append("- Axis 3 (No Regression): pre-existing control entity unchanged")
    lines.append("- Axis 4 (4位一体 UX): boundary 4xx + specific message + actionHint")
    lines.append("")
    lines.append("Phase 2-5 CRUD-driven Tabs (alerts/notify/business-rules/pricing/cron) exercise all 4 axes.")
    lines.append("Phase A Tabs (thresholds/food-safety/indicators) exercise axes 1+3+4.")
    lines.append("Pre-existing read-mostly Tabs (workflow/approval/triggers/validation/fields/permissions/")
    lines.append("module-permissions/tools/scheduler-v2) primarily exercise axes 3 (stability) + 4 (boundary).")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## Bugs / Findings")
    lines.append("")
    # Classify failures by round number type
    boundary_fails = [f for f in failures if "BOUNDARY" in f["title"]]
    crud_fails = [f for f in failures if "CRUD" in f["title"] or "VARIANT" in f["title"]]
    other_fails = [f for f in failures if f not in boundary_fails and f not in crud_fails]

    lines.append(f"**Real backend validation gaps caught: {len(boundary_fails)}** (P1 candidates)")
    lines.append(f"**Adapter shape drift or test issues: {len(crud_fails)}** (test-side)")
    lines.append(f"**Other: {len(other_fails)}**")
    lines.append("")
    if boundary_fails:
        lines.append("### P1 candidates — backend accepted invalid payload")
        lines.append("")
        for f in boundary_fails:
            tab = tab_key_from_file(f["file"])
            lines.append(f"- **[{tab}]** {f['title']}")
        lines.append("")
        lines.append("These mean a boundary check passed validation (HTTP 200) when it should have rejected.")
        lines.append("Recommended next step: open backend issues per Tab + add server-side @Valid annotations.")
        lines.append("")
    if crud_fails:
        lines.append("### Test-side adapter drift")
        lines.append("")
        for f in crud_fails:
            tab = tab_key_from_file(f["file"])
            lines.append(f"- **[{tab}]** {f['title']}")
        lines.append("")
    if other_fails:
        lines.append("### Other failures")
        lines.append("")
        for f in other_fails:
            tab = tab_key_from_file(f["file"])
            lines.append(f"- **[{tab}]** {f['title']}")
        lines.append("")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("End of report.")

    with open(out_md, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print(f"Wrote {out_md}")
    print(f"Total: {total}, Pass: {total_pass} ({pass_rate:.1f}%), Fail: {total_fail}, Skip: {total_skip}")


if __name__ == "__main__":
    main()
