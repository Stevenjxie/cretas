"""EmbeddingAgent: cosine similarity over factory dim embeddings."""
from __future__ import annotations

from typing import (
    TYPE_CHECKING,
    List,
    Optional,
    Tuple,
)

from smartbi.canonical.entity_resolution._embedding_cache import (
    EmbeddingCache,
    EmbedFn,
)
from smartbi.canonical.entity_resolution.agents.base import BaseAgent
from smartbi.canonical.entity_resolution.orchestrator import (
    AgentResult,
    EntityType,
    ResolutionInput,
)

if TYPE_CHECKING:
    import asyncpg


class EmbeddingAgent(BaseAgent):
    """Cosine sim over per-factory dim embeddings, with bounded LRU cache."""

    name = "embedding"
    ship_threshold = 0.90
    CACHE_MAX_SIZE = 50_000

    def __init__(self, embed_fn: Optional[EmbedFn] = None) -> None:
        self._embed_fn: Optional[EmbedFn] = embed_fn
        self._cache = EmbeddingCache(max_size=self.CACHE_MAX_SIZE)

    async def _embed(self, text: str, factory_id: str) -> Optional[List[float]]:
        if self._embed_fn is None:
            from smartbi.services.llm_fallback_logger import (
                get_embedding as default_embed,
            )

            self._embed_fn = default_embed
        return await self._cache.get_or_embed(
            self._embed_fn,
            text,
            key=("embedding", factory_id, text),
        )

    @staticmethod
    def _cosine(a: List[float], b: List[float]) -> float:
        dot = 0.0
        na = 0.0
        nb = 0.0
        for x, y in zip(a, b):
            dot += x * y
            na += x * x
            nb += y * y
        if na == 0 or nb == 0:
            return 0.0
        return dot / ((na ** 0.5) * (nb ** 0.5))

    async def resolve(
        self,
        input: ResolutionInput,
        pool: "asyncpg.Pool",
    ) -> AgentResult:
        query_emb = await self._embed(input.raw_name, input.factory_id)
        if query_emb is None:
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning="embedding 服务返回 None",
            )

        async with pool.acquire() as conn:
            # DISH: candidate set is dim_canonical_dish (canonical_dish_id /
            # canonical_name), NOT dim_<value>. Compare cosine over canonical_name.
            if input.entity_type == EntityType.DISH:
                rows = await conn.fetch(
                    "SELECT canonical_dish_id AS eid, canonical_name AS name "
                    "FROM dim_canonical_dish "
                    "WHERE factory_id = $1 AND status = 'active' LIMIT 1000",
                    input.factory_id,
                )
                cmp_field = "name"
                entity_table = "dim_canonical_dish"
            else:
                entity_table = f"dim_{input.entity_type.value}"
                id_column = f"{input.entity_type.value}_id"
                if input.entity_type == EntityType.PRODUCT:
                    rows = await conn.fetch(
                        f"SELECT {id_column} AS eid, name, normalized_name "
                        f"FROM {entity_table} WHERE factory_id = $1 LIMIT 1000",
                        input.factory_id,
                    )
                    cmp_field = "normalized_name"
                else:
                    rows = await conn.fetch(
                        f"SELECT {id_column} AS eid, name FROM {entity_table} "
                        f"WHERE factory_id = $1 LIMIT 1000",
                        input.factory_id,
                    )
                    cmp_field = "name"

        if not rows:
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning=f"{entity_table} 表为空",
            )

        scores: List[Tuple[int, str, float]] = []
        for r in rows:
            cand_text = r[cmp_field]
            if not cand_text:
                continue
            cand_emb = await self._embed(cand_text, input.factory_id)
            if cand_emb is None:
                continue
            sim = self._cosine(query_emb, cand_emb)
            scores.append((r["eid"], cand_text, sim))

        if not scores:
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning="所有候选 embedding 失败",
            )

        scores.sort(key=lambda x: x[2], reverse=True)
        top_5 = [
            {"entity_id": s[0], "name": s[1], "sim": s[2]} for s in scores[:5]
        ]
        best = scores[0]
        return AgentResult(
            matched_entity_id=best[0] if best[2] > 0.7 else None,
            confidence=best[2],
            reasoning=f"cosine sim={best[2]:.3f}, candidate='{best[1]}'",
            candidates=top_5,
        )
