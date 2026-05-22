"""LLM-friendly response wrapper for restaurant endpoints (Sprint 11 MealClaw Response).

Decision baseline:
- Steve decision 2 (brief §2.5): STRICT WHITELIST output for LLM context. Only the
  5 fields below are passed to the LLM. Everything else (`_toolCount`, `_trace`,
  `_internalId`, `_executionMs`, raw SQL artifacts, etc.) is stripped.
- Path B (PM 2026-05-22, dispatch log §3): default window 30d → auto-fallback 365d
  when the underlying business tables are empty. This is the operational expression
  of Cretas differentiation #1 ("AI 哨兵告诉你哪儿数据不对" — audit §7.2).

Whitelist (and ONLY these 5 fields ever leave this module):
    summary             — short natural-language description for LLM/UI
    topItems            — list of high-signal items (cost / wastage / loss)
    recommendations     — actionable next-step strings
    evidence            — provenance: data_window, source labels, fallback flag
    dataAvailable       — bool (top-level) AND breakdown dict per data source

This module is pure (no DB I/O, no HTTP). Endpoints fetch raw data via the
existing pipeline and pass the raw dict + a small wrapping context dict to
``to_llm_format``. Tests can therefore stub the upstream entirely.
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional, Set

# ── Whitelist (Steve decision 2 — frozen set, no mutation downstream) ────────
WHITELIST: Set[str] = frozenset({
    "summary",
    "topItems",
    "recommendations",
    "evidence",
    "dataAvailable",
})

# ── Path B fallback window (PM decision 2026-05-22) ──────────────────────────
DEFAULT_WINDOW_DAYS: int = 30
FALLBACK_WINDOW_DAYS: int = 365


def _has_business_data(raw: Dict[str, Any]) -> bool:
    """Decide whether the raw response contains any business signal.

    Heuristic, applied across the 3 endpoints we wrap:
    - top-ingredients shape: ``{"success": True, "data": [ {…items…} ]}``  → non-empty list
    - completeness shape:    ``{"modules": [...], "overallCompleteness": int}`` → overall > 0
    - ETL shape:             ``{"success": True, "data": {"fact_*_upserted": int, …}}``
                              → any *_upserted > 0
    """
    if not raw:
        return False

    # ETL / generic envelope: {"success": bool, "data": ...}
    if "success" in raw:
        if not raw.get("success"):
            return False
        data = raw.get("data")
        if isinstance(data, list):
            return len(data) > 0
        if isinstance(data, dict):
            # ETL upsert counters
            for k, v in data.items():
                if k.endswith("_upserted") and isinstance(v, int) and v > 0:
                    return True
            # generic non-empty payload
            return any(
                bool(v)
                for v in data.values()
                if not (isinstance(v, list) and len(v) == 0)
            )

    # completeness shape
    if "overallCompleteness" in raw:
        return int(raw.get("overallCompleteness") or 0) > 0

    return False


def _check_data_available(raw: Dict[str, Any]) -> Dict[str, Any]:
    """Build the dataAvailable section per Path B (reflects audit §6.2 finding).

    Returns:
        {
            "overall": bool,
            "posData":         bool,  # SmartBI Gold POS / fact_pos_item
            "wastageData":     bool,  # wastage_records 30d window
            "consumptionData": bool,  # material_consumptions hooks (audit §6.2: false)
        }
    """
    overall = _has_business_data(raw)

    # Per audit §6.2 + BI subagent #1 finding (cretas business tables empty 30d).
    # When raw is the completeness response, infer per-module availability.
    pos_data = overall
    wastage_data = False
    consumption_data = False  # MaterialConsumption hooks NOT wired (audit §6.2)

    if "modules" in raw and isinstance(raw["modules"], list):
        for m in raw["modules"]:
            mid = m.get("id")
            count = int(m.get("recordCount") or 0)
            if mid == "pos_sales":
                pos_data = count > 0
            elif mid == "wastage":
                wastage_data = count > 0
            elif mid == "requisition":
                # requisition is the closest proxy to consumption per data audit
                consumption_data = count > 0

    return {
        "overall": overall,
        "posData": pos_data,
        "wastageData": wastage_data,
        "consumptionData": consumption_data,
    }


def _build_evidence(
    raw: Dict[str, Any],
    requested_days: int,
    actual_days: int,
    fallback: bool,
    source_label: str = "smartbi-gold",
) -> Dict[str, Any]:
    """Provenance block — surfaces window + fallback reason to the LLM."""
    fallback_reason: Optional[str] = None
    if fallback:
        fallback_reason = (
            f"近 {requested_days} 天无销售数据 (cretas 业务表 hooks 未 wire, audit §6.2), "
            f"已自动 fallback 到过去 {actual_days} 天 SmartBI Gold 历史数据"
        )

    return {
        "dataWindow": {
            "requested": f"{requested_days}d",
            "actual": f"{actual_days}d",
            "fallback": bool(fallback),
            "fallbackReason": fallback_reason,
        },
        "source": source_label,
    }


def _extract_top_items(raw: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Pull a normalized topItems list from any of the 3 endpoint shapes."""
    # top-ingredients shape: list of {name, value, rank, ...}
    data = raw.get("data") if isinstance(raw, dict) else None
    if isinstance(data, list):
        items: List[Dict[str, Any]] = []
        for r in data[:10]:  # cap at 10 for LLM context budget
            items.append({
                "name": r.get("name") or r.get("ingredient_id") or "未知",
                "value": float(r.get("value", 0) or 0),
                "rank": int(r.get("rank", 0) or 0),
                "category": r.get("category"),
                "unit": r.get("unit"),
            })
        return items

    # completeness shape: surface the lowest-coverage modules as "top items needing
    # attention" — directly maps to Cretas differentiation #1 "AI 哨兵".
    if "modules" in raw and isinstance(raw["modules"], list):
        modules = sorted(
            raw["modules"], key=lambda m: int(m.get("coverage", 0) or 0)
        )
        return [
            {
                "name": m.get("name"),
                "value": int(m.get("coverage", 0) or 0),
                "rank": idx + 1,
                "category": "data_completeness",
                "unit": "%",
            }
            for idx, m in enumerate(modules[:5])
            if int(m.get("coverage", 0) or 0) < 80
        ]

    # ETL shape: surface the per-table upsert counts as items
    if isinstance(data, dict):
        items = []
        rank = 1
        for k, v in data.items():
            if k.endswith("_upserted") and isinstance(v, int):
                items.append({
                    "name": k.replace("_upserted", "").replace("_", " "),
                    "value": int(v),
                    "rank": rank,
                    "category": "etl_table",
                    "unit": "rows",
                })
                rank += 1
        items.sort(key=lambda x: x["value"], reverse=True)
        for i, it in enumerate(items):
            it["rank"] = i + 1
        return items[:10]

    return []


def _build_recommendations(
    raw: Dict[str, Any],
    data_available: Dict[str, Any],
    fallback: bool,
    requested_days: int,
) -> List[str]:
    """Produce actionable advice for the LLM. Honors Steve decision 1: when a
    data source is missing, tell the LLM so it does NOT hallucinate.
    """
    recs: List[str] = []

    if fallback:
        recs.append(
            f"近 {requested_days} 天无新数据, 已显示历史窗口 — "
            "建议补 wire MaterialConsumption / Sales / Wastage hooks (Sprint 11 Day 7+)"
        )

    if not data_available.get("consumptionData"):
        recs.append(
            "MaterialConsumption hooks 未 wire — 无法做实际耗用 vs 计划差异分析 "
            "(audit §6.2 finding)"
        )

    if not data_available.get("wastageData"):
        recs.append("近期无 wastage_records 数据 — 无法做损溢根因诊断")

    # completeness-style: surface low-coverage modules
    if "modules" in raw and isinstance(raw["modules"], list):
        for m in raw["modules"]:
            cov = int(m.get("coverage", 0) or 0)
            if cov < 30:
                hints = m.get("missingHints") or []
                if hints:
                    recs.append(f"{m.get('name')} 数据缺 ({cov}%): {hints[0]}")
                else:
                    recs.append(f"{m.get('name')} 数据严重不足 (coverage={cov}%)")

    # top-ingredients with strong skew: suggest review
    items = _extract_top_items(raw)
    if items and items[0].get("category") != "data_completeness":
        total = sum(it.get("value", 0) for it in items)
        top1 = items[0].get("value", 0)
        if total > 0 and top1 / total > 0.4:
            recs.append(
                f"{items[0].get('name')} 占总值 {top1/total:.0%} — "
                "集中度过高, 建议核查供应/配方"
            )

    # always trim duplicates while preserving order
    seen = set()
    deduped: List[str] = []
    for r in recs:
        if r not in seen:
            seen.add(r)
            deduped.append(r)
    return deduped[:8]


def _build_summary(
    raw: Dict[str, Any],
    requested_days: int,
    actual_days: int,
    fallback: bool,
    data_available: Dict[str, Any],
    factory_id: Optional[str] = None,
) -> str:
    """One- to three-sentence summary suitable for direct LLM context."""
    factory_label = factory_id or raw.get("factoryId") or "当前餐厅"

    if fallback:
        # Path B fallback: be explicit so LLM does not pretend recent data exists.
        base = (
            f"{factory_label} 近 {requested_days} 天无销售数据, "
            f"已显示过去 {actual_days} 天 SmartBI Gold 历史数据 (646K+ POS rows)"
        )
    else:
        base = f"{factory_label} 近 {requested_days} 天餐饮经营概览"

    # Append data-source caveats per Steve decision 1.
    caveats: List[str] = []
    if not data_available.get("consumptionData"):
        caveats.append("MaterialConsumption hooks 未 wire")
    if not data_available.get("wastageData"):
        caveats.append("无 wastage 数据")

    if caveats:
        base = f"{base} (注: {', '.join(caveats)})"

    # completeness-shape: tack on overall coverage score
    if "overallCompleteness" in raw:
        base = f"{base}. 数据完整性 {raw.get('overallCompleteness')}/100"

    items = _extract_top_items(raw)
    if items and items[0].get("category") != "data_completeness":
        base = f"{base}. 当前榜首: {items[0].get('name')} ({items[0].get('value')})"

    return base


def to_llm_format(
    raw_response: Dict[str, Any],
    *,
    requested_days: int = DEFAULT_WINDOW_DAYS,
    actual_days: Optional[int] = None,
    fallback: bool = False,
    factory_id: Optional[str] = None,
    source_label: str = "smartbi-gold",
) -> Dict[str, Any]:
    """Wrap a raw endpoint response into the strict LLM whitelist shape.

    Args:
        raw_response: the original endpoint payload (will be inspected, not mutated)
        requested_days: window the caller asked for (e.g. 30)
        actual_days: window actually used (e.g. 365 after Path B fallback). Defaults
            to ``requested_days`` when not provided.
        fallback: True if Path B widened the window.
        factory_id: optional override for summary text.
        source_label: e.g. "smartbi-gold" / "cretas-business-tables".

    Returns:
        Strict 5-field dict matching ``WHITELIST``. No other keys leak.
    """
    if actual_days is None:
        actual_days = requested_days

    data_available = _check_data_available(raw_response)

    body = {
        "summary": _build_summary(
            raw_response, requested_days, actual_days, fallback, data_available, factory_id
        ),
        "topItems": _extract_top_items(raw_response),
        "recommendations": _build_recommendations(
            raw_response, data_available, fallback, requested_days
        ),
        "evidence": _build_evidence(
            raw_response, requested_days, actual_days, fallback, source_label
        ),
        "dataAvailable": data_available,
    }

    # Defensive: enforce whitelist post-build. If a helper accidentally returned
    # an extra key in the future, this strips it before the LLM ever sees it.
    return {k: v for k, v in body.items() if k in WHITELIST}


__all__ = [
    "WHITELIST",
    "DEFAULT_WINDOW_DAYS",
    "FALLBACK_WINDOW_DAYS",
    "to_llm_format",
]
