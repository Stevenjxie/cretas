"""RAG retrieval from REAL Java schema (β C5, post-W0 fix-pass F1).

Reads:
- ai_learned_expressions (curated expressions, ExpressionLearner ingests high-
  confidence matches every 5min). expression → query, intent_code stays,
  confidence cast to real. embedding_vec added by V20260501_15 migration.

(2026-07-23) intent_match_records arm removed: its query_embedding_vec column
was never populated by anyone (prod count = 0 non-null of 2119 rows), so the
UNION arm only cost a full-table filter per Stage-8 call. Restore it only
together with an actual writer for that column.

Returns top-K most similar historical cases by cosine similarity. Embedding via
ai.embedding.get_embedding_cached (request-scoped cache shared with stage 5).

asyncpg has no pgvector type adapter on the shared pool, so we stringify the
vector to pgvector text literal and let `$1::vector` cast handle parsing at PG.
Same pattern as ai/matcher/semantic.py.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import List

from ai.embedding import get_embedding_cached, vec_to_pgvector_text

logger = logging.getLogger(__name__)


# ai_learned_expressions only (curated, ExpressionLearner ingests every 5min).
# 2026-07-23 审计: intent_match_records.query_embedding_vec 全库 0 行非空 —
# Java 侧从未写入该列 (persistence 一直是 "future"), 那一臂 UNION 每次白扫
# 全表。删除该臂; 若未来 Java 补了写入端, 再恢复 UNION 并注明写入方。
RAG_SQL = """
SELECT
    expression AS query, intent_code, confidence::real AS confidence, factory_id,
    1 - (embedding_vec <=> $1::vector) AS similarity,
    'learned_expression' AS source
FROM ai_learned_expressions
WHERE embedding_vec IS NOT NULL
  AND is_active = true
  AND (factory_id = $2 OR factory_id IS NULL)
ORDER BY similarity DESC
LIMIT $3
"""


@dataclass
class RAGCase:
    """One retrieved historical case for context enrichment."""
    query: str
    intent_code: str
    confidence: float
    similarity: float
    source: str  # "learned_expression" (match_record arm removed 2026-07-23)


class RAGRetriever:
    """Reads existing intent_match_records + ai_learned_expressions for context retrieval."""

    def __init__(self, pool):
        self.pool = pool

    async def retrieve(self, query: str, factory_id: str, top_k: int = 5) -> List[RAGCase]:
        """Returns top-K most similar historical cases. Empty if embedding fails or DB error."""
        vec = await get_embedding_cached(query)
        if vec is None:
            logger.warning("RAG: embedding unavailable, returning empty")
            return []
        vec_text = vec_to_pgvector_text(vec)
        try:
            async with self.pool.acquire() as conn:
                rows = await conn.fetch(RAG_SQL, vec_text, factory_id, top_k)
        except Exception:
            logger.exception("RAG retrieval failed (table missing? schema mismatch?)")
            return []
        return [
            RAGCase(
                query=r["query"], intent_code=r["intent_code"],
                confidence=float(r["confidence"]), similarity=float(r["similarity"]),
                source=r["source"],
            )
            for r in rows
        ]
