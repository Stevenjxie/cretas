"""Comprehensive-synthesis (P2 多维综合分析) query router.

Mirrors the keyword-group pattern of ``smartbi/gold/restaurant_ops_router.py``
(``match_restaurant_ops``) but answers a different question: "is this a
free-form *multi-dimension* question that should be served by the synthesis
engine?" rather than "which single ops template does it map to?".

Routing philosophy (per ``feedback_rules_first_llm_fallback`` +
``feedback_intent_gate_must_cover_all_execution_paths``)
-----------------------------------------------------------------------
- **Rules-first, confident-only.** The synthesis engine is an *additive*
  path. It must NOT steal a single-dimension query that the existing
  accurate single-dim routes (review-* / finance / template) already serve.
- A match therefore requires EITHER:
    (a) an explicit "综合 / 整体 / 全面 / 多维 / 综合诊断" synthesis verb, OR
    (b) ≥2 distinct analysis dimensions co-occurring (评价+经营 / VIP+菜品 /
        时段+营收 / 平台+评分 / 城市+营收 ...).
  A single-dimension query ("评价怎么样", "总营收多少", "畅销菜品") has neither
  → returns False → falls through to the single-dim routes.
- No LLM call here (省 token + avoid mis-routing). Dimension *planning*
  inside the engine may use an LLM only for queries already confirmed to be
  comprehensive — never at this routing gate.

The router is a sub-1ms pure function with no I/O so both Path A (Java
intent → tool) and Path B (Python chat stream) can reuse the same decision
(intent-gate-cover-all-paths rule: one gate, both entrypoints consistent).
"""
from __future__ import annotations

from typing import List

# ---------------------------------------------------------------------------
# Explicit synthesis verbs — any one of these phrases alone signals the user
# wants a cross-dimensional / holistic read, even without naming dimensions.
# These are two-token groups so "综合" or "整体" appearing incidentally inside
# an unrelated word does not over-trigger (require the 分析/诊断 partner token).
# ---------------------------------------------------------------------------
_SYNTHESIS_VERB_GROUPS: List[List[str]] = [
    ["综合", "分析"],
    ["综合", "诊断"],
    ["综合", "评估"],
    ["整体", "分析"],
    ["整体", "诊断"],
    ["整体", "情况"],
    ["全面", "分析"],
    ["全面", "诊断"],
    ["多维", "分析"],
    ["多维度", "分析"],
    ["综合", "看"],          # "综合看一下经营" colloquial
]

# Single-token synthesis verbs that are unambiguous on their own.
_SYNTHESIS_VERB_SINGLES: List[str] = [
    "综合分析",
    "整体分析",
    "全面分析",
    "综合诊断",
    "多维分析",
    "综合评估",
    "经营诊断",
]

# ---------------------------------------------------------------------------
# Dimension vocabularies. A query matches by co-occurrence iff it touches ≥2
# DISTINCT dimensions below. Single-dimension queries (only one bucket hit)
# do NOT match — they belong to the existing single-dim routes.
#
# Buckets are intentionally NON-overlapping concepts:
#   review   — 口碑/评价/星级/好评差评
#   finance  — 营收/营业额/客单价/经营(财务面)
#   sales    — 菜品/商品/销量/渠道/折扣
#   customer — VIP/会员/客群/复购 (a cross-cut customer segment dimension)
#   time     — 时段/早午晚/工作日周末/趋势
#   place    — 门店/分店/城市/上海/杭州/地区
#   platform — 平台/点评/美团/渠道口碑
# ---------------------------------------------------------------------------
_DIM_VOCAB = {
    "review":   ["评价", "口碑", "星级", "好评", "差评", "评分", "点评分", "满意度"],
    "finance":  ["营收", "营业额", "经营", "财务", "客单价", "收入", "业绩", "利润"],
    "sales":    ["菜品", "商品", "销量", "畅销", "慢销", "折扣", "套餐", "菜系"],
    "customer": ["vip", "会员", "客群", "复购", "顾客分层", "客户分层"],
    "time":     ["时段", "早午晚", "工作日", "周末", "趋势", "时间段"],
    "place":    ["门店", "分店", "店铺", "城市", "上海", "杭州", "地区", "区域", "哪家店"],
    "platform": ["平台", "美团", "渠道"],
}

# "渠道" appears in both sales and platform vocab intentionally — but it counts
# once per query toward the distinct-dimension total via the set() below.


def _hit_dimensions(q_lower: str) -> set:
    """Return the set of distinct dimension keys whose vocabulary the query hits."""
    hits = set()
    for dim, vocab in _DIM_VOCAB.items():
        if any(tok in q_lower for tok in vocab):
            hits.add(dim)
    return hits


def match_comprehensive_synthesis(query: str) -> bool:
    """True iff ``query`` is a free-form multi-dimension / holistic question.

    Decision (rules-first, confident-only):
      1. An explicit synthesis verb ("综合分析" / "整体诊断" / "全面分析" / "多维分析"
         / "综合" + "分析" group ...) → True.
      2. OR ≥2 distinct analysis dimensions co-occur → True.
      3. Otherwise → False (single-dim query; let the existing routes serve it).

    The function never raises and never calls an LLM. ``query`` is lowercased
    for ASCII-token matches (e.g. "VIP" → "vip") while CJK tokens match as-is.
    """
    if not query:
        return False
    q = query.strip()
    ql = q.lower()

    # 1. Explicit synthesis verb (single tokens first, then two-token groups).
    for verb in _SYNTHESIS_VERB_SINGLES:
        if verb in q:
            return True
    for group in _SYNTHESIS_VERB_GROUPS:
        if all(tok in q for tok in group):
            return True

    # 2. ≥2 distinct dimensions co-occurring.
    if len(_hit_dimensions(ql)) >= 2:
        return True

    # 3. No synthesis verb, <2 dimensions → not a synthesis question.
    return False
