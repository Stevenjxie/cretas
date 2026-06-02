"""P4a — orchestrator EntityType.DISH + _record_history dish table mapping.

Spec §5 Task 2: dish history must write to dim_canonical_dish / canonical_dish_id /
canonical_name, NOT the naive f"dim_{entity_type.value}" = "dim_dish" (table does not
exist → would fail the whole resolve). Other entity types unchanged.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

from smartbi.canonical.entity_resolution.agents.base import BaseAgent
from smartbi.canonical.entity_resolution.orchestrator import (
    AgentResult,
    EntityResolutionOrchestrator,
    EntityType,
    ResolutionInput,
)


def _make_mock_pool(name_value="招牌青花椒鱼"):
    conn = AsyncMock()
    conn.fetchrow = AsyncMock(return_value={"name": name_value})
    conn.execute = AsyncMock(return_value=None)
    pool = MagicMock()
    acquire_ctx = MagicMock()
    acquire_ctx.__aenter__ = AsyncMock(return_value=conn)
    acquire_ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = MagicMock(return_value=acquire_ctx)
    return pool, conn


class _FakeAgent(BaseAgent):
    def __init__(self, name, ship_threshold, result):
        self.name = name
        self.ship_threshold = ship_threshold
        self._result = result

    async def resolve(self, input, pool):
        return self._result


def test_entity_type_dish_exists():
    assert EntityType.DISH.value == "dish"


async def test_dish_history_uses_canonical_dish_table():
    """dish match → fetchrow SELECT canonical_name FROM dim_canonical_dish WHERE canonical_dish_id."""
    pool, conn = _make_mock_pool()
    a1 = _FakeAgent(
        "deterministic",
        0.95,
        AgentResult(matched_entity_id=7, confidence=1.0, reasoning="rule exact"),
    )
    orch = EntityResolutionOrchestrator(pool, [a1])
    inp = ResolutionInput(
        raw_name="青花椒味鱼",
        entity_type=EntityType.DISH,
        factory_id="RES_3101_009",
    )

    out = await orch.resolve(inp)

    assert out.matched_entity_id == 7
    # b_name lookup must hit dim_canonical_dish / canonical_name / canonical_dish_id.
    lookup_sql = conn.fetchrow.await_args_list[0].args[0]
    assert "dim_canonical_dish" in lookup_sql
    assert "canonical_name" in lookup_sql
    assert "canonical_dish_id" in lookup_sql
    assert "dim_dish" not in lookup_sql
    # history insert still targets entity_resolution_history with entity_type='dish'.
    insert_call = conn.execute.await_args_list[0]
    assert "entity_resolution_history" in insert_call.args[0]
    assert "dish" in insert_call.args  # entity_type bind value


async def test_store_history_unchanged():
    """store match still uses dim_store / store_id / name (no regression)."""
    pool, conn = _make_mock_pool(name_value="门店A")
    a1 = _FakeAgent(
        "deterministic",
        0.95,
        AgentResult(matched_entity_id=42, confidence=1.0, reasoning="exact"),
    )
    orch = EntityResolutionOrchestrator(pool, [a1])
    inp = ResolutionInput(
        raw_name="门店A",
        entity_type=EntityType.STORE,
        factory_id="F001",
    )

    await orch.resolve(inp)

    lookup_sql = conn.fetchrow.await_args_list[0].args[0]
    assert "dim_store" in lookup_sql
    assert "store_id" in lookup_sql
    assert "dim_canonical_dish" not in lookup_sql
