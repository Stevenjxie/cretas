#!/usr/bin/env python3
"""Parse 12 Workdesk AI responses and apply DOUBLE assertion per audit brief."""
import json
import os
import re
import sys

OUT = "C:/Users/Steve/my-prototype-logistics/docs/audits/2026-05-22-workdesk-ai-output-quality"

# (workdesk-name, path, query, intentCode)
TESTS = [
    ("sales-owner", "pathA", "今天该跟谁?", "DAILY_CUSTOMER_FOLLOWUP"),
    ("sales-owner", "pathB", "今天哪些客户需要拜访", None),
    ("finance-manager", "pathA", "本月经营怎么样?", "MONTHLY_FINANCIAL_CLOSE"),
    ("finance-manager", "pathB", "这个月业绩如何", None),
    ("quality-manager", "pathA", "今天 HACCP 监控全通过吗?", "FOOD_SAFETY_RECALL"),
    ("quality-manager", "pathB", "今天质量监控有问题吗", None),
    ("warehouse-keeper", "pathA", "今天要收什么货?", "WAREHOUSE_KEEPER_TODAY_TASKS"),
    ("warehouse-keeper", "pathB", "今天有什么入库", None),
    ("purchaser", "pathA", "下周采购什么?", "PURCHASER_WEEKLY_PLAN"),
    ("purchaser", "pathB", "下周需要进货吗", None),
    ("quality-chief", "pathA", "今天哪些批次待放行?", "QUALITY_CHIEF_WORKDESK"),
    ("quality-chief", "pathB", "今天有什么批次需要审批放行", None),
]

NEGATIVE_MARKERS = [
    "暂不支持",
    "_toolCount",
    "_executionOrder",
    "_reasoning",
]
UNDERSCORE_KEY_RE = re.compile(r'_[a-zA-Z]+":')
BARE_DONE_RE = re.compile(r'^查询完成[\s。\.]*$')
JSON_BRACE_RATIO_THRESHOLD = 0.05  # >5% raw JSON dump

DOMAIN_KEYWORDS_RE = re.compile(r'¥\s*\d|N件|N个|本月|今日|订单|批次|客户|供应商|今天|昨天|本周|本季|去年|同比|环比|应收|应付|采购|入库|出库|质检|跟进|商机|经营|月度|周度|日度|采购单|放行|HACCP|告警|微信|通话|销售|入库单|物料|批号|追溯')
# 累计 20+ Chinese chars allowing alphanumeric/whitespace/punct interleave
ALL_CHINESE_RE = re.compile(r'[一-鿿]')
# Suspicious "bare done" — formatted summary that says "完成" + "项数据指标" only
BARE_TEMPLATE_RE = re.compile(r'^查询完成\s*\n?\s*包含\s*\d+\s*项数据指标')


def analyze(path):
    with open(path, 'r', encoding='utf-8') as f:
        raw = f.read()
    body = json.loads(raw)
    data = body.get("data") or {}
    intentRecognized = data.get("intentRecognized")
    intentCode = data.get("intentCode")
    status = data.get("status")
    formattedText = data.get("formattedText") or ""
    message = data.get("message") or ""
    resultData = data.get("resultData")

    # 取实际 user-facing string (formattedText 优先, fallback to message)
    user_text = formattedText or message
    content_len = len(user_text)

    # 1. text-visible leaks (what user actually sees in 'AI 输出' panel)
    text_visible = formattedText or message
    # 2. resultData payload leaks (technically not user-visible but feeds Vue extractors + dev tools)
    resultData_json = json.dumps(resultData, ensure_ascii=False) if resultData else ""

    # Negative checks — ALWAYS check both text-visible AND raw payload
    negatives_hit = []
    negatives_hit_payload_only = []
    for marker in NEGATIVE_MARKERS:
        if marker in text_visible:
            negatives_hit.append(marker)
        elif marker in resultData_json:
            negatives_hit_payload_only.append(marker)
    # underscore-prefix keys
    if UNDERSCORE_KEY_RE.search(text_visible):
        found = set(UNDERSCORE_KEY_RE.findall(text_visible))
        for f in found:
            negatives_hit.append(f.rstrip('":') + "(underscore-key)")
    if UNDERSCORE_KEY_RE.search(resultData_json):
        found = set(UNDERSCORE_KEY_RE.findall(resultData_json))
        for f in found:
            negatives_hit_payload_only.append(f.rstrip('":') + "(payload-leak)")
    # bare "查询完成"
    if BARE_DONE_RE.match(user_text.strip()):
        negatives_hit.append("bare-查询完成")
    # bare template "查询完成\n包含 N 项数据指标 — 详情请查看下方数据卡片或对应报表模块。"
    if BARE_TEMPLATE_RE.match(user_text.strip()):
        negatives_hit.append("bare-template-N项数据指标")
    # raw JSON brace ratio
    if content_len > 0:
        brace_count = user_text.count('{') + user_text.count('}')
        ratio = brace_count / content_len
        if ratio > JSON_BRACE_RATIO_THRESHOLD:
            negatives_hit.append(f"json-dump({ratio:.2%})")

    # Positive checks — cumulative Chinese char count ≥20
    chinese_count = len(ALL_CHINESE_RE.findall(user_text))
    chinese_run_ok = chinese_count >= 20
    domain_keyword_match = DOMAIN_KEYWORDS_RE.search(user_text)
    domain_keyword_str = domain_keyword_match.group(0) if domain_keyword_match else None

    # Verdict
    verdict = "PASS"
    if negatives_hit:
        verdict = "FAIL"
    if not chinese_run_ok or not domain_keyword_str:
        if verdict == "PASS":
            verdict = "FAIL"

    if status == "FAILED":
        verdict = "FAIL"

    return {
        "status_field": status,
        "intentRecognized": intentRecognized,
        "intentCode_returned": intentCode,
        "content_len": content_len,
        "formattedText_preview": user_text[:200],
        "negatives_hit": negatives_hit,
        "chinese_run_ok": chinese_run_ok,
        "domain_keyword": domain_keyword_str,
        "verdict": verdict,
        "user_text": user_text,
    }


# Run analysis
results = {}
for (name, path, query, intentCode) in TESTS:
    fn = f"{OUT}/raw-{name}-{path}.json"
    if not os.path.exists(fn):
        print(f"MISSING: {fn}")
        continue
    r = analyze(fn)
    r["query"] = query
    r["intentCode_sent"] = intentCode
    results[f"{name}-{path}"] = r

# Print table
print()
print("# WORKDESK AI Output Quality Audit — 2026-05-22")
print()
print("| Workdesk | Path | Query | Status | Len | Verdict | Negative hits | Chinese-run | Domain-kw |")
print("|---|---|---|---|---|---|---|---|---|")
for key in sorted(results.keys()):
    r = results[key]
    parts = key.split("-")
    path = parts[-1]
    workdesk = "-".join(parts[:-1])
    neg = ", ".join(r["negatives_hit"]) or "—"
    cn = "✓" if r["chinese_run_ok"] else "✗"
    kw = r["domain_keyword"] or "✗"
    print(f"| {workdesk} | {path} | `{r['query']}` | {r['status_field']} | {r['content_len']} | **{r['verdict']}** | {neg[:60]} | {cn} | `{kw}` |")

# Save full
with open(f"{OUT}/analysis.json", 'w', encoding='utf-8') as f:
    json.dump(results, f, ensure_ascii=False, indent=2)

# Print per-FAIL detail
print()
print("## Per-test detail (raw user_text + preview)")
for key, r in sorted(results.items()):
    print(f"\n### {key} — VERDICT: {r['verdict']}")
    print(f"  query: {r['query']}")
    print(f"  intentCode sent: {r['intentCode_sent']}")
    print(f"  intentCode returned: {r['intentCode_returned']}")
    print(f"  status: {r['status_field']}")
    print(f"  intentRecognized: {r['intentRecognized']}")
    print(f"  content_len: {r['content_len']}")
    print(f"  formattedText preview (first 500c):")
    print(f"    {r['user_text'][:500]}")
    if len(r['user_text']) > 500:
        print(f"    [... truncated, total {r['content_len']} chars]")
    print(f"  negatives: {r['negatives_hit']}")
    print(f"  chinese_run_ok: {r['chinese_run_ok']}")
    print(f"  domain_keyword: {r['domain_keyword']}")
