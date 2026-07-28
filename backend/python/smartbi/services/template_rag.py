"""Phase 3 hybrid RAG router.

hybrid_match(query) runs:
  Layer 1 — keyword match (existing query_router._PATTERNS logic) — <1ms
  Layer 2 — vector RAG via cosine_topk                             — ~30ms
  Layer 3 — LLM fallback (caller's responsibility)                 — 10-30s

Scoring rules:
  - Keyword + vector both agree on the same template → return template (strong)
  - Keyword returns X and vector top is different → prefer keyword
    (deterministic, existing production behavior; vector is additive)
  - Only vector match with similarity ≥ 0.85 → return the vector template
  - Vector max similarity 0.70-0.85 → return None + optional hint
    (caller may pass to LLM with "可能是问 X 类问题" hint)
  - Nothing ≥ 0.70 → return None (pure LLM fallback)

The router is best-effort: any failure returns None and the caller
falls through to LLM. Never raises.

Public API:
  await hybrid_match(pool, query, keyword_code=None) -> Optional[HybridMatch]
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Optional

from .template_embedding_index import cosine_topk

logger = logging.getLogger(__name__)

# Similarity thresholds originally tuned for template-query embeddings.
# Current vectors come from local gte-base-zh; keep conservative thresholds
# until production feedback/logs justify a retune.
# Tuning history:
#   Apr 23 2026: 0.85 / 0.70 — too strict; lowered HIGH to 0.80 after prod
#     showed "谁是销售冠军" (sim 0.824) / "我想看付款方式" (sim 0.839) wrongly
#     dropped.
#   Apr 26 2026 v4 B2-A: 0.80 → 0.78 — prod log analysis of 923 ambiguous
#     misses showed 125 high-sim (≥0.78) cases for unambiguous templates:
#       store_performance ×31, period_comparison_trend ×23, monthly_trend ×23,
#       dish_slow_movers ×23, reviews_sentiment_summary ×16, member ×8 + misc.
#     All single-domain templates with no real conflict at 0.78. Recovers
#     ~125 cache hits/day with low false-positive risk.
HIGH_CONFIDENCE = 0.78   # Vector-only match at/above this → serve template
MIN_USEFUL = 0.70        # Below this → no hint, pure LLM fallback


@dataclass
class HybridMatch:
    template_code: str
    similarity: float
    matched_sample: Optional[str]  # which sample_query triggered the match
    via: str  # "keyword+vector" / "keyword_only" / "vector_only"


async def hybrid_match(
    pool, query: str, keyword_code: Optional[str] = None,
) -> Optional[HybridMatch]:
    """Return a high-confidence template match, or None if uncertain.

    `keyword_code` is the result of the existing Layer 1 keyword match
    (from query_router.match_template). Pass it in so we can double-verify
    via the vector layer — if they agree, confidence is maximal.

    Returns None when:
      - Embedding call failed
      - No vector result ≥ MIN_USEFUL
      - Keyword didn't match AND vector similarity < HIGH_CONFIDENCE

    In those cases the caller should fall through to LLM. The caller may
    still expose the best vector candidate below threshold as a "possibly"
    hint to the LLM prompt.
    """
    # 2026-07-07 restaurant intent tiered routing design section 3.3:
    # exclude RESTAURANT_OPS_* from the FACTORY-side vector search so a
    # factory query can never vector-match a restaurant-only template. The
    # mirror (restaurant queries scoped to code_prefix='RESTAURANT_OPS_') is
    # in smartbi.gold.restaurant.restaurant_intent._t2_vector_match -- together they
    # give bidirectional business-type isolation.
    candidates = await cosine_topk(
        pool, query, k=3, min_similarity=MIN_USEFUL,
        exclude_prefix="RESTAURANT_OPS_",
    )
    if not candidates:
        if keyword_code:
            return HybridMatch(
                template_code=keyword_code,
                similarity=1.0,
                matched_sample=None,
                via="keyword_only",
            )
        return None

    top_code, top_sim, top_sample = candidates[0]

    # Rule 1: keyword + top vector agree → strong double-vote
    if keyword_code and keyword_code == top_code:
        return HybridMatch(
            template_code=keyword_code,
            similarity=top_sim,
            matched_sample=top_sample,
            via="keyword+vector",
        )

    # Rule 2: keyword matched something different, but vector thinks
    # something else. Preserve keyword (deterministic / existing prod
    # behavior) but log the disagreement for later analysis.
    if keyword_code:
        logger.info(
            f"[hybrid-rag] keyword→{keyword_code}, vector top→{top_code} ({top_sim:.3f}). "
            f"Serving keyword match (deterministic precedence)."
        )
        return HybridMatch(
            template_code=keyword_code,
            similarity=1.0,
            matched_sample=None,
            via="keyword_only",
        )

    # Rule 3: no keyword match; rely purely on vector. Need high confidence.
    if top_sim >= HIGH_CONFIDENCE:
        logger.info(
            f"[hybrid-rag] vector-only match: {top_code} sim={top_sim:.3f} "
            f"via sample '{top_sample[:30]}'"
        )
        return HybridMatch(
            template_code=top_code,
            similarity=top_sim,
            matched_sample=top_sample,
            via="vector_only",
        )

    # 0.70 ≤ sim < 0.85: ambiguous. Return None; caller goes to LLM. We log
    # so we can tune the threshold based on real miss/hit rates later.
    logger.info(
        f"[hybrid-rag] ambiguous: top={top_code} sim={top_sim:.3f} below threshold, "
        f"falling to LLM"
    )
    return None
