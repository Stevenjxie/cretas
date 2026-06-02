"""Entity resolution orchestrator + shared types."""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Sequence, Set, TYPE_CHECKING

if TYPE_CHECKING:
    import asyncpg

    from smartbi.canonical.entity_resolution.agents.base import BaseAgent


class EntityType(Enum):
    """Resolvable entity kinds."""

    STORE = "store"
    PRODUCT = "product"
    STAFF = "staff"
    # P4a 跨店菜名归一: dish 指向 dim_canonical_dish (跨店 canonical), 比 dim_product
    # (门店级 SKU) 更高一层。其 entity_table / id_column / name 列与 dim_<value> 默认推导
    # 不一致 (表名 dim_canonical_dish / id canonical_dish_id / name canonical_name),
    # 故 _record_history + 各 agent 对 DISH 走特判。
    DISH = "dish"


@dataclass
class ResolutionInput:
    """Per-call input. Mutable: agents may write to context."""

    raw_name: str
    entity_type: EntityType
    factory_id: str
    context: Dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class AgentResult:
    """Per-agent output."""

    matched_entity_id: Optional[int]
    confidence: float
    reasoning: str
    candidates: List[Dict[str, Any]] = field(default_factory=list)


@dataclass
class OrchestrationContext:
    """Cross-agent shared state. Internal to orchestrator."""

    top_k_candidates: List[Dict[str, Any]] = field(default_factory=list)
    tried_entity_ids: Set[int] = field(default_factory=set)
    agent_outputs: Dict[str, AgentResult] = field(default_factory=dict)


@dataclass(frozen=True)
class ResolutionOutput:
    """Final resolution result."""

    matched_entity_id: Optional[int]
    confidence: float
    decided_by_agent: str
    reasoning: str
    fallback_chain: List[str] = field(default_factory=list)
    is_tentative: bool = False


class EntityResolutionOrchestrator:
    """Run agents in sequence; first to ship wins, else tentative or admin queue.

    Tenant safety: caller must set ``app.factory_id`` (via ``tenant_ctx.set_factory_id``
    or the pool's setup callback) BEFORE invoking ``resolve()``. ``_record_history``
    and ``_queue_for_admin`` write to FORCE-RLS tables which silently drop rows (or
    raise InsufficientPrivilegeError) on factory_id mismatch.
    """

    def __init__(
        self,
        pool: "asyncpg.Pool",
        agents: Sequence["BaseAgent"],
    ) -> None:
        self._pool = pool
        self._agents = list(agents)
        self._tentative_threshold = 0.80

    async def resolve(self, input: ResolutionInput) -> ResolutionOutput:
        ctx = OrchestrationContext()
        chain: List[str] = []
        best: Optional[tuple[str, AgentResult]] = None

        for agent in self._agents:
            input.context["top_k_candidates"] = ctx.top_k_candidates
            input.context["tried_entity_ids"] = list(ctx.tried_entity_ids)
            result = await agent.resolve(input, self._pool)
            chain.append(agent.name)
            ctx.agent_outputs[agent.name] = result
            if result.candidates:
                ctx.top_k_candidates = result.candidates[:5]
            if result.matched_entity_id is not None:
                ctx.tried_entity_ids.add(result.matched_entity_id)

            if result.confidence >= agent.ship_threshold:
                output = ResolutionOutput(
                    matched_entity_id=result.matched_entity_id,
                    confidence=result.confidence,
                    decided_by_agent=agent.name,
                    reasoning=result.reasoning,
                    fallback_chain=chain.copy(),
                    is_tentative=False,
                )
                await self._record_history(input, output)
                return output

            if best is None or result.confidence > best[1].confidence:
                best = (agent.name, result)

        if (
            best is not None
            and best[1].confidence >= self._tentative_threshold
            and best[1].matched_entity_id is not None
        ):
            output = ResolutionOutput(
                matched_entity_id=best[1].matched_entity_id,
                confidence=best[1].confidence,
                decided_by_agent=best[0],
                reasoning=f"[tentative] {best[1].reasoning}",
                fallback_chain=chain.copy(),
                is_tentative=True,
            )
            await self._record_history(input, output)
            await self._queue_for_admin(input, output, priority="low")
            return output

        output = ResolutionOutput(
            matched_entity_id=None,
            confidence=0.0,
            decided_by_agent="admin_queue",
            reasoning="所有 agent 置信度均不足, 进入人工审核",
            fallback_chain=chain.copy(),
        )
        await self._queue_for_admin(input, output, priority="high")
        return output

    async def _record_history(
        self,
        input: ResolutionInput,
        output: ResolutionOutput,
    ) -> None:
        """Insert/upsert audit row into entity_resolution_history."""
        if output.matched_entity_id is None:
            return
        async with self._pool.acquire() as conn:
            # DISH special-case: canonical dish lives in dim_canonical_dish with
            # id column canonical_dish_id and display name column canonical_name.
            # The naive f"dim_{value}" = "dim_dish" table does NOT exist, so the
            # default derivation would raise UndefinedTableError and fail the whole
            # resolve. Other types keep the dim_<value> / <value>_id / name shape.
            if input.entity_type == EntityType.DISH:
                entity_table = "dim_canonical_dish"
                id_column = "canonical_dish_id"
                name_column = "canonical_name"
            else:
                entity_table = f"dim_{input.entity_type.value}"
                id_column = f"{input.entity_type.value}_id"
                name_column = "name"
            row = await conn.fetchrow(
                f"SELECT {name_column} AS name FROM {entity_table} "
                f"WHERE factory_id = $1 AND {id_column} = $2",
                input.factory_id,
                output.matched_entity_id,
            )
            b_name = row["name"] if row else input.raw_name
            await conn.execute(
                """
                INSERT INTO entity_resolution_history
                  (factory_id, entity_type, a_name, b_name, b_entity_id,
                   confidence, decided_by_agent, reasoning)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                ON CONFLICT (factory_id, entity_type, a_name, b_entity_id) DO UPDATE SET
                  confidence = GREATEST(
                      entity_resolution_history.confidence, EXCLUDED.confidence
                  ),
                  decided_by_agent = EXCLUDED.decided_by_agent,
                  reasoning = EXCLUDED.reasoning
                """,
                input.factory_id,
                input.entity_type.value,
                input.raw_name,
                b_name,
                output.matched_entity_id,
                output.confidence,
                output.decided_by_agent,
                output.reasoning,
            )

    async def _queue_for_admin(
        self,
        input: ResolutionInput,
        output: ResolutionOutput,
        priority: str,
    ) -> None:
        """Insert pending row into entity_resolution_admin_queue."""
        async with self._pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO entity_resolution_admin_queue
                  (factory_id, entity_type, raw_name, candidate_entity_id,
                   confidence, decided_by_agent, reasoning, priority)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                """,
                input.factory_id,
                input.entity_type.value,
                input.raw_name,
                output.matched_entity_id,
                output.confidence,
                output.decided_by_agent,
                output.reasoning,
                priority,
            )
