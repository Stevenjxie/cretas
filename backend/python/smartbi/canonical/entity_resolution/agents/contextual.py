"""ContextualAgent: cosine sim over context-enriched embeddings."""
from __future__ import annotations

from typing import (
    TYPE_CHECKING,
    Any,
    Dict,
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


class ContextualAgent(BaseAgent):
    """Embeds raw_name + ctx (city/category/role) to disambiguate near-duplicates."""

    name = "contextual"
    ship_threshold = 0.85
    CACHE_MAX_SIZE = 50_000

    def __init__(self, embed_fn: Optional[EmbedFn] = None) -> None:
        self._embed_fn: Optional[EmbedFn] = embed_fn
        self._cache = EmbeddingCache(max_size=self.CACHE_MAX_SIZE)

    async def _embed(
        self,
        text: str,
        factory_id: str,
        entity_type_value: str,
    ) -> Optional[List[float]]:
        if self._embed_fn is None:
            from smartbi.services.llm_fallback_logger import (
                get_embedding as default_embed,
            )

            self._embed_fn = default_embed
        return await self._cache.get_or_embed(
            self._embed_fn,
            text,
            key=("contextual", factory_id, entity_type_value, text),
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

    @staticmethod
    def _has_context_fields(entity_type: EntityType, ctx: Dict[str, Any]) -> bool:
        if entity_type == EntityType.STORE:
            return bool(ctx.get("city") or ctx.get("district"))
        if entity_type == EntityType.PRODUCT:
            return bool(ctx.get("category") or ctx.get("store_name"))
        if entity_type == EntityType.STAFF:
            return bool(ctx.get("store_name") or ctx.get("role"))
        if entity_type == EntityType.DISH:
            # dish ctx: category (dish_classifier) + price range (agg_product_period)
            return bool(ctx.get("category") or ctx.get("price_range"))
        return False

    @staticmethod
    def _build_input_context_str(
        raw_name: str,
        entity_type: EntityType,
        ctx: Dict[str, Any],
    ) -> str:
        if entity_type == EntityType.STORE:
            city = str(ctx.get("city", "") or "")
            district = str(ctx.get("district", "") or "")
            return f"{raw_name} {city} {district}".strip()
        if entity_type == EntityType.PRODUCT:
            category = str(ctx.get("category", "") or "")
            store_name = str(ctx.get("store_name", "") or "")
            return f"{raw_name} {category} 店:{store_name}".strip()
        if entity_type == EntityType.STAFF:
            store_name = str(ctx.get("store_name", "") or "")
            role = str(ctx.get("role", "") or "")
            return f"{raw_name} 店:{store_name} 岗位:{role}".strip()
        if entity_type == EntityType.DISH:
            category = str(ctx.get("category", "") or "")
            price_range = str(ctx.get("price_range", "") or "")
            return f"{raw_name} 品类:{category} 价位:{price_range}".strip()
        return raw_name

    @staticmethod
    def _build_candidate_context_str(
        entity_type: EntityType,
        row: Any,
    ) -> str:
        name = row.get("name") or ""
        if entity_type == EntityType.STORE:
            city = row.get("city") or ""
            province = row.get("province") or ""
            primary = city or province
            return f"{name} {primary}".strip()
        if entity_type == EntityType.PRODUCT:
            category = row.get("category") or ""
            sub_category = row.get("sub_category") or ""
            return f"{name} {category} {sub_category}".strip()
        if entity_type == EntityType.STAFF:
            role = row.get("role") or ""
            store_name = row.get("store_name") or ""
            return f"{name} 店:{store_name} 岗位:{role}".strip()
        if entity_type == EntityType.DISH:
            category = row.get("category") or ""
            return f"{name} 品类:{category}".strip()
        return str(name)

    async def resolve(
        self,
        input: ResolutionInput,
        pool: "asyncpg.Pool",
    ) -> AgentResult:
        ctx = input.context or {}
        if not self._has_context_fields(input.entity_type, ctx):
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning="no context fields populated",
            )

        context_str = self._build_input_context_str(
            input.raw_name, input.entity_type, ctx
        )

        query_emb = await self._embed(
            context_str, input.factory_id, input.entity_type.value
        )
        if query_emb is None:
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning="embedding 服务返回 None (contextual)",
            )

        async with pool.acquire() as conn:
            if input.entity_type == EntityType.STORE:
                rows = await conn.fetch(
                    "SELECT store_id AS eid, name, city, province "
                    "FROM dim_store WHERE factory_id = $1 LIMIT 1000",
                    input.factory_id,
                )
            elif input.entity_type == EntityType.PRODUCT:
                rows = await conn.fetch(
                    "SELECT product_id AS eid, name, category, sub_category "
                    "FROM dim_product WHERE factory_id = $1 LIMIT 1000",
                    input.factory_id,
                )
            elif input.entity_type == EntityType.STAFF:
                rows = await conn.fetch(
                    "SELECT s.staff_id AS eid, s.name, s.role, "
                    "st.name AS store_name "
                    "FROM dim_staff s "
                    "LEFT JOIN dim_store st ON st.store_id = s.store_id "
                    "WHERE s.factory_id = $1 LIMIT 1000",
                    input.factory_id,
                )
            elif input.entity_type == EntityType.DISH:
                # candidate set = dim_canonical_dish (canonical_name + category)
                rows = await conn.fetch(
                    "SELECT canonical_dish_id AS eid, canonical_name AS name, "
                    "category "
                    "FROM dim_canonical_dish "
                    "WHERE factory_id = $1 AND status = 'active' LIMIT 1000",
                    input.factory_id,
                )
            else:
                rows = []

        if not rows:
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning=f"dim_{input.entity_type.value} 表为空",
            )

        scores: List[Tuple[int, str, float]] = []
        for r in rows:
            cand_str = self._build_candidate_context_str(input.entity_type, r)
            if not cand_str:
                continue
            cand_emb = await self._embed(
                cand_str, input.factory_id, input.entity_type.value
            )
            if cand_emb is None:
                continue
            sim = self._cosine(query_emb, cand_emb)
            scores.append((r["eid"], cand_str, sim))

        if not scores:
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning="所有候选 contextual embedding 失败",
            )

        scores.sort(key=lambda x: x[2], reverse=True)
        top_5 = [
            {"entity_id": s[0], "name": s[1], "sim": s[2]} for s in scores[:5]
        ]
        best = scores[0]
        return AgentResult(
            matched_entity_id=best[0] if best[2] > 0.7 else None,
            confidence=best[2],
            reasoning=(
                f"contextual cosine sim={best[2]:.3f}, "
                f"query='{context_str}', candidate='{best[1]}'"
            ),
            candidates=top_5,
        )
