"""Agent layer — budget tracking + narrative cache + orchestration.

Week 5 of Unified Data Layer v1 spec (§4.2, §4.3).

The agent layer sits between the /executive/insights endpoints and the
underlying Gold read primitives. It adds:

- Per-tenant daily token budget (agent_budget_daily) with tiered caps
- SHA256-keyed narrative cache (narrative_cache) with 24h TTL + upload
  invalidation
- Orchestrator that composes cache-check → budget-check → Gold prompt
  → LLM call → consume-and-cache

All behind env flag SMARTBI_AGENT_LAYER_ENABLED (default off) until
Day 5 verification is green.
"""
from smartbi.agent.budget_tracker import (
    AgentBudgetTracker,
    DEFAULT_TIER_CAPS,
    BudgetCheckResult,
)
from smartbi.agent.narrative_cache import (
    NarrativeCacheService,
    compute_question_hash,
)
from smartbi.agent.orchestrator import (
    AgentOrchestrator,
    InsightResponse,
    RESULT_SOURCE_CACHE,
    RESULT_SOURCE_CORPUS,
    RESULT_SOURCE_DEGRADED,
    RESULT_SOURCE_LLM,
)

__all__ = [
    "AgentBudgetTracker",
    "AgentOrchestrator",
    "BudgetCheckResult",
    "DEFAULT_TIER_CAPS",
    "InsightResponse",
    "NarrativeCacheService",
    "RESULT_SOURCE_CACHE",
    "RESULT_SOURCE_CORPUS",
    "RESULT_SOURCE_DEGRADED",
    "RESULT_SOURCE_LLM",
    "compute_question_hash",
]
