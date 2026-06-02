"""DeterministicAgent: exact match after normalization."""
from __future__ import annotations

import re
from typing import TYPE_CHECKING

from smartbi.canonical.entity_resolution.agents.base import BaseAgent
from smartbi.canonical.entity_resolution.orchestrator import (
    AgentResult,
    EntityType,
    ResolutionInput,
)

if TYPE_CHECKING:
    import asyncpg


_TRAD_TO_SIMP = {
    "魚": "鱼",
    "豐": "丰",
    "陸": "陆",
    "匯": "汇",
    "門": "门",
    "萬": "万",
    "眾": "众",
    "慶": "庆",
    "颱": "台",
    "滿": "满",
    "隴": "陇",
    "紅": "红",
    "橋": "桥",
    "東": "东",
    "華": "华",
    "國": "国",
    "業": "业",
    "產": "产",
    "個": "个",
    "場": "场",
    "頭": "头",
    "團": "团",
    "車": "车",
    "電": "电",
}

_PUNCT_RE = re.compile(
    r"[()（）【】\[\]\{\}<>《》、，。．\.!！\?？:：;；'\"‘’“”·•、|\\/_\-—~`@#\$%\^&\*\+=]"
)
_WS_RE = re.compile(r"[\s　]+")


def normalize_for_dim(text: str) -> str:
    """Return canonical form for deterministic compare."""
    if not text:
        return ""
    out = "".join(_TRAD_TO_SIMP.get(ch, ch) for ch in text)
    out = _PUNCT_RE.sub("", out)
    out = _WS_RE.sub(" ", out).strip()

    def _lower_ascii(ch: str) -> str:
        return ch.lower() if ch.isascii() and ch.isalpha() else ch

    out = "".join(_lower_ascii(ch) for ch in out)
    return out


class DeterministicAgent(BaseAgent):
    """Exact match against dim_<entity> after canonical normalization."""

    name = "deterministic"
    ship_threshold = 0.95

    async def resolve(
        self,
        input: ResolutionInput,
        pool: "asyncpg.Pool",
    ) -> AgentResult:
        # DISH: candidate set is dim_canonical_dish (NOT dim_product). The input
        # raw_name is a dim_product.name; rule-normalize it (strip 5 suffix classes)
        # to the canonical normalized_key, then exact-match an existing canonical.
        if input.entity_type == EntityType.DISH:
            from smartbi.services.materialized_analytics.restaurant.dish_rule_normalize import (  # noqa: E501
                dish_rule_normalize,
            )

            normalized_key = dish_rule_normalize(input.raw_name)
            if not normalized_key:
                return AgentResult(
                    matched_entity_id=None,
                    confidence=0.0,
                    reasoning="空 dish name",
                )
            async with pool.acquire() as conn:
                row = await conn.fetchrow(
                    "SELECT canonical_dish_id FROM dim_canonical_dish "
                    "WHERE factory_id = $1 AND normalized_key = $2 "
                    "AND status = 'active'",
                    input.factory_id,
                    normalized_key,
                )
            if row:
                return AgentResult(
                    matched_entity_id=row["canonical_dish_id"],
                    confidence=1.0,
                    reasoning=(
                        f"规则层归一 key 完全匹配 canonical: "
                        f"'{input.raw_name}' → '{normalized_key}'"
                    ),
                )
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning=f"无 canonical 完全匹配 (key='{normalized_key}')",
            )

        normalized_input = normalize_for_dim(input.raw_name)
        if not normalized_input:
            return AgentResult(
                matched_entity_id=None,
                confidence=0.0,
                reasoning="空 name",
            )

        async with pool.acquire() as conn:
            if input.entity_type == EntityType.PRODUCT:
                row = await conn.fetchrow(
                    "SELECT product_id FROM dim_product "
                    "WHERE factory_id = $1 AND normalized_name = $2",
                    input.factory_id,
                    normalized_input,
                )
                if row:
                    return AgentResult(
                        matched_entity_id=row["product_id"],
                        confidence=1.0,
                        reasoning=(
                            f"normalized_name 完全匹配: "
                            f"'{input.raw_name}' → '{normalized_input}'"
                        ),
                    )
            elif input.entity_type == EntityType.STORE:
                rows = await conn.fetch(
                    "SELECT store_id, name FROM dim_store WHERE factory_id = $1",
                    input.factory_id,
                )
                for r in rows:
                    if normalize_for_dim(r["name"]) == normalized_input:
                        return AgentResult(
                            matched_entity_id=r["store_id"],
                            confidence=1.0,
                            reasoning=(
                                f"name 归一后完全匹配: "
                                f"'{input.raw_name}' → '{normalized_input}'"
                            ),
                        )
            elif input.entity_type == EntityType.STAFF:
                rows = await conn.fetch(
                    "SELECT staff_id, name FROM dim_staff WHERE factory_id = $1",
                    input.factory_id,
                )
                for r in rows:
                    if normalize_for_dim(r["name"]) == normalized_input:
                        return AgentResult(
                            matched_entity_id=r["staff_id"],
                            confidence=1.0,
                            reasoning=(
                                f"name 归一后完全匹配 (staff): "
                                f"'{input.raw_name}' → '{normalized_input}'"
                            ),
                        )

        return AgentResult(
            matched_entity_id=None,
            confidence=0.0,
            reasoning="无完全归一化匹配",
        )
